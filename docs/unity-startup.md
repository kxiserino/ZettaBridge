# Unity startup experiment

Tested on 19 September 2026 with Unity 5.5.1f1 / ARMv7 client 0.63.4, in an
Android 15 API35 ARM64-only emulator using SwiftShader. No root or guest Android VM.
Development ADB was used. No physical-device, account-login or gameplay acceptance.

## Changes and evidence

| Failure | Change | Observed result |
| --- | --- | --- |
| Unity could not load because `ASensorEventQueue_disableSensor` was missing. | Supply the legacy sensor ABI with an empty sensor inventory. Queue operations fail; no fake samples or successful enable calls. | Unity loads and reaches its main thread. Actual compass/accelerometer forwarding is still absent. |
| Unity displayed `Failed to load IL2CPP` after Java loaded the library. | Map the active plugin's ARM64 proxy paths back to ARM32 libraries for guest filesystem operations. ART still gets its proxy. | IL2CPP metadata/resources open and graphics initialization proceeds. |
| `eglCreateWindowSurface` rejected a released window handle. | Keep guest handles until the final acquired reference is released. Fix the mock backend to model reference counts too. | EGL creates a 720x1280 surface and Unity issues OpenGL calls. |
| The screen stayed black with 868 GL calls and zero swaps. Boehm GC (IL2CPP) suspends threads with `SIGPWR` and waits for each to acknowledge. The target thread's handler calls `rt_sigsuspend`, which was unimplemented (`-ENOSYS`), so the handler spun; a thread already blocked in a guest futex never returned to the stop dispatcher at all, so the signal was never delivered. | Implement `rt_sigsuspend`/`sigsuspend` (park on the per-thread post word until a deliverable signal is pending) and bound every blocking guest futex wait so a posted signal is observed within one poll. | The stop-the-world handshake completes; Kanto renders its splash and reaches `prepareLoginScreen`. |

| The boot stalled on the splash: `UnityUtil.nativeInit` threw `UnsatisfiedLinkError` because `libNianticLabsPlugin.so` is a shim whose `JNI_OnLoad` dlopens the real `libkantoLegacyN2.so`. ART resolves a native method against the library Java loaded, and ZettaBridge only bound `Java_*` exports of libraries loaded through the proxy. | After each loader call, scan the libraries the guest mapped itself, reopen each with `RTLD_NOLOAD` and bind its `Java_*` exports. | `nativeInit` binds; the game boots to the date-of-birth (age gate) screen. |
| On a real Adreno device the guest crashed with a null jump in `libunity` (`glObjectLabelKHR`, then `glLabelObjectEXT`, then `glTexImage3DOES`): Adreno advertises more GL extensions than SwiftShader and Unity calls them without null-checking. | Generate the debug-label/marker entry points and drop from the reported `GL_EXTENSIONS` the extensions ZettaBridge cannot serve. | The phone reaches the age gate without crashing. |
| The phone ran at well under 1 fps: a 25 ms poll added to every no-timeout futex wait (to deliver guest signals to blocked threads) reprogrammed an hrtimer per wait, and **91% of the process's cycles were in the kernel's timer/scheduler path**. | Post the signal with a reserved no-op host signal (`SIGRT 40`) that breaks the blocking syscall with `EINTR`; remove the futex polling. | System time fell from ~5.7 cores to ~0.1; the kernel's share of the profile from 91% to 9.4%; the age gate renders at ~52 fps on the OnePlus 7T. |

Current result: the game renders and reaches the interactive **date-of-birth
(age gate) screen**, where the month/day/year fields and SUBMIT respond. The
runtime report shows no guest exit, no unimplemented host call and no JNI error.
On a real ARM64 device (OnePlus 7T, Adreno 640) the age gate renders at ~52 fps,
and a `simpleperf` profile is dominated by the translated guest code and the
bridge (kernel 9.4%), not the scheduler. The emulator stays slow because it runs
on SwiftShader (software GL) and its `-gpu host` path renders black (only 4
swaps) for this client. The client is a private-server build and no
server/account has been supplied, so past the age gate is not assessed. Do not
claim a working game.

## In-game stall: what is known after the 2026-09-20 session

The guest still stalls intermittently during the client's start-up, and two
separate things are involved. Both are recorded here because the first one is
easy to mistake for the second.

### The OS freezes the app (verified)

OnePlus's background freezer stops the whole `:guest` process on the start-up
resize, and it never unfreezes:

```text
OPBF: setCGroupState():tofreeze=true, uid:10328 pkg:com.zettabridge.launcher  reason:reportResized
/dev/freezer/10328/freezer.state = FROZEN     (every thread in state D, zero syscalls)
```

That is the "game goes dead and never comes back" symptom, and it is not the
translator. Whitelisting the launcher stops it:

