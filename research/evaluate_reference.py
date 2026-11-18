"""Illustrative owner-reference check, excluded from the held-out experiment."""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from PIL import Image, ImageOps, ImageDraw
from fetch_assets import ROOT
from evaluate import OUT, camera_boxes, crop
from nima import NimaScorer
from gaic import GaicScorer


def main(path):
    image = Image.open(path).convert("RGB")
    boxes = camera_boxes(); results, views = [], [image]
    for scorer in [NimaScorer(), GaicScorer()]:
        scores = scorer.score(image, boxes); index = int(np.argmax(scores))
        results.append({"model": scorer.name, "cropId": index, "scores": [float(v) for v in scores]})
        views.append(crop(image, boxes[index]))
    sheet = Image.new("RGB", (990, 470), "white"); draw = ImageDraw.Draw(sheet)
    for index, (label, view) in enumerate(zip(["Reference starting view", "NIMA candidate", "GAIC candidate"], views)):
        thumb = ImageOps.contain(view, (310, 420)); sheet.paste(thumb, (index*330+(310-thumb.width)//2, 25))
        draw.text((index*330+4, 450), label, fill="black")
    sheet.save(OUT / "reference-comparison.jpg", quality=92)
    report = {"sourceSha256": hashlib.sha256(path.read_bytes()).hexdigest(), "results": results,
        "status": "illustrative owner-reference check; excluded from evaluation set",
        "limitations": ["Reference screenshot contains viewfinder grid lines.",
            "Content outside the initial frame cannot be recovered by cropping.",
            "Nine-crop search does not reproduce the reference app's larger zoom change or physical movement."]}
    (ROOT / "research" / "results" / "reference-check.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(); parser.add_argument("image", type=Path)
    main(parser.parse_args().image)
