# Product direction

## Owner decisions

- Native Android first; standard Vivo V30 is the primary test phone.
- Useful casual and deliberate photographic composition, with fast local feedback.
- First priorities: scenery and everyday scenes, including buildings, streets, plants and objects.
- Discover a better shot, including a short sweep when additional observed views help.
- Understand the environment across observations: subjects, spatial relationships, viewpoint and perspective. A crop of one submitted image does not satisfy the intended assistant. Depth/3D should support that understanding where the device and evidence permit it.
- Strict $0 ongoing budget; no end-user provider-key setup in the eventual public app.
- The owner leads UI/UX. Engineering tools remain plain until a design is supplied.

## Current result

Version 0.1 aligned a generic detected object to a chosen target. Owner feedback reported intermittent detection and poor directional-text UX. Version 0.2 added one-image cloud crop selection and feature tracking. The owner reported a timeout and correctly distinguished manual crop tracking from smart composition.

Version 0.3 implements research infrastructure and an explicitly experimental local comparison. [COMPOSITION_RESEARCH.md](COMPOSITION_RESEARCH.md) records actual 20-development/60-evaluation results. GAIC is stronger than NIMA for rated crop ranking and synthetic stability on this dataset. Neither model selected the desired palm-crown shot from the owner's reference screenshot. Neither has human preference or physical-device acceptance.

The next automatic-composition implementation must demonstrate useful subject/shot choices over observed views. High crop-rating correlation and successful tracking cannot substitute for that evidence. Keep/refine/observe-more decisions and explicit subject preservation have separate responsibilities from camera control and drawing.

The latest [environment investigation](ENVIRONMENT_COMPOSITION_RESEARCH.md) diagnoses the city failure and records rejected candidate pipelines. The 295-crop plus semantic experiment finds tower/bridge ideas on the owner's example but lacks a reliable good-view decision. A two-view 3D prototype also has substantial errors on calibrated real footage. The next backend needs validated tracking across time and a scene-aware photographic planner. Neither experiment drives the main button.

## Remaining acceptance work

1. Review model-anonymous comparisons and starting-view quality. Require 70% preference on improvable held-out scenes and a favorable comparison with matched captures from the old phone heuristic. The static fixed-crop control is a limited comparison, not that full baseline.
2. Extend the scene set with casual Vivo captures, more plants/objects and real low light. Use exported local sweeps to evaluate candidate selection across actually observed angles. Calibrate scores and confidence before connecting the experimental policy to automatic guidance.
3. Measure physical subject preservation, already-good views, tracking direction/loss, cancellation, manual zoom, camera switching and interruption recovery. Test cold and warm timing and a five-minute responsive preview/guidance session. Target selection within two seconds after observation.
4. Compare saved photos against the stock Vivo camera at matched lens/zoom/framing, using exposed vendor processing and standard capture separately. Use capability reports rather than marketing specifications to determine app access.
5. Validate environment coverage, calibration and perspective across observed views. Establish spatial tracking accuracy and failure detection independently of photographic preference. Account for the standard Vivo V30's unconfirmed ARCore support. Keep unsupported metric depth, hidden surfaces, walking directions and camera-height advice explicit and unavailable until validated.

## UI, service and release

After photographic behavior passes, implement the owner's camera-screen design. Research before UI work identified common generated-interface patterns such as decorative purple/blue gradients, repeated rounded feature-card grids and decorative typography. The current engineering scaffolding uses plain native controls and the system font. The browser review tool is a local research utility.

Local camera/suggestions/tracking work independently of a service. [CLOUD_GATEWAY.md](CLOUD_GATEWAY.md) evaluates optional Firebase AI Logic access with enforced App Check and an unbilled Spark project. No managed gateway has been deployed. Free cloud capacity is limited and can be exhausted; retain a local fallback.

Public release also requires checkpoint/data permissions, more Android devices, release signing, final permissions/privacy disclosures and current store/distribution requirements. The debug model asset stays out of release builds. No paid infrastructure, store publication, provider-account changes or external messages are part of this research delivery.
