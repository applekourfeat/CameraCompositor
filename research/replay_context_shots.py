"""Run the Vivo demo's Android policy with its shipped models on camera stills."""

import argparse
import json
from pathlib import Path
import subprocess
from time import perf_counter

import cv2
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageOps

from evaluate import crop
from evaluate_composition_styles import MODEL, write_inputs
from evaluate_isolated_views import gaic_scores, gaic_session
from export_scene_semantics import ASSETS, ROOT, pixels, session


def compare(driver, path, zoom, detector, patch_model, ranker):
    image = Image.open(path).convert("RGB")
    start = perf_counter()
    patches = patch_model.run(None, {"image": pixels(path)})[0]
    write_inputs(driver, {"path": path}, image, detector, tap=(zoom, 0), suffix=patches.astype(">f4").tobytes())
    count = int(driver.stdout.readline())
    rows = [driver.stdout.readline().decode().split() for _ in range(count)]
    boxes = [list(map(float, row[:4])) for row in rows]
    scores = gaic_scores(ranker, image, boxes)
    driver.stdin.write(scores.astype(">f4").tobytes())
    driver.stdin.flush()
    winner, gain = map(float, driver.stdout.readline().split())
    index = int(winner)
    box = boxes[index]
    result = {"input": path.name, "sourceZoom": zoom, "compared": count, "decision": "Guide" if index else "Keep",
              "subject": rows[index][4], "frame": box, "chosenZoom": round(zoom / (box[2] - box[0]), 3),
              "framingGain": round(gain, 4), "modelScore": round(float(scores[index]), 4),
              "wideScore": round(float(scores[0]), 4), "desktopMs": round((perf_counter() - start) * 1000, 2)}
    return result, image, crop(image, box)


def sheet(images, path):
    panels = Image.new("RGB", (560, len(images) * 360), "#181919")
    draw = ImageDraw.Draw(panels)
    for index, (row, before, after) in enumerate(images):
        y = index * 360
        draw.text((14, y + 12), f"{row['input']}   Starting view", fill="white")
        draw.text((282, y + 12), "Proposed frame" if row["decision"] == "Guide" else "Kept starting view", fill="white")
        for left, photo in ((14, before), (282, after)):
            image = ImageOps.contain(photo, (250, 320), Image.Resampling.LANCZOS)
            panels.paste(image, (left + (250 - image.width) // 2, y + 34))
    panels.save(path, quality=91)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--frame", action="append", nargs=2, metavar=("IMAGE", "ZOOM"), required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--comparison", type=Path)
    args = parser.parse_args()
    cv2.setNumThreads(1)
    ort.disable_telemetry_events()
    detector, patch_model = session(MODEL), session(ASSETS / "scene-regions-192.onnx")
    ranker = gaic_session()
    command = [args.java, "--class-path", args.classpath, str(ROOT / "research/ReplayContextShots.java")]
    with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, cwd=ROOT) as driver:
        results = [compare(driver, Path(path).resolve(), float(zoom), detector, patch_model, ranker)
                   for path, zoom in args.frame]
        driver.stdin.write(bytes(4))
        driver.stdin.close()
        if driver.wait() != 0:
            raise ValueError("Android policy replay failed.")
    if args.comparison is not None:
        sheet(results, args.comparison)
    data = json.dumps({"scope": "Recorded stills, desktop CPU. Timing includes local model calls and replay overhead.",
                       "vivoTests": "pending", "humanPreference": "pending", "results": [r[0] for r in results]},
                      indent=2) + "\n"
    if args.output:
        args.output.write_text(data, encoding="utf-8")
    print(data, end="")


if __name__ == "__main__":
    main()
