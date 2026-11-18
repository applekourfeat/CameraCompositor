# Camera Compositor

Android camera with on-demand scene planning, a live visual framing guide, and quality-priority photo capture. Version 0.2.0 is a personal test build. The owner leads final UI/UX design.

## What changed

- **Find a composition:** sends one reduced scene image to Google Gemini, which proposes a framing and explains its visual intent.
- **Visual guidance:** a target-frame thumbnail, an outline, and an arrow replace repeated left/right text instructions.
- **Automatic zoom:** adjusts gradually near the selected frame, within the phone's available range and at most 1.8× relative to the scanned view. Pinching takes manual zoom control.
- **Scene tracking:** OpenCV optical flow and feature matching follow the selected scene, including texture that a generic object detector might miss. Temporary tracking loss pauses alignment and zoom; the shot plan stays available for reacquisition.
- **Photo capture:** quality priority, standard high-resolution 4:3 JPEG, and manufacturer Auto/HDR processing when the phone exposes it. Night is selectable when available. Analysis pauses during manufacturer capture because extensions cannot reliably run it concurrently.
- **Offline manual fallback:** Options > Try the framing guide without AI lets you select a framing and test tracking/zoom without uploading a frame.

Android 10+, ARM64. Primary test phone: Vivo V30. This build compiles and has automated geometry/protocol checks. The new camera pipeline and live API call still require physical-phone testing.

## Install and use

Install `artifacts/CameraCompositor-0.2-debug.apk` when supplied. It updates the earlier debug app on this computer's signing key.

1. Open **Options > AI setup** and enter your own Gemini API key privately on the phone. Default model: `gemini-3.8-flash`.
2. Tap **Find a composition** and review the first-upload disclosure.
3. Hold the scanned scene while the request finishes. Follow the outline and arrow, then use the shutter when the framing settles.

See [AI setup](docs/AI_SETUP.md) and [phone testing](docs/PHONE_TESTING.md). Capture works without an API key or internet connection.

## Data, keys, and cost

An explicit Find button press sends one JPEG of the visible viewfinder to Google's Gemini API, after the owner allows that behavior. Live tracking stays on-device. Full-resolution saved photos stay on the phone. Google may use free-tier inputs for product improvement; see [provider pricing/privacy](https://ai.google.dev/gemini-api/docs/pricing).

The owner enters a personal test key on the phone. It is encrypted with Android Keystore, excluded from backup, and never compiled into the APK. Setup masks the field and protects that dialog from screenshots. Requests use HTTPS and an authentication header; credentials are excluded from URLs, request JSON, logs, and provider error displays. Options can remove the saved key.

There are no automatic retries. This build limits scan attempts to 20 per phone per day and one every 15 seconds. These are development guardrails, not a provider-side spending cap. API availability, free-tier eligibility, rates, and quotas belong to the owner's Google project. Making a public app free requires a separate cost plan and a backend that protects the service key. Never publish a shared provider key inside a mobile app.

## Build

Open this folder in [Android Studio](https://developer.android.com/studio), sync Gradle, and run on a connected phone. SDK Platform 35 and Build Tools 35.0.0 are used.

The existing project-local toolchain can also build without Android Studio:

```powershell
./scripts/build.ps1
```

This runs `assembleDebug`, `testDebugUnitTest`, and `lintDebug`. With your own Java/Android SDK configured, use `./gradlew.bat assembleDebug testDebugUnitTest lintDebug`.

The portable toolchain is under ignored `.tools/`. `scripts/bootstrap_windows.py` can provision it on another Windows computer. Review [Android SDK terms](https://developer.android.com/studio/terms) first; the script downloads official tools, accepts SDK package licenses, and installs SDK 35.

## Code map

| Component | Responsibility |
| --- | --- |
| `MainActivity.kt` | Permission flow, scan/cancel state, visual guide, shutter, camera switching, options |
| `CloudShotPlanner.kt` | One cancellable HTTPS scene request; no background image streaming |
| `GeminiProtocol.kt` | Structured request and validated response, independent of Android camera objects |
| `PlannerSettings.kt` | Encrypted personal key, upload consent, local scan allowance |
| `SceneFrameAnalyzer.kt` | Camera frame extraction and sensor-to-view coordinate mapping |
| `SceneTracker.kt` | Optical flow, feature matching, homography checks, plan identity |
| `ShotGeometry.kt` | Crop bounds, alignment, zoom limits, manual framing choices |
| `CameraQuality.kt` | Runtime manufacturer-extension capability checks |
| `GuideOverlay.kt` / `ViewfinderFrame.kt` | Visual target and 4:3 preview layout |

## Verification and remaining work

[Verification](docs/VERIFICATION.md) distinguishes automated checks, model experiments, and device tests. The earlier on-device model experiments failed the required consistency/speed checks; their large models and runtimes are excluded from this app. [Research findings](docs/SCENE_MODEL_FINDINGS.md) record that decision.

Current scope: framing proposals inside the scanned view, portrait 3:4 photos, scene tracking rather than independent tracking of moving people, and a temporary functional interface. Physical perspective changes require moving the phone. Scene quality, cloud latency, feature tracking on real movement, and manufacturer capture quality must be measured on the Vivo. [Product plan](docs/PRODUCT_PLAN.md), [GitHub guide](docs/GITHUB.md).
