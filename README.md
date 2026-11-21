# Camera Compositor

The private Vivo V30 build chooses a framing automatically as you aim. It checks overlapping views taken a fraction of a second apart, so a useful landmark can stay in the composition when its outline briefly drops out. A gravity level helps you straighten the shot. [Landmark replay](research/results/landmark-window-replay.json) and [level guide](docs/V0120_LEVEL_AND_SUBJECTS.md).

Native Android camera research for live composition guidance. It can suggest a new angle when a subject is visibly cut off. UI/UX is yet to be worked on.

The main camera and automatic composition run offline on the regular Android camera. ARCore and personal-key online scans are optional research tools. The measured photographic preference study and Vivo performance checks remain pending.

## Try the research build

Install `artifacts/CameraCompositor-0.14.0-landmarks-debug.apk` on the Vivo V30. It uses this computer's existing debug signing key and can update the earlier prototype.

1. Aim at the scene and line up the outline when it appears. The camera chooses the framing. Tap a subject if you want to choose what to keep in the frame. **Find shot** checks the view again.
2. Line up the two short level marks with the moving line. For a straight-down shot, center the dot. On eligible shots, **Auto shutter** starts its three-second countdown when the framing is verified, level and steady. **Take photo** works at any angle.
3. **Options > Photo processing** defaults to **Camera JPEG**. Compatible cameras also offer manufacturer HDR, Night, Auto, and scene-aware selection. The photo status shows what actually saved the image. **Developer tools** contains camera capabilities, sweep export, optional ARCore testing and the personal-key online experiment.

The guide tries to recover from brief interruptions and reevaluates if it loses its target. Saved photographs use quality-priority high-resolution capture, with manufacturer processing when exposed. Photo quality compared with Vivo's stock camera remains unmeasured.

## Research results and review

The [composition research gallery](docs/composition-research/README.md) shows the starting skyline, candidate crops from two experiments, and the tighter view used to check whether the planner would keep an already framed shot.

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

To add the models included in the APK, run `research/export_software_depth.py`, `research/fetch_subject_model.py`, and `research/export_scene_semantics.py` with the installed research dependencies. A checkout without those assets uses the image-detail framing policy. `includeLegacyResearch=true` is a Gradle property for private debug builds with the older GAIC asset. [Asset setup and provenance](research/README.md).

## Code responsibilities

| Component | Responsibility |
| --- | --- |
| `LocalResearchScorer` | Local GAIC preprocessing and inference, separate from camera control |
| `EnvironmentMotionTracker`, `EnvironmentSweep`, `EnvironmentMap` | Continuous image tracking, bounded scene observations and measured return routes |
| `SoftwareDepth`, `SceneLayout`, `LocalComposition` | Optional RGB depth, visible subject and framing cues, and on-device viewpoint selection |
| `SceneRecognition`, `SceneDiscovery` | Local visual categories, subject candidates, and rotation proposals |
| `EnvironmentProtocol`, `CloudShotPlanner` | Structured subject/relationship assessment and constrained observed-view selection |
| `SpatialProbeActivity` | Isolated optional ARCore camera pose, sparse point and depth sampling |
| `CompositionDecision` | Experimental keep/observe/guide contract, view identities and subject/zoom constraints |
| `SceneFrameAnalyzer`, `SceneTracker`, `MotionOrientation` | Camera coordinates, image tracking and optional rotation estimates |
| `ShotGeometry`, `GuideOverlay` | Framing rules and viewfinder drawing |
| `ResearchSweepRecorder` | Explicit local capture of bounded observed views and relative rotation |
| `SceneHttpClient`, `ScanDiagnostics` | Bounded/cancellable online research requests and sanitized stages |
| `CameraQuality`, `CameraCapabilityReport` | Exposed manufacturer processing and camera inventory |

[Verification](docs/VERIFICATION.md) distinguishes software checks, desktop experiments, owner feedback and outstanding physical-device tests. [Phone test steps](docs/PHONE_TESTING.md), [third-party notices and model permissions](docs/THIRD_PARTY.md), [GitHub guide](docs/GITHUB.md).
