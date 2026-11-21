"""Replay short camera observations with measured image registration and a single-frame control."""
import argparse
import json
from pathlib import Path
import struct
import subprocess
import tempfile
from functools import lru_cache

import cv2
import numpy as np
from PIL import ImageEnhance
from evaluate import verify
import replay_context_shots as replay


def gray(path):
    image = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    scale = min(1., 384 / max(image.shape))
    return cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)


def registration(first, second):
    """Estimate a direct link with forward/backward tracks, then fit a robust homography."""
    a, b = gray(first), gray(second)
    if a.shape != b.shape:
        return b"\0"
    points = cv2.goodFeaturesToTrack(a, 220, .01, 5.)
    if points is None or len(points) < 30:
        return b"\0"
    moved, good, error = cv2.calcOpticalFlowPyrLK(a, b, points, None)
    back, reverse, _ = cv2.calcOpticalFlowPyrLK(b, a, moved, None)
    keep = ((good * reverse).ravel() > 0) & (error.ravel() < 30) & (np.linalg.norm(points-back, axis=2).ravel() < 1)
    axy, bxy = points[keep], moved[keep]
    if len(axy) < 24:
        return b"\0"
    h, mask = cv2.findHomography(axy, bxy, cv2.RANSAC, 2.)
    if h is None:
        return b"\0"
    ok = mask.ravel().astype(bool)
    residual = np.linalg.norm(cv2.perspectiveTransform(axy[ok], h)-bxy[ok], axis=2)
    coverage = cv2.contourArea(cv2.convexHull(axy[ok])) / a.size
    scale = np.diag([a.shape[1], a.shape[0], 1.])
    normalized = np.linalg.inv(scale) @ h @ scale
    center = normalized @ np.array([.5, .5, 1.])
    motion = np.linalg.norm(center[:2] / center[2] - .5)
    return struct.pack(">?ii12f", True, int(ok.sum()), len(axy), motion, np.median(residual), coverage, *normalized.ravel())


def history(paths, link):
    data = bytearray(struct.pack(">i", len(paths)-1))
    for a, b in zip(paths, paths[1:]):
        encoded = a.as_posix().encode("utf-8")
        data.extend(struct.pack(">i", len(encoded)) + encoded + link(a, b))
    return data


def variants(record, directory, full_sequence):
    image = verify(record)
    w, h = image.size
    frames = [replay.ROOT / record["path"]]
    changes = [("center", 0, 0, 1), ("left", -.01, 0, 1), ("right", .01, 0, 1),
               ("up", 0, -.01, 1), ("down", 0, .01, 1), ("dim", 0, 0, .96), ("bright", 0, 0, 1.04)]
    for name, dx, dy, gain in changes if full_sequence else changes[:2]:
        rect = tuple(round(v*d) for v, d in zip([.02+dx, .02+dy, .98+dx, .98+dy], [w, h, w, h]))
        path = directory / (name + ".png")
        ImageEnhance.Brightness(image.crop(rect).resize(image.size)).enhance(gain).save(path)
        frames.append(path)
    return frames


def evaluate(driver, models, frames, zoom=1., last_only=False):
    link = lru_cache(maxsize=8)(registration)
    rows = []
    for i, path in enumerate(frames):
        if last_only and i != len(frames)-1:
            continue
        window = frames[max(0, i-2):i+1]
        row = {"frame": path.stem}
        for label, sequence in [("single", [path]), ("window", window)]:
            result, _, _ = replay.compare(driver, path, zoom, *models, history(sequence, link))
            row[label] = {"zoom": result["chosenZoom"], "box": result["frame"], "subject": result["subject"]}
        rows.append(row)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--all-scenes", action="store_true")
    parser.add_argument("--review-originals", action="store_true", help="End each evaluation window on the verified original for blind review")
    args = parser.parse_args()
    root = replay.ROOT
    replay.cv2.setNumThreads(1)
    models = replay.session(replay.MODEL), replay.session(replay.ASSETS/"scene-regions-192.onnx"), replay.gaic_session()
    scenes = json.loads((root/"research/benchmark.json").read_text())["scenes"]
    diagnostic = {"309808", "475676", "396968"}
    results = {}
    changed = {}
    evaluated = 0
    review = []
    cmd = [args.java, "--class-path", args.classpath, str(root/"research/ReplayContextShots.java"), "--adaptive", "--window"]
    with subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE) as driver:
        for record in scenes:
            if args.review_originals:
                if record["split"] != "evaluation":
                    continue
                with tempfile.TemporaryDirectory(dir=root/".local") as folder:
                    original, center, left = variants(record, Path(folder), False)
                    row = evaluate(driver, models, [center, left, original], last_only=True)[0]
                review.append(dict(scene=record["id"], sha256=record["sha256"], before=row["single"], after=row["window"]))
                continue
            focus = record["id"].split("-")[-1] in diagnostic
            if not focus and not args.all_scenes:
                continue
            with tempfile.TemporaryDirectory(dir=root/".local") as folder:
                rows = evaluate(driver, models, variants(record, Path(folder), focus), last_only=not focus)
            if focus:
                results[record["id"]] = rows
            variations = rows[1:] if focus else rows
            evaluated += len(variations)
            changed[record["id"]] = sum(row["single"]["box"] != row["window"]["box"] for row in variations)
            print(record["id"], flush=True)
        refs = root / ".local/reference-frames"
        palm = [refs / f"after-{t}.jpg" for t in ["5.1", "5.4", "5.75", "6.05"]]
        if not args.review_originals and all(path.is_file() for path in palm):
            results["palm-clip"] = evaluate(driver, models, palm, .5)
        driver.stdin.write(bytes(4))
        driver.stdin.close()
        if driver.wait():
            raise RuntimeError("Desktop planner replay failed")
    report = dict(method="Three diagnostic photos use a sliding window over seven synthetic variations after the original. Other photos compare the third observation (original, centered crop, left crop). Both policies see identical current pixels. Links use desktop optical flow. Four palm clip excerpts are evaluated separately. No phone timings or preference votes.",
                  scenes=len(changed), evaluatedVariations=evaluated, changedVariantsByScene=changed, examples=results)
    if args.review_originals:
        report = dict(method="Each window ends on the verified original photo after centered and left-shifted observations. Before uses the single-frame policy; after can use verified outlines from the earlier views.", scenes=review)
    args.output.write_text(json.dumps(report, indent=2)+"\n", encoding="utf-8")


if __name__ == "__main__":
    main()
