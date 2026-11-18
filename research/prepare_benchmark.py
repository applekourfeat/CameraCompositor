"""Freeze 20 validation + 60 test photos from the authors' GAICv2 splits.

No photos are redistributed. Human scene/keep labels remain unset until reviewed.
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageOps
from fetch_assets import ROOT, CACHE

OUT = ROOT / "artifacts" / "composition-research"
MANIFEST = ROOT / "research" / "benchmark.json"
SEED = "CameraCompositor-2026-11-18-v1"


def main():
    records, seen = [], set()
    for source_split, split, count in [("val", "development", 20), ("test", "evaluation", 60)]:
        folder = CACHE / "gaic-dataset" / "GAIC" / "images" / source_split
        paths = sorted(folder.glob("*.jpg"), key=lambda p: hashlib.sha256((SEED + p.name).encode()).hexdigest())
        selected = []
        for path in paths:
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if digest in seen:
                continue
            seen.add(digest)
            with Image.open(path) as image:
                selected.append({"id": f"gaic-{source_split}-{path.stem}", "split": split,
                    "sourceSplit": source_split, "path": path.relative_to(ROOT).as_posix(), "sha256": digest,
                    "width": image.width, "height": image.height, "category": "unreviewed", "alreadyGood": None,
                    "annotations": (CACHE / "gaic-dataset" / "GAIC" / "annotations" / source_split / f"{path.stem}.txt").relative_to(ROOT).as_posix()})
            if len(selected) == count:
                break
        if len(selected) != count:
            raise ValueError(f"Insufficient {split} photos")
        records += selected
    manifest = {"schema": 1, "seed": SEED, "dataset": "GAICv2 author download",
        "datasetArchiveSha256": "b895a3f9e03c8f70c37194370441dc2f17bb60e0ab437447ee240b003cd6550b",
        "limitations": ["Photo redistribution rights are unverified; images remain local.",
            "Splits exclude calibration overlap, not possible overlap with NIMA's AVA training photos.",
            "Static crops cannot validate discovery across physical camera viewpoints.",
            "Human category and already-good labels require review."], "scenes": records}
    MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    OUT.mkdir(parents=True, exist_ok=True)
    for page in range(4):
        sheet = Image.new("RGB", (1000, 1000), "white")
        draw = ImageDraw.Draw(sheet)
        for index, record in enumerate(records[page*20:(page+1)*20]):
            image = Image.open(ROOT / record["path"]).convert("RGB")
            thumb = ImageOps.contain(image, (240, 165))
            x, y = (index % 4)*250, (index // 4)*200
            sheet.paste(thumb, (x+(240-thumb.width)//2, y))
            draw.text((x+4, y+168), f"{page*20+index+1}. {record['id']}", fill="black")
        sheet.save(OUT / f"contact-{page+1}.jpg", quality=90)
    print(f"Frozen {len(records)} unique photos. Contact sheets: {OUT}")


if __name__ == "__main__":
    main()
