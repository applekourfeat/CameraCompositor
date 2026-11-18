"""Local semantic crop experiment. Similarity is subject evidence, not beauty/confidence.

Fixed general vocabulary, including negative controls, is shared across all scenes.
All photo processing is offline. This file is a research instrument, not an Android claim.
"""
import os
os.environ["USE_TF"] = "0"
os.environ["USE_FLAX"] = "0"
os.environ["HF_HUB_OFFLINE"] = "1"
import argparse
import hashlib
import json
import time
from pathlib import Path
import numpy as np
from PIL import Image
import torch
from transformers import CLIPModel, CLIPTokenizerFast, CLIPImageProcessor
from evaluate import OUT, crop
from gaic import GaicScorer
from owner_city_experiment import expanded_boxes, diverse, contact_sheet

SUBJECTS = [
    "a person", "a group of people", "a dog", "a cat", "a bird", "a wild animal", "an insect",
    "a flower", "a tree", "a forest", "a mountain", "the sea", "a lake", "a waterfall", "a sunset",
    "a city skyline", "a tall building", "a bridge", "a street", "an old building", "a modern building",
    "a car", "a train", "a bicycle", "a boat", "food", "a drink", "a book", "a small object",
]
NEGATIVES = ["empty sky", "a blank wall", "a blurry scene", "an obstructing railing", "a curtain", "a reflection in a window"]

# Freeze before the evaluation split. These are research thresholds, not calibrated probabilities.
POLICY = {"revision": "distinct-shot-ideas-v1", "minNegativeMargin": .025, "minGaic": 2.5,
          "focusZooms": [1.8, 2.2], "contextZooms": [1.25, 1.5], "detailZooms": [2.6, 3.2],
          "maxIdeas": 3, "maxIoU": .6}


