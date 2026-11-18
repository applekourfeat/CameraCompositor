"""Diagnose the owner's real failure with the same photo and a wider candidate search.

The towers/bridge examples are not target labels or hand-authored winning rectangles.
No owner photographs are committed or uploaded. A dense crop search is a diagnostic,
not a claim that GAIC can independently choose photographic intent.
"""
import json
import argparse
import hashlib
import time
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageOps
from evaluate import OUT, camera_boxes, crop
from gaic import GaicScorer


def expanded_boxes():
    boxes = [[0., 0., 1., 1.]]
    for zoom in [1.25, 1.5, 1.8, 2.2, 2.6, 3.2]:
        half = .5/zoom
        for y in np.linspace(half, 1-half, 7):
            for x in np.linspace(half, 1-half, 7):
                boxes.append([float(x-half), float(y-half), float(x+half), float(y+half)])
    return boxes


def diverse(scores, boxes, count=6):
    chosen = []
    def iou(a, b):
        intersection = max(0, min(a[2],b[2])-max(a[0],b[0])) * max(0, min(a[3],b[3])-max(a[1],b[1]))
        return intersection / ((a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - intersection)
    for index in np.argsort(scores)[::-1]:
        if index == 0:
            continue
        if all(iou(boxes[index], boxes[other]) < .6 for other in chosen):
            chosen.append(int(index))
            if len(chosen) == count:
                break
    return chosen


def contact_sheet(image, boxes, selected, labels, path):
    sheet = Image.new("RGB", (1200, 940), "white")
    draw = ImageDraw.Draw(sheet)
    for position, (index, label) in enumerate(zip(selected, labels)):
        candidate = crop(image, boxes[index])
        thumb = ImageOps.contain(candidate, (380, 420))
        x, y = position%3*400, position//3*470
        sheet.paste(thumb, (x+(380-thumb.width)//2, y))
        draw.text((x+8, y+430), label, fill="black")
    sheet.save(path, quality=92)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--screenshot", type=Path, help="Owner's unobstructed 1080x2400 screenshot; retained locally only")
    args = parser.parse_args()
    if args.screenshot:
        source = Image.open(args.screenshot).convert("RGB")
        if source.size != (1080,2400):
            raise ValueError("Expected the supplied 1080x2400 screenshot; no crop guessed for a different layout")
        target = Path(".local/composition-research/owner-scenes"); target.mkdir(exist_ok=True)
        source.crop((0,285,1080,1725)).save(target/"window-city.png")
        (target/"provenance.json").write_text(json.dumps({
            "sourceSha256": hashlib.sha256(args.screenshot.read_bytes()).hexdigest(),
            "cropPixels": [0,285,1080,1725], "method": "exact screenshot viewfinder extraction; no content reconstruction"
        },indent=2),encoding="utf-8")
    image = Image.open(".local/composition-research/owner-scenes/window-city.png").convert("RGB")
    scorer = GaicScorer()
    original_boxes = camera_boxes()
    old = scorer.score(image, original_boxes)
    boxes = expanded_boxes()
    start = time.perf_counter(); scores = scorer.score(image, boxes); elapsed = (time.perf_counter()-start)*1000
    selected = diverse(scores, boxes)
    target = OUT / "owner-city"; target.mkdir(exist_ok=True)
    contact_sheet(image, boxes, selected, [f"GAIC crop {i}; score {scores[i]:.2f}" for i in selected], target/"gaic-dense.jpg")
    report = {"originalNineChoice": int(np.argmax(old)), "originalNineScores": [float(v) for v in old],
        "denseCandidateCount": len(boxes), "denseHighestChoice": int(np.argmax(scores)),
        "fullViewScore": float(scores[0]), "denseMs": elapsed,
        "diverseNonFullCandidates": [{"index": i, "box": boxes[i], "score": float(scores[i])} for i in selected],
        "status": "local diagnostic on owner input; not a human preference result"}
    (target/"gaic-dense.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
