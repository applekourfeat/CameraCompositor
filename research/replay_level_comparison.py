"""Compare two compiled planners against the same on-device model outputs and existing crop ratings."""
import argparse
import io
import json
import subprocess
from contextlib import ExitStack
from pathlib import Path
from types import SimpleNamespace

import numpy as np
from evaluate import verify
from replay_context_shots import ROOT, MODEL, ASSETS, gaic_scores, gaic_session, pixels, session, write_inputs


def decision(driver, evidence, image, ranker):
    driver.stdin.write(evidence)
    driver.stdin.flush()
    count = int(driver.stdout.readline())
    candidates = [driver.stdout.readline().decode().split() for _ in range(count)]
    boxes = [list(map(float, row[:4])) for row in candidates]
    scores = gaic_scores(ranker, image, boxes)
    driver.stdin.write(scores.astype(">f4").tobytes())
    driver.stdin.flush()
    index = int(driver.stdout.readline().split()[0])
    box = boxes[index]
    return dict(box=box, subject=candidates[index][4], relativeZoom=round(1 / (box[2] - box[0]), 3))


def rating(box, annotations):
    lower = np.maximum(box[:2], annotations[:, :2])
    upper = np.minimum(box[2:], annotations[:, 2:4])
    shared = np.maximum(0, upper - lower).prod(axis=1)
    area = (box[2] - box[0]) * (box[3] - box[1])
    union = area + (annotations[:, 2:4] - annotations[:, :2]).prod(axis=1) - shared
    iou = shared / union
    index = int(iou.argmax())
    return dict(overlap=round(float(iou[index]), 4), mos=float(annotations[index, 4]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--baseline-driver", required=True)
    parser.add_argument("--baseline-classpath", required=True)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    detectors = session(MODEL), session(ASSETS / "scene-regions-192.onnx")
    ranker = gaic_session()
    rows = []
    with ExitStack() as stack:
        drivers = [stack.enter_context(subprocess.Popen([args.java, "--class-path", cp, str(source), "--adaptive"],
                   stdin=subprocess.PIPE, stdout=subprocess.PIPE)) for cp, source in [
                   (args.baseline_classpath, args.baseline_driver),
                   (args.classpath, ROOT / "research/ReplayContextShots.java")]]
        try:
            manifest = json.loads((ROOT / "research/benchmark.json").read_text())["scenes"]
            for scene in (s for s in manifest if s["split"] == "evaluation"):
                path = ROOT / scene["path"]
                image = verify(scene)
                patches = detectors[1].run(None, {"image": pixels(path)})[0]
                buffer = io.BytesIO()
                writer = SimpleNamespace(stdin=buffer)
                write_inputs(writer, {"path": path}, image, detectors[0], tap=(1, 0), suffix=patches.astype(">f4").tobytes())
                before, after = [decision(driver, buffer.getvalue(), image, ranker) for driver in drivers]
                raw = np.loadtxt(ROOT / scene["annotations"])
                ann = raw[(raw[:, 4] >= 1) & (raw[:, 4] <= 5)][:, [1, 0, 3, 2, 4]]
                ann[:, :4] /= [image.width, image.height, image.width, image.height]
                for item in (before, after):
                    item.update(rating(item["box"], ann))
                rows.append(dict(scene=scene["id"], before=before, after=after))
                print(scene["id"], flush=True)
        finally:
            for driver in drivers:
                if driver.poll() is None:
                    driver.stdin.write(bytes(4))
                    driver.stdin.close()
        if any(driver.wait() for driver in drivers):
            raise RuntimeError("Replay failed")
    changed = [row for row in rows if not np.allclose(row["before"]["box"], row["after"]["box"], atol=.001)]
    matched = [r for r in changed if min(r[k]["overlap"] for k in ("before", "after")) >= .85]
    delta = [r["after"]["mos"] - r["before"]["mos"] for r in matched]
    summary = dict(scenes=len(rows), changed=len(changed), matchedChanges=len(delta), higher=sum(d > 0 for d in delta),
                   equal=sum(d == 0 for d in delta), lower=sum(d < 0 for d in delta))
    args.output.write_text(json.dumps(dict(scope="Still frames. Nearest annotated crop is a proxy for the suggestion.",
                                           minimumPairedIoU=.85, summary=summary, scenes=rows), indent=2) + "\n")
    print(summary)


if __name__ == "__main__":
    main()
