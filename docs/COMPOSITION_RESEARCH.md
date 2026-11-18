# Composition research, 18 November 2026

GAIC is the strongest candidate tested in this round. Its crop rankings agree substantially better with the dataset's human ratings than NIMA's rankings. This supports further GAIC research. It does not establish that people prefer its camera suggestions, that it discovers better physical angles, or that the Vivo meets the speed target.

The primary button in version 0.3 is a local research comparison. It requires no API key. The owner reviews a proposed crop before choosing to use the framing guide. No model has release approval.

## What the reference cameras disclose

| System | Supported finding | Limit of the evidence |
| --- | --- | --- |
| [GudoCam](https://gudocam.com/en/) | Its first reference video identifies the app. Its [privacy policy](https://gudocam.com/en/privacy), sections 13 and 14, describes a ResNet composition model, LLM advice, and temporary server processing of camera images. | Marketing describes basic local analysis. The exact boundary between local and server processing, training data, weights, latency and accuracy are unpublished. |
| [Framed](https://shootframed.com/) | The second clip appears to match its palm-tree camera demonstration. Its [App Store listing](https://apps.apple.com/us/app/framed-take-aesthetic-photos/id6761207600) distinguishes automatic scene scanning from manually drawn frames. Its [privacy policy](https://shootframed.com/privacy.html) discloses third-party AI processing. | No public model, evaluation set, tracking algorithm or measured latency was found. A successful promotional example establishes a demonstrated interaction, not general reliability. |
| [Google Camera Coach](https://support.google.com/pixelcamera/answer/17367411?hl=en) | Google documents scene analysis, guided steps, rescanning, internet dependence and a 30-second connection timeout. | It is an integrated Pixel feature. These documents do not supply a transferable implementation for Vivo. |
| [Samsung Shot suggestions](https://www.samsung.com/in/support/mobile-devices/how-to-use-shot-suggestions-galaxy/) | Samsung documents suggested composition and live alignment. | It does not publish the trained model or an Android library that other camera apps can use. |
| [GridCam](https://gridcam.app/) | The project describes MobileNetV2-based recognition of four composition styles. | Recognizing a grid style is a different task from choosing an interesting subject or a better viewpoint. Its claims are not our benchmark results. |

Rewatching the second clip identifies the valuable decision: choosing the sunlit palm crown. Tracking and zoom subsequently execute that choice. The current research separates subject/shot selection, crop refinement, and geometric tracking rather than treating tracking success as proof of a good photograph.

## Relevant research

[Towards Smart Point-and-Shoot Photography, CVPR 2025](https://arxiv.org/html/2505.03638v1), or SPAS, explicitly studies camera-pose adjustment rather than only cropping. A composition assessor supplies labels, and a pose model first decides whether adjustment is warranted, then suggests yaw/pitch changes. Its user study involved 25 students; suggested adjustments were preferred in 64% of cases and judged worse in 27%. Its no-adjustment decision was accepted in 82%. These are paper-specific results, not a guarantee for this app. The [public PCARD repository](https://github.com/image0202/PCARD-dataset) provides a dataset link but no ready-to-integrate trained phone model. Street View-derived data rights need separate checking before training or redistribution.

[ShutterMuse](https://github.com/lijayuTnT/ShutterMuse) distinguishes keep/refine/reject decisions and detailed crop localization. It is useful as an evaluation reference. Its released checkpoint is based on Qwen3-VL-8B, and its repository's license section says licensing information still needs adding. No ShutterMuse code, weights or generated pose images are included in this app. Its illustrated pose renderings should not be treated as physical-camera evidence.

The earlier SmolVLM2/Qwen2-VL experiments tested two general-purpose language models on a very small sample. They did not justify rejecting all local vision models. The present experiments test specialized visual models directly.

## Reproducible experiment

The authors' GAICv2 archive supplies 20 development photos from its validation split and 60 separate evaluation photos from its test split. Selection is deterministic using seed `CameraCompositor-2026-11-18-v1`. All 80 image SHA-256 hashes are distinct. No training-split photo is selected. The manifest, exact source revisions, archive hashes, package lock and raw results are stored under `research/`.

Two experiments are kept separate:

1. **Annotated crop ranking:** score the dataset's rated crops and compare their order with human mean opinion scores. Spearman correlation measures ranking agreement. MOS regret is the rating difference between the highest-rated available crop and the model-selected annotated crop.
2. **Camera candidate selection:** score the exact nine fixed, same-aspect crop options from the prototype, including keeping the full view. Test reversed candidate order and five synthetic perturbations: brightness changes of plus/minus 4% and small image-window shifts. These are synthetic stability checks, not actual camera rotation.

Timings below use two CPU threads on this Windows desktop, after model initialization. They include per-image preparation and nine-crop selection. Model loading, phone runtime, camera acquisition and preview rendering are separate measurements.

| Metric, 60 evaluation photos | MobileNet NIMA | GAIC MobileNet CPU port |
| --- | ---: | ---: |
| Mean rank correlation with annotated human crop ratings | 0.251 | 0.843 |
| Mean MOS regret, dataset rating units | 1.060 | 0.251 |
| Median nine-crop selection | 154.8 ms | 21.6 ms |
| p95 nine-crop selection | 165.0 ms | 24.0 ms |
| Same selected crop after synthetic perturbation | 74.3% | 97.0% |
| Selected the original full view | 28/60 | 40/60 |
| Maximum score difference after reversing candidate order | 0.0000015 | 0 |
| Human preference over the starting photo | Pending | Pending |
| Vivo selection time and thermal behavior | Pending | Pending |

GAIC was developed for this dataset family, which favors it in this comparison. Possible overlap between NIMA's AVA training images and the source photos is unknown. The photographs are often already deliberately composed. Contact-sheet inspection finds scenery, buildings, streets, people, animals, some plants and objects, and some dark scenes. The set is scenery-heavy and lacks sufficient casual-phone plant/object examples, sensor-noisy low-light captures and measured physical sweeps. It is an initial static benchmark, not a representative release qualification set.

## Port and export checks

The original GAIC code depends on old custom CUDA ROI/region-of-discard operators. The research CPU port follows their published forward equations, including boundary handling and sampling positions. Scalar-reference tests cover those operations. The checkpoint's active layer names load strictly; its omitted inference-time dropout has no active inference effect. Direct parity against the upstream CUDA binary has not been measured.

The Android candidate uses a standard-operator ONNX export. Across all 20 development photos, the ONNX runtime preserved every crop choice, with maximum MOS difference `0.00000190735` from the CPU port. The exported file is 11,651,155 bytes; SHA-256 `0853d7468f78f391730ee39c07daf41e0f600caba3bc2731ee086e63fab766ee`. Desktop ONNX inference alone had a 7.5 ms median. That number excludes image preparation and is not a phone timing.

NIMA's optional FP16 LiteRT export preserved all 20 development choices but changed a score by as much as 0.05744, exceeding the chosen 0.02 conversion tolerance. The export was rejected and excluded from the APK. Its original desktop results remain a comparison baseline.

## Human comparison and controls

`artifacts/composition-research/review.html` contains randomized, model-anonymous comparisons and local progress storage. It sends no requests. Downloaded answers can be analyzed using `research/summarize_review.py`. Missing votes, missing starting-view labels and insufficient improvable scenes remain pending; they cannot produce a pass. Ties count as no improvement.

The tool contains 40 development comparisons against the original and 120 evaluation comparisons against the original, plus an equal number of optional comparisons with a fixed upper-right crop control. This control uses an existing crop option. It does not reproduce version 0.1's ML Kit detector and physical pan instructions. A matched real-phone comparison with the old heuristic is still required. No volunteer or photographer ratings have been fabricated or substituted with AI judgment.

The first six seeded evaluation examples are generated by `research/make_examples.py`. They are visual inspection examples, not hand-picked successes. The reviewer scripts and 320 distinct trial mappings passed local syntax/data checks. The automated in-app browser refused `file:` navigation, so interactive browser rendering remains unverified.

An additional illustrative check used the owner's palm-tree reference frame, excluded from the 80-photo benchmark. Both NIMA and GAIC selected the full original view. Neither reproduced the reference app's palm-crown shot. That screenshot retains its viewfinder grid, and our nine candidates and 1.8x relative zoom limit do not reproduce the clip's wider zoom range or physical rotation. The current crop rankers therefore fail to demonstrate the intended shot-finding behavior on this example, despite GAIC's strong dataset result. See `research/results/reference-check.json` and the generated `reference-comparison.jpg`.

Recommendation: retain GAIC as an experimental refinement/ranking component. Investigate semantic subject/shot proposals over observed nearby views and evaluate those proposals with the owner's preferred photographic intent before automating guidance. A good score for an existing wide photograph does not supply that missing subject choice. The sweep recorder provides input for that next experiment; it is not a completed angle-discovery system.

## Current app behavior and remaining gates

- **Local research comparison:** the primary button runs the GAIC candidate on one reduced camera view. A kept view produces no movement command. A crop proposal requires the owner's explicit choice to use its guide. No calibrated confidence, subject understanding or better-photo claim is shown.
- **Observed-view research:** an explicit four-second local recording saves eight reduced views, approximate relative device-rotation samples, zoom limits and view transforms. Export uses Android's file picker. `research/evaluate_sweep.py` can rank actual observed views offline. Cross-view calibration, camera-pose calibration and automatic sweep guidance remain unvalidated.
- **Decision contract:** `Keep`, `ObserveMore` and `Guide` distinguish the three outcomes. The experimental policy rejects stale/different-scene views, impossible zoom, malformed crops and candidates that discard an explicitly identified subject. Its default gain/confidence settings are scaffolding, not learned calibration. It does not drive the production button.
- **Tracking lifetime:** geometric evidence alone never counts as aesthetic approval. Lost tracking expires the guide after three seconds. Pinching takes manual zoom control. Guide cancellation, pause/resume and capture restoration remain device checks.
- **Camera quality:** CameraX is updated to 1.6.2 with SDK 36 compilation and a compatible build plugin. The app queries exposed camera IDs, focal lengths, JPEG sizes, RAW/OIS/HDR capability and vendor extensions at runtime. Android's [extension guidance](https://developer.android.com/media/camera/camerax/extensions-api) warns about support removal on some devices using older CameraX releases. No equivalence with Vivo's stock processing is claimed without paired photos.

Release remains closed until human preference reaches 70% on improvable held-out scenes, the old heuristic comparison is favorable, already-good views/subject preservation/stability pass, licenses are clear, and a physical Vivo session meets the two-second selection target with responsive preview over five minutes. The current report recommends GAIC for the next research step, not automatic release.

## Cost and distribution

The default local experiment needs no account, API key or paid compute. Optional cloud scans remain personal diagnostics. [CLOUD_GATEWAY.md](CLOUD_GATEWAY.md) evaluates a managed keyless-user route under a strict zero-billing constraint.

NIMA's implementation repository is Apache 2.0. GAIC's code repository contains an MIT license, but checkpoint redistribution and dataset-image rights have not been explicitly established. Source license and model/data permissions are separate. Photos and model binaries remain ignored research files; the GAIC asset is added only to the private debug build. Release assets do not include it. Public APK/model distribution stays gated on permission verification. SPAS and ShutterMuse remain research references.
