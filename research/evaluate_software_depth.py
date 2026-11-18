"""Check the packaged model's near/far ordering against registered ETH3D depth."""
import argparse
import hashlib
import json
from pathlib import Path
from time import perf_counter
from zipfile import ZipFile

import cv2
import numpy as np
import onnxruntime as ort
from scipy.stats import spearmanr

ROOT = Path(__file__).resolve().parents[1]
RGB_SHA256 = "cf6e2d74a606b021bb90d0426ad3fd651bf9efbc60220d7c462cf1c8836375ee"
DEPTH_SHA256 = "b8c77ea1003ffbc8f622fe6bc83cc43ed2430d7b441a69f4b59cca8d296a59f8"
ASSET = ROOT / ".local/software-depth-assets/zipdepth-192x256.onnx"
WIDTH, HEIGHT = 192, 256
MINIMUM_PAIR_METRES = .10


def read_image(archive, name, flags):
    with archive.open(name) as entry:
        encoded = np.frombuffer(entry.read(), dtype=np.uint8)
    decoded = cv2.imdecode(encoded, flags)
    if decoded is None:
        raise ValueError(f"Invalid image in {name}")
    return decoded


def portrait_crop(image):
    height, width = image.shape[:2]
    keep = min(width, round(height * WIDTH / HEIGHT))
    start = (width - keep) // 2
    return image[:, start:start + keep]


def assess(prediction, distances):
    # Pool the 192x256 output like the app. Drop sparse ground-truth cells.
    shape = (32, 8, 24, 8)
    ground = distances.reshape(shape).transpose(0, 2, 1, 3).reshape(-1, 64)
    valid = (ground > 0).sum(axis=1) >= 48
    actual = np.nanmedian(np.where(ground[valid] > 0, ground[valid], np.nan), axis=1)
    estimates = prediction.reshape(shape).mean(axis=(1, 3)).ravel()[valid]
    row, col = np.triu_indices(actual.size, k=1)
    separated = np.abs(actual[row] - actual[col]) >= MINIMUM_PAIR_METRES
    row, col = row[separated], col[separated]
    correct = np.sign(estimates[row] - estimates[col]) == np.sign(actual[col] - actual[row])
    rho = spearmanr(estimates, -actual).statistic
    return float(rho) if np.isfinite(rho) else None, float(correct.mean()) if row.size else None, int(valid.sum())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source_dir = ROOT / ".local/composition-research"
    parser.add_argument("--rgb", type=Path, default=source_dir / "cables_2_mono.zip")
    parser.add_argument("--depth", type=Path, default=source_dir / "cables_2_rgbd.zip")
    args = parser.parse_args()
    for path, expected in ((args.rgb, RGB_SHA256), (args.depth, DEPTH_SHA256)):
        with path.open("rb") as source:
            if hashlib.file_digest(source, "sha256").hexdigest() != expected:
                raise ValueError(f"Unexpected ETH3D cables_2 archive {path.name}")
    ort.disable_telemetry_events()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.add_session_config_entry("session.intra_op.allow_spinning", "0")
    cpu = ort.InferenceSession(str(ASSET), options, providers=["CPUExecutionProvider"])
    metrics = []
    with ZipFile(args.rgb) as colors, ZipFile(args.depth) as depths:
        images = sorted(name for name in colors.namelist() if "/rgb/" in name and name.endswith(".png"))
        sample = np.linspace(0, len(images) - 1, 24, dtype=int)
        for index in sample:
            name = images[index]
            color = portrait_crop(read_image(colors, name, cv2.IMREAD_COLOR))
            depth = portrait_crop(read_image(depths, name.replace("/rgb/", "/depth/"), cv2.IMREAD_UNCHANGED))
            rgb = cv2.resize(color, (WIDTH, HEIGHT), interpolation=cv2.INTER_LINEAR)[..., ::-1]
            model_input = np.ascontiguousarray(rgb.transpose(2, 0, 1)[None], dtype=np.float32) / 255
            ground = cv2.resize(depth, (WIDTH, HEIGHT), interpolation=cv2.INTER_NEAREST) / 5000
            start = perf_counter()
            prediction = cpu.run(["depth"], {"image": model_input})[0]
            elapsed_ms = (perf_counter() - start) * 1000
            rho, ordered, cells = assess(prediction, ground)
            metrics.append({"sourceFrame": int(index), "validCells": cells, "rankCorrelation": rho,
                            "correctlyOrderedPairs": ordered, "inferenceMs": elapsed_ms})
    with ASSET.open("rb") as source:
        model_hash = hashlib.file_digest(source, "sha256").hexdigest()
    correlations = [m["rankCorrelation"] for m in metrics if m["rankCorrelation"] is not None]
    ordering = [m["correctlyOrderedPairs"] for m in metrics if m["correctlyOrderedPairs"] is not None]
    report = {
        "dataset": "ETH3D cables_2_rgbd", "source": "https://www.eth3d.net/slam_datasets",
        "rgbArchiveSha256": RGB_SHA256, "depthArchiveSha256": DEPTH_SHA256,
        "modelSha256": model_hash, "sampledFrames": len(metrics),
        "minimumDepthDifferenceMetresForPairs": MINIMUM_PAIR_METRES, "minimumValidDepthPerCell": .75,
        "rankCorrelationFrames": len(correlations), "rankCorrelationMedian": float(np.median(correlations)),
        "rankCorrelationMinimum": min(correlations), "pairOrderingFrames": len(ordering),
        "correctlyOrderedPairsMedian": float(np.median(ordering)), "correctlyOrderedPairsMinimum": min(ordering),
        "desktopWarmInferenceMedianMs": float(np.median([m["inferenceMs"] for m in metrics[1:]])),
        "scope": "One tabletop sequence. Results measure near/far ordering, without metric reconstruction or composition preferences.",
        "frames": metrics,
    }
    (ROOT / "research/results/software-depth-eth3d.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "frames"}, indent=2))


if __name__ == "__main__":
    main()
