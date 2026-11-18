"""Inspect the research delivery without revealing any credential-pattern matches."""
from pathlib import Path
import hashlib
import json
import re
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile
import argparse

ROOT = Path(__file__).resolve().parents[1]
MODEL_SHA = "0853d7468f78f391730ee39c07daf41e0f600caba3bc2731ee086e63fab766ee"
DEPTH_ASSET = "zipdepth-192x256.onnx"
DEPTH_SHA = "dad03af25e12af84feb7694e0b0182dd2811a0d2a47d4c009cd7bcb41b5b7d25"
CAMERA_VERSION = "0.5.0-camera"
KEY_PATTERN = re.compile(rb"AIza[0-9A-Za-z_-]{35}|gh[pousr]_[0-9A-Za-z]{30,}|sk-proj-[0-9A-Za-z_-]{40,}")


def file_sha(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def verify_arm64_segments(image):
    assert image[:6] == b"\x7fELF\x02\x01", "Expected a little-endian ELF64 native dependency"
    header_offset = struct.unpack_from("<Q", image, 32)[0]
    header_size, count = struct.unpack_from("<HH", image, 54)
    segments = [struct.unpack_from("<II6Q", image, header_offset + i * header_size) for i in range(count)]
    loads = [segment for segment in segments if segment[0] == 1]
    assert loads and all(s[7] >= 16384 and s[2] % 16384 == s[3] % 16384 for s in loads), "ELF load alignment failed"


def verify_apk(apk, abi, version):
    with zipfile.ZipFile(apk) as source:
        names = source.namelist()
        native = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
        assert native and all(name.split("/")[1] == abi for name in native), "Unexpected native ABI"
        assert any("ONNX-Runtime-1.23.2-LICENSE" in name for name in names)
        assert not any(name.endswith((".tflite", ".litertlm", ".hdf5", ".pth")) for name in names)
        if version in {"0.4.0-environment", "0.4.1-spatial", CAMERA_VERSION}:
            assert "assets/third-party/ARCore-NOTICE.txt" in names
            assert f"lib/{abi}/libarcore_sdk_jni.so" in native
        verify_model = DEPTH_ASSET if version == CAMERA_VERSION else "gaic-mobilenet.onnx"
        model_hash = DEPTH_SHA if version == CAMERA_VERSION else MODEL_SHA
        weights = source.getinfo("assets/" + verify_model)
        assert weights.compress_type == zipfile.ZIP_STORED
        assert hashlib.sha256(source.read(weights)).hexdigest() == model_hash
        if version == CAMERA_VERSION:
            assert "assets/third-party/ZipDepth-LICENSE.txt" in names
            assert {n for n in names if n.endswith(".onnx")} == {"assets/" + DEPTH_ASSET}
            if abi == "arm64-v8a":
                for name in native:
                    verify_arm64_segments(source.read(name))
        assert not any(KEY_PATTERN.search(source.read(n)) for n in names if not n.endswith("/")), "Credential-like content in APK"


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--version",default=CAMERA_VERSION)
    args=parser.parse_args()
    version=args.version
    assert re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[a-z]+)?",version), "Invalid delivery version"
    apk = ROOT / "artifacts" / f"CameraCompositor-{version}-debug.apk"
    arm32 = ROOT / "artifacts" / f"CameraCompositor-{version}-armv7-debug.apk"
    tests = list((ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    count = failures = 0
    for path in tests:
        suite = ET.parse(path).getroot()
        count += int(suite.get("tests", "0"))
        failures += int(suite.get("failures", "0")) + int(suite.get("errors", "0"))
    minimum = {CAMERA_VERSION: 63, "0.4.1-spatial": 55, "0.4.0-environment": 47}.get(version, 35)
    assert count >= minimum and failures == 0, "Unit checks are missing or failed"
    outputs = ROOT / "app/build/outputs/apk/debug"
    metadata = json.loads((outputs / "output-metadata.json").read_text())
    assert all(m["versionName"] == version for m in metadata["elements"]), "Build metadata is stale"
    if version == CAMERA_VERSION:
        artifacts = {"arm64-v8a": apk, "armeabi-v7a": arm32}
        elements = {f["value"]: m for m in metadata["elements"] for f in m["filters"] if f["filterType"] == "ABI"}
        assert metadata["minSdkVersionForDexing"] == 29 and elements.keys() == artifacts.keys()
        for abi, path in artifacts.items():
            assert file_sha(path) == file_sha(outputs / elements[abi]["outputFile"]), "Delivery does not match tested build"
            verify_apk(path, abi, version)
    else:
        assert file_sha(apk) == file_sha(outputs / "app-debug.apk"), "Delivery does not match tested build"
        verify_apk(apk, "arm64-v8a", version)
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    issues = list(lint.iter("issue"))
    errors = sum(i.get("severity") in {"Error", "Fatal"} for i in issues)
    assert errors == 0, "Lint errors remain"
    files = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT).decode().split("\0")
    for name in filter(None, files):
        path = ROOT / name
        if path.is_file():
            assert path.stat().st_size < 2_000_000, "Unexpected large source file"
            assert not KEY_PATTERN.search(path.read_bytes()), "Credential-like content in source"
    release = ROOT / "app/build/intermediates/assets/release/mergeReleaseAssets"
    assert release.is_dir(), "Run mergeReleaseAssets first"
    release_weights = {p.name: p for p in release.rglob("*") if p.suffix in {".onnx", ".tflite", ".litertlm", ".pth", ".hdf5"}}
    assert release_weights.keys() == ({DEPTH_ASSET} if version == CAMERA_VERSION else set()), "Unexpected model in release assets"
    assert version != CAMERA_VERSION or file_sha(release_weights[DEPTH_ASSET]) == DEPTH_SHA
    result = {"build": version, "apkBytes": apk.stat().st_size,
        "apkSha256": file_sha(apk), "unitTests": count, "unitFailures": failures,
        "lintErrors": errors, "lintWarnings": sum(i.get("severity") == "Warning" for i in issues),
        "modelSha256": MODEL_SHA, "releaseResearchAssets": "absent", "credentialPatternCheck": "pass",
        "physicalVivoTests": "owner v0.4 export confirms ARCore pose/points/depth on Vivo V30; v0.4.1 capture alignment, photographic selection, provider inference and performance remain pending",
        "humanPreferenceGate": "pending",
        "liveProviderDiagnostic": "v0.3.0 owner report: WAIT_RESPONSE timeout after client upload; cause unresolved"}
    if version == CAMERA_VERSION:
        result.update({"modelSha256": DEPTH_SHA, "releaseResearchAssets": "ZipDepth only, no legacy research weights",
            "armeabiV7aApkBytes": arm32.stat().st_size, "armeabiV7aApkSha256": file_sha(arm32),
            "arm64Elf16KiBLoadAlignment": "pass",
            "physicalVivoTests": "v0.5.0 pending; v0.4.1 owner reported spatial tracking temporarily unavailable"})
    tag=version.replace(".","").replace("-","_")
    path = ROOT / f"research/results/delivery-verification-v{tag}.json"
    path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
