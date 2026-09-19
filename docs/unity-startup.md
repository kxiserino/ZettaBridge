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

Current limit: black screen, 868 reported GL calls, zero swaps, one rejected
`glTexImage2D`, no recorded guest exit in the observed run. The texture rejection
is a lead, not a proven explanation for the lack of frames. Do not claim working
rendering, acceptable speed, or a working game from this checkpoint.

## Checks

- Sensor regression failed to link against the original guest library, then passed
  through the real ARM32 translator. Covers enumeration, null/default sensors,
  unavailable queues and preservation of event output buffers on failure.
- `path_translation_test` failed before the redirect and passes after it, including
  directory aliases, unrelated paths, prefix collisions and escaping symlinks.
- `proxy_runtime_test` checks that the plugin directory reaches runtime options.
- `native_window_test` failed before the reference-count fix and passes after it.
- Guest CPU/threads/signals/C++ regression programs passed on the ARM64 emulator.
- Android native build, generated runtime bundle validation and Gradle debug build pass.

Build with the upstream commands using NDK r29. `tools/build_guest.sh` builds the
new sensor test; `tools/run_guest_tests.sh` runs it alongside the existing cases.
The native unit targets can also be cross-compiled with `ZB_BUILD_TESTS=ON` and run
on Android. Set `TMPDIR` to a writable directory for filesystem tests.

Client binaries, screenshots and raw device logs remain outside this repository.
This is research under the original licence, not authorization to bundle or ship
ZettaBridge in a product. No upstream contact has been sent.
