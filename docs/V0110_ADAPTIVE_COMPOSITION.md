# Automatic framing on the Vivo V30

Install `artifacts/CameraCompositor-0.11.0-adaptive-debug.apk` over the previous build. Open the camera and aim. The camera chooses a composition automatically. Use the live outline to align the shot. **Find shot** rechecks the current view, and a tap lets you choose a subject directly.

## What the camera checks

The previous private build made proposals around recognized subjects within the selected preset. An ordinary room with several pieces of furniture could produce an empty list of proposals. The current camera compares framing the whole scene with bringing out a subject. It measures upright lines, the balance of visible detail, margin around the subject, and how much of the scene a tighter frame retains. A doorway, the layout of a room, or a person can each give the guide a reason to shift the camera.

Both candidate groups compete with the original view in one crop-scoring batch. The app checks low-detail views again after the camera has had time to settle. It gives a lighting or framing cue when visible information stays weak, and keeps the view when it finds no clear improvement. Small off-center reframes require closer alignment before zoom or countdown. The camera saves the JPEG at capture time.

The same policy can guide from scene geometry when the learned crop ranker is unavailable. The private rehearsal package includes the local ranker, and the standard package can use the geometry path. See the model and checkpoint details in [Third-party components](THIRD_PARTY.md).

## Results

The indoor replay contains 16 views of 11 rooms, including portrait and dim variations. The previous subject-only policy proposed a shot in one view. The current policy with crop ranking proposed a shot in all 16. Most changes were modest whole-room reframes, and the desk view selected a tighter 1.61× shot. The fallback with the crop ranker disabled proposed three indoor guides. [Replay outputs and timings](../research/results/adaptive-indoor-replay.json).

The desktop replay kept the already-framed skyline, the later palm view, and the blank control. It selected the towers at 1.61× in the opening skyline and the crown at 0.91× in the opening palm view. The build passed 104 Android tests, 20 Python research tests, lint with zero errors, and APK signing and alignment checks. [Delivery verification](../research/results/delivery-verification-v0110_adaptive.json).

## Reproducing the replay

The indoor scenes use the RGB panels from `nyu_depth_v2_web.jpg` and `nyu_depth_v2_labeled.jpg`. The RGB panels are extracted locally and kept out of Git and the APK. The data comes from Indoor Segmentation and Support Inference from RGBD Images, ECCV 2012, by Nathan Silberman, Pushmeet Kohli, Derek Hoiem and Rob Fergus. [NYU Depth V2](https://cs.nyu.edu/~fergus/datasets/nyu_depth_v2.html).

For the ten-panel demo, extract RGB columns `1..261` and `878..1139` at each of the five rows. Each slice starts one pixel below its 200-pixel row boundary and ends two pixels before the next. The classroom RGB panel uses `(2, 2, 395, 298)` in the labeled demo. The portrait cases are centered 3:4 slices of the bedroom, second bedroom, desk, and conference corridor. The dim bedroom uses a brightness multiplier of 0.57. All indoor scenes start at 1×.

Use `--adaptive` with [the desktop replay command](../research/README.md#vivo-rehearsal-replay). The default replay compares the old policy with its Thirds setting. Add `--geometry-only` to `--adaptive` to exercise the crop-ranker fallback with subject recognition still available. Replay stills measure shot selection on the desktop. Vivo live preview, tracking, zoom, shutter behavior, and human photography preference need separate checks.
