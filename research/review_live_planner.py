"""Build and score blind still-image comparisons for the 0.12 Vivo planner."""
import argparse
import hashlib
import json
import random
from collections import Counter
from pathlib import Path

from PIL import ImageOps
from build_review import PAGE
from evaluate import ROOT, OUT, verify


DIRECTORY = OUT / "v012-review"
FULL = (0, 0, 1, 1)
BASELINES = {"original": "starting view", "previous": "0.11", "thirds": "historical Thirds"}


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def bounds(box, size):
    if len(box) != 4 or not all(0 <= v <= 1 for v in box):
        raise ValueError(f"Invalid crop coordinates {box}")
    pixels = tuple(round(v * size[i % 2]) for i, v in enumerate(box))
    if pixels[0] >= pixels[2] or pixels[1] >= pixels[3]:
        raise ValueError(f"Empty crop {box}")
    return pixels


def build():
    manifest = read(ROOT / "research/benchmark.json")
    scenes = [s for s in manifest["scenes"] if s["split"] == "evaluation"]
    level_rows = read(ROOT / "research/results/level-heldout-replay.json")["scenes"]
    style_rows = read(ROOT / ".local/composition-style-replays.json")["evaluation"]
    levels = {r["scene"]: r for r in level_rows}
    styles = {r["id"]: r for r in style_rows}
    ids = {s["id"] for s in scenes}
    if not all(len(rows) == 60 for rows in (scenes, level_rows, style_rows, ids)):
        raise ValueError("Expected exactly 60 unique scenes per replay.")
    if ids != levels.keys() or ids != styles.keys():
        raise ValueError("Replay inputs must cover the same 60 evaluation scenes.")
    images = DIRECTORY / "images"
    images.mkdir(parents=True, exist_ok=True)
    starts, keys, trials = [], [], []
    for scene in scenes:
        sid = scene["id"]
        image = verify(scene)
        files = {}

        def save(rect):
            if rect not in files:
                filename = digest(f"{scene['sha256']}/{rect}".encode())[:24] + ".jpg"
                sample = ImageOps.contain(image.crop(rect), (720, 720))
                sample.save(images / filename, quality=92)
                files[rect] = "images/" + filename
            return files[rect]

        original = bounds(FULL, image.size)
        selected = bounds(levels[sid]["after"]["box"], image.size)
        references = {"original": original, "previous": bounds(levels[sid]["before"]["box"], image.size),
                      "thirds": bounds(styles[sid]["modes"]["thirds"]["crop"], image.size)}
        framed = save(selected)
        starts.append(dict(id=sid, split="evaluation", image=save(original)))
        for kind, ref in references.items():
            tid = digest(f"v012/{sid}/{kind}/{selected}/{ref}".encode())[:20]
            entry = dict(id=tid, sceneId=sid, type=kind, identical=selected == ref, kept=selected == original)
            keys.append(entry)
            if selected != ref:
                trials.append(dict(id=tid, type=kind, split="evaluation", a=framed, b=save(ref)))
    by_id = {item["id"]: item for item in keys}
    rng = random.Random(120604)
    rng.shuffle(trials)
    tasks = [["labels", "Label starting views"]]
    for kind, name in BASELINES.items():
        pairs = [pair for pair in trials if pair["type"] == kind]
        if pairs:
            tasks.append([kind, f"Compare with {name}"])
        sides = ["left", "right"] * (len(pairs) // 2)
        if len(pairs) % 2:
            sides.append(rng.choice(["left", "right"]))
        rng.shuffle(sides)
        for pair, side in zip(pairs, sides):
            by_id[pair["id"]]["candidateSide"] = side
            if side == "right":
                pair["a"], pair["b"] = pair["b"], pair["a"]
    identity = digest(json.dumps([keys, starts], sort_keys=True).encode())
    rng.shuffle(starts)
    data = dict(manifestHash=identity, trials=trials, starts=starts, tasks=tasks,
                scope="Start by labeling the original views. In each comparison, pick the framing you prefer. Identical frame pairs count as ties automatically.")
    (DIRECTORY / "review.html").write_text(PAGE.replace("__DATA__", json.dumps(data)), encoding="utf-8")
    (DIRECTORY / "key.json").write_text(json.dumps(dict(manifestHash=identity, trials=keys)), encoding="utf-8")
    print(f"{len(starts)} starting views. Distinct pairs: {dict(Counter(t['type'] for t in trials))}")
    print(DIRECTORY / "review.html")


def summarize(answers, key):
    if answers.get("manifestHash") != key["manifestHash"]:
        raise ValueError("Answer file belongs to a different review.")
    result = {}
    for kind in BASELINES:
        counts = Counter()
        for t in key["trials"]:
            if t["type"] != kind:
                continue
            label = answers.get("startingViews", {}).get(t["sceneId"], {}).get("answer")
            if label == "good":
                counts["alreadyGood"] += 1
                counts["alreadyGoodKept"] += t["kept"]
                continue
            if label != "needs_work":
                counts["uncertainLabel" if label == "unsure" else "missingLabel"] += 1
                continue
            counts["improvable"] += 1
            vote = answers.get("answers", {}).get(t["id"], {}).get("answer")
            if t["identical"] or vote == "tie":
                counts["ties"] += 1
            elif vote in ("left", "right"):
                counts["wins" if vote == t["candidateSide"] else "losses"] += 1
            elif vote == "unsure":
                counts["unsure"] += 1
            else:
                counts["missingVote"] += 1
        complete = not any(counts[name] for name in ("missingVote", "missingLabel", "uncertainLabel"))
        rate = counts["wins"] / counts["improvable"] if complete and counts["improvable"] else None
        result[kind] = dict(counts=counts, winFraction=rate)
        if kind == "original":
            result[kind]["preferenceGate"] = ("pending" if rate is None or counts["improvable"] < 20
                                               else "pass" if rate >= .70 else "fail")
        if kind == "thirds":
            result[kind]["beatsThirds"] = None if rate is None else counts["wins"] > counts["losses"]
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--answers", type=Path)
    args = parser.parse_args()
    if args.answers:
        print(json.dumps(summarize(read(args.answers), read(DIRECTORY / "key.json")), indent=2))
    else:
        build()
