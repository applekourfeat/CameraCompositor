# Camera Compositor

Native Android camera research for useful composition guidance. Version **0.5.0-camera** compares real viewpoints using the ordinary CameraX stream. It runs offline and can add relative depth from RGB when overlapping views support it. The owner leads final UI/UX. [Compatibility experiment and evidence](docs/V050_CAMERA_COMPATIBILITY.md).

The main camera and composition button require neither ARCore, a depth sensor, nor an account. ARCore and personal-key online scans are available under Developer tools. The managed cloud gateway, photographic preference study and Vivo performance checks remain pending.

## Try the research build

Install `artifacts/CameraCompositor-0.5.0-camera-debug.apk` on the Vivo V30. It uses this computer's existing debug signing key and can update the earlier prototype. The builds target Android 10 and later. The `armv7` APK is for Android phones that run 32-bit ARM apps.

1. Tap **Find a composition** once. Turn gently across a small area, then hold when the app says it is checking the views. Holding still scans the current angle.
2. Review any suggestion and tap **Use this shot** to start the guide. The app can guide back through observed views and adjust zoom. Pinching takes over zoom. Taking a photo cancels a scan.
3. **Options > Developer tools** contains camera capabilities, local sweep export, optional ARCore testing and the personal-key online experiment.

The framing guide expires after three seconds of lost tracking. Saved photographs use quality-priority full-resolution capture, with manufacturer processing when exposed. Photo quality compared with Vivo's stock camera remains unmeasured.

## Research results and review

On 60 held-out GAICv2 test photos, GAIC's average crop-rating rank correlation was 0.843, compared with NIMA's 0.251. Its nine-crop selection had a 21.6 ms median on this Windows CPU; phone speed remains pending. These measurements do not establish the 70% human-preference gate or physical angle discovery.

Open `artifacts/composition-research/review.html` in your desktop browser to compare model-anonymous framings. Progress stays in that browser; download answers to keep a copy. [Reproduction instructions](research/README.md), [raw results](research/results/), [remaining product gates](docs/PRODUCT_PLAN.md).

## Optional cloud diagnostics

The old single-image path is under **Options > Developer tools > Cloud research scan (personal key)**. It retains encrypted phone-only key storage and its original upload consent. **Test service with a generated image** uploads a synthetic chart through that older request format without uploading the viewfinder or granting multi-view consent.

**Scan diagnostics** reports sanitized connection, upload, response and parsing events. A 35-second total deadline and cancellation bound each scene request. No request images, keys or provider error bodies are written to diagnostics. Quota errors do not trigger automatic retries.

The owner's instrumented generated-image request reached WAIT_RESPONSE at 187 ms and timed out at 30,189 ms without an HTTP response code. The specific cause remains unresolved. [Diagnostic setup](docs/AI_SETUP.md).

End users can eventually use managed online assistance without supplying credentials. [The Firebase evaluation](docs/CLOUD_GATEWAY.md) describes an unbilled Spark project, enforced App Check, limited shared free capacity and a local fallback. No managed service has been deployed.

## Build

The project uses CameraX 1.6.2, optional ARCore 1.57.0, SDK 36 compilation, target SDK 35, minimum SDK 29, AGP 8.10.1, Gradle 8.11.1 and Kotlin 2.3.20. The local Java toolchain is under ignored `.tools/`.

```powershell
./scripts/build.ps1
```

This runs `assembleDebug`, `testDebugUnitTest` and `lintDebug`. Android Studio can also open the project normally. `scripts/bootstrap_windows.py` provisions the local SDK/toolchain on another Windows computer; review the Android SDK terms first.

To add the optional MIT-licensed depth model, run `research/export_software_depth.py` with the installed research dependencies and development images. The build picks up the result from `.local/software-depth-assets`. A fresh checkout without that file uses RGB-only composition. `includeLegacyResearch=true` is a Gradle property for private debug builds with the older GAIC asset. [Asset setup and provenance](research/README.md).

## Code responsibilities

| Component | Responsibility |
| --- | --- |
| `LocalResearchScorer` | Local GAIC preprocessing and inference, separate from camera control |
| `EnvironmentMotionTracker`, `EnvironmentSweep`, `EnvironmentMap` | Continuous image tracking, bounded scene observations and measured return routes |
| `SoftwareDepth`, `SceneLayout`, `LocalComposition` | Optional RGB depth, visible subject and framing cues, and on-device viewpoint selection |
| `EnvironmentProtocol`, `CloudShotPlanner` | Structured subject/relationship assessment and constrained observed-view selection |
| `SpatialProbeActivity` | Isolated optional ARCore camera pose, sparse point and depth sampling |
| `CompositionDecision` | Experimental keep/observe/guide contract, view identities and subject/zoom constraints |
| `SceneFrameAnalyzer`, `SceneTracker` | Camera-coordinate extraction and geometric feature tracking |
| `ShotGeometry`, `GuideOverlay` | Framing rules and viewfinder drawing |
| `ResearchSweepRecorder` | Explicit local capture of bounded observed views and relative rotation |
| `SceneHttpClient`, `ScanDiagnostics` | Bounded/cancellable online research requests and sanitized stages |
| `CameraQuality`, `CameraCapabilityReport` | Exposed manufacturer processing and camera inventory |

[Verification](docs/VERIFICATION.md) distinguishes software checks, desktop experiments, owner feedback and outstanding physical-device tests. [Phone test steps](docs/PHONE_TESTING.md), [third-party notices and model permissions](docs/THIRD_PARTY.md), [GitHub guide](docs/GITHUB.md).
