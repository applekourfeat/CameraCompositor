"""Pin upstream notices for the libraries added by the research build."""
from pathlib import Path
import urllib.request
import importlib.metadata
from fetch_assets import ROOT

NOTICES = {
    "OkHttp-4.12.0-LICENSE.txt": "https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/LICENSE.txt",
    "ONNX-Runtime-1.23.2-LICENSE.txt": "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.23.2/LICENSE",
    "ONNX-Runtime-1.23.2-ThirdPartyNotices.txt": "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.23.2/ThirdPartyNotices.txt",
}

if __name__ == "__main__":
    target = ROOT / "app" / "src" / "main" / "assets" / "third-party"
    for name, url in NOTICES.items():
        if name.startswith("ONNX-"):
            distribution = importlib.metadata.distribution("onnxruntime")
            assert distribution.version == "1.23.2"
            basename = "ThirdPartyNotices.txt" if "ThirdParty" in name else "LICENSE"
            candidate = next(f for f in distribution.files if f.name == basename)
            data = Path(distribution.locate_file(candidate)).read_bytes()
        else:
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read(2_000_000)
        (target / name).write_bytes(data)
        print(name, len(data))
