# Camera Compositor

Native Android camera research for useful composition guidance. Version **0.3.1** corrects the blocking reports and stale control state reported by the owner. The owner leads final UI/UX. [Usability correction and new Vivo evidence](docs/V031_USABILITY_FIX.md).

Latest owner feedback exposes poor shot discovery in the city scene. The owner requires understanding the environment, viewpoints and perspective. [The new environment-composition investigation](docs/ENVIRONMENT_COMPOSITION_RESEARCH.md) reproduces that failure, tests a broader subject-proposal pipeline and measures an initial 3D geometry backend against real camera poses. Both remain below the product gates. Unreleased source adds recording geometry metadata; no improved photographic APK is delivered by this investigation.

The local model requires **no API key or internet connection**. It is a research candidate whose photographic quality and Vivo performance still need acceptance testing. Read [the measured research results](docs/COMPOSITION_RESEARCH.md).

## Try the research build

Install `artifacts/CameraCompositor-0.3.1-debug.apk` on the Vivo V30. Android 10+, ARM64. It uses this computer's existing debug signing key and can update the earlier prototype.

1. Tap **Find a frame**. The model compares the current view with eight crops. If it selects the full view, a short status reports that no suggestion was found. This is abstention, not a rating of the photograph.
2. For a crop suggestion, compare the two images. Choose **Use frame** or **Dismiss**. You can take a photo while a scan runs; that cancels the scan. Pinching takes manual zoom control during a guide.
3. **Options > Developer tools > Record a local evaluation sweep** records eight reduced views and relative rotation samples in about four seconds. Nothing is uploaded. Export it through the file picker when convenient.
4. **Options > Developer tools > Camera capability report** queries camera access on this actual phone.

The framing guide expires after three seconds of lost tracking. Saved photographs use quality-priority full-resolution capture, with manufacturer processing when exposed. Photo quality compared with Vivo's stock camera remains unmeasured.

## Research results and review

On 60 held-out GAICv2 test photos, GAIC's average crop-rating rank correlation was 0.843, compared with NIMA's 0.251. Its nine-crop selection had a 21.6 ms median on this Windows CPU; phone speed remains pending. These measurements do not establish the 70% human-preference gate or physical angle discovery.

Open `artifacts/composition-research/review.html` in your desktop browser to compare model-anonymous framings. Progress stays in that browser; download answers to keep a copy. [Reproduction instructions](research/README.md), [raw results](research/results/), [remaining product gates](docs/PRODUCT_PLAN.md).

## Optional cloud diagnostics

The old Gemini path is under **Options > Developer tools > Cloud research scan (personal key)**. It retains encrypted phone-only key storage and explicit upload consent. It is separate from the default local feature. **Test service with a generated image** uploads a synthetic chart through the same composition request format without uploading the viewfinder or granting future camera-upload consent.

**Scan diagnostics** reports sanitized connection, upload, response and parsing events. A 35-second total deadline and cancellation bound each scene request. No request images, keys or provider error bodies are written to diagnostics. Quota errors do not trigger automatic retries.

The owner's instrumented generated-image request reached WAIT_RESPONSE at 187 ms and timed out at 30,189 ms without an HTTP response code. The specific cause remains unresolved. [Diagnostic setup](docs/AI_SETUP.md).

End users can eventually use managed online assistance without supplying credentials. [The Firebase evaluation](docs/CLOUD_GATEWAY.md) describes an unbilled Spark project, enforced App Check, limited shared free capacity and a local fallback. No managed service has been deployed.

## Build

The project uses CameraX 1.6.2, SDK 36 compilation, target SDK 35, minimum SDK 29, AGP 8.10.1, Gradle 8.11.1 and Kotlin 2.3.20. The local Java toolchain is under ignored `.tools/`.

```powershell
./scripts/build.ps1
```

This runs `assembleDebug`, `testDebugUnitTest` and `lintDebug`. Android Studio can also open the project normally. `scripts/bootstrap_windows.py` provisions the local SDK/toolchain on another Windows computer; review the Android SDK terms first.

The experimental model is injected into **debug assets only** from ignored `.local/research-assets`. Run the research export before building to include it. A fresh checkout builds without a model and explains the absent research asset. Release builds contain no research checkpoint. Public model/APK distribution remains gated on checkpoint permission and photographic/device acceptance.

## Code responsibilities

| Component | Responsibility |
| --- | --- |
| `LocalResearchScorer` | Local GAIC preprocessing and inference, separate from camera control |
| `CompositionDecision` | Experimental keep/observe/guide contract, view identities and subject/zoom constraints |
| `SceneFrameAnalyzer`, `SceneTracker` | Camera-coordinate extraction and geometric feature tracking |
| `ShotGeometry`, `GuideOverlay` | Framing rules and viewfinder drawing |
| `ResearchSweepRecorder` | Explicit local capture of bounded observed views and relative rotation |
| `SceneHttpClient`, `ScanDiagnostics` | Bounded/cancellable online research requests and sanitized stages |
| `CameraQuality`, `CameraCapabilityReport` | Exposed manufacturer processing and camera inventory |

[Verification](docs/VERIFICATION.md) distinguishes software checks, desktop experiments, owner feedback and outstanding physical-device tests. [Phone test steps](docs/PHONE_TESTING.md), [third-party notices and model permissions](docs/THIRD_PARTY.md), [GitHub guide](docs/GITHUB.md).
