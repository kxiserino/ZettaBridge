#!/usr/bin/env python3
"""Symbolize libil2cpp.so addresses from a ZettaBridge runtime report.

Unity ships the two files this needs next to the extracted il2cpp data, inside
the imported plugin:

    <android/data>/<pkg>/files/plugins/<plugin>/il2cpp/SymbolMap-ARMv7
    <android/data>/<pkg>/files/plugins/<plugin>/il2cpp/Metadata/global-metadata.dat

SymbolMap-ARMv7 is a header (u32 count) followed by count * 12-byte records of
(u64 address, u32 size), with no names at all. The names come from
global-metadata.dat's method table, which uses a 56-byte record with the name
index in its first i32 and the name string in the metadata's string blob.

Only even SymbolMap records carry a method: record 2k is method k. Pairing
record i with method i instead resolves about 73% of names, and the odd records
land on unrelated strings ("mscorlib", "rlib"); pairing 2k with k resolves about
92%, which is the mapping used here. The remainder are methods whose names the
heuristic below rejects; they are reported as None rather than guessed.

Usage:
    symbolize_il2cpp.py <SymbolMap-ARMv7> <global-metadata.dat> <report.txt>
    symbolize_il2cpp.py <SymbolMap> <metadata> 0x13aab10 0x13bfca0 ...
"""

import bisect
import re
import struct
import sys

METHOD_STRIDE = 56
NAME_FIELD = 0
RECORD_SIZE = 12
# A plausible managed method name: an identifier, optionally a constructor or
# with one generic parameter list. Deliberately strict: a wrong name is worse
# than no name when the output is read as evidence.
IDENTIFIER = re.compile(r"^(?:[A-Za-z_][A-Za-z0-9_]*|\.[A-Za-z_][A-Za-z0-9_]*)(<[^<>]*>)?$")


def load_symbols(symbol_map_path, metadata_path):
    """Return a sorted list of (start, end, name) for every resolvable method."""
    blob = open(symbol_map_path, "rb").read()
    count = struct.unpack_from("<I", blob, 0)[0]
    records = [struct.unpack_from("<QI", blob, 4 + RECORD_SIZE * i) for i in range(count)]

    metadata = open(metadata_path, "rb").read()
    sanity, _version = struct.unpack_from("<Ii", metadata, 0)
    if sanity != 0xFAB11BAF:
        raise SystemExit(f"{metadata_path}: not an il2cpp global-metadata file")
    # The header is sanity, version, then (offset, size) pairs in a fixed order:
    # 0 stringLiteral, 1 stringLiteralData, 2 string, 3 events, 4 properties,
    # 5 methods.
    methods_offset, methods_size = struct.unpack_from("<ii", metadata, 8 + 5 * 8)
    string_offset, string_size = struct.unpack_from("<ii", metadata, 8 + 2 * 8)
    methods = methods_size // METHOD_STRIDE

    def name_of(index):
        if index < 0 or index >= methods:
            return None
        name_index = struct.unpack_from("<i", metadata, methods_offset + index * METHOD_STRIDE + NAME_FIELD)[0]
        if name_index < 0 or name_index >= string_size:
            return None
        end = metadata.find(b"\0", string_offset + name_index)
        raw = metadata[string_offset + name_index:end]
        if not raw or len(raw) > 250:
            return None
        try:
            text = raw.decode("ascii")
        except UnicodeDecodeError:
            return None
        return text if IDENTIFIER.match(text) else None

    intervals = []
    for method in range(methods):
        first, second = 2 * method, 2 * method + 1
        if second >= len(records):
            break
        name = name_of(method)
        if not name:
            continue
        # The two records of a method are contiguous in 99.9% of cases (the second
        # continues the first), so the method spans both.
        start = records[first][0]
        end = records[second][0] + records[second][1]
        if end > start:
            intervals.append((start, end, name))
    intervals.sort()
    return intervals


def symbolizer(intervals):
    starts = [entry[0] for entry in intervals]

    def lookup(address):
        index = bisect.bisect_right(starts, address) - 1
        if index < 0:
            return None
        start, end, name = intervals[index]
        return name if start <= address < end else None

    return lookup


def addresses_from_report(text):
    return sorted({int(value, 16) for value in
                   re.findall(r"libil2cpp\.so offset 0x([0-9a-f]+)", text)})


def main(argv):
    if len(argv) < 4:
        raise SystemExit(__doc__)
    intervals = load_symbols(argv[1], argv[2])
    lookup = symbolizer(intervals)
    sys.stderr.write(f"symbolize_il2cpp: {len(intervals)} methods indexed\n")

    if argv[3].lower().startswith("0x"):
        for value in argv[3:]:
            address = int(value, 16)
            print(f"0x{address:x} -> {lookup(address)}")
        return 0

    text = open(argv[3], encoding="utf-8", errors="replace").read()
    for line in text.splitlines():
        if "libil2cpp.so offset" not in line:
            continue
        names = [lookup(int(value, 16))
                 for value in re.findall(r"libil2cpp\.so offset 0x([0-9a-f]+)", line)]
        names = [name for name in names if name]
        label = line.split(":")[0]
        if names:
            print(f"{label}: " + " <- ".join(names))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
