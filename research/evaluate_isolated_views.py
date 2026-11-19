"""Check whether crop rankers can judge individually observed views.

Each sampled crop has an existing human mean opinion score. Scoring the same crop
as a standalone image tests a change in inputs while preserving those labels.
This proxy measures framing preference with common scene content and position.
"""

import argparse
import json
from pathlib import Path
from time import perf_counter

import cv2
import numpy as np
import onnxruntime as ort
from scipy.stats import spearmanr

from evaluate import ROOT, crop, verify
from nima import NimaScorer

MODEL = ROOT / ".local/shot-discovery-assets/gaic-dynamic.onnx"
FULL = (0, 0, 1, 1)
MARGIN = .5


def gaic_session():
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.add_session_config_entry("session.intra_op.allow_spinning", "0")
    return ort.InferenceSession(str(MODEL), options, providers=["CPUExecutionProvider"])


def gaic_scores(model, image, boxes):
    factor = 256 / min(image.size)
    width, height = [round(length * factor / 32) * 32 for length in image.size]
    pixels = cv2.resize(np.asarray(image), (width, height)).astype(np.float32) / 256
    mean = np.array([.485, .456, .406], np.float32)
    std = np.array([.229, .224, .225], np.float32)
    pixels = np.ascontiguousarray(((pixels - mean) / std).transpose(2, 0, 1)[None])
    rois = np.asarray(boxes, np.float32) * [width, height, width, height]
    rois[:, :2] = np.floor(rois[:, :2])
    rois[:, 2:] = np.ceil(rois[:, 2:])
    return model.run(None, {"image": pixels, "boxes": rois.astype(np.float32)})[0] * .8 + 2.95


def sample(scene, image, count):
    rows = np.loadtxt(ROOT / scene["annotations"])
    rows = rows[(rows[:, 4] >= 1) & (rows[:, 4] <= 5) & (rows[:, 2] > rows[:, 0]) & (rows[:, 3] > rows[:, 1])]
    # Freeze positions by scene ID. The score column has no role in candidate order.
    rng = np.random.default_rng(20261119 + int(scene["id"].split("-")[-1]))
    chosen = rows[rng.choice(len(rows), min(count, len(rows)), replace=False)]
    boxes = chosen[:, [1, 0, 3, 2]] / [image.width, image.height, image.width, image.height]
    if not ((boxes >= 0) & (boxes <= 1)).all():
        raise ValueError(f"Crop outside annotation image: {scene['id']}")
    return boxes, chosen[:, 4]


def measure(scores, human):
    if not np.isfinite(scores).all():
        raise ValueError("Invalid scores")
    strong_pairs = (human[:, None] - human[None, :]) >= MARGIN
    differences = (scores[:, None] - scores[None, :])[strong_pairs]
    correct = np.count_nonzero(differences > 0) + .5 * np.count_nonzero(differences == 0)
    # Average the true score across tied top choices to keep ordering neutral.
    top = human[scores == np.max(scores)]
    correlation = spearmanr(scores, human).statistic if np.ptp(scores) > 0 and np.ptp(human) > 0 else None
    return {"pairs": len(differences), "correct": correct, "rankCorrelation": correlation,
            "gapToBest": float(np.max(human) - np.mean(top))}


def evaluate(scene, model, nima, count):
    image = verify(scene)
    options, human = sample(scene, image, count)
    start = perf_counter()
    with_context = gaic_scores(model, image, options)
    context_ms = (perf_counter() - start) * 1000
    observed = [crop(image, box) for box in options]
    start = perf_counter()
    standalone = np.array([gaic_scores(model, shot, [FULL])[0] for shot in observed])
    isolated_ms = (perf_counter() - start) * 1000
    start = perf_counter()
    general = nima.score(image, options)
    nima_ms = (perf_counter() - start) * 1000
    methods = {"gaicWithContext": (with_context, context_ms), "gaicIsolated": (standalone, isolated_ms),
               "nimaIsolated": (general, nima_ms), "largestFrame": ((options[:, 2] - options[:, 0]) *
               (options[:, 3] - options[:, 1]), 0)}
    return {name: {"candidates": len(options), "batchMs": ms, **measure(scores, human)}
            for name, (scores, ms) in methods.items()}


def summarize(rows):
    summary = {}
    for name in rows[0]:
        results = [row[name] for row in rows]
        pairs = sum(row["pairs"] for row in results)
        correlations = [row["rankCorrelation"] for row in results if row["rankCorrelation"] is not None]
        summary[name] = {"ratedPairs": pairs, "pairAgreement": round(sum(row["correct"] for row in results) / pairs, 3),
                         "meanRankCorrelation": round(float(np.mean(correlations)), 3),
                         "averageGapToBest": round(float(np.mean([row["gapToBest"] for row in results])), 3),
                         "desktopMedianBatchMs": round(float(np.median([row["batchMs"] for row in results])), 2)}
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidates", type=int, default=12)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--split", choices=("development", "evaluation", "all"), default="all")
    args = parser.parse_args()
    if args.candidates < 2:
        parser.error("At least two candidates are required.")
    cv2.setNumThreads(1)
    ort.disable_telemetry_events()
    scorer, nima = gaic_session(), NimaScorer()
    manifest = json.loads((ROOT / "research/benchmark.json").read_text(encoding="utf-8"))
    records = {"development": [], "evaluation": []}
    for scene in manifest["scenes"]:
        if args.split in (scene["split"], "all"):
            records[scene["split"]].append(evaluate(scene, scorer, nima, args.candidates))
    report = {"test": "Independently score rated crops as full images",
              "scope": "Matched content, static crops, desktop CPU. Human ratings come from GAICv2.",
              "maxCandidatesPerScene": args.candidates, "minimumRatingDifference": MARGIN,
              "settlingRotationAndPhoneLatency": "unmeasured",
              "partitions": {split: {"scenes": len(rows), "methods": summarize(rows)}
                             for split, rows in records.items() if rows}}
    data = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.write_text(data, encoding="utf-8")
    print(data, end="")


if __name__ == "__main__":
    main()
