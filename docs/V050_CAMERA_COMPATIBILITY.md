# Camera-only composition experiment

Version `0.5.0-camera` runs **Find a composition** on the regular camera. The Vivo reported a temporary spatial tracking failure in `0.4.1-spatial` despite producing tracking and depth in an earlier diagnostic. The failure message leaves the exact cause unknown. This build removes the AR session handoff from the main camera workflow.

The signed test APKs target Android 10 or later, with one build each for ARM64 and 32-bit ARM. Android 15 devices can use 16 KB memory pages. The ARM64 package passes the APK and native load-segment alignment checks. Runtime testing on a 16 KB device remains pending. [Android compatibility guidance](https://developer.android.com/guide/practices/page-sizes).

## How a scan works

1. The CameraX preview and photo camera stay active. One press collects six reduced observations over roughly 2.4 seconds. The user can turn gently to show a few angles or hold steady for a single angle. [CameraX image analysis](https://developer.android.com/media/camera/camerax/analyze).
2. The analysis worker uses matching details to register consecutive views. The planner compares the current view and earlier views that can be tracked back to. It checks that the main detail will remain visible, weighs context and edge clutter, and considers supported zoom. An earlier view can win at the same zoom.
3. ZipDepth can estimate foreground and background from ordinary RGB on the CPU. At most four distinct observations are considered for inference. The estimates can affect scoring when at least two registered views agree on relative order in their overlap. The RGB path handles absent, inconsistent or unusable depth and permits a current-view suggestion even when the sweep loses overlap. [ZipDepth model and method](https://zipdepth.github.io/).
4. A suggestion uses real observations and the live outline and zoom guide. A stale observation remains ineligible. Saved photos use the full camera capture pipeline. Camera analysis for this feature stays on the device and requires no account, API key, hardware depth sensor or Google Play Services for AR.

The optional ARCore test remains under Developer tools. It reports more specific setup or tracking failures when available. The existing consent-based personal-key research scans have a separate entry. [ARCore optional app configuration](https://developers.google.com/ar/develop/java/enable-arcore).

## Evidence and limits

| Check | Result |
| --- | --- |
| Export fidelity | Across 20 development images, the 12.3 MB model had minimum ordinal correlation 0.99946 against the upstream checkpoint and maximum error 1.04% of the reference depth range. [Results](../research/results/software-depth-export.json) |
| Measured-depth comparison | On 24 registered RGB and depth frames from one ETH3D tabletop sequence, median near/far ordering accuracy was 82.8% for sampled cells at least 10 cm apart. The worst frame was 5.7%. This motivates cross-view agreement and conservative fallback. [Results](../research/results/software-depth-eth3d.json), [ETH3D depth format](https://www.eth3d.net/slam_documentation#rgb-d-data) |
| Viewpoint regression | A generated two-view fixture selects the earlier view at fixed zoom when the near obstruction drops out. Other tests cover missing depth, broken overlap, conflicting depth order, a low-detail scene and stable same-angle matching. |
| Owner city reference | The RGB-only planner kept the full view when evaluated on the single supplied skyline image on desktop. This still case leaves the reported photographic problem open. A recorded camera sweep is needed to assess actual viewpoint selection on the scene. |
| Compatibility and quality | APKs are built, signed and checked. Vivo and budget-phone startup, capture quality, sweep timing, guide alignment, and the 70% blind preference gate remain pending. [Verification](VERIFICATION.md) |

The depth model estimates relative ordering within a view. The planner currently uses image detail, depth layers and overlap. Semantic subject recognition, calibrated scene measurements, guidance for walking or changing camera height, and previously unseen viewpoints remain outside this experiment.
