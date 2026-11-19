# Composition styles and camera JPEGs

Version `0.7.0-styles` responds to the owner's 0.6.0 feedback about composition and photo processing. Both the manual scan and Auto follow the selected style. The app remembers the last style and photo preference.

| Style | What the planner looks for |
| --- | --- |
| Thirds | A visible subject near a grid intersection with room around it. For tall people, placement uses an upper-body point. |
| Centered | The subject close to the center line with enough space around its bounds. |
| Negative space | An off-center subject with a sufficiently large, visually quiet area beside it. |
| Wide scene | Useful context and real neighboring viewpoints, with at most 1.25× tighter framing. |

Tap the subject to give the next scan a starting point. If a model box covers the tap, the planner preserves its bounds. Otherwise it protects the neighborhood around the tap and limits zoom. It follows the selection through registered observations and asks for another tap when it cannot reliably find the same area. A candidate must fit the chosen style and preserve room around the visible subject. The planner weighs borders, clipping and foreground intrusions, and searches observed viewpoints and supported zoom. [Photography composition reference](https://www.nikonusa.com/learn-and-explore/c/tips-and-techniques/5-easy-composition-guidelines).

The viewfinder grid changes with the style. **Find a composition** previews the candidate and **Start Auto** can guide and start the existing countdown when the subject and framing are reliable. For an uncertain match the scan asks for a clearer subject or a different angle. Changing styles clears the old guide and exits Auto.

## Saved photos

**Camera JPEG** is the new default. The app writes the camera's full still JPEG to the gallery byte for byte. The earlier software shadow lift, smoothing and JPEG re-encode have been removed. This preserves the camera output, including its metadata and processing, and avoids the wait for a second image encode.

**Options > Photo processing** exposes manufacturer settings supported on the selected camera. **Scene-aware HDR or Night** is optional. It requests HDR on clearly backlit views, Night when most of the sampled view is dim, and Camera JPEG for ordinary or uncertain lighting. An unavailable or failed extension can fall back to Camera JPEG. The saved-photo status shows the path that produced the file. Vivo stock-camera matching requires matched images on the phone. [CameraX Extensions](https://developer.android.com/media/camera/camerax/extensions-api).

## Evaluation

The desktop replay feeds the frozen 20 development and 60 evaluation photos through the packaged NanoDet model and the app's Kotlin framing planner. It counts distinct framing choices and checks visible detector-box containment. Single stills can only evaluate framing within an observed image. Live viewpoint guidance, aesthetic preference and actual Vivo capture quality remain separate. [Replay instructions](../research/README.md), [desktop results](../research/results/composition-styles-desktop.json), [phone test](PHONE_TESTING.md).

In the 60-photo set, 14 shots received distinct suggestions in multiple styles. Thirds and Centered each gave 16 guides. Negative space gave five when the frame supported it. All 34 guides across those styles with a confident detection contained the full detected box. Wide kept the original on 57 stills. In the separate owner skyline replay, a tap on the taller building led Centered to a 1.56× framing of the two towers above the railing. Wide retained the bridge and surrounding scene. The remaining photographic preference and motion checks need live scenes and human judgments.
