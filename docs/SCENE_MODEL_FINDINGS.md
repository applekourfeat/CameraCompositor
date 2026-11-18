# Earlier scene-planner evaluation

Historical results from versions 0.1 and 0.2. The version 0.2 cloud-only direction below is superseded by the local research comparison and measured results in [COMPOSITION_RESEARCH.md](COMPOSITION_RESEARCH.md).

Date: 18 November 2026. Goal: a free Android app with useful scene-aware framing and a responsive live guide.

The initial generic-object-detection prototype did not choose a photograph. It aligned a detected object to a manually chosen target. The owner reported detection interruptions and poor directional-text UX. The replacement separates one scene-planning decision from lightweight live scene tracking.

## Offline experiments

Both model files were downloaded from fixed public revisions and checked against their published size and SHA-256. Evaluation used the LiteRT-LM 0.19.1 JVM runtime on this Windows computer, with CPU vision/decoder. A scene from the supplied palm-tree reference video was cropped to the visible camera image. These are desktop observations, not phone benchmarks or statistically reliable quality scores.

| Model | File size | Observations |
| --- | --- | --- |
| SmolVLM2 2.2B | 1,511,391,232 bytes | A plain caption recognized palm trees, sky, and a building. The constrained framing prompt produced a generic answer or repetitive numeric text. A simpler grid prompt selected crop 6 with generic photographic language. Inference took approximately 4.8 to 9.7 seconds in these runs. |
| Qwen2-VL 2B | 1,783,808,000 bytes | The numbered grid prompt selected crop 3, an initially plausible palm-crown framing. Renumbering the same images changed the chosen photograph, failing the intended consistency check. The whole-scene fixed-choice prompt selected crop 6. Inference took approximately 12 to 13 seconds in these runs. |

These results do not meet the desired reliability/speed bar. The offline runtimes, model downloader, and large models were removed from the Android app. Evaluation scripts and logs remain in ignored local research files. Disposable model downloads are separate from the source and app. The app requires no multi-gigabyte model installation.

Model sources: [SmolVLM conversion](https://huggingface.co/litert-community/SmolVLM2-2.2B), [Qwen2-VL conversion](https://huggingface.co/litert-community/Qwen2-VL-2B), [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM). Both model repositories identify Apache 2.0 licensing. No weights are committed.

## Historical version 0.2 decision

Use an explicit one-frame Gemini scene request, then OpenCV tracking and bounded zoom on-device. The owner approved the online setup direction and created a personal API key. The key is entered privately on the phone, encrypted locally, and excluded from the APK/source.

The online protocol is tested with fixture responses and malformed outputs. A real online request, composition quality across scenes, latency on the owner's network, and physical camera behavior remain phone acceptance tests. Key existence in AI Studio does not prove that a particular model call succeeds.

## Quality gate for the next iteration

Use a scene set that includes people, architecture, landscapes, close objects, clutter, low light, and moving subjects. Compare original versus suggested framing, test repeatability and small camera movements, and collect the owner's preferences. Measure median/p95 scan time and guide timing separately. Keep a manual override and reject invalid plans. Avoid describing a heuristic or an unvalidated model as a reliable aesthetic judgment.
