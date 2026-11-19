"""Export TinyCLIP patch similarities with a pinned image encoder and prompt vocabulary."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.request

os.environ["USE_TF"] = "0"
os.environ["USE_FLAX"] = "0"

import numpy as np
import onnxruntime as ort
from PIL import Image
import torch
from torch.nn.functional import normalize
from transformers import CLIPModel, CLIPTokenizerFast

from export_software_depth import compact_weights, session

ROOT = Path(__file__).resolve().parents[1]
REVISION = "a2a8c6eaa2549ad66eb7c31b85022bf58273a26c"
UPSTREAM = f"https://huggingface.co/wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M/resolve/{REVISION}"
SOURCE = ROOT / ".local/composition-research/tinyclip-8m"
ASSETS = ROOT / ".local/scene-semantics-assets"
SIZE = 192
FILES = {
    "config.json": "c586d856dbd5589b67525db9e791ee0917402405672c7773aead373efb7d7bca",
    "tokenizer.json": "b556ac8c99757ffb677208af34bc8c6721572114111a6e0aaf5fa69ff0b8d842",
    "model.safetensors": "9339ee3d736344d0ddcaa6c03edc9f89688f08caaea5401220885233da726fcc",
}
# Column order is part of the app's SceneRegions input contract.
PROMPTS = [
    ("palm tree fronds", "a palm tree crown in sunlight"), ("a tree canopy and branches", "a tree with leaves"),
    ("flowers", "a small green plant"), ("a city building facade", "modern architecture and windows"),
    ("a bridge", "bridge towers and cables"), ("mountains", "a mountain ridge"), ("the sea", "a river or lake"),
    ("a person", "a portrait of someone"), ("an animal", "a pet"), ("furniture in a room", "objects in a room"),
    ("pavement", "a road or sidewalk"), ("a blank wall", "an indoor ceiling"), ("empty blue sky", "a patch of sky with clouds"),
    ("a railing", "a balcony railing"), ("ground or flooring", "a close view of the floor"),
]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_files():
    SOURCE.mkdir(parents=True, exist_ok=True)
    for name, expected in FILES.items():
        path = SOURCE / name
        if not path.exists():
            with urllib.request.urlopen(f"{UPSTREAM}/{name}?download=true", timeout=60) as response:
                data = response.read(94_000_001)
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError(f"Unexpected TinyCLIP download: {name}")
            path.write_bytes(data)
        if sha(path) != expected:
            raise ValueError(f"Unexpected TinyCLIP input: {name}")


class PatchScores(torch.nn.Module):
    def __init__(self, model, concepts):
        super().__init__()
        self.vision = model.vision_model
        self.projection = model.visual_projection
        self.register_buffer("concepts", concepts)
        # The app uses a fixed input size, so bake the position interpolation.
        count = (SIZE // 16) ** 2
        positions = self.vision.embeddings.interpolate_pos_encoding(
            torch.zeros(1, count + 1, self.vision.config.hidden_size), SIZE, SIZE
        )
        self.register_buffer("positions", positions)

    def forward(self, image):
        embeddings = self.vision.embeddings
        patches = embeddings.patch_embedding(image).flatten(2).transpose(1, 2)
        hidden = self.vision.pre_layrnorm(torch.cat((embeddings.class_embedding[None, None, :], patches), dim=1) + self.positions)
        for layer in self.vision.encoder.layers[:-1]:
            hidden = layer(hidden, None, None)[0]
        last = self.vision.encoder.layers[-1]
        attention = last.self_attn
        # MaskCLIP's value and output projection decode patch evidence without final spatial averaging.
        pixels = attention.out_proj(attention.v_proj(last.layer_norm1(hidden)))[:, 1:]
        return normalize(self.projection(self.vision.post_layernorm(pixels)), dim=-1) @ self.concepts.T


def pixels(path):
    image = Image.new("RGB", (SIZE, SIZE), "#a4a4a4") if path is None else Image.open(path)
    with image:
        rgb = np.asarray(image.convert("RGB").resize((SIZE, SIZE), Image.Resampling.BILINEAR), dtype=np.float32) / 255
    rgb -= np.array([.48145466, .4578275, .40821073], dtype=np.float32)
    rgb /= np.array([.26862954, .26130258, .27577711], dtype=np.float32)
    return np.ascontiguousarray(rgb.transpose(2, 0, 1)[None])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("images", nargs="*", type=Path, help="Optional local images for score and latency checks")
    args = parser.parse_args()
    source_files()
    ASSETS.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(2)
    ort.disable_telemetry_events()
    model = CLIPModel.from_pretrained(SOURCE, local_files_only=True, use_safetensors=True).eval()
    model.set_attn_implementation("eager")
    tokenizer = CLIPTokenizerFast(tokenizer_file=str(SOURCE / "tokenizer.json"), model_max_length=77)
    prompts = [f"a photo of {prompt}." for pair in PROMPTS for prompt in pair]
    with torch.no_grad():
        text = model.get_text_features(**tokenizer(prompts, padding=True, return_tensors="pt"))
        concepts = normalize(normalize(text, dim=-1).reshape(len(PROMPTS), 2, -1).mean(dim=1), dim=-1)
        scorer = PatchScores(model, concepts).eval()
        sample = torch.from_numpy(pixels(args.images[0] if args.images else None))
        expected = scorer(sample).numpy()
        full = SOURCE / "scene-regions-fp32.onnx"
        compact = ASSETS / "scene-regions-192.onnx"
        torch.onnx.export(scorer, sample, str(full), input_names=["image"], output_names=["scores"],
                          opset_version=17, dynamo=False)
    compact_weights(full, compact)
    regular, packed = session(full), session(compact)
    np.testing.assert_allclose(regular.run(None, {"image": sample.numpy()})[0], expected, atol=1e-5)
    results = []
    for path in [None, *args.images]:
        source = {"image": pixels(path)}
        regular_scores = regular.run(None, source)[0]
        packed_scores = packed.run(None, source)[0]
        assert packed_scores.shape == (1, 144, len(PROMPTS)) and np.isfinite(packed_scores).all()
        np.testing.assert_allclose(regular_scores, packed_scores, atol=.002)
        measured = []
        for _ in range(7):
            start = time.perf_counter()
            packed.run(None, source)
            measured.append((time.perf_counter() - start) * 1000)
        results.append({"input": path.name if path else "flat control", "maxDelta": float(np.max(np.abs(regular_scores - packed_scores))),
                        "promptAgreement": float(np.mean(regular_scores.argmax(-1) == packed_scores.argmax(-1))),
                        "warmMedianMs": round(float(np.median(measured)), 2)})
    print(json.dumps({"revision": REVISION, "sha256": sha(compact), "bytes": compact.stat().st_size,
                      "desktopModelOnly": results}, indent=2))


if __name__ == "__main__":
    main()
