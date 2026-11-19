# Automatic subject discovery

Open the viewfinder and aim at a scene. The camera looks for subjects automatically. It can ask you to turn toward a subject, check the new view, then guide the final framing and zoom. With Auto shutter on, a steady, visually confirmed frame starts the three-second countdown. You can turn automatic photos off with one tap and keep the guidance.

## How the camera picks a shot

- A compact pretrained image encoder looks for regions such as foliage, buildings, water, people, and animals. For a skyline it considers structures against open sky, where a silhouette can stand out from clutter at the bottom. For a tree with its crown visible it can frame the crown while some branches extend beyond the picture. NanoDet supplies whole-object boxes when it detects a distinct object. A tap takes precedence over automatic subject choice. The shipped TinyCLIP image encoder is about 15.3 MB and runs locally. [Pinned checkpoint](https://huggingface.co/wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M/tree/a2a8c6eaa2549ad66eb7c31b85022bf58273a26c), [MaskCLIP method](https://arxiv.org/abs/2112.01071).
- A likely subject near the edge can lead to a turn at the current zoom. That proposed field of view can extend past the starting image. A fresh observation searches for the original subject category near its predicted position, then chooses whether to frame it. If the subject evidence is weak, the camera waits.
- Image features guide the camera through movement. Recent verified views help reacquire the scene after a brief occlusion. When the phone exposes a rotation sensor, its estimate can keep the guide visible during a gap. The shutter requires fresh visual matches for the countdown. Pinching cancels the current plan and gives you control of zoom. [Android position sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position).

The main path uses CameraX on Android 10 and later. Its visual models work from ordinary camera frames, with additional rotation input on supported devices. Guidance covers turning and framing. Walk, height, and metric 3D perspective need separate validation.

## What was checked

The desktop replay runs the Android planning code with the packaged model weights. It records choices on the frozen 20 development and 60 evaluation stills, the owner's skyline image, and stills from the supplied palm-tree video. The video stills contain the original app's viewfinder overlays. They exercise recognition and proposed turns as well as a fresh look at the tree after moving. [Desktop results](../research/results/scene-discovery-desktop.json).

| Replay | Result |
| --- | --- |
| Held-out stills | 24 proposed turns beyond the initial frame, 18 framing guides, 2 keep decisions, and 16 requests for more evidence. The 0.7 policy offered 16 framing guides and 3 keeps on these same stills. |
| Palm-tree clip | The initial proposal turns right and up. Four later observations yielded three guides and one keep decision. The earliest later view chose foliage around a neighboring tree. |
| Owner's skyline | The app selected the architecture silhouetted against the sky and offered a small framing adjustment. |
| Flat control | The app waited for more evidence. The shutter stayed unarmed. |

The held-out desktop run measured a 121 ms median for the patch model alone. The APK passed 88 Android unit tests, Android lint with zero errors, signature and alignment checks, and asset integrity checks. The Python research suite passed 20 tests. [Build verification](../research/results/delivery-verification-v080_discovery.json).

The 70% blind preference gate, live subject reacquisition, five-minute responsiveness, and matched stock-camera photo quality still need testing on the Vivo V30. This is a sideloadable research build. [Short phone check](PHONE_TESTING.md).
