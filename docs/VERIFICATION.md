# Version 0.3 research verification

Latest unreleased investigation: [environment-composition evidence](ENVIRONMENT_COMPOSITION_RESEARCH.md). Android `assembleDebug`, `testDebugUnitTest` and `lintDebug` pass after timestamp/transform/capability-recording changes. The 35 Android tests and 17 Python research tests pass. No model or imagery from the new experiments is added to Android assets. No new photographic APK is delivered; versioned artifact results below describe their original builds. The real-image 3D backend fails accuracy expectations and remains disconnected from guidance. Vivo environment recording, calibration, tracking and speed remain untested.

Version 0.3.1 corrects the reported usability/state failures. See [V031_USABILITY_FIX.md](V031_USABILITY_FIX.md) and `research/results/delivery-verification-v031.json` for the update. The version 0.3.0 measurements below are historical.

Date: 18 November 2026. The [research report](COMPOSITION_RESEARCH.md) and `research/results/` separate measured desktop behavior from pending human/device evidence.

## Android software checks

`scripts/build.ps1` completed `assembleDebug`, `testDebugUnitTest` and `lintDebug` successfully. The final build passed **29 unit tests**, with zero failures/errors. Android lint completed with **0 errors and 51 warnings**. The warnings include English-only research strings, prototype portrait/ARM64 scope, styling/KTX preferences and newer optional dependencies. No physical-phone or emulator run is implied.

The tests cover crop/alignment geometry, hold/reset and zoom limits, invalid/truncated provider outputs, observed-frame identities, already-good views, stale-scene rejection, selected-subject preservation policy and guide expiry. Real local-server HTTP tests cover sanitized stage reporting, delayed-response timeout, cancellation followed by a fresh request, response-size limits, quota errors without retries, and redirect rejection.

The update uses CameraX 1.6.2. Its SDK 36 compile requirement was handled by installing SDK 36 and moving to AGP 8.10.1, which supports the existing Gradle 8.11.1 wrapper. Minimum SDK 29 and target SDK 35 remain unchanged. The project bootstrap now writes escaped Windows SDK paths; lint's earlier property-path error was corrected.

`mergeReleaseAssets` succeeded separately. Inspection confirms the release asset set contains no research `.onnx`, `.tflite` or `.litertlm` weights. Only the private debug build receives the exported GAIC asset. This is an asset-set check, not a claim that a signed production release has been qualified.

## Research checks

- The 20 development/60 evaluation manifest contains 80 distinct image SHA-256 hashes, excludes the author's training split and reproduces deterministic selection.
- Both models completed annotated-crop ranking, nine-camera-crop selection, reversed-order checks and five synthetic perturbations on all 80 scenes. The raw results and pinned download metadata are preserved.
- Python research tests cover the CPU gather operators against scalar equations, candidate geometry, split separation, local-sweep validation and missing/partial/mismatched human-review results.
- The ONNX export preserved every development-set selection with maximum score error below 0.000002. Android/ARM numerical parity remains pending.
- The optional NIMA FP16 conversion failed the 0.02 score-error tolerance and was excluded from the APK.
- The local reviewer has 320 distinct randomized trial mappings. JavaScript syntax passed Node's parser. Ninety-one full-view candidate trials produce byte-identical left/right JPEGs, preventing extra compression from biasing those comparisons.
- The first six seeded evaluation comparisons and the owner's reference comparison were inspected as images. Both models kept the original palm-tree reference view. That check is excluded from held-out metrics.
- The browser tool blocked local `file:` navigation. No alternate browser/network workaround was used. Interactive reviewer rendering and downloads remain a manual browser check.

## Artifact checks

Delivered private research APK: `artifacts/CameraCompositor-0.3-research-debug.apk`, **75,180,791 bytes**. The stable `CameraCompositor-debug.apk` alias contains this same build. Earlier versioned APKs remain available.

SHA-256: `2499790516fa2877c31a280af520525c596f6ff7ccd796ad35e01bacb6c5e60b`.

Android SDK apksigner verified its v2 signature. Certificate SHA-256: `eaee0220bb17b9ddd0bd08bcae877bc08c5fde6bf7c5bd1534ef43be7d5cf70d`. The binary includes the expected GAIC model hash and ARM64 libraries. It contains no NIMA, large VLM or training-model files. The added library notices are packaged.

`scripts/check_delivery.py` passed source/APK credential-pattern checks without printing possible matches, checked the model hash, validated the test/lint results and inspected release-asset exclusion. No personal key, camera sweep, source dataset photo or model binary is committed. Model/checkpoint distribution remains gated separately from source licensing.

## Evidence still required

The owner's version 0.2 report establishes that its manual guide appeared to work and its online scan displayed a timeout. The exact transport stage remains unknown. Version 0.3 adds a generated-chart diagnostic and stage timings so the private phone request can supply that evidence without sharing a key or viewfinder image in its report.

Pending items include the real provider request, actual Vivo capabilities, local model startup/warm timing, guide direction/zoom and pause/resume, observed-view export, saved-photo quality against Vivo's camera, five-minute responsiveness/thermal behavior, actual human preference and the physical old-heuristic comparison. No 70% preference, two-second Vivo selection time, native-quality equivalence, calibrated semantic subject selection or reliable automatic angle discovery is claimed.