```bash
adb shell su -c 'cmd deviceidle whitelist +com.zettabridge.launcher'
```

After that `freezer.state` stays `THAWED`. The launcher should request the
exemption itself instead of relying on the user.

### The underlying stall: the guest re-resolves libil2cpp

With the freezer out of the way the stall is reproducible and readable. The
client loads five libraries, renders a few dozen frames, then stops, and the
report's repeated-path list names the loop:

```text
path-repeat-1: .../ac.kanto.client/base.apk                      x638
path-repeat-2: .../lib/libil2cpp.so                              x415
path-repeat-3: .../zb/guest/lib/libil2cpp.so                     x414
path-repeat-5: .../assets/bin/Data/sharedassets0.resource.split0  x30
```

So the guest linker re-resolves `libil2cpp.so` hundreds of times and re-opens the
APK, rather than failing one `dlopen`. A live `debuggerd -b` shows several
threads inside `Process::record_file_mapping` (`vector<Process::FileMapping*>`
inserts) and one in the runtime's `serve()`. `count-clone-thread-calls: 65` and
`count-borrow-calls: 6` rule out our own thread or carrier creation as the storm.

### Diagnostics added for this (all in the runtime report)

`watch-freeze` and `watch-freeze-thread-*` (every thread's guest backtrace when GL
host calls stop), `watch-freeze-gl-thread-*` (the render thread), `gl-count-*`
(the busiest GL calls), `jni-lookup-*`, `signal-*` (a rolling window of posts and
`sigsuspend` enter/leave), `syscall-trace-*` (the last 40 syscalls with
arguments), `count-*` (named counters: clone/borrow calls), `path-repeat-*`,
`asset-open-failed-*`, `long-sleep-*`, `threads-mutex-owner`, `processor-ids` and
`processor-ids-exhausted`, `jni-carrier-*`.

`tools/symbolize_il2cpp.py` turns the guest's `libil2cpp.so` offsets into names
using the `SymbolMap-ARMv7` and `global-metadata.dat` the client ships (the
metadata method record is 56 bytes, and each method spans two contiguous
SymbolMap records). Our own frames can be symbolized by relinking
`libzbridge.so` without `-Wl,--strip-all`, which has identical layout, and
running `llvm-addr2line` on the `debuggerd` output.

### Fixes landed while chasing this

`mremap` implemented (was a hard `-ENOMEM`, which made guest libc log
`__cxa_atexit: mmap/mremap failed ... Out of memory` on every launch);
`epoll_event` translated between the 32-bit guest (12 bytes, packed) and the
64-bit host (16 bytes), without which the guest read every event's fd out of
padding; the scheduler syscalls Unity uses (`sched_getparam`, `sched_setparam`,
`sched_setscheduler`, `sched_get_priority_max/min`, previously `-ENOSYS`); the
signal reader-wait bounded so thread teardown cannot hang forever; the JIT
processor-id pool raised from 256 to 1024 with a high-water report.

### Ruled out with evidence

Lost GC signal wake (the signal trace ends in a clean resume), OOM, crash,
unimplemented host and syscalls, GL errors, ANR, the thread-registry mutex being
held (`(free)` at every freeze), long guest sleeps, failed asset opens,
processor-id exhaustion (high-water 111/1024), carrier supply (nine borrows,
pool one, no timeouts), and bridge throughput (about 44 fps, `UnityMain` mostly
sleeping).

## A 16 KiB page device cannot map the guest at all (2026-09-20)

A Pixel 11 crashed on launch after the launcher had done everything right: the
breadcrumbs in `zb-errors.txt` read `bundled=true ... imported=ac.kanto.client`
and `launching ac.kanto.client`, so the crash is in the `:guest` process, with no
Java stack.

The cause is the page size. `GuestMemory` mapped the guest at `base() + addr` with
`mmap(MAP_FIXED)` and `mprotect`, using a fixed 4 KiB guest page. Both calls
require *host* page alignment, so on a device whose pages are 16 KiB every mapping
and protection fails with `EINVAL` and the guest dies before it runs. The page
size is also the one thing a device we cannot hold differs by, so `BootActivity`
now writes it into the breadcrumb (`boot: ... pageSize=16384 ...`).

The fix is in `core/include/zb/guest_memory.h`: `kPageSize` follows
`sysconf(_SC_PAGESIZE)`, so the page table, the ELF mappings, the host protection
and `AT_PAGESZ` all agree on 4 KiB or 16 KiB as the device requires.
`kMmap2PageSize` stays 4096 because the 32-bit `mmap2` ABI fixes its offset unit
there whatever the host page size is. On a 4 KiB device nothing changes.

## Getting a report off a user's phone (2026-09-20)

