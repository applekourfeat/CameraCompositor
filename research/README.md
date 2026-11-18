# Reproduce the composition research

The research compares actual model outputs with rated crops and provides a separate human-review tool. The recorded results are desktop measurements. No human preference or physical-phone result is implied.

## Open the results

- Read [the report](../docs/COMPOSITION_RESEARCH.md).
- Open `artifacts/composition-research/review.html` in your own desktop browser. Start with Development > Compare suggestions. You can stop and return later. Download answers before moving or closing the workspace.
- Run `summarize_review.py` on the downloaded answers. Blank or partial reviews remain pending.
- Inspect `artifacts/composition-research/examples-1.jpg` and `examples-2.jpg` for the first six seeded evaluation scenes.

The photos and generated review pages remain local. They are recreated from the pinned author download, rather than committed to GitHub. Opening the reviewer requires no server or account. The in-app automated browser blocks local file navigation, so its interactive behavior needs a manual browser check.

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
