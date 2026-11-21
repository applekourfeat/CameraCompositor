# Reproduce the composition research

The research compares actual model outputs with rated crops and provides a separate human-review tool. The recorded results are desktop measurements. No human preference or physical-phone result is implied.

## Open the results

- Read [the report](../docs/COMPOSITION_RESEARCH.md).
- Read the latest [environment-composition investigation](../docs/ENVIRONMENT_COMPOSITION_RESEARCH.md), including the owner's city failure and the rejected initial 3D backend. Crop proposals and spatial geometry have separate evidence and neither is a completed environment-aware camera assistant.
- Open `artifacts/composition-research/review.html` in your own desktop browser. Start with Development > Compare suggestions. You can stop and return later. Download answers before moving or closing the workspace.
- Run `summarize_review.py` on the downloaded answers. Blank or partial reviews remain pending.
- Inspect `artifacts/composition-research/examples-1.jpg` and `examples-2.jpg` for the first six seeded evaluation scenes.

The benchmark photos and generated review pages stay in the local research workspace. Recreate them from the pinned author download. The [GitHub gallery](../docs/composition-research/README.md) uses the project owner's skyline sample. Opening the reviewer requires no server or account. The in-app automated browser blocks local file navigation, so its interactive behavior needs a manual browser check.

## Environment and reproduction

The existing Windows environment is `.tools/research-venv`. For another machine, use Python 3.12, create that environment, and install `requirements-lock.txt` with the official PyTorch CPU extra index. The lock records the exact installed versions, including CPU-only PyTorch. No paid GPU service is used.

```powershell
$ResearchPython = '.tools/research-venv/Scripts/python.exe'
& $ResearchPython -m pip install -r research/requirements-lock.txt --extra-index-url https://download.pytorch.org/whl/cpu
& $ResearchPython -X utf8 research/fetch_assets.py --gaic-downloads
& $ResearchPython -X utf8 research/prepare_benchmark.py
& $ResearchPython -X utf8 research/evaluate.py --model nima
& $ResearchPython -X utf8 research/evaluate.py --model gaic
& $ResearchPython -X utf8 research/export_gaic.py
& $ResearchPython -X utf8 research/build_review.py
& $ResearchPython -X utf8 research/make_examples.py
& $ResearchPython -X utf8 -m unittest discover -s research -p 'test_*.py'
```

Run model benchmarks sequentially, with no concurrent build or other heavy workload. Repeating `prepare_benchmark.py` regenerates the canonical deterministic manifest. Human judgments live in separate downloaded JSON, so they are never filled in by that script. Check each command's exit status before proceeding if a dependency/download fails.

`export_nima.py` records a rejected optional FP16 conversion in this environment. It is a negative research result, not a required build step. Its failed asset is removed. GAIC export creates the private debug assets in `.local/research-assets`; a fresh checkout builds without those assets but will explain that the local model is absent.

```powershell
& $ResearchPython -X utf8 research/summarize_review.py 'C:/path/to/composition-review-answers.json'
& $ResearchPython -X utf8 research/evaluate_sweep.py 'C:/path/to/CameraCompositor-observed-views.zip'
```

Only the explicit evaluation-sweep recording saves camera frames for later research. Scene-service diagnostics store stage timings/status codes without images or keys. Never include personal camera sweeps or credentials in commits.

## Interpretation

The 20/60 split separates our development and evaluation samples and uses the author's validation/test split. NIMA's pretraining overlap is unknown. The static set cannot establish physical angle discovery, unbiased phone-scene quality or manufacturer image processing.

Candidate-order invariance and the scalar tests verify useful numerical behavior. The 70% gate comes from actual human comparisons on improvable held-out scenes. The fixed upper-right crop control is not equivalent to the old ML Kit pan-guidance pipeline, so matched physical captures remain a separate required comparison. Additional phone examples are needed to improve coverage of plants, objects and real low light.

Model/data redistribution rights remain a separate gate. The code includes the upstream GAIC MIT notice in `GAIC-LICENSE.txt`; that notice does not establish permission to redistribute its checkpoint or source photographs.

## Environment and subject-discovery experiments

### Vivo rehearsal replay

