"""Fetch a pinned, MIT-labelled TinyCLIP checkpoint for local scene experiments."""
from pathlib import Path
import hashlib
import json
import urllib.request
from fetch_assets import CACHE

REPOSITORY = "wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M"
REVISION = "95ec8197b3f2fe7f747865c61ca556cf0768b2f7"
FILES = {
    "model.safetensors": ("sha256", "1b08131ac0ac54429aab216c397c550527d31f5682afb5d21d35091ad39538ae"),
    "config.json": ("git", "caaceca6279aa5f636bd9f8fb44666662d6982b3"),
    "preprocessor_config.json": ("git", "5a12a1eb250987a4eee0e3e7d7338c4b22724be1"),
    "tokenizer.json": ("git", "564c0ebd5ce29c4ee4864004aee693deadd3128c"),
}
TARGET = CACHE / "tinyclip"


def main():
    TARGET.mkdir(exist_ok=True)
    records = []
    for name, (kind, expected) in FILES.items():
        target = TARGET / name
        if not target.exists():
            temporary = target.with_suffix(target.suffix+".partial")
            print(f"Downloading {name}", flush=True)
            with urllib.request.urlopen(f"https://huggingface.co/{REPOSITORY}/resolve/{REVISION}/{name}", timeout=45) as source, temporary.open("wb") as output:
                total = 0
                while data := source.read(1024*1024):
                    total += len(data)
                    if total > 400_000_000:
                        raise ValueError("Download exceeded its expected size bound")
                    output.write(data)
            temporary.replace(target)
        raw = target.read_bytes()
        actual = hashlib.sha256(raw).hexdigest() if kind == "sha256" else hashlib.sha1(b"blob "+str(len(raw)).encode()+b"\0"+raw).hexdigest()
        if actual != expected:
            raise ValueError(f"Hash mismatch: {name}")
        records.append({"file": name, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()})
    report = {"repository": REPOSITORY, "revision": REVISION, "licenseMetadata": "mit", "files": records}
    (TARGET/"provenance.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
