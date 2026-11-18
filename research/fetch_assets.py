"""Fetch only pinned public research assets; keep photos/weights outside Git and APKs."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".local" / "composition-research"
REVISIONS = {
    "nima": ("idealo/image-quality-assessment", "dceaf7c2d218bc6e80b21d6e147e3b56a21b7f31"),
    "gaic": ("HuiZeng/Grid-Anchor-based-Image-Cropping-Pytorch", "344e86fb54b4b083d83c405aa63d7fbeeed4fcdc"),
}
EXPECTED = {
    "nima-dceaf7c2d218bc6e80b21d6e147e3b56a21b7f31.zip": "53b4a14209d16a8b93c9c3b4017b4294a923774c1429228ccfe08765e4ba1a59",
    "gaic-344e86fb54b4b083d83c405aa63d7fbeeed4fcdc.zip": "c0070fb1bb461e0c6355e01fce0a2a9547f0555c09a1e5818576699a638e5ede",
    "nima-aesthetic.hdf5": "e563ad91b3d47410e45f7238f07ab8f6abd1bd0c4b18a4b0af9c681a21a91cb2",
    "gaic-models.zip": "b327c170b9738680b164783a0757fac7bd1f3f7eba76fcd71da09b7e605feef0",
    "gaic-dataset.zip": "b895a3f9e03c8f70c37194370441dc2f17bb60e0ab437447ee240b003cd6550b",
}


def digest(path: Path) -> str:
    with path.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != EXPECTED[path.name]:
        raise ValueError(f"SHA-256 mismatch: {path.name}")
    return actual


def download(url: str, path: Path) -> dict:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        temporary = path.with_suffix(path.suffix + ".partial")
        request = urllib.request.Request(url, headers={"User-Agent": "CameraCompositor-research/0.3"})
        with urllib.request.urlopen(request, timeout=45) as source, temporary.open("wb") as out:
            count = 0
            while data := source.read(1024 * 1024):
                count += len(data)
                if count > 512 * 1024 * 1024:
                    raise ValueError("Research download exceeded its size limit")
                out.write(data)
        temporary.replace(path)
    return {"url": url, "path": str(path.relative_to(ROOT)), "bytes": path.stat().st_size,
            "sha256": digest(path)}


def extract(archive: Path, destination: Path) -> None:
    destination = destination.resolve()
    with zipfile.ZipFile(archive) as source:
        for item in source.infolist():
            target = (destination / item.filename).resolve()
            if not target.is_relative_to(destination) or (item.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Unsafe archive entry")
        source.extractall(destination)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gaic-downloads", action="store_true", help="Also try authors' Google Drive dataset and weights")
    args = parser.parse_args()
    records = []
    for name, (repository, revision) in REVISIONS.items():
        archive = CACHE / f"{name}-{revision}.zip"
        records.append(download(f"https://codeload.github.com/{repository}/zip/{revision}", archive))
        extract(archive, CACHE / "sources")
    repository, revision = REVISIONS["nima"]
    path = "models/MobileNet/weights_mobilenet_aesthetic_0.07.hdf5"
    records.append(download(f"https://raw.githubusercontent.com/{repository}/{revision}/{path}", CACHE / "nima-aesthetic.hdf5"))
    if args.gaic_downloads:
        import gdown
        for name, file_id in [("gaic-models.zip", "1KWYQdL6R5hmOC9toTymbMORZDThpiEW4"),
                              ("gaic-dataset.zip", "1tDdQqDe8dMoMIVi9Z0WWI5vtRViy01nR")]:
            target = CACHE / name
            try:
                if not target.exists():
                    gdown.download(id=file_id, output=str(target), quiet=True)
                records.append({"path": str(target.relative_to(ROOT)), "drive_id": file_id,
                                "bytes": target.stat().st_size, "sha256": digest(target)})
                extract(target, CACHE / name.removesuffix(".zip"))
            except Exception as error:
                records.append({"drive_id": file_id, "status": "unavailable", "error_type": type(error).__name__})
    (CACHE / "downloads.json").write_text(json.dumps({"revisions": REVISIONS, "assets": records}, indent=2), encoding="utf-8")
    print(json.dumps(records, indent=2))


if __name__ == "__main__":
    main()
