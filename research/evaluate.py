"""Reproduce static crop/ranking experiments. Human preference is a separate gate."""
from __future__ import annotations
import argparse
import hashlib
import json
import platform
import time
from pathlib import Path
import numpy as np
from PIL import Image, ImageEnhance
from scipy.stats import spearmanr
from fetch_assets import ROOT, CACHE

OUT = ROOT / "artifacts" / "composition-research"


def camera_boxes():
    # Exactly the nine crops in ShotGeometry.options, in normalized x/y coordinates.
    boxes = [[0, 0, 1, 1]]
    for zoom, x, y in [(1.35,.5,.5),(1.6,.34,.34),(1.6,.66,.34),(1.6,.34,.66),
                       (1.6,.66,.66),(1.8,.5,.5),(1.6,.5,.32),(1.6,.5,.68)]:
        half = .5 / zoom
        boxes.append([x-half, y-half, x+half, y+half])
    return boxes


def crop(image, box):
    return image.crop(tuple(round(v * (image.width if i % 2 == 0 else image.height)) for i, v in enumerate(box)))


def verify(record):
    path = ROOT / record["path"]
    if hashlib.sha256(path.read_bytes()).hexdigest() != record["sha256"]:
        raise ValueError(f"Changed benchmark photo: {record['id']}")
    return Image.open(path).convert("RGB")


def evaluate(scorer, scenes):
    results = []
    boxes = camera_boxes()
    for number, scene in enumerate(scenes):
        image = verify(scene)
        # JPEG sources are already oriented in the GAIC annotation coordinate space.
        rows = np.loadtxt(ROOT / scene["annotations"])
        rows = rows[rows[:, 4] != -2]
        human = rows[:, 4]
        annotated = [[r[1]/image.width, r[0]/image.height, r[3]/image.width, r[2]/image.height] for r in rows]
        started = time.perf_counter()
        prediction = scorer.score(image, annotated)
        annotated_ms = (time.perf_counter() - started) * 1000
        started = time.perf_counter()
        scores = scorer.score(image, boxes)
        camera_ms = (time.perf_counter() - started) * 1000
        index = int(np.argmax(scores))
        if not np.isfinite(scores).all() or not np.isfinite(prediction).all():
            raise ValueError("Nonfinite model output")
        # Same candidates in reverse order: the selected photograph must stay the same.
        reverse = scorer.score(image, list(reversed(boxes)))[::-1]
        perturbations = [ImageEnhance.Brightness(image).enhance(.96), ImageEnhance.Brightness(image).enhance(1.04)]
        for dx, dy in [(.01, 0), (-.01, 0), (0, .01)]:
            margin = .02
            perturbations.append(crop(image, [margin+dx, margin+dy, 1-margin+dx, 1-margin+dy]).resize(image.size))
        stable = [int(np.argmax(scorer.score(p, boxes))) == index for p in perturbations]
        selected_human = float(human[int(np.argmax(prediction))])
        correlation = float(spearmanr(human, prediction).statistic)
        output = OUT / "images" / scorer.name
        output.mkdir(parents=True, exist_ok=True)
        crop(image, boxes[index]).save(output / f"{scene['id']}.jpg", quality=92)
        results.append({"sceneId": scene["id"], "split": scene["split"], "cameraChoice": index,
            "cameraScores": [round(float(v), 6) for v in scores], "cameraMs": round(camera_ms, 3),
            "annotatedMs": round(annotated_ms, 3), "annotationCount": len(rows),
            "spearman": correlation if np.isfinite(correlation) else None,
            "selectedHumanMOS": selected_human, "bestHumanMOS": float(max(human)),
            "mosRegret": float(max(human)) - selected_human,
            "candidateOrderMaxError": float(np.max(np.abs(reverse - scores))),
            "perturbationSameChoice": stable})
        print(f"{scorer.name}: {number+1}/{len(scenes)} {scene['id']} crop={index} {camera_ms:.1f}ms", flush=True)
    return results


def summary(results):
    test = [r for r in results if r["split"] == "evaluation"]
    return {"evaluatedScenes": len(test),
        "meanSpearman": float(np.mean([r["spearman"] for r in test if r["spearman"] is not None])),
        "meanMOSRegret": float(np.mean([r["mosRegret"] for r in test])),
        "cameraMedianMs": float(np.median([r["cameraMs"] for r in test])),
        "cameraP95Ms": float(np.percentile([r["cameraMs"] for r in test], 95)),
        "keptOriginal": sum(r["cameraChoice"] == 0 for r in test),
        "perturbationSameChoiceRate": float(np.mean([s for r in test for s in r["perturbationSameChoice"]])),
        "maxCandidateOrderError": max(r["candidateOrderMaxError"] for r in test),
        "humanPreferenceGate": "pending blind review", "phonePerformanceGate": "pending physical Vivo test",
        "perspectiveDiscoveryGate": "untested by a static crop dataset"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", choices=["nima", "gaic"], required=True)
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()
    from nima import NimaScorer
    from gaic import GaicScorer
    scorer = NimaScorer() if args.model == "nima" else GaicScorer()
    scenes = json.loads((ROOT / "research" / "benchmark.json").read_text(encoding="utf-8"))["scenes"]
    if args.limit:
        scenes = scenes[:args.limit]
    # Keep only model startup outside the timings. All per-image preparation is timed.
    results = evaluate(scorer, scenes)
    report = {"schema": 1, "model": scorer.name, "device": "Windows desktop CPU, not Vivo",
        "python": platform.python_version(), "platform": platform.platform(), "threads": 2,
        "manifestSha256": hashlib.sha256((ROOT / "research" / "benchmark.json").read_bytes()).hexdigest(),
        "summary": summary(results) if any(r["split"] == "evaluation" for r in results) else None,
        "results": results}
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"{args.model}-results.json"
    path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["summary"], indent=2))


if __name__ == "__main__":
    main()
