#!/usr/bin/env python3
"""Unwind a guest thread backtrace from a ZettaBridge runtime report.

The report's bare " | <pc>@<where>" frames are a scan for stack words that happen
to name a mapping, which pads the chain with stale return addresses. ARM32 il2cpp
keeps no frame pointer, so the real chain has to come from the ARM EHABI tables
(.ARM.exidx / .ARM.extab), which survive stripping.

llvm-readelf --unwind decodes those tables into plain English, so this does not
reimplement the encoding: it parses the decoded opcodes and executes them.

    llvm-readelf --unwind <lib> | parse -> {function address: [opcodes]}

Each report frame now carries "sp=<hex> ... stack=<192 words of hex>", and the
report carries "lib-base-<n>: 0x<base> 0x<size> <path>" for every guest .so, so an
unwound address can be placed in a library and named (libil2cpp via the symbol map
when --symbols is given, otherwise the nearest exported symbol).

Usage:
    unwind_guest_stack.py <report.txt> <lib-dir> [--readelf PATH] [--symbols MAP META]
"""

import bisect
import os
import re
import subprocess
import sys
import tempfile

FRAME = re.compile(r"^(watch-freeze[^:]*): (.*)$")
ALIASES = {"fp": 11, "ip": 12, "sp": 13, "lr": 14, "pc": 15}


def parse_report(path):
    """Return (threads, library_regions). A thread is (label, pc, lr, sp, [words])."""
    threads = []
    regions = []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.match(r"^lib-base-\d+: 0x([0-9a-f]+) 0x([0-9a-f]+) (.*)$", line.rstrip("\n"))
        if m:
            regions.append((int(m.group(1), 16), int(m.group(2), 16), m.group(3)))
            continue
        m = FRAME.match(line.rstrip("\n"))
        if not m:
            continue
        body = m.group(2)
        pc = re.search(r"pc=([0-9a-f]{8})", body)
        lr = re.search(r"lr=([0-9a-f]{8})", body)
        sp = re.search(r"sp=([0-9a-f]{8})", body)
        stack = re.search(r"stack=([0-9a-f]*)", body)
        if not (pc and sp and stack):
            continue
        words = [int(stack.group(1)[i:i + 8], 16) for i in range(0, len(stack.group(1)) - 7, 8)]
        threads.append((m.group(1), int(pc.group(1), 16), int(lr.group(1), 16) if lr else 0,
                        int(sp.group(1), 16), words))
    return threads, regions


class Lib:
    """Decoded EHABI unwind information for one shared object."""

    def __init__(self, path, readelf):
        self.path = path
        self.starts = []
        self.opcodes = {}
        text = subprocess.run([readelf, "--unwind", path], capture_output=True, text=True).stdout
        func = None
        ops = None
        for line in text.splitlines():
            stripped = line.strip()
            address = re.match(r"^FunctionAddress: 0x([0-9a-f]+)$", stripped)
            if address:
                if func is not None:
                    self.opcodes[func] = ops
                func = int(address.group(1), 16)
                ops = []
                continue
            if stripped == "Opcodes [":
                ops = []
                continue
            if stripped == "]" or stripped == "}":
                continue
            op = re.match(r"^(0x[0-9A-Fa-f]{2}(?: 0x[0-9A-Fa-f]{2})*)\s*;\s*(.*)$", stripped)
            if op and func is not None:
                ops.append(([int(b, 16) for b in op.group(1).split()], op.group(2).strip()))
        if func is not None:
            self.opcodes[func] = ops
        self.starts = sorted(self.opcodes)

    def entry_for(self, pc):
        index = bisect.bisect_right(self.starts, pc) - 1
        if index < 0:
            return None
        return self.opcodes[self.starts[index]]


def execute(opcodes, sp, memory, registers):
    """Run one frame's unwind opcodes. Returns (next_sp, next_pc) or None."""
    regs = dict(registers)
    for raw, comment in opcodes:
        byte = raw[0]
        if comment == "finish":
            break
        if comment.startswith("vsp = vsp + "):
            # The decoded form already lists the pops that restore lr, so no implicit one here.
            sp += int(comment.split("+")[1])
        elif comment.startswith("vsp = vsp - "):
            sp -= int(comment.split("-")[1])
        elif comment.startswith("vsp = r"):
            sp = regs.get(int(comment.split("r")[1]), sp)
        elif comment.startswith("pop {"):
            members = [m.strip() for m in comment[5:].rstrip("}").split(",")]
            for member in members:
                if member.startswith("d"):
                    sp += 8
                    continue
                number = ALIASES.get(member, int(member[1:]) if member.startswith("r") else None)
                if number is None:
                    continue
                value = memory(sp)
                sp += 4
                regs[number] = value
    # Whether or not an opcode restored lr, the return address for the next frame is lr: entries
    # that are just "finish" describe a frame that does not move sp and returns through lr.
    return sp, regs.get(14, 0)