A package's own `Android/data` directory is hidden from file managers and MTP on
Android 11+, and reaching it otherwise needs root, so a user asked for the
runtime report cannot send it. `Diagnostics.exportReports` copies
`zb-runtime-report.txt` and `zb-errors.txt` into the shared **Downloads**
collection through MediaStore at start-up, which needs no permission and puts
them somewhere the Files app can share. The wrapper's start-up breadcrumbs
(`boot: bundled=... pageSize=...`) go to the same place.

## A NULL GL entry point crashes Unity (2026-09-20)

A Pixel 11 died with `guest SIGILL: jump to non-executable memory at pc 0x00000000`,
called from `libunity.so+0x52dd1c`, with `r0 = GL_ARRAY_BUFFER`. The runtime report
named the cause itself:

```
egl-procaddress-misses: 8
egl-procaddress-miss-1: glDrawBuffersEXT
egl-procaddress-miss-2: glBlitFramebufferNV
egl-procaddress-miss-3: glMapBufferRangeEXT
...
```

Unity 5.5 probes these through `eglGetProcAddress`, gets NULL because our guest stub
library does not export them, and then **calls them anyway**. `glMapBufferRangeEXT`'s
signature is `(target, offset, length, access)`, which is exactly the crash's
`r0 = GL_ARRAY_BUFFER`. A miss is not a missing feature to Unity; it is a jump to zero.

The eight names are the extension spellings of entry points the driver already has, so
they are now in `gen_stubs.GLES_EXTENSIONS` (append-only) and regenerated. Two
generator bugs surfaced on the way:

- `mechanical()` looked only at a command's parameters, so `glMapBufferRangeEXT` — which
  *returns* `void*` — was emitted as a raw passthrough that would hand the guest a host
  address. A pointer result now forces a handler.
- `kLowestAllocPage` in `guest_memory.cpp` was a namespace-scope constant whose dynamic
  initializer divides by `kPageSize`; the order against that constant's own initializer
  in another translation unit is unspecified, so it can be computed while `kPageSize` is
  zero. It is a function now. That is what made `find_free` fail and the guest report
  `no guest address space for .../zbhost`.

With the fix, `egl-procaddress-misses` is 0, all six libraries load, and the client
renders.

## Mapped buffers are keyed by target, which drops uploads (2026-09-20)

With the NULL proc address fixed, a Pixel 11 played its music and rendered
(`egl-swaps: 1245`, 148k GL calls) but showed a black screen, and its report had
no GL error but 65 rejections of one kind:

```
gl-rejection-1: glMapBufferRangeEXT: buffer target is already mapped
                args=0x8892 (GL_ARRAY_BUFFER), 0x0, 0xc, ...
```

`glMapBufferRange` maps whatever buffer is *bound* to the target, and an engine
routinely has several buffers of the same target, but the mirror table was keyed
by the target. A second buffer's map was therefore rejected as "already mapped",
its upload was dropped, with no GL error the guest would see. The table is keyed
by the buffer object now.

This was *not* the black screen, though. A later run that still had all 68
rejections rendered the map correctly, so the black screen was simply the NULL
proc address crash: until the GL extension entry points resolved, the client died
before it drew anything. The mapping fix stands on its own as 68 silently dropped
uploads.
`BootActivity` also stamps the runtime bundle version into the breadcrumb
(`boot: ... runtime=65d5a5cdc9b0 ...`) so a report always says which build made it.

## Checks

- Sensor regression failed to link against the original guest library, then passed
  through the real ARM32 translator. Covers enumeration, null/default sensors,
  unavailable queues and preservation of event output buffers on failure.
- `path_translation_test` failed before the redirect and passes after it, including
  directory aliases, unrelated paths, prefix collisions and escaping symlinks.
- `proxy_runtime_test` checks that the plugin directory reaches runtime options.
- `native_window_test` failed before the reference-count fix and passes after it.
- `sigsuspend_dynamic` covers a signal delivered to a thread blocked in a futex and
  `sigsuspend` on a pending signal; it failed before the signal/futex fixes and passes
  after them.
- Guest CPU/threads/signals/C++ regression programs passed on the ARM64 emulator.
- Android native build, generated runtime bundle validation and Gradle debug build pass.

Build with the upstream commands using NDK r29. `tools/build_guest.sh` builds the
new sensor test; `tools/run_guest_tests.sh` runs it alongside the existing cases.
The native unit targets can also be cross-compiled with `ZB_BUILD_TESTS=ON` and run
on Android. Set `TMPDIR` to a writable directory for filesystem tests.

Client binaries, screenshots and raw device logs remain outside this repository.
This is research under the original licence, not authorization to bundle or ship
ZettaBridge in a product. No upstream contact has been sent.
