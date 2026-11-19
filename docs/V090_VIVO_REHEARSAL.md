# Vivo V30 rehearsal build

Install `artifacts/CameraCompositor-0.9.0-vivo-debug.apk` as an update to the earlier prototype. This private build is the ARM64 package for the standard Vivo V30.

1. Open the app and aim at one clear daytime subject with room around it. The camera starts wider if Android exposes that zoom range.
2. Turn slowly across the scene once and pause when a framing outline appears. The target preview shows the proposed framing. Follow the outline and give the zoom time to settle.
3. Hold for the countdown when Auto shutter is on, or tap **Take photo**. Open **Last photo** to verify the saved framing.

The camera keeps the current view or asks for another observed angle when its evidence is weak. **Skip shot** dismisses a proposal. Pinching gives you control of zoom, and a tap selects a specific subject.

## What the candidate does

The model proposes several framings around distinct visible subjects. It considers up to five observations over about 1.24 seconds, plus acquisition time. A candidate from an earlier observation can compete when the camera can map it reliably into the current image. The scorer compares these candidates and the full current image in one batch. Subject completeness, separation, and selected style affect the choice. A movement suggestion outside the current view leads to new observations before a shot is selected. The app saves the camera's actual JPEG.

The build requests the minimum zoom exposed by the rear camera when that is below 1×. On a device exposing 1× as its minimum, the same search works with a short turn. Live lens access and transitions need rehearsal on the Vivo V30. [CameraX zoom range](https://developer.android.com/reference/androidx/camera/core/ZoomState).

## Evidence to check before class

Desktop replay runs the subject models, learned crop scorer, and Android selection policy on recorded stills. The comparison sheet shows the actual input and the proposed frame. The lighting, lens access, stability, and saved-photo framing still require a live rehearsal. The broad human preference gate remains pending.

| Replay | Choice |
| --- | --- |
| Palm clip, opening wide view | Compared seven frames and proposed the upper-right crown. |
| Palm clip, after the turn | Compared nine frames and kept the wide view. |
| Owner's skyline image | Compared nine frames and proposed a shot that retains the towers and removes the balcony railing. |
| Flat control | No distinct subject frame. |

The selected frames place more emphasis on the identified subject. The learned crop score alone favored the wider view in both examples. The result combines that score with subject framing and separation. [Desktop replay results](../research/results/vivo-rehearsal-replay.json).

The signed ARM64 APK passed the Android build, 91 unit tests, lint with zero errors, native alignment checks, and package integrity checks. The research suite also passed 20 tests. The APK uses the same debug certificate as 0.8.0. [Build verification](../research/results/delivery-verification-v090_vivo.json).

The contextual checkpoint is opt-in for local debug use. Its code notice is bundled and the release build excludes the checkpoint. See the model provenance in [Third-party components](THIRD_PARTY.md).

```powershell
# Prepare the dynamic ONNX export using the research instructions before packaging.
New-Item -ItemType Directory -Path .local/vivo-demo-assets -Force
Copy-Item .local/shot-discovery-assets/gaic-dynamic.onnx .local/vivo-demo-assets/
Copy-Item research/GAIC-LICENSE.txt .local/vivo-demo-assets/GAIC-CODE-LICENSE.txt
./scripts/build.ps1 -Tasks assembleDebug,testDebugUnitTest,lintDebug,mergeReleaseAssets,'-PvivoDemo=true'
```
