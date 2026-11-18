# Auto capture and photo processing

Version `0.6.0-auto` is a phone test build. The owner reported that `0.5.0-camera` scans ran consistently on the Vivo V30, with weak indoor suggestions and slow, stepped zoom. This version adds an opt-in Auto mode, keeps the existing manual controls available, and runs the default composition path offline.

## From viewfinder to shutter

**Start Auto** arms the camera for the current session. The app observes overlapping views as the user aims, picks a supported composition, and opens the guide directly. With strong subject evidence, it can also guide a view that already has suitable framing. It samples new scenes after a saved shot. **Stop Auto**, pinching to zoom, opening Options, or leaving the app exits the automatic flow.

Auto zoom moves toward the planned ratio on a timed curve, with one CameraX request in flight at a time. The guide can skip intermediate observations when sufficient overlap keeps the target reachable. The app waits for aligned, steady frames and zoom settling, shows a three-second countdown, and uses a fresh aligned frame to trigger the shutter. Drift, loss of alignment and stale frames clear the countdown. [CameraX zoom control](https://developer.android.com/reference/androidx/camera/core/CameraControl).

## Saved JPEGs

Automatic processing asks the phone for its exposed Auto extension first. On a dim preview it then prefers Night when available, with HDR available as another fallback or a manual selection. Vendor captures keep the camera's original JPEG. If the vendor path fails, the app can recover to the ordinary camera at the requested zoom. [CameraX manufacturer extensions](https://developer.android.com/media/camera/camerax/extensions-api).

On the ordinary path the app can lift shadows and soften small noise variations in dark, smooth areas. The saved photo keeps its dimensions and common capture metadata. Well-exposed, wide-gamut, gain-map and oversized JPEGs keep the original camera output. A separate photo worker stages the file and publishes it to the gallery after writing succeeds. **Standard JPEG** is available for comparisons.

Stock Vivo output and GCam quality are separate comparisons. GCam's published HDR+ approach aligns and combines a burst of RAW frames. The app's local fallback works on an existing JPEG. Multi-frame processing in this build depends on the manufacturer's exposed modes. [Google HDR+ paper](https://research.google/pubs/burst-photography-for-high-dynamic-range-and-low-light-imaging-on-mobile-cameras/).

## Composition evidence

The included 3.8 MB NanoDet model provides boxes for common subjects such as people, animals and household objects. It runs on the last observed frame. Earlier saved viewpoints must retain a confidently detected subject when geometry can project its box. The planner still uses independent image evidence to select a shot. The detector can help protect subject space and does not supply photographic taste. [Pinned model and license](https://github.com/opencv/opencv_zoo/tree/48b10fa505aeb51fbd127fc60a5c660040e823b7/models/object_detection_nanodet).

The first subject-ranking policy overfavored large close-ups. It changed 19 of the 60 evaluation photos. The nearest annotated crop scores improved in three cases and dropped in 15. The delivered policy uses detection to protect framing. It left the same 60 static framing choices and the owner's skyline example unchanged. The subject-protection regression test covers a crop that would cut off a detected subject. This still-image check leaves the quality of live shot discovery open. [Initial desktop results](../research/results/subject-assist-initial-stills.json), [final desktop results](../research/results/subject-assist-stills.json).

The build and signature checks, 71 Android tests, 20 Python tests and Android lint pass. Automated tests exercise the three-second shutter gate, motion and stale-frame reset, duplicate-scene suppression, the zoom curve, JPEG tone functions and subject preservation. Vivo preview smoothness, camera behavior during manufacturer capture, matched saved-photo quality and the 70% blind preference gate remain pending. [Delivery results](../research/results/delivery-verification-v060_auto.json), [phone test](PHONE_TESTING.md).
