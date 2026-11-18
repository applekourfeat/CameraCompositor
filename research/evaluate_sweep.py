"""Rank only real observed views exported by the research APK. No cloud or invented views."""
import argparse
import hashlib
import io
import json
import math
from pathlib import Path
import zipfile
from PIL import Image, ImageDraw, ImageOps
import numpy as np
from gaic import GaicScorer
from evaluate import OUT, camera_boxes, crop


def read_sweep(path):
    with zipfile.ZipFile(path) as source:
        info = source.getinfo("observations.json")
        if info.file_size > 64_000:
            raise ValueError("Sweep metadata exceeds its limit")
        metadata = json.loads(source.read(info))
        if metadata.get("schema") != 1 or not 1 <= len(metadata.get("frames", [])) <= 8:
            raise ValueError("Invalid sweep schema or frame count")
        frames, identities = [], set()
        for frame in metadata["frames"]:
            identity = frame["id"]
            if type(identity) is not int or not 0 <= identity <= 7 or identity in identities:
                raise ValueError("Invalid or duplicate observed frame identity")
            identities.add(identity)
            info = source.getinfo(f"frames/{identity:02d}.jpg")
            if info.file_size > 2_000_000:
                raise ValueError("Observed frame exceeds its limit")
            image = Image.open(io.BytesIO(source.read(info)))
            if image.width * image.height > 2_000_000:
                raise ValueError("Observed image dimensions exceed their limit")
            zoom, max_relative = frame["zoom"], frame["maxRelativeZoom"]
            if not math.isfinite(zoom) or zoom <= 0 or not math.isfinite(max_relative) or not 1 <= max_relative <= 1.8001:
                raise ValueError("Invalid observed zoom range")
            frames.append((frame, image.convert("RGB")))
    return metadata, frames


def evaluate(path):
    metadata, frames = read_sweep(path)
    scorer = GaicScorer()
    boxes = camera_boxes()
    candidates = []
    for frame, image in frames:
        scores = scorer.score(image, boxes)
        for index, (box, score) in enumerate(zip(boxes, scores)):
            relative_zoom = 1 / (box[2]-box[0])
            if relative_zoom <= frame["maxRelativeZoom"] + .0001:
                candidates.append({"observedViewId": frame["id"], "cropId": index, "crop": box,
                    "absoluteZoom": frame["zoom"] * relative_zoom, "score": float(score)})
    selected = max(candidates, key=lambda row: (row["score"], -row["absoluteZoom"]))
    identity = hashlib.sha256(path.read_bytes()).hexdigest()[:12]
    output = OUT / f"sweep-{identity}"
    output.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGB", (1000, 440), "white")
    draw = ImageDraw.Draw(sheet)
    for index, (frame, image) in enumerate(frames):
        thumb = ImageOps.contain(image, (240, 190))
        x, y = index%4*250, index//4*220
        sheet.paste(thumb, (x, y)); draw.text((x+4, y+193), f"Observed view {frame['id']}", fill="black")
        if frame["id"] == selected["observedViewId"]:
            crop(image, selected["crop"]).save(output / "candidate.jpg", quality=92)
    sheet.save(output / "observed-views.jpg", quality=92)
    report = {"schema": 1, "sourceDevice": metadata.get("device"), "candidate": selected,
        "candidateCount": len(candidates), "framesObserved": len(frames),
        "status": "experimental candidate for human review; no camera commands issued",
        "limitations": ["Cross-view aesthetic score calibration is unverified.",
            "Sensor readings are relative device rotation, not camera pose ground truth.",
            "Automatic angle guidance and physical performance require separate Vivo tests."]}
    (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(); parser.add_argument("sweep", type=Path)
    print(json.dumps(evaluate(parser.parse_args().sweep), indent=2))
