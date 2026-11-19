"""Replay the Android planner on the frozen 20/60 image set using the packaged subject detector."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import time

import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image

from evaluate import verify
from fetch_assets import ROOT
from fetch_subject_model import APP_SHA256

STYLES = ("thirds", "centered", "negativeSpace", "wide")
MODEL = ROOT / ".local/subject-assets/nanodet-416.onnx"
SCORES = ("792", "814", "836")
BOXES = ("795", "817", "839")


def letterbox(image):
    width, height = image.size
    scale = 416 / max(width, height)
    width, height = max(1, int(width * scale)), max(1, int(height * scale))
    left, top = (416 - width) // 2, (416 - height) // 2
    rgb = np.zeros((416, 416, 3), np.uint8)
    rgb[top:top + height, left:left + width] = cv2.resize(np.array(image), (width, height), interpolation=cv2.INTER_LINEAR)
    mean = np.array([103.53, 116.28, 123.675], np.float32)
    std = np.array([57.375, 57.12, 58.395], np.float32)
    tensor = ((rgb.astype(np.float32) - mean) / std).transpose(2, 0, 1)[None]
    return np.ascontiguousarray(tensor), width, height


def replay(process, record, image, session, tap=None):
    start = time.perf_counter()
    pixels, width, height = letterbox(image)
    names = [name for pair in zip(SCORES, BOXES) for name in pair]
    output = session.run(names, {"input.1": pixels})
    elapsed = (time.perf_counter() - start) * 1000
    path = Path(record["path"]).as_posix().encode("utf-8")
    process.stdin.write(struct.pack("!I", len(path)) + path + struct.pack("!iiff", width, height, *(tap or (-1, -1))))
    for array in output:
        process.stdin.write(array.astype(">f4").tobytes())
    process.stdin.flush()
    reply = process.stdout.readline().decode("utf-8").strip().split("\t")
    if len(reply) != 3 + 3 * len(STYLES):
        raise ValueError("The desktop composition replay returned invalid data")
    modes = {}
    for index, name in enumerate(STYLES):
        decision, crop, automatic = reply[3 + 3 * index:6 + 3 * index]
        modes[name] = {"decision": decision, "crop": [float(value) for value in crop.split(",")], "auto": automatic == "true"}
    return {"id": record["id"], "modelMs": elapsed, "detected": float(reply[1]), "tap": tap, "modes": modes,
            "subject": [float(value) for value in reply[0].split(",")] if reply[0] != "-" else None,
            "priorCrop": [float(value) for value in reply[2].split(",")]}


def subject_retained(subject, crop):
    return crop[0] <= subject[0] and crop[1] <= subject[1] and crop[2] >= subject[2] and crop[3] >= subject[3]


def summary(rows):
    distinct = 0
    for row in rows:
        plans = {tuple(round(v, 3) for v in mode["crop"]) for mode in row["modes"].values() if mode["decision"] == "Guide"}
        distinct += len(plans) > 1
    by_mode = {}
    for name in STYLES:
        choices = [row["modes"][name] for row in rows]
        recognized_guides = [row for row in rows if row["detected"] >= .54 and row["modes"][name]["decision"] == "Guide"]
        by_mode[name] = {decision: sum(mode["decision"] == decision for mode in choices)
                         for decision in ("Guide", "Keep", "ObserveMore")}
        by_mode[name]["recognizedGuides"] = len(recognized_guides)
        by_mode[name]["wholeRecognizedSubjects"] = sum(subject_retained(row["subject"], row["modes"][name]["crop"])
                                                       for row in recognized_guides)
    return {"photos": len(rows), "distinctGuidesInMultipleStyles": distinct, "modes": by_mode,
            "medianDesktopDetectionMs": float(np.median([row["modelMs"] for row in rows]))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True, help="Path to the JDK java executable")
    parser.add_argument("--classpath", required=True, help="Compiled debug Kotlin classes and Kotlin stdlib")
    parser.add_argument("--reference", type=Path, help="Optional local image outside the frozen set")
    parser.add_argument("--tap", type=float, nargs=2, metavar=("X", "Y"), help="Normalized subject point for the reference")
    args = parser.parse_args()
    if args.tap and (not args.reference or any(value < 0 or value > 1 for value in args.tap)):
        parser.error("Provide a reference image and a subject point from 0 to 1")
    if hashlib.sha256(MODEL.read_bytes()).hexdigest() != APP_SHA256:
        raise ValueError("Unexpected packaged subject model")
    cv2.setNumThreads(1)
    ort.disable_telemetry_events()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.add_session_config_entry("session.intra_op.allow_spinning", "0")
    session = ort.InferenceSession(str(MODEL), options, providers=["CPUExecutionProvider"])
    manifest = json.loads((ROOT / "research/benchmark.json").read_text(encoding="utf-8"))
    rows = {"development": [], "evaluation": []}
    reference = None
    run = [args.java, "--class-path", args.classpath, str(ROOT / "research/ReplayCompositionStyles.java")]
    with subprocess.Popen(run, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE) as process:
        for record in manifest["scenes"]:
            rows[record["split"]].append(replay(process, record, verify(record), session))
        if args.reference:
            record = {"id": "local-reference", "path": args.reference.resolve()}
            with Image.open(record["path"]) as image:
                reference = replay(process, record, image.convert("RGB"), session, args.tap)
        process.stdin.write(b"\0\0\0\0")
        process.stdin.close()
        if process.wait() != 0:
            raise ValueError("The desktop driver did not finish")
    report = {"subjectModelSha256": APP_SHA256, "scope": "Frozen stills and desktop CPU. Measures proposed styles and subject containment. Human preference and live camera behavior remain separate.",
              **{name: summary(records) for name, records in rows.items()}}
    if reference:
        report["localReference"] = reference
    target = ROOT / "research/results/composition-styles-desktop.json"
    target.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (ROOT / ".local/composition-style-replays.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