def unwind(libs, regions, sp, pc, lr, words, limit=32):
    """Walk frames using the stack words dumped from the initial sp.

    The unwind tables are indexed by each library's own offsets, so an absolute address is first
    placed in a library and reduced to an offset; looking an absolute address up directly matches
    the last entry in the table and unwinds with the wrong function.
    """
    base = sp
    high = base + 4 * len(words) - 4

    def memory(address):
        if address < base or address > high:
            return 0xFFFFFFFF
        return words[(address - base) // 4]

    # Keyed by the path the report used (the device path), not the local copy's path.
    by_path = {getattr(candidate, 'region_path', candidate.path): candidate for candidate in libs}

    def place(address):
        for start, size, path in regions:
            if start <= address < start + size:
                candidate = by_path.get(path)
                if candidate is None:
                    continue
                return candidate.entry_for(address - start)
        return None

    # A thread parked in a syscall sits in an assembler wrapper that has no EHABI entry, so the
    # walk starts from the caller instead: the wrapper does not move sp, so the caller's frame can
    # be unwound with the same sp.
    if place(pc) is None and place(lr) is not None:
        pc = lr

    registers = {13: sp, 14: lr, 15: pc}
    frames = []
    for _ in range(limit):
        opcodes = place(pc)
        if opcodes is None:
            break
        result = execute(opcodes, sp, memory, registers)
        if result is None:
            break
        sp, next_pc = result
        next_pc &= ~1
        if next_pc == 0 or next_pc == pc:
            break
        frames.append((next_pc, None))
        registers[15], registers[13] = next_pc, sp
        pc = next_pc
        if sp < base:
            break
    return frames


def name_for(pc, path, regions, symbol_map):
    for base, size, library in regions:
        if base <= pc < base + size:
            path = library
            offset = pc - base
            if symbol_map is not None and library.endswith("libil2cpp.so"):
                name = symbol_map(offset)
                if name:
                    return f"{os.path.basename(library)}+0x{offset:x} {name}"
            return f"{os.path.basename(library)}+0x{offset:x}"
    return f"0x{pc:08x}"


def main(argv):
    if len(argv) < 3:
        raise SystemExit(__doc__)
    report, lib_dir = argv[1], argv[2]
    readelf = "llvm-readelf"
    symbol_map = None
    rest = argv[3:]
    # The report carries device paths; --map rewrites them onto local copies of the same files.
    rewrites = []
    while "--map" in rest:
        index = rest.index("--map")
        rewrites.append((rest[index + 1], rest[index + 2]))
        del rest[index:index + 3]
    if "--readelf" in rest:
        readelf = rest[rest.index("--readelf") + 1]
    if "--symbols" in rest:
        index = rest.index("--symbols")
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import symbolize_il2cpp

        intervals = symbolize_il2cpp.load_symbols(rest[index + 1], rest[index + 2])
        starts = [entry[0] for entry in intervals]

        def symbol_map(offset):
            position = bisect.bisect_right(starts, offset) - 1
            if position < 0:
                return None
            start, end, name = intervals[position]
            return name if start <= offset < end else None

    def local(device_path):
        for device_prefix, local_prefix in rewrites:
            if device_path.startswith(device_prefix):
                return local_prefix + device_path[len(device_prefix):]
        return device_path

    threads, regions = parse_report(report)
    libraries = []
    cache = {}
    for _, size, path in regions:
        if path in cache:
            continue
        local_path = local(path)
        if not os.path.exists(local_path):
            sys.stderr.write(f"unwind: no local copy of {path}\n")
            continue
        cache[path] = Lib(local_path, readelf)
        cache[path].region_path = path
        libraries.append(cache[path])
    sys.stderr.write(f"unwind: {len(threads)} threads, {len(libraries)} libraries\n")

    for label, pc, lr, sp, words in threads:
        frames = unwind(libraries, regions, sp, pc, lr, words)
        print(f"{label}: pc={name_for(pc, None, regions, symbol_map)}")
        for address, path in frames:
            print(f"    {name_for(address, path, regions, symbol_map)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
