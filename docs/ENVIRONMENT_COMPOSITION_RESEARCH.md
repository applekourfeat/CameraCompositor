# Environment-aware composition: diagnosis and next architecture

Follow-up implementation: [version 0.4 environment prototype](V040_ENVIRONMENT_PROTOTYPE.md) adds multi-view planning, native temporal image tracking, measured return routes and an isolated optional ARCore spatial backend. The investigation below records the earlier candidate failures. The main planner still lacks verified metric pose/depth, and photographic/device gates remain open.

Updated 2026-11-18. The owner clarified that the assistant must understand the surrounding scene, viewpoint and perspective. Choosing a crop from one image is an intermediate experiment. It does not fulfill the product requirement.

## What is implemented in this iteration

- A reproducible test of the owner's clear city screenshot and a general, broader shot-proposal experiment.
- A frozen proposal policy evaluated on the existing 20 development and 60 separate evaluation photographs.
- Separate ONNX exports for variable crop counts and a semantic image encoder, with numerical checks and a rejected quantization recorded.
- A calibrated two-view geometry prototype that matches real image features, estimates relative rotation/translation and triangulates sparse points. It refuses tested cases with inadequate parallax. Its recovered scale is explicitly unknown.
- Independent real-image geometry measurements against ETH3D's supplied camera poses. These expose substantial errors and reject this prototype for camera guidance.
- Android research recording now includes actual image timestamps, sensor-to-buffer and buffer-to-view transforms, source dimensions, sensor-sample timestamps and a camera capability inventory. Unknown camera pose and depth are stored as null. Camera2 capability reporting includes exposed intrinsic calibration, distortion, active sensor arrays, depth formats and timestamp source.

The default Find button still uses the existing nine-crop ranker. No environment map or scene-aware photographic planner is connected to it. This iteration produces research code and evidence, with unreleased recording improvements. It does not deliver an improved photographic APK.

## Why the city view failed

Both supplied clear screenshots are identical, SHA-256 `7383b0975081b06753f05d872c195a556b5460da3799993a1f32f9b2312f2a82`. The exact viewfinder rectangle is `(0,285)-(1080,1725)`, giving a 1080x1440 photograph. Extraction changes no scene content. Images and generated comparisons stay in ignored local directories.

The app considers the full view and eight fixed crops, capped at 1.8x relative zoom. On this input, full-view GAIC score is 4.632; it wins. The UI reports no suggestion. A dense search of 295 positions/scales still gives the full view the highest score. Therefore widening the search alone does not fix its decision.

The diagnostic semantic pipeline uses a fixed general vocabulary covering people, animals, plants, landscapes, architecture, vehicles and everyday objects, plus empty/obstructed-scene controls. It scores spatially diverse candidates at six zoom scales from 1.25x to 3.2x. A focus-search policy separates possible details from the full-view aesthetic score. Its thresholds and scale preferences were developed using the owner's scene and development set before evaluating the 60-photo split. The towers and bridge have no hand-authored target rectangles or scene-specific label list.

This pipeline selects a 1.8x tower framing and a separate 2.2x bridge framing on the owner input, removing the railing in both. See local `artifacts/composition-research/owner-city-comparison.jpg`. These are real crops selected by the experiment. They provide a useful diagnostic of subject proposals, while still falling short of the owner's environment-understanding requirement.

## Proposal evaluation and remaining failures

| Measurement | Development | Evaluation |
| --- | ---: | ---: |
| Images | 20 | 60 |
| Images receiving at least one possible detail | 18 | 55 |
| Median desktop processing | 751 ms | 757 ms |
| p95 desktop processing | 810 ms | 797 ms |
| Blind preference for a better photograph | Pending | Pending |
| Already-good view protection | Absent | Absent |
| Vivo runtime | Pending | Pending |

Measured sequentially with two Windows CPU threads, models loaded before per-scene timing. Timings include crop preparation, GAIC screening and semantic inference. The first scene includes first-image execution overhead. Model loading, phone runtime, camera acquisition and preview rendering are excluded. Counts describe proposal availability, never photographic success.

Agent visual inspection, outside the blind human gate, found useful flower, bird and architectural details, along with failures. The mountain-reflection development scene `gaic-val-398441` loses most of its reflection; its subject label is mountain despite the balloon. Evaluation scene `gaic-test-231629` loses the dramatic cloud-light arrangement when it zooms into a building. `gaic-test-274076` cuts away most of the boardwalk that supplies the original leading line. `gaic-test-220921` drops one of the two people from a scene with a visible interaction. Static content labels, low reprojection error and proposal frequency cannot establish photographic intent or subject preservation.

