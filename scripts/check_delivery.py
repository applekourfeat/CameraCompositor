"""Inspect the research delivery without revealing any credential-pattern matches."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile
import argparse

ROOT = Path(__file__).resolve().parents[1]
MODEL_SHA = "0853d7468f78f391730ee39c07daf41e0f600caba3bc2731ee086e63fab766ee"
KEY_PATTERN = re.compile(rb"AIza[0-9A-Za-z_-]{35}|gh[pousr]_[0-9A-Za-z]{30,}|sk-proj-[0-9A-Za-z_-]{40,}")


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--version",default="0.4.0-environment")
    args=parser.parse_args()
    version=args.version
    assert re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[a-z]+)?",version), "Invalid delivery version"
    APK=ROOT/"artifacts"/f"CameraCompositor-{version}-debug.apk"
    tests = list((ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    count = failures = 0
    for path in tests:
        suite = ET.parse(path).getroot()
        count += int(suite.get("tests", "0"))
        failures += int(suite.get("failures", "0")) + int(suite.get("errors", "0"))
    assert count >= (47 if version=="0.4.0-environment" else 35) and failures == 0, "Unit checks are missing or failed"
    metadata = json.loads((ROOT / "app/build/outputs/apk/debug/output-metadata.json").read_text())
    assert metadata["elements"][0]["versionName"] == version, "Build metadata is stale"
    assert APK.read_bytes() == (ROOT / "app/build/outputs/apk/debug/app-debug.apk").read_bytes(), "Delivery does not match tested build"
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
        if version=="0.4.0-environment":
            assert "assets/third-party/ARCore-NOTICE.txt" in names
            assert "lib/arm64-v8a/libarcore_sdk_jni.so" in names
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
    result = {"build": version, "apkBytes": APK.stat().st_size,
        "apkSha256": hashlib.sha256(APK.read_bytes()).hexdigest(), "unitTests": count, "unitFailures": failures,
        "lintErrors": errors, "lintWarnings": sum(i.get("severity") == "Warning" for i in issues),
        "modelSha256": MODEL_SHA, "releaseResearchAssets": "absent", "credentialPatternCheck": "pass",
        "physicalVivoTests": "prior owner observations recorded; environment sweep, native flow, ARCore and provider inference remain pending on Vivo",
        "humanPreferenceGate": "pending",
        "liveProviderDiagnostic": "v0.3.0 owner report: WAIT_RESPONSE timeout after client upload; cause unresolved"}
    tag=version.replace(".","").replace("-","_")
    path = ROOT / f"research/results/delivery-verification-v{tag}.json"
    path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