def overlap(a, b):
    intersection = max(0, min(a[2],b[2])-max(a[0],b[0])) * max(0, min(a[3],b[3])-max(a[1],b[1]))
    return intersection / ((a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - intersection)


def choose_ideas(rows):
    """Find possible details, not a replacement for an already-good-view assessment.

    A focus bank and per-subject diversity prevent a high wide-photo score from vetoing
    every detail. The original remains a separate user choice. This is an explicit
    search intent, not an assertion that zooming or a high CLIP score improves beauty.
    """
    def valid_box(box):
        return len(box) == 4 and all(np.isfinite(box)) and 0 <= box[0] < box[2] <= 1 and 0 <= box[1] < box[3] <= 1 and abs((box[2]-box[0])-(box[3]-box[1])) < .001
    eligible = [r for r in rows if r["index"] != 0 and valid_box(r["box"])
                and r["negativeMargin"] > POLICY["minNegativeMargin"] and r["gaic"] > POLICY["minGaic"]
                and all(np.isfinite([r["gaic"], r["negativeMargin"], r["similarity"]]))]
    selected = []
    for bank in ["focusZooms", "contextZooms", "detailZooms"]:
        options = [r for r in eligible if any(abs(1/(r["box"][2]-r["box"][0])-z) < .001 for z in POLICY[bank])]
        options.sort(key=lambda r: (-r["gaic"], -r["negativeMargin"], r["index"]))
        for row in options:
            if all(row["subject"] != other["subject"] and overlap(row["box"], other["box"]) < POLICY["maxIoU"] for other in selected):
                selected.append(row)
            if len(selected) == POLICY["maxIdeas"]:
                return selected
    return selected


class SemanticScorer:
    def __init__(self):
        torch.set_num_threads(2)
        path = Path(".local/composition-research/tinyclip")
        self.model = CLIPModel.from_pretrained(path, local_files_only=True, use_safetensors=True).eval()
        self.tokenizer = CLIPTokenizerFast(tokenizer_file=str(path/"tokenizer.json"),
            bos_token="<|startoftext|>", eos_token="<|endoftext|>", pad_token="<|endoftext|>", model_max_length=77)
        self.processor = CLIPImageProcessor.from_pretrained(path, local_files_only=True)
        labels = SUBJECTS + NEGATIVES
        # Two general prompts per class, averaged after individual L2 normalization.
        prompts = [p for label in labels for p in [f"a photo of {label}.", f"a photograph featuring {label}."]]
        with torch.inference_mode():
            features = self.model.get_text_features(**self.tokenizer(prompts, padding=True, return_tensors="pt"))
            features = torch.nn.functional.normalize(features, dim=-1).reshape(len(labels), 2, -1).mean(1)
            self.text = torch.nn.functional.normalize(features, dim=-1)

    def score(self, images):
        values = []
        with torch.inference_mode():
            for start in range(0, len(images), 8):
                # Preserve the whole candidate: an explicit resize rather than CLIP center cropping.
                # Stretching is experimental and recorded so parity and phone preprocessing can match.
                batch = self.processor(images=[im.resize((224,224), Image.Resampling.BICUBIC) for im in images[start:start+8]],
                    do_resize=False, do_center_crop=False, return_tensors="pt")
                features = torch.nn.functional.normalize(self.model.get_image_features(**batch), dim=-1)
                values.extend((features @ self.text.T).numpy())
        return np.asarray(values)


def shortlist(scores, boxes, count=36):
    # Retain spatially different options at each zoom, rather than letting wide scores erase details.
    selected = [0]
    for band in range(6):
        indices = list(range(1+49*band, 1+49*(band+1)))
        local = diverse(np.asarray(scores)[[0]+indices], [boxes[i] for i in [0]+indices], count//6)
        selected.extend(indices[i-1] for i in local)
    return selected


def run(image, gaic, semantic, name):
    boxes = expanded_boxes()
    start = time.perf_counter()
    scores = gaic.score(image, boxes)
    indices = shortlist(scores, boxes)
    gaic_ms = (time.perf_counter()-start)*1000
    start = time.perf_counter()
    similarities = semantic.score([crop(image, boxes[i]) for i in indices])
    semantic_ms = (time.perf_counter()-start)*1000
    rows = []
    for index, similarity in zip(indices, similarities):
        subject = int(np.argmax(similarity[:len(SUBJECTS)]))
        margin = float(similarity[subject]-max(similarity[len(SUBJECTS):]))
        rows.append({"index": index, "box": boxes[index], "gaic": float(scores[index]),
            "subject": SUBJECTS[subject], "similarity": float(similarity[subject]), "negativeMargin": margin,
            "top": [( (SUBJECTS+NEGATIVES)[j], float(similarity[j])) for j in np.argsort(similarity)[-4:][::-1]]})
    selected = choose_ideas(rows)
    output = OUT/name; output.mkdir(exist_ok=True, parents=True)
    for position, row in enumerate(selected):
        crop(image, row["box"]).save(output/f"idea-{position+1}.jpg", quality=94)
    contact_sheet(image, boxes, [r["index"] for r in selected],
        [f"{r['subject']}; GAIC {r['gaic']:.2f}" for r in selected], output/"ideas.jpg")
    contact_sheet(image, boxes, indices[:6], [f"shortlist {i}" for i in indices[:6]], output/"shortlist.jpg")
    report = {"policy": POLICY, "photoPixelSha256": hashlib.sha256(image.tobytes()).hexdigest(),
        "gaicMs": gaic_ms, "semanticMs": semantic_ms, "candidateCount": len(boxes), "semanticCount": len(indices),
        "selected": selected, "all": rows, "status": "diagnostic, not human preference or phone performance evidence"}
    (output/"semantic.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"scene": name, **{k:v for k,v in report.items() if k != "all"}}, indent=2), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--image", default=".local/composition-research/owner-scenes/window-city.png")
    parser.add_argument("--name", default="owner-city-semantic"); args = parser.parse_args()
    run(Image.open(args.image).convert("RGB"), GaicScorer(), SemanticScorer(), args.name)


if __name__ == "__main__":
    main()
