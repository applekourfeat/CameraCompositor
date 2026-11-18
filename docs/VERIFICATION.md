# Version 0.2 verification

Date: 18 November 2026.

`scripts/build.ps1` passed: Android debug compilation, 17 unit tests with 0 failures/errors, and Android lint with 0 errors and 42 warnings. Unit coverage includes crop bounds, alignment hold/reset, zoom limits, malformed geometry, structured request fields, and invalid/truncated provider responses. The delivered APK signature was verified with Android SDK apksigner. A credential-pattern scan passed for source and APK DEX files, and the removed offline runtime is absent from the APK.

Delivered file: `artifacts/CameraCompositor-0.2-debug.apk`, 36,269,430 bytes. SHA-256: `5a404579e81a3bd9e9ccaae964d4aa2018dd52785f61c08aba482a65f1e403d9`.

Desktop model experiments are recorded in [SCENE_MODEL_FINDINGS.md](SCENE_MODEL_FINDINGS.md). They led to removing the large offline models from the Android build.

Google AI Studio visibly showed the owner's key named CameraCompositor. The key was not copied into source, an APK, or a local configuration file. No real credential is used by the automated tests. Real provider authentication and scene requests remain pending the phone's private setup and first scan.

The new ARM64 APK targets SDK 35 and Android 10+. The owner could not connect the Vivo during development and wanted to continue using it. No new physical-device or emulator runtime test is claimed. Scene-to-screen coordinate mapping, OpenCV tracking behavior, actual cloud latency, capture framing, and Vivo manufacturer processing require the checks in [PHONE_TESTING.md](PHONE_TESTING.md).

Lint warnings include pinned dependency updates, portrait/ARM64 prototype scope, English-only temporary UI strings, accessibility/style suggestions, and KTX preferences. They are separate from device acceptance and final UI design.
