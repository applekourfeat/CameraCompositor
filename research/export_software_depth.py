"""Prepare and check the pinned ZipDepth mobile model for the CameraX path."""
import hashlib
import io
import json
from pathlib import Path
import runpy
import sys
import time
import types
import urllib.request
import zipfile

import cv2
import numpy as np
import onnx
import onnxruntime as ort
from onnx import helper, numpy_helper
from scipy.stats import spearmanr
import torch

ROOT = Path(__file__).resolve().parents[1]
REVISION = "91f3fd21e131641f51e8d35736d1958350180e3a"
REPO = "https://github.com/fabiotosi92/ZipDepth"
SOURCE = ROOT / ".local/composition-research/zipdepth" / REVISION
CHECKPOINT = "checkpoints/zipdepth_base_npu.pth"
WEIGHT_SHA = "627c04fda584133ead4310074884a4a037061b4c01ba86e73e492ea30fab570d"
WIDTH, HEIGHT = 192, 256
ASSETS = ROOT / ".local/software-depth-assets"
ASSET = "zipdepth-192x256.onnx"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_files():
    if not (SOURCE / CHECKPOINT).exists():
        location = "https://codeload.github.com/fabiotosi92/ZipDepth/zip/" + REVISION
        with urllib.request.urlopen(location, timeout=45) as response:
            archive = response.read(100_000_001)
        if len(archive) > 100_000_000:
            raise ValueError("ZipDepth download too large")
        with zipfile.ZipFile(io.BytesIO(archive)) as source:
            for entry in source.infolist():
                name = entry.filename.split("/", 1)[1]
                if entry.is_dir() or not (name.startswith("zipdepth/") or name in
                        ("LICENSE", "scripts/export.py", CHECKPOINT)):
                    continue
                subpath = Path(name)
                if subpath.is_absolute() or ".." in subpath.parts or entry.file_size > 28_000_000:
                    raise ValueError("Unexpected ZipDepth archive entry")
                target = SOURCE / subpath
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read(entry))
    if sha(SOURCE / CHECKPOINT) != WEIGHT_SHA:
        raise ValueError("Unexpected ZipDepth checkpoint")
    license_bytes = (SOURCE / "LICENSE").read_bytes()
    if hashlib.sha1(b"blob " + str(len(license_bytes)).encode() + b"\0" + license_bytes).hexdigest() != "d1d06825017876a3708f9caa16ae6184312eb3d2":
        raise ValueError("Unexpected ZipDepth license")


def compact_weights(source, destination):
    """Store large constants as FP16. Cast back to FP32 at load for ordinary CPU inference."""
    model = onnx.load(source)
    casts = []
    for index, tensor in enumerate(model.graph.initializer):
        if tensor.data_type != onnx.TensorProto.FLOAT:
            continue
        weights = numpy_helper.to_array(tensor)
        if weights.size < 256:
            continue
        name = tensor.name
        packed = numpy_helper.from_array(weights.astype(np.float16), name=name + "_packed16")
        model.graph.initializer[index].CopyFrom(packed)
        casts.append(helper.make_node("Cast", [packed.name], [name], to=onnx.TensorProto.FLOAT))
    original_nodes = list(model.graph.node)
    del model.graph.node[:]
    model.graph.node.extend(casts + original_nodes)
    onnx.checker.check_model(model)
    onnx.save(model, destination)


def prepare_image(bgr):
    resized = cv2.resize(bgr, (WIDTH, HEIGHT), interpolation=cv2.INTER_LINEAR)
    return np.ascontiguousarray(resized[..., ::-1].transpose(2, 0, 1)[None], dtype=np.float32) / 255


def session(path):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.add_session_config_entry("session.intra_op.allow_spinning", "0")
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def main():
    source_files()
    ort.disable_telemetry_events()
    torch.set_num_threads(2)
    sys.path.insert(0, str(SOURCE))
    upstream = runpy.run_path(str(SOURCE / "scripts/export.py"))
    model = upstream["load_model"](str(SOURCE / CHECKPOINT), "base", "balanced", "cpu", upsample_unfold=False)
    manifest = json.loads((ROOT / "research/benchmark.json").read_text(encoding="utf-8"))["scenes"]
    scenes = [s for s in manifest if s["split"] == "development"]
    inputs = [prepare_image(cv2.imread(str(ROOT / s["path"]))) for s in scenes]
    with torch.inference_mode():
        reference = [model(torch.from_numpy(i)).numpy() for i in inputs]

    export_dir = SOURCE / "export"
    export_dir.mkdir(exist_ok=True)
    full = export_dir / "float32.onnx"
    compact = export_dir / ASSET
    # At dimensions divisible by 32 the pyramid's cross-scale pooling is exactly 2x2.
    # Preserve learned global attention. The upstream mobile exporter replaces it with a mean.
    def fixed_cross_scale(layer, high, low):
        up = torch.nn.functional.interpolate(layer.low_to_high(low), scale_factor=2, mode="nearest")
        down = torch.nn.functional.avg_pool2d(layer.high_to_low(high), 2)
        return high + up * .3, low + down * .3

    model.encoder.cross_scale.forward = types.MethodType(fixed_cross_scale, model.encoder.cross_scale)
    with torch.inference_mode():
        torch.onnx.export(model, torch.from_numpy(inputs[0]), str(full), dynamo=False, opset_version=17,
                          input_names=["image"], output_names=["depth"])
    compact_weights(full, compact)
    started = time.perf_counter()
    cpu = session(compact)
    init_ms = (time.perf_counter() - started) * 1000
    times, correlations, errors = [], [], []
    for image, expected in zip(inputs, reference):
        started = time.perf_counter()
        actual = cpu.run(["depth"], {"image": image})[0]
        times.append((time.perf_counter() - started) * 1000)
        assert actual.shape == expected.shape and actual.size == WIDTH * HEIGHT and np.isfinite(actual).all()
        flat_expected, flat_actual = expected.ravel(), actual.ravel()
        correlations.append(float(spearmanr(flat_expected, flat_actual).statistic))
        scale = max(float(np.ptp(flat_expected)), 1e-6)
        errors.append(float(np.max(np.abs(flat_actual - flat_expected)) / scale))
    passed = min(correlations) >= .99 and max(errors) <= .035
    report = {
        "source": REPO, "revision": REVISION, "checkpointSha256": WEIGHT_SHA, "asset": ASSET,
        "assetSha256": sha(compact), "assetBytes": compact.stat().st_size, "license": "MIT",
        "inputNchwRgb01": [1, 3, HEIGHT, WIDTH], "output": "relative inverse depth; affine scale unknown",
        "encoding": "FP16 stored weights, FP32 CPU compute", "developmentImages": len(inputs),
        "minimumOrdinalCorrelationAgainstPyTorch": min(correlations),
        "maximumNormalizedDifferenceAgainstPyTorch": max(errors), "conversionPassed": passed,
        "desktopCpuThreads": 2, "desktopSessionInitMs": init_ms, "desktopP50Ms": float(np.median(times[1:])),
        "desktopP95Ms": float(np.percentile(times[1:], 95)), "phoneTiming": "pending",
    }
    print(json.dumps(report, indent=2))
    (ROOT / "research/results/software-depth-export.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if not passed:
        raise ValueError("ZipDepth export failed ordinal parity")
    ASSETS.mkdir(exist_ok=True)
    (ASSETS / ASSET).write_bytes(compact.read_bytes())


if __name__ == "__main__":
    main()
