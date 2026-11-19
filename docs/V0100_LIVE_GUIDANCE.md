# Live composition on the Vivo V30

Install `artifacts/CameraCompositor-0.10.0-live-debug.apk` as an update. This is the private ARM64 build for the standard Vivo V30.

1. Aim at the skyline with **Style** set to **Thirds**. Hold the view while the camera checks it automatically.
2. When an outline appears, bring it to the center of the viewfinder. The camera adjusts zoom after it verifies the target. **Find shot** rechecks the current view on demand.
3. Hold through the countdown with **Auto shutter on**, or use **Take photo**. **Last photo** opens the saved JPEG.

## Why the earlier build could stall

The owner reported rare suggestions and repeated turning prompts on 0.9.0. The code gathered five frames before it could suggest anything. It could then discard an otherwise useful result if the multi-view overlap estimate became stale during model inference. These are two failure paths identified in code. The exact path taken on the phone was unmeasured.

Samsung's Shot Suggestions derives a target from the current camera view and guides the user toward alignment. Samsung's engineers report using a model trained on more than 100 million photos. FrameMaster also offers live composition guidance on Android. Adobe's Project Indigo describes ultrawide context for composition as a research direction. The private build uses the current-view interaction first and asks for a new angle only when a strong subject reaches an edge. [Samsung support](https://www.samsung.com/sg/support/mobile-devices/shot-suggestions-on-your-galaxy-mobile/), [Samsung engineering interview](https://news.samsung.com/global/interview-how-samsung-developed-the-galaxy-s10s-share-friendly-ai-powered-camera), [FrameMaster listing](https://play.google.com/store/apps/details?hl=en-US&id=com.framemaster.app), [Adobe Research](https://research.adobe.com/articles/playground/playground.html).

## What the change does

The first pass uses one observed view. Building proposals draw on facade evidence, texture and the outline against the sky. The planner compares these proposals with the wide view and accounts for detailed foreground below the subject. It keeps the selected framing separate from camera zoom, tracking and drawing.

The app can show a proposed outline while live image tracking acquires it. A substantial change in the preview requires a visual match before the drawing appears. A zoom change or stale frame requests a fresh check. Zoom and the countdown require fresh, verified image tracking. The saved JPEG comes from the camera at capture time.

## Verification

The desktop policy proposed a building shot in eight of nine variations of the owner's skyline. The previous policy proposed six. The original result kept both towers and removed the balcony railing at about 1.61×. The lower-saturation and left-shifted variations gained proposals. The right-shifted view still yielded no direct proposal. The already-framed skyline, later palm view and blank control produced no further framing change. The opening palm view produced a crown-focused shot. [Recorded replay](../research/results/live-view-stability.json).

To reproduce the skyline checks, use the original view at 1× and run the [desktop replay procedure](../research/README.md#vivo-rehearsal-replay). The exposure variants multiply brightness by 0.72 and 1.20. Lower saturation uses a factor of 0.55 and the smaller preview is 192 × 256. Each directional variant crops a 90% window shifted by 5% of frame width or height around its center and replays at 1.11×. The already-framed control uses normalized bounds `(0.0838, 0.0348, 0.7038, 0.6548)` at 1.61×. The variation set probes one skyline and is unsuitable for estimating general photographic preference.

The private package passed the Android build, 97 Android unit tests, lint with zero errors, all 20 research tests, signature verification and 16 KiB alignment. Its signing certificate matches 0.9.0. [Delivery verification](../research/results/delivery-verification-v0100_live.json).

Desktop replay can verify the proposals and their use of visible subjects. Live tracking, zoom behavior and wall-clock speed on the Vivo still require a rehearsal. The broader blinded photographic-preference gate remains pending.
