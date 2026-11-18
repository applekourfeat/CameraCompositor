# Camera Compositor

An Android camera prototype for live composition assistance. Working name and temporary native controls. The owner designs the final UI/UX.

## Current milestone

- Rear/front camera preview, tap to focus, pinch to zoom.
- JPEG capture to `Pictures/CameraCompositor`, with a button to open the latest shot.
- On-device ML Kit detection of one prominent subject.
- Manual left-third, center, or right-third framing targets.
- Live pan instructions, subject outline, grid, and a target marker.
- A 650 ms hold before alignment turns green; expired results are cleared.
- Guide toggle disables image analysis to reduce work.

Android 10 or newer. First test device: Vivo V30. Native Kotlin and CameraX.

This milestone locates a subject with ML and applies geometric rules. It does **not** yet choose a composition based on scene meaning, detect a horizon, understand a person's pose, rate a photo, or reproduce the scene-scan and automatic zoom behavior in the reference videos. The subject box center is the alignment anchor, so a person and a landscape will need richer framing logic later.

## Try the APK

After a successful build, use `app/build/outputs/apk/debug/app-debug.apk`, or the convenient copy in `artifacts/CameraCompositor-debug.apk` when supplied. These generated files are excluded from Git.

See [the phone testing guide](docs/PHONE_TESTING.md) for installation and the first test session. A Play Store account is unnecessary for local testing.

## Build

Recommended beginner workflow: install [Android Studio](https://developer.android.com/studio), open this folder, let Gradle sync, then run the app on a connected phone. Android Studio's bundled Java runtime works for this project. Install Android SDK Platform 35 and Build Tools 35.0.0 if prompted.

This computer also has an isolated toolchain under `.tools/`. It can build without Android Studio:

```powershell
./scripts/build.ps1
```

That runs `assembleDebug`, `testDebugUnitTest`, and `lintDebug`. With your own Java and Android SDK configured, use the standard wrapper instead:

```powershell
./gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

`scripts/bootstrap_windows.py` provisions the portable toolchain for another Windows machine. Review the [Android SDK terms](https://developer.android.com/studio/terms) before running it. It downloads Java 21, Gradle 8.11.1, and Android command-line tools from their official sources, accepts SDK package licenses, and installs SDK 35. It requires Python 3 and internet access. For most beginners Android Studio is the easier setup.

## How the code fits together

Think of the camera as a game render loop and the detector as a slower background system:

1. CameraX shows the camera stream and handles capture and focus.
2. A separate analyzer samples up to 10 frames per second at a requested 640 × 480 resolution.
3. ML Kit locates a prominent subject. CameraX maps its coordinates into the visible viewfinder.
4. `CompositionEngine` compares the subject center to the target you selected.
5. The overlay and one line of guidance update without drawing onto the saved photo.

Old analysis frames are dropped rather than queued. The 10-check limit is a work cap, not a measured frame-rate guarantee. Actual preview smoothness, inference latency, heat, battery use, and photo quality require physical-device testing.

| File | Purpose |
| --- | --- |
| `app/src/main/java/dev/cameracompositor/MainActivity.kt` | Camera lifecycle, permission flow, photo saving, detector wiring |
| `app/src/main/java/dev/cameracompositor/CompositionEngine.kt` | Framing rules, direction decisions, alignment hold |
| `app/src/main/java/dev/cameracompositor/ThrottledAnalyzer.kt` | Limits analysis work and forwards coordinate transforms |
| `app/src/main/java/dev/cameracompositor/GuideOverlay.kt` | Draws viewfinder guides |
| `app/src/main/res/layout/activity_main.xml` | Temporary screen layout |
| `app/src/test/java/dev/cameracompositor/CompositionEngineTest.kt` | Direction, clipping, missing-subject, and alignment tests |

## Model and connectivity

The ML Kit object detector downloads its model on first use. Keep internet available for the first launch, and expect an initial warm-up before detection starts. Camera capture can still work while detection is unavailable. Test offline after the model is ready.

The app has no accounts, cloud inference API, paid API key, or server backend. ML Kit processes images on the device. Its SDK can communicate for model delivery and diagnostics under [Google's terms and privacy documentation](https://developers.google.com/ml-kit/terms). The app's internet permission supports the model dependency.

## Design and next milestones

See [the product plan](docs/PRODUCT_PLAN.md). Final layouts, typography, colors, interactions, and professional/casual modes belong to the owner. Wireframes in Figma, sketches, and annotated screenshots can all guide implementation.

See [the GitHub guide](docs/GITHUB.md) for saving and publishing changes. Reference videos, decoded reference frames, build tools, local SDKs, APKs, and signing keys are excluded from version control.