After building with `-PvivoDemo=true`, use `research/replay_context_shots.py` with the Java and classpath arguments below. Repeat `--frame IMAGE ZOOM` for each local capture. The replay runs the Android candidate and selection policy on recorded stills using the shipped model weights. Optional `--comparison` adds a local before-and-proposed sheet.

Add `--adaptive` for the current automatic composition policy and optionally `--geometry-only` to disable its learned crop score. The default mode replays the historical subject-only policy. [Indoor sources and crop coordinates](../docs/V0110_ADAPTIVE_COMPOSITION.md).

### Compare isolated views with wider context

With the frozen benchmark photos and model exports available, run `research/evaluate_isolated_views.py --output research/results/isolated-view-ranking.json`. It samples identical rated candidates for all scorers, then compares a learned crop model receiving the wide image with models receiving each candidate alone. The test measures a closer proxy for ranking views from a phone sweep. Optical viewpoint changes, subjective preferences on those changes, and device performance require separate checks.

### Automatic subject discovery

Run `research/export_scene_semantics.py` with the locked environment to fetch the pinned TinyCLIP checkpoint, embed the fixed prompts, and check the 192×192 CPU export. It writes `.local/scene-semantics-assets/scene-regions-192.onnx`. Positional embeddings are interpolated and baked into the export. The packaged image encoder stores large constants in FP16 with FP32 math. The MIT notice accompanies the APK.

After preparing the same benchmark images and NanoDet model and building the app, run `research/evaluate_scene_discovery.py` with the same Java and classpath arguments as the style replay. `--reference` adds a local starting photo and `--after-turn` tests a different observation of its subject. The paths and source pictures remain local. [Results and limitations](../docs/V080_SCENE_DISCOVERY.md).

### Replay composition styles

After building the app and preparing the frozen benchmark and NanoDet asset, run `research/evaluate_composition_styles.py` with `--java` set to the local JDK executable and `--classpath` pointing to `app/build/tmp/kotlin-classes/debug` and the Kotlin stdlib JAR. Use the platform's classpath separator. The Java driver runs the same Kotlin framing policy as the app. `--reference` and `--tap X Y` can test a local photo separately. The replay records composition choices and subject coverage. It does not measure blind preference or device performance.

### Reviewing the Vivo planner's framing

`review_live_planner.py` uses the frozen benchmark photos, the committed 0.11/0.12 paired replay, and the historical Thirds output in `.local/composition-style-replays.json`. The latter is produced by `evaluate_composition_styles.py --java <java> --classpath <compiled classes and Kotlin stdlib>`. Run these commands with the research Python environment:

```powershell
& $ResearchPython research/review_live_planner.py
# After exporting votes from the generated review.html
& $ResearchPython research/review_live_planner.py --answers 'path/to/composition-review-answers.json'
```

Open `artifacts/composition-research/v012-review/review.html` and label the starting photographs first. The page shows the distinct pairs in shuffled order and saves progress in the browser. Keep the answer key separate from the reviewer. The scorer counts identical crops as ties and reports how often the planner kept already-good views. The 70% threshold uses every labeled improvable scene, including ties and unsure votes. It requires complete starting labels, all distinct votes on improvable scenes for that comparison, and at least 20 improvable scenes. Thirds uses its recorded still-image framing. Camera motion and physical leveling need a separate phone test.

For 0.14, run `replay_landmark_window.py` with the same Java executable and current compiled debug classpath used by the replay tools. Pass `--all-scenes --output research/results/landmark-window-replay.json` to compare the window and single-view policies on 98 variants across 80 source photographs. Add the local palm clip excerpts at `.local/reference-frames/after-{5.1,5.4,5.75,6.05}.jpg` for the clip portion. Those excerpts are available only in the owner's workspace.

For blind review, run the script with `--review-originals --output research/results/landmark-review-originals.json`. This sequence ends on the verified original so both candidate policies and the historical Thirds framing can be judged on the same pixels. Run `review_live_planner.py --landmarks` to generate `artifacts/composition-research/v014-review/review.html`. The two policies chose identical framings on those 60 original end frames, so the review presents 43 distinct pairs against the starting view and 46 against Thirds. Pass `--landmarks --answers <exported JSON>` to score votes.

### Camera-only software depth

