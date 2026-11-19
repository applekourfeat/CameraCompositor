"""Compare subject and rotation proposals from the packaged Android planner on desktop still images."""
import argparse
import json
from pathlib import Path
import subprocess
import time

import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image

from evaluate import verify
from evaluate_composition_styles import MODEL, write_inputs
from export_scene_semantics import ASSETS, REVISION, ROOT, pixels, session, sha


def replay(process, record, image, semantic_model, detector, allow_exploration=True):
    prepared = pixels(record["path"])
    tick = time.perf_counter()
    scores = semantic_model.run(None, {"image": prepared})[0]
    model_ms = (time.perf_counter() - tick) * 1000
    write_inputs(process, record, image, detector, suffix=scores.astype(">f4").tobytes() + bytes([allow_exploration]))
    result = process.stdout.readline().decode("utf-8").strip().split("\t")
    if len(result) != 8:
        raise ValueError("Unexpected discovery replay output")
    previous, decision, frame, kind, bounds, strength, confidence, shutter = result
    return {"id": record["id"], "previous": previous, "decision": decision,
            "frame": [round(float(v), 4) for v in frame.split(",")], "region": None if kind == "-" else kind,
            "bounds": None if bounds == "-" else [round(float(v), 4) for v in bounds.split(",")],
            "strength": round(float(strength), 4), "detectorConfidence": round(float(confidence), 4),
            "autoEligible": shutter == "true", "patchModelMs": round(model_ms, 2)}


def summarize(rows):
    return {"scenes": len(rows), "previous": counts(rows, "previous"), "discovery": counts(rows, "decision"),
            "rotationProposalsOutsideFirstFrame": sum(row["decision"] == "Explore" and
                (min(row["frame"]) < 0 or max(row["frame"]) > 1) for row in rows),
            "shutterEligibleFromObservedView": sum(row["autoEligible"] for row in rows),
            "desktopMedianPatchModelMs": round(float(np.median([row["patchModelMs"] for row in rows])), 2)}


def counts(rows, key):
    return {name: sum(row[key] == name for row in rows) for name in ("Guide", "Keep", "ObserveMore", "Explore")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--split", choices=("development", "evaluation", "all", "none"), default="all")
    parser.add_argument("--reference", action="append", type=Path, default=[])
    parser.add_argument("--after-turn", action="append", type=Path, default=[],
                        help="Use the subject proposed by the last --reference image in this follow-up view")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.after_turn and not args.reference:
        parser.error("--after-turn requires a starting --reference")
    cv2.setNumThreads(1)
    ort.disable_telemetry_events()
    packed = ASSETS / "scene-regions-192.onnx"
    semantic_model, detector = session(packed), session(MODEL)
    run = [args.java, "--class-path", args.classpath, str(ROOT / "research/ReplayCompositionStyles.java"), "--discovery"]
    rows = {"development": [], "evaluation": [], "reference": [], "afterTurn": []}
    records = json.loads((ROOT / "research/benchmark.json").read_text(encoding="utf-8"))["scenes"]
    with subprocess.Popen(run, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE) as process:
        for record in records:
            if args.split in ("all", record["split"]):
                rows[record["split"]].append(replay(process, record, verify(record), semantic_model, detector))
        for group, paths in (("reference", args.reference), ("afterTurn", args.after_turn)):
            for index, path in enumerate(paths, 1):
                with Image.open(path) as image:
                    rows[group].append(replay(process, {"id": f"{group}-{index}", "path": path}, image.convert("RGB"),
                                              semantic_model, detector, allow_exploration=(group == "reference")))
        process.stdin.write(b"\0\0\0\0")
        process.stdin.close()
        if process.wait() != 0:
            raise ValueError("The desktop driver did not finish")
    result = {"revision": REVISION, "patchModelSha256": sha(packed),
              "scope": "Static desktop replay. Subject hypotheses and proposed turns only. No tracking or human preference metrics.",
              **{name: summarize(rows[name]) for name in ("development", "evaluation") if rows[name]},
              "references": rows["reference"], "afterTurn": rows["afterTurn"]}
    formatted = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(formatted, encoding="utf-8")
    print(formatted, end="")


if __name__ == "__main__":
    main()