The existing dataset is scenery-heavy and often deliberately composed. It also shares a dataset family with GAIC's training. These limitations continue to apply. No human ratings were invented. The 70% preference gate remains pending; already-good and environment-understanding requirements are unmet. This candidate is excluded from the default app.

## Model revisions, export and permissions

TinyCLIP checkpoint: `wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M`, revision `95ec8197b3f2fe7f747865c61ca556cf0768b2f7`. Safetensors SHA-256: `1b08131ac0ac54429aab216c397c550527d31f5682afb5d21d35091ad39538ae`. Config, tokenizer and preprocessing files are also checked by pinned Git blob hashes in `fetch_semantic_assets.py`.

The [model card](https://huggingface.co/wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M/tree/95ec8197b3f2fe7f747865c61ca556cf0768b2f7) labels its license MIT; the [upstream TinyCLIP license](https://github.com/microsoft/Cream/blob/main/TinyCLIP/LICENSE) supplies the source terms. No TinyCLIP weights are packaged in Android. Existing GAIC checkpoint redistribution and source-photo rights remain unverified. ETH3D images remain local; redistribution permission was not established in this iteration. No dataset imagery is placed in the app or Git.

Semantic inference explicitly resizes each whole crop to 224x224 with bicubic interpolation and uses CLIP normalization. It avoids an implicit center crop, but stretching is itself experimental. Two general text templates are averaged for each label. Similarity margins serve as experimental subject evidence. They are not probabilities, depth estimates or beauty scores.

| Export measurement | Result |
| --- | ---: |
| Dynamic GAIC maximum error across 20 development images, 9 and 295 crops each | 0.00000263 |
| Float image encoder maximum feature error | 0.00000113 |
| Float image encoder size | 158,953,765 bytes |
| Int8 image encoder size before rejection | 45,365,746 bytes |
| Int8 minimum embedding cosine on 60 development probes | 0.9862 |
| Int8 maximum text-similarity error | 0.02059 |
| Int8 identical top label | 59/60 |

The initial fidelity gates required cosine above 0.99 and similarity error below 0.02. The int8 export failed and its binary was removed. Conversion success for the float encoder does not establish acceptable Android memory, latency or composition quality. Exported assets live in `.local/shot-discovery-assets`, which is outside Android's asset sources.

[MobileCLIP2-S0's checked license](https://huggingface.co/apple/MobileCLIP2-S0/blob/3136ea51c8ed56b9f9abfab04cb816735aaad6cb/LICENSE) excludes product development from its permitted research purposes. Its weights were excluded before download for this app work.

## Spatial understanding on the Vivo

The [Google supported-device list](https://developers.google.com/ar/devices) lists V30 Lite 5G and V30 SE, but the standard Vivo V30 was not found. This establishes an integration constraint, not a runtime result from the owner's phone. ARCore availability and depth must be queried on the actual variant before choosing that backend. Requiring ARCore now would risk excluding the primary phone.

[ARCore depth documentation](https://developers.google.com/ar/develop/depth) describes depth from motion and its best accuracy at roughly 0.5 to 5 metres. This can help separate nearby foreground and subjects. It does not establish reliable depth for distant skyline subjects. [SharedCamera](https://developers.google.com/ar/reference/java/com/google/ar/core/SharedCamera) also has depth-sensor limitations; simultaneous ARCore and CameraX capture requires a deliberate camera architecture, rather than opening a second camera session alongside the existing controller.

The standard-phone fallback needs a short sequence of overlapping observations. A rotation-oriented panorama can supply scene coverage and relationships for distant subjects. Reliable translation and perspective changes additionally need calibrated visual-inertial tracking or independently validated multi-view reconstruction. A lone device quaternion supplies rotation, not camera position. Monocular geometry supplies relative structure with an unknown scale. Digital zoom changes framing, while moving the camera changes perspective.

### Geometry proof and failed real-image result

`environment_geometry.py` implements mutual feature matching, essential-matrix estimation, pose recovery, parallax checks, positive-depth triangulation and reprojection checks. Its convention is camera x right, y down, z forward. Returned points are relative to the first camera. Walking and camera-height instructions remain disabled.

Four synthetic checks cover known translation/rotation, pure rotation or tiny baseline, outlier matches and invalid calibration/correspondences. These pass. They establish mathematical behavior on controlled inputs.

Real-image testing uses the public [ETH3D cables_2 sequence](https://www.eth3d.net/slam_datasets), 98 images, ZIP SHA-256 `cf6e2d74a606b021bb90d0426ad3fd651bf9efbc60220d7c462cf1c8836375ee`. Twelve pairs use fixed starts 0,8,16,... with end=start+6. No best-pair selection is performed. The supplied [calibration and camera-to-world ground truth](https://www.eth3d.net/slam_documentation) are independent of the estimated poses. Nearest pose timestamps are required within 20 ms.

| Real-image result | ORB matching | SIFT matching |
| --- | ---: | ---: |
| Pairs yielding a sparse reconstruction | 9/12 | 9/12 |
| Median rotation error among reconstructed pairs | 9.71 degrees | 7.83 degrees |
| Median translation-direction error | 28.68 degrees | 28.47 degrees |
| Desktop median image matching and geometry | About 50 ms | About 103 ms |

SIFT was tested after observing the ORB failures, so this is a development comparison. Neither result qualifies the backend. Low reprojection error can coexist with an incorrect pose. A reconstructed pair is not automatically trusted for movement guidance. The sequence uses a calibrated global-shutter laboratory rig; phone rolling shutter, OIS, autofocus, calibration, thermal behavior and real scene changes remain untested.

This rules out directly connecting a minimal two-image feature matcher to camera guidance. The next viable spatial backend must maintain tracks over time, fuse appropriately calibrated inertial observations, detect failure/relocalization and verify pose accuracy on independent sequences before integration. ARCore can be evaluated where the actual device supports it; a mature visual-inertial backend requires a separate Android feasibility and licensing evaluation.

## Product architecture after the owner's clarification

1. **Observe:** one Find action evaluates current scene coverage and asks for a short sweep only when more observations are needed. Keep overlapping keyframes with their actual capture geometry, calibration source, lens/zoom and tracking status. Images remain local unless a separately enabled online request is made.
2. **Represent the environment:** maintain observed subject identities, relationships, direction, horizon/vanishing-line evidence, nearby occluders and supported spatial structure. Record unknown or uncertain quantities explicitly. Discard a stale map after a scene change or tracking reset. The current recorder and pair solver do not yet implement this map.
3. **Choose a photograph:** give a scene-aware planner the observed environment and alternative viewpoints. It must judge subject isolation, scale, foreground/background relationships, completeness, perspective and photographic intent, and recognize an already-good view. A target represents its observed viewpoint, lens/field of view, selected subject and source evidence. Cropping is an optional final refinement. The crop-ranker plus TinyCLIP experiment does not meet this responsibility.
4. **Guide:** use verified tracking to guide rotation and lens/framing changes toward the selected observed target. Recheck the real view. Hand zoom control to the owner immediately after manual input. Position/height advice requires separate spatial and movement validation. Geometry tracking runs independently of the slower photographic decision.

The two-second selection target applies after observation; responsive preview and live tracking have their own budgets. Physical speed and five-minute behavior remain measurements to make, rather than promises from desktop timings. A whole-scene mesh is not required before testing useful scene coverage and subject relationships, but a 2D crop list cannot stand in for environment understanding.

[PhotoFramer, CVPR 2026](https://openaccess.thecvf.com/content/CVPR2026/html/You_PhotoFramer_Multi-modal_Image_Composition_Instruction_CVPR_2026_paper.html) provides a useful research reference separating shifts, zoom and viewpoint changes. Its [released preview](https://huggingface.co/zhiyuanyou/PhotoFramer-preview) is based on BAGEL-7B; the card distinguishes it from the original unreleased weights. Its [assessment model](https://github.com/zhiyuanyou/PhotoFramer-Assessment) is Qwen2.5-VL-7B based. These are potential desktop reference/teacher candidates, with practical hardware requirements still to check. They are not selected as phone engines. A generated example view would remain a hypothesis until supported by real observations.

## Recommendation and release status

Close the crop-only experiment as a diagnostic, retaining GAIC as a possible refinement component. Continue with observed environment coverage, a validated spatial-tracking backend and a photographic planner evaluated across actual viewpoints. Reliable geometry and photographic judgment require separate benchmarks. Neither has passed the product gates here.

End-user API keys remain excluded from the intended public experience. Local observation/tracking must work without a paid service. Any optional managed online planner still depends on a verified gateway, applicable image-processing terms and shared free quota; the existing timeout is unresolved and no gateway is deployed. No paid services were enabled.

The current iteration passes 17 Python research tests and the Android build, 35 composition/network/control unit tests and lint. Android physical results are owner reports only. Model preference, environment-map correctness, subject preservation, photo-quality comparison and physical speed remain open. No new camera-quality claim or better-composition release is made.
