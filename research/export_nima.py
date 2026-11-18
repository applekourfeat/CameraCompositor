"""Export debug-only NIMA assets and measure FP16 conversion error on development scenes."""
import hashlib
import json
from pathlib import Path
import numpy as np
from PIL import Image
from fetch_assets import CACHE, ROOT, REVISIONS
from nima import NimaScorer
from evaluate import camera_boxes, verify


def main():
    model = NimaScorer()
    target = ROOT / ".local" / "research-assets"
    target.mkdir(parents=True, exist_ok=True)
    path = target / "nima-mobilenet-fp16.tflite"
    model.export(path)
    interpreter = model.tf.lite.Interpreter(model_path=str(path), num_threads=2)
    interpreter.allocate_tensors()
    input_index = interpreter.get_input_details()[0]["index"]
    output_index = interpreter.get_output_details()[0]["index"]
    scenes = json.loads((ROOT / "research" / "benchmark.json").read_text(encoding="utf-8"))["scenes"]
    errors, matches = [], []
    for scene in [s for s in scenes if s["split"] == "development"]:
        image = verify(scene)
        samples = model.prepare(image, camera_boxes())
        expected = model.score(image, camera_boxes())
        scores = []
        for sample in samples:
            interpreter.set_tensor(input_index, sample[None])
            interpreter.invoke()
            scores.append(float(interpreter.get_tensor(output_index)[0] @ np.arange(1, 11)))
        errors.append(float(np.max(np.abs(expected - scores))))
        matches.append(int(np.argmax(expected)) == int(np.argmax(scores)))
    report = {"source": "idealo/image-quality-assessment", "revision": REVISIONS["nima"][1],
        "weightsSha256": hashlib.sha256((CACHE / "nima-aesthetic.hdf5").read_bytes()).hexdigest(),
        "tfliteSha256": hashlib.sha256(path.read_bytes()).hexdigest(), "bytes": path.stat().st_size,
        "precision": "float16 weights, float32 inputs/outputs", "developmentScenes": len(errors),
        "maximumScoreError": max(errors), "sameSelectionRate": float(np.mean(matches)),
        "androidNumericalParity": "pending device test", "compositionQuality": "unvalidated research candidate"}
    report["status"] = "accepted" if max(errors) <= .02 and all(matches) else "rejected"
    (ROOT / "artifacts" / "composition-research" / "nima-conversion.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    if report["status"] == "rejected":
        path.unlink()
        raise ValueError(f"Conversion failed fidelity gate: {report}")
    (target / "nima-model.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    license_source = CACHE / "sources" / f"image-quality-assessment-{REVISIONS['nima'][1]}" / "LICENSE"
    (target / "NIMA-LICENSE.txt").write_text(license_source.read_text(encoding="utf-8"), encoding="utf-8")
    (ROOT / "artifacts" / "composition-research" / "nima-conversion.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
