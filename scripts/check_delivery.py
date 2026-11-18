"""Inspect the research delivery without revealing any credential-pattern matches."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "artifacts" / "CameraCompositor-0.3-research-debug.apk"
MODEL_SHA = "0853d7468f78f391730ee39c07daf41e0f600caba3bc2731ee086e63fab766ee"
KEY_PATTERN = re.compile(rb"AIza[0-9A-Za-z_-]{35}|gh[pousr]_[0-9A-Za-z]{30,}|sk-proj-[0-9A-Za-z_-]{40,}")


def main():
    tests = list((ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    count = failures = 0
    for path in tests:
        suite = ET.parse(path).getroot()
        count += int(suite.get("tests", "0"))
        failures += int(suite.get("failures", "0")) + int(suite.get("errors", "0"))
    assert count >= 29 and failures == 0, "Unit checks are missing or failed"
    lint = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot()
    issues = list(lint.iter("issue"))
    errors = sum(i.get("severity") in {"Error", "Fatal"} for i in issues)
    assert errors == 0, "Lint errors remain"
    with zipfile.ZipFile(APK) as source:
        names = source.namelist()
        assert "assets/gaic-mobilenet.onnx" in names
        assert hashlib.sha256(source.read("assets/gaic-mobilenet.onnx")).hexdigest() == MODEL_SHA
        assert not any(n.endswith((".tflite", ".litertlm", ".hdf5", ".pth")) for n in names)
        assert all("arm64-v8a" in n for n in names if n.startswith("lib/"))
        assert any("ONNX-Runtime-1.23.2-LICENSE" in n for n in names)
        assert not any(KEY_PATTERN.search(source.read(n)) for n in names if not n.endswith("/")), "Credential-like content in APK"
    files = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT).decode().split("\0")
    for name in filter(None, files):
        path = ROOT / name
        if path.is_file():
            assert path.stat().st_size < 2_000_000, "Unexpected large source file"
            assert not KEY_PATTERN.search(path.read_bytes()), "Credential-like content in source"
    release = ROOT / "app/build/intermediates/assets/release/mergeReleaseAssets"
    assert release.is_dir(), "Run mergeReleaseAssets first"
    assert not any(p.suffix in {".onnx", ".tflite", ".litertlm"} for p in release.rglob("*")), "Research weights leaked into release assets"
    result = {"build": "0.3.0-research", "apkBytes": APK.stat().st_size,
        "apkSha256": hashlib.sha256(APK.read_bytes()).hexdigest(), "unitTests": count, "unitFailures": failures,
        "lintErrors": errors, "lintWarnings": sum(i.get("severity") == "Warning" for i in issues),
        "modelSha256": MODEL_SHA, "releaseResearchAssets": "absent", "credentialPatternCheck": "pass",
        "physicalVivoTests": "pending", "humanPreferenceGate": "pending", "liveProviderDiagnostic": "pending"}
    path = ROOT / "research/results/delivery-verification.json"
    path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
