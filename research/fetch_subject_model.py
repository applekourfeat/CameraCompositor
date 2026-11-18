"""Prepare the pinned OpenCV Zoo NanoDet weights for the offline camera build."""
import hashlib
from pathlib import Path
from urllib.request import urlopen

import onnx

ROOT = Path(__file__).resolve().parents[1]
REVISION = "48b10fa505aeb51fbd127fc60a5c660040e823b7"
SOURCE = ("https://media.githubusercontent.com/media/opencv/opencv_zoo/" + REVISION
          + "/models/object_detection_nanodet/object_detection_nanodet_2022nov.onnx")
UPSTREAM_SHA256 = "4b82da9944b88577175ee23a459dce2e26e6e4be573def65b1055dc2d9720186"
APP_SHA256 = "d891b8c939ce062b13fc7440195d427eeecbec307c2417564d6908309b263e19"


def main():
    with urlopen(SOURCE, timeout=30) as response:
        data = response.read(3_800_955)
    if hashlib.sha256(data).hexdigest() != UPSTREAM_SHA256:
        raise ValueError("Unexpected NanoDet checkpoint")
    graph = onnx.load_from_string(data)
    constants = {weight.name for weight in graph.graph.initializer}
    image_inputs = [entry for entry in graph.graph.input if entry.name not in constants]
    if [entry.name for entry in image_inputs] != ["input.1"]:
        raise ValueError("Unexpected NanoDet input")
    # The upstream export also lists frozen weights as inputs. Keep the weights as initializers.
    del graph.graph.input[:]
    graph.graph.input.extend(image_inputs)
    onnx.checker.check_model(graph)
    model = graph.SerializeToString()
    if hashlib.sha256(model).hexdigest() != APP_SHA256:
        raise ValueError("Unexpected packaged NanoDet model. Use the pinned ONNX version in the research environment.")
    target = ROOT / ".local/subject-assets/nanodet-416.onnx"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(model)
    print(f"Prepared {target.name} ({len(model):,} bytes).")


if __name__ == "__main__":
    main()
