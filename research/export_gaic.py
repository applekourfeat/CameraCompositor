"""Export the CPU port with standard ONNX operators, then verify development-set parity."""
import hashlib
import json
import math
import time
import numpy as np
import onnx
import onnxruntime as ort
ort.disable_telemetry_events()
import torch
from fetch_assets import ROOT, CACHE, REVISIONS
from gaic import GaicScorer
from evaluate import camera_boxes, verify


def inputs(image):
    pixels, w, h = GaicScorer.prepare(image)
    boxes = torch.tensor([[math.floor(b[0]*w), math.floor(b[1]*h), math.ceil(b[2]*w), math.ceil(b[3]*h)]
                         for b in camera_boxes()], dtype=torch.float32)
    return pixels, boxes


def main():
    scorer = GaicScorer()
    scenes = json.loads((ROOT / "research" / "benchmark.json").read_text(encoding="utf-8"))["scenes"]
    development = [s for s in scenes if s["split"] == "development"]
    example = inputs(verify(development[0]))
    target = ROOT / ".local" / "research-assets"
    target.mkdir(parents=True, exist_ok=True)
    path = target / "gaic-mobilenet.onnx"
    torch.onnx.export(scorer.model, example, str(path), input_names=["image", "boxes"], output_names=["scores"],
        opset_version=17, dynamo=False, dynamic_axes={"image": {2: "height", 3: "width"}})
    onnx.checker.check_model(str(path))
    options = ort.SessionOptions(); options.intra_op_num_threads = 2; options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
    errors, matches, durations = [], [], []
    for scene in development:
        image = verify(scene)
        pixels, boxes = inputs(image)
        expected = scorer.score(image, camera_boxes())
        started = time.perf_counter()
        prediction = session.run(None, {"image": pixels.numpy(), "boxes": boxes.numpy()})[0] * .8 + 2.95
        durations.append((time.perf_counter()-started)*1000)
        errors.append(float(np.max(np.abs(prediction - expected))))
        matches.append(int(np.argmax(expected)) == int(np.argmax(prediction)))
    report = {"source": REVISIONS["gaic"][0], "revision": REVISIONS["gaic"][1],
        "checkpointArchiveSha256": "b327c170b9738680b164783a0757fac7bd1f3f7eba76fcd71da09b7e605feef0",
        "onnxSha256": hashlib.sha256(path.read_bytes()).hexdigest(), "bytes": path.stat().st_size,
        "developmentScenes": len(errors), "maximumScoreError": max(errors), "sameSelectionRate": float(np.mean(matches)),
        "desktopInferenceMedianMs": float(np.median(durations)), "androidParity": "pending Vivo test",
        "upstreamCudaParity": "not measured on GPU; CPU equations covered by scalar-reference tests",
        "distribution": "private debug research only; checkpoint redistribution permission unverified"}
    if max(errors) > .001 or not all(matches):
        path.unlink()
        raise ValueError(f"ONNX conversion failed fidelity gate: {report}")
    (target / "gaic-model.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    license_source = CACHE / "sources" / f"Grid-Anchor-based-Image-Cropping-Pytorch-{REVISIONS['gaic'][1]}" / "LICENSE"
    (target / "GAIC-CODE-LICENSE.txt").write_text(license_source.read_text(encoding="utf-8"), encoding="utf-8")
    (ROOT / "artifacts" / "composition-research" / "gaic-conversion.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
