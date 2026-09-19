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

Current result: the game renders and reaches the interactive **date-of-birth
(age gate) screen**, where the month/day/year fields and SUBMIT respond. The
runtime report shows no guest exit, no unimplemented host call and no JNI error.
Speed on the emulator is low: it runs on SwiftShader (software GL) and an
ARM32-to-ARM64 JIT, and the emulator's `-gpu host` path renders black (only 4
swaps) for this client, so the emulator cannot be made fast. Performance on real
ARM64 hardware (the target device) is not yet measured. The client is a
private-server build and no server/account has been supplied, so past the age
gate is not assessed. Do not claim acceptable speed or a working game.

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