Run `research/fetch_subject_model.py` to download and verify the pinned Apache-licensed NanoDet checkpoint. The script writes the processed ONNX to `.local/subject-assets` for Android builds. The app can still plan from image cues when the subject model is absent or finds no subject.

With the development images and research environment set up above, run `research/export_software_depth.py`. It fetches the pinned ZipDepth revision if needed, checks the checkpoint and MIT license, compares the converted output with PyTorch on the 20 development images, and writes the app asset to `.local/software-depth-assets`. It uses the learned global attention from the checkpoint and stores large weights as FP16 with FP32 CPU math.

To rerun the depth-order comparison, download ETH3D `cables_2_mono.zip` and `cables_2_rgbd.zip` into `.local/composition-research`, then run `research/evaluate_software_depth.py`. The latter archive contains the matching depth files. The evaluator checks both archive hashes. [Official dataset downloads](https://www.eth3d.net/slam_datasets#training-datasets), [results](results/software-depth-eth3d.json).

### Earlier experiments

Version [0.4 environment prototype](../docs/V040_ENVIRONMENT_PROTOTYPE.md) now contains a native observation/route pipeline and isolated optional ARCore test. The Python temporal experiment is reproduced with `evaluate_environment_geometry.py --feature temporal`. It tracks through all seven frames per window on the same development sequence; this is not independent phone VIO validation or Android numerical parity. The temporal experiment's recorded time includes loading/decoding its seven input frames.

All of these commands run locally. Only the fetch commands download public model/dataset assets. Camera photos, reconstructed points and generated comparisons stay outside Git and Android assets.

```powershell
& $ResearchPython -X utf8 research/fetch_semantic_assets.py
# Use the owner's exact unobstructed 1080x2400 screenshot. Its coordinates extract the
# viewfinder only; they do not prescribe any winning composition.
& $ResearchPython -X utf8 research/owner_city_experiment.py --screenshot 'C:/path/to/owner-screenshot.png'
& $ResearchPython -X utf8 research/semantic_shots.py
& $ResearchPython -X utf8 research/make_owner_comparison.py
& $ResearchPython -X utf8 research/evaluate_shot_discovery.py --split development
& $ResearchPython -X utf8 research/evaluate_shot_discovery.py --split evaluation
& $ResearchPython -X utf8 research/evaluate_environment_geometry.py --feature orb
& $ResearchPython -X utf8 research/evaluate_environment_geometry.py --feature sift
& $ResearchPython -X utf8 -m unittest discover -s research -p 'test_*.py'
```

Run the scene benchmarks sequentially, without concurrent model export or builds. The fixed semantic vocabulary, scale-bank policy and thresholds are research choices. Proposal count is not a preference rate. Development and evaluation images still have the existing dataset limitations. The ETH3D script verifies a pinned 37 MB ZIP before reading images and calibration; supplied camera poses are used only to evaluate its independently estimated geometry. The second detector experiment was developed after observing the first detector's failures. This is not an independent held-out SLAM qualification.

`export_shot_models.py` creates isolated float/dynamic ONNX files in `.local/shot-discovery-assets`, with parity measurements over the 20 development images. Its int8 conversion currently fails the fidelity gates and exits with an error after saving the negative result and removing the rejected binary. It never replaces `.local/research-assets` or includes these experiments in the app.

`environment_geometry.py frame-a.png frame-b.png calibration.json` can test an independently calibrated, undistorted pair. Calibration JSON requires `verifiedForTheseFrames: true`, `distortionCorrected: true`, the exact `imageSize: [width,height]` and `cameraMatrix: [[fx,0,cx],[0,fy,cy],[0,0,1]]`. Those flags assert measurements that must actually be established; they are not a substitute for calibration. The existing phone sweep lacks verified aligned intrinsics and cannot be passed straight into this solver. Unknown scale stays explicit, even after a pair triangulates.

Unreleased Android sweep metadata keeps schema 1 for compatibility and adds `geometryMetadataVersion`, actual camera timestamps, sensor timestamps, source dimensions and pixel transforms. A separate `camera-capabilities.txt` inventories all exposed lenses. Sensor quaternion samples remain unsynchronised device orientation, with no camera-pose or metric-depth claim. These fields support future calibration work; they do not make the recording a SLAM map.
