# Prototype verification

Date: 18 November 2026. Version: 0.1.0.

| Check | Result |
| --- | --- |
| `assembleDebug` | Passed |
| `testDebugUnitTest` | Passed: 5 tests, 0 failures, 0 errors |
| `lintDebug` | Passed: 0 errors, 14 warnings |
| APK signature | Verified with Android SDK `apksigner`, APK Signature Scheme v2 |
| Package metadata | `dev.cameracompositor`, version 0.1.0, target SDK 35 |
| Installable artifact | `artifacts/CameraCompositor-debug.apk` |
| Physical Vivo V30 test | Pending |

Unit tests cover pan directions, all three framing targets, clipped subjects, invalid/missing detections, and the alignment hold/reset. They exercise the pure composition rules, not camera hardware or the ML model.

Lint warnings cover newer dependency versions, portrait orientation, backup compatibility guidance, the temporary application icon/control styles, a KTX style suggestion, and a viewfinder accessibility string that still needs resource extraction. Dependency versions are pinned for this prototype. These are follow-up polish items.

The APK is a debug-signed development build. Camera/model runtime behavior, transformed subject coordinates, preview/capture framing, device performance, and thermal behavior require the physical-device checks in PHONE_TESTING.md. No emulator or phone runtime session has been completed.

The two local reference videos were decoded into contact sheets and visually inspected. They were not added to Git.
