"""Evaluate a frozen shot-ideas policy; never infer human preference from proposal count."""
import argparse
import hashlib
import json
import time
import numpy as np
from PIL import Image, ImageDraw, ImageOps
from semantic_shots import SemanticScorer, POLICY, run
from evaluate import OUT, verify
from gaic import GaicScorer
from fetch_assets import ROOT


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--split", choices=["development", "evaluation"], required=True)
    args = parser.parse_args()
    records = json.loads((ROOT/"research/benchmark.json").read_text())["scenes"]
    scenes = [s for s in records if s["split"] == args.split]
    gaic, semantic = GaicScorer(), SemanticScorer()
    results = []
    for scene in scenes:
        report = run(verify(scene), gaic, semantic, "shot-discovery/"+scene["id"])
        results.append({"sceneId": scene["id"], "split": args.split, "imageSha256": scene["sha256"],
            "gaicMs": report["gaicMs"], "semanticMs": report["semanticMs"],
            "selected": report["selected"], "semanticCount": report["semanticCount"]})
    for page in range((len(results)+9)//10):
        sheet = Image.new("RGB", (1800, 1000), "white"); draw = ImageDraw.Draw(sheet)
        for position, row in enumerate(results[page*10:(page+1)*10]):
            scene = next(s for s in scenes if s["id"] == row["sceneId"])
            pair = [verify(scene)]
            if row["selected"]:
                pair.append(Image.open(OUT/"shot-discovery"/row["sceneId"]/"idea-1.jpg"))
            x,y = position%5*360,position//5*500
            for j, im in enumerate(pair):
                thumb = ImageOps.contain(im, (172,440)); sheet.paste(thumb,(x+j*180,y+30))
            draw.text((x,y+5),row["sceneId"],fill="black")
            draw.text((x,y+470), row["selected"][0]["subject"] if row["selected"] else "ABSTAIN", fill="black")
        sheet.save(OUT/f"shot-discovery-{args.split}-{page+1}.jpg", quality=92)
    totals = [r["gaicMs"]+r["semanticMs"] for r in results]
    report = {"policy": POLICY, "policySha256": hashlib.sha256(json.dumps(POLICY, sort_keys=True).encode()).hexdigest(),
        "split": args.split, "sceneCount": len(results), "proposalScenes": sum(bool(r["selected"]) for r in results),
        "desktopMedianMs": float(np.median(totals)), "desktopP95Ms": float(np.percentile(totals,95)),
        "timing": "two Windows CPU threads; warm models; image preparation and inference included; phone/loading pending",
        "humanPreference": "pending", "alreadyGoodPreservation": "unimplemented: this policy proposes details only",
        "releaseApproved": False, "results": results}
    target = ROOT/f"research/results/shot-discovery-{args.split}.json"
    target.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({k:v for k,v in report.items() if k != "results"}, indent=2), flush=True)


if __name__ == "__main__":
    main()
