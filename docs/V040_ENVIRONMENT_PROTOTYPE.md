# Version 0.4.0 environment prototype

Superseded by [0.4.1 spatial planning](V041_SPATIAL_PLANNING.md), following the owner's successful Vivo pose/point/depth export. This page describes the earlier implementation. Its first delivered APK had 78 lint warnings; its final local checkpoint has 81. Both had zero lint errors and 48 passing Android tests.

18 November 2026. This version implements the first multi-observation camera flow. It remains an engineering prototype, with photographic preference, Vivo performance and live provider inference pending.

## Implemented

The primary **Find a composition** action records six reduced views over approximately four seconds. It tracks image features through intervening analysis frames, builds a bounded temporal observation graph and measures projective overlap using inliers, reprojection error and the convex-hull area of supporting points. Static repeated views do not automatically become new scene coverage. Camera images stay in memory during this flow and are released on cancellation, failed assessment, dismissal or completion of the active guide.

Up to four observations go to the saved personal research service after separate multi-image consent. The request contains actual view identities, lens zoom, remaining zoom limits and measured image-overlap evidence. Camera translation and metric depth are explicitly null on this CameraX path. An overlap edge identifies its actual preceding capture, even if that capture was omitted from the selected upload set.

The requested assessment contains a small subject graph, subject visibility by observed view, qualitative relationships, a scene summary and a keep/observe-more/guide decision. Relationships are model inferences, not measured depths. Guide targets must name a real supplied view and a subject reported visible in that view. Geometry is checked against that view's image bounds and available zoom. Optional crop refinement supports up to 3.2x relative zoom where the device permits it. Optical zoom quality has not been established by that limit.

For a selected view outside the current field of view, the guide uses the recorded intermediate views in reverse order. A broken overlap chain prevents a return guide. Intermediate stages hold the observation zoom; the final stage applies the selected framing. Manual pinch cancels the environment route and hands control to the owner. A new stage resets the alignment hold. Tracking loss still expires a guide after three seconds. An assessment is rejected if live image tracking has become stale, and the preview has a 15-second acceptance window.

The temporal tracker targets up to 30 analysis updates per second during observation, with the usual 10 Hz cap afterward. Preview is a separate CameraX stream. These are scheduler targets; actual Vivo frame rate, latency and heat remain unmeasured. The analyzer's timing samples do not measure rendered preview FPS.

The previous local nine-crop experiment is available under **Options > Developer tools > Legacy local crop research**. It no longer runs from the primary Find button. Offline camera capture and the manual guide remain available. Reliable automatic offline composition selection has not been achieved.

## Optional real spatial backend

**Options > Developer tools > Test spatial tracking (ARCore)** runs a separate camera owner. The app first releases CameraX, then checks ARCore support and the installed service on the actual phone. Unsupported devices retain the normal camera path. Google may request installation/update of Google Play Services for AR. Returning from the test resumes the normal camera controller.

This backend uses ARCore **1.57.0**, verified against Google's published Maven metadata on this date. It can record five seconds of session-local camera pose, camera intrinsics and sparse 3D points on supported devices. Automatic depth is enabled only if the session reports support; a small sampled depth grid is recorded only when data becomes available. Sampling is bounded at four observations per second, with at most 128 retained points per observation. Tracking/depth availability remains explicit, including an empty recording outcome. Data is held in memory and exported only through the owner's file-picker action. No camera images are included in that export.

ARCore's coordinates are session-local, x right/y up/-z forward, with quaternion XYZW and SDK-estimated metres. These differ from the OpenCV research convention and the device quaternion readings in the old sweep recorder. Per-frame points are not merged into a persistent mesh. Do not fuse coordinates from different sessions or treat their metre estimates as a calibrated physical-device accuracy result.

The spatial test is isolated from the CameraX photographic planner. ARCore poses and depth are **not yet attached to its planning observations**. This avoids pretending to support simultaneous CameraX/ARCore camera access before a shared-camera architecture is validated. Cloud Anchors, Geospatial APIs and location permission are absent. The SDK is subject to [ARCore Additional Terms](https://developers.google.com/ar/develop/terms); its [required privacy disclosure](https://developers.google.com/ar/develop/privacy-requirements) is provided before activation and on the test screen. No paid service or billing account was enabled.

## Measured development evidence

On the same ETH3D cables_2 development sequence, tracking correspondences through all seven frames of each fixed window gives 12/12 sparse reconstructions, median rotation error **1.87 degrees** and translation-direction error **7.17 degrees**. The earlier two-image ORB experiment reconstructed 9/12 with errors of 9.71 and 28.68 degrees respectively.

This is development evidence from a calibrated laboratory camera. It is not independent held-out validation, calibrated phone VIO, metric scale recovery or proof of the Android implementation's numerical parity. The Python experiment uses 800 initial corners; the native image-overlap tracker uses 400 and does not infer translation. Its approximately 79 ms desktop window timing includes reading/decoding the seven input images. No new movement advice is enabled from that experiment. See `research/results/environment-geometry-eth3d-temporal.json`.

Android build, **48 unit tests** and lint pass; lint reports zero errors and 78 warnings. Warnings include hardcoded engineering text and existing target/icon/style/dependency items. **20 Python research tests** pass. The tests cover multi-view requests, subject/view identity constraints, unsupported geometry, response truncation, quota, bounded transport, cancellation, fixed-zoom observation graphs, stale control states and return routes. They do not replace physical camera, GL rendering, ARCore service, native OpenCV or live provider tests.

## Install and first checks

Install `artifacts/CameraCompositor-0.4.0-environment-debug.apk` over the previous app. Package name and debug signing certificate are unchanged; version code is 5. Android 10+, ARM64. The stable `CameraCompositor-debug.apk` alias contains this build too.

1. Tap **Find a composition**. The saved personal key is reused. Allow the separate multi-view upload notice if desired, then turn slowly across a small, overlapping scene while holding zoom fixed. Hold the final view during assessment.
2. Compare the selected observed shot before choosing **Use this view**. Test dismiss/cancel and photo capture during observation. Test pinching during a return guide. A result derived from a different scene or unsupported target must not move the camera.
3. For actual standard-V30 spatial capability, open **Options > Developer tools > Test spatial tracking (ARCore)**. Record the support/tracking/depth status. If supported, record five seconds near textured nearby objects and export the spatial observations when convenient. This test needs no personal AI key.

The prior online timeout remains unresolved. Multi-image planning uses the same bounded transport and saved provider configuration, with a 35-second total limit and no automatic retries. Mock-server tests validate request/response behavior; they do not prove a real provider response from the Vivo. If it times out again, the new observation architecture still needs that separate connection/inference investigation. No 70% photographic preference or two-second physical selection claim is made.

## Distribution and remaining work

This private research APK still includes the old GAIC checkpoint for its developer tool; public redistribution permission remains unverified. Release assets exclude research weights. New TinyCLIP exports remain excluded. ARCore is declared optional in the manifest and includes a terms/privacy notice.

The public product still needs a keyless managed planner or a qualified local model. Firebase setup, App Check, quota policy and image-processing terms are not deployed or enabled by this version. It does not require an end-user key as a final product decision; this personal research build still uses one for online planning. Final UI/UX remains the owner's work. Photo quality relative to Vivo's stock camera remains unmeasured.
