"""Visual checks from the first six frozen evaluation scenes, not cherry-picked successes."""
import json
from PIL import Image, ImageDraw, ImageOps
from evaluate import OUT, verify
from fetch_assets import ROOT


def main():
    scenes = json.loads((ROOT / "research" / "benchmark.json").read_text())["scenes"]
    scenes = [s for s in scenes if s["split"] == "evaluation"][:6]
    reports = {m: json.loads((OUT / f"{m}-results.json").read_text()) for m in ["nima", "gaic"]}
    for page in range(2):
        sheet = Image.new("RGB", (1050, 1020), "white")
        draw = ImageDraw.Draw(sheet)
        for row, scene in enumerate(scenes[page*3:(page+1)*3]):
            images = [verify(scene)] + [Image.open(OUT/"images"/reports[m]["model"]/f"{scene['id']}.jpg") for m in ["nima", "gaic"]]
            draw.text((8, row*340+8), scene["id"], fill="black")
            for col, (label, image) in enumerate(zip(["Starting view", "NIMA candidate", "GAIC candidate"], images)):
                thumb = ImageOps.contain(image, (330, 280))
                sheet.paste(thumb, (col*350+(330-thumb.width)//2, row*340+35))
                draw.text((col*350+8, row*340+315), label, fill="black")
        sheet.save(OUT/f"examples-{page+1}.jpg", quality=92)


if __name__ == "__main__":
    main()
