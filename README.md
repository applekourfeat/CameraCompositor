# Camera Compositor

Native Android camera research for useful composition guidance. Version **0.4.1-spatial** connects SDK camera poses, intrinsics and available depth to multi-view planning, with visual registration back to the full-resolution photo camera. The owner's Vivo V30 export confirms ARCore pose/point/depth output. The owner leads final UI/UX. [Implementation, evidence and limits](docs/V041_SPATIAL_PLANNING.md).

The owner's city-scene feedback requires understanding the environment, viewpoints and perspective. [The preceding investigation](docs/ENVIRONMENT_COMPOSITION_RESEARCH.md) explains the crop-only failure. The new flow observes several views and requests a subject/relationship graph before choosing a shot. Photographic quality and Vivo performance remain unqualified.

Camera capture, the manual guide, legacy local ranking and supported-device spatial testing operate independently of the personal AI service. The new photographic planner uses the saved research key and explicit multi-view upload consent. The eventual public app's keyless gateway remains unimplemented.

## Try the research build

Install `artifacts/CameraCompositor-0.4.1-spatial-debug.apk` on the Vivo V30. Android 10+, ARM64. It uses this computer's existing debug signing key and can update the earlier prototype.

1. Tap **Find a composition**. After personal research setup and image/pose/depth consent, supported rear cameras collect six spatial views through ARCore; other paths use image overlap. Turn gently across a small scene, then hold the last view while the photo camera matches it. Up to four observations are sent for assessment.
2. A suggestion references an actually observed viewpoint and subject. Review **Use this view** or **Dismiss**. A return guide follows measured intermediate views; missing overlap or stale tracking causes abstention. Pinching cancels an environment route and returns zoom control. Taking a photo cancels a scan.
3. **Options > Developer tools > Test spatial tracking (ARCore)** retains the separate local pose/point/depth export without an AI key. The primary sweep now has its own spatial capture path feeding the photographic planner. Continuous world-pose guidance during CameraX capture remains unfinished.
4. **Camera capability report**, the explicit eight-view research recorder and **Legacy local crop research** remain under Developer tools. These diagnostics are separate from proof of photographic quality.

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

The legacy model is injected into **debug assets only** from ignored `.local/research-assets`. A fresh checkout builds without that asset; multi-view observation/planning and spatial testing are separate from it. Release builds contain no research checkpoint. Public model/APK distribution remains gated on checkpoint permission and photographic/device acceptance.

## Code responsibilities

| Component | Responsibility |
| --- | --- |
| `LocalResearchScorer` | Local GAIC preprocessing and inference, separate from camera control |
| `EnvironmentMotionTracker`, `EnvironmentSweep`, `EnvironmentMap` | Continuous image tracking, bounded scene observations and measured return routes |
| `EnvironmentProtocol`, `CloudShotPlanner` | Structured subject/relationship assessment and constrained observed-view selection |
| `SpatialProbeActivity` | Isolated optional ARCore camera pose, sparse point and depth sampling |
| `CompositionDecision` | Experimental keep/observe/guide contract, view identities and subject/zoom constraints |
| `SceneFrameAnalyzer`, `SceneTracker` | Camera-coordinate extraction and geometric feature tracking |
| `ShotGeometry`, `GuideOverlay` | Framing rules and viewfinder drawing |
| `ResearchSweepRecorder` | Explicit local capture of bounded observed views and relative rotation |
| `SceneHttpClient`, `ScanDiagnostics` | Bounded/cancellable online research requests and sanitized stages |
| `CameraQuality`, `CameraCapabilityReport` | Exposed manufacturer processing and camera inventory |

[Verification](docs/VERIFICATION.md) distinguishes software checks, desktop experiments, owner feedback and outstanding physical-device tests. [Phone test steps](docs/PHONE_TESTING.md), [third-party notices and model permissions](docs/THIRD_PARTY.md), [GitHub guide](docs/GITHUB.md).
