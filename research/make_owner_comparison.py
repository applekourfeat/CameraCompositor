"""Show the actual pipeline's chosen ideas alongside the extracted original frame."""
import json
from PIL import Image, ImageDraw, ImageOps
from evaluate import OUT, crop


def main():
    image=Image.open(".local/composition-research/owner-scenes/window-city.png").convert("RGB")
    report=json.loads((OUT/"owner-city-semantic/semantic.json").read_text())
    selected=report["selected"]
    views=[image]+[crop(image,r["box"]) for r in selected]
    labels=["Original camera view"]+[f"Idea {i+1}: {r['subject']} ({1/(r['box'][2]-r['box'][0]):.1f}x relative zoom)" for i,r in enumerate(selected)]
    sheet=Image.new("RGB",(360*len(views),520),"white"); draw=ImageDraw.Draw(sheet)
    for i,(im,label) in enumerate(zip(views,labels)):
        thumb=ImageOps.contain(im,(342,456)); sheet.paste(thumb,(i*360+9,12))
        draw.text((i*360+9,482),label,fill="black")
    sheet.save(OUT/"owner-city-comparison.jpg",quality=94)
    print(OUT/"owner-city-comparison.jpg")


if __name__ == "__main__": main()
