"""Export isolated shot-discovery assets, verify dynamic GAIC and quantized image encoder.

The existing Android debug asset stays untouched. New assets remain in an unpackaged
research directory until composition and physical-device gates justify integration.
"""
import os
os.environ["USE_TF"] = "0"
os.environ["USE_FLAX"] = "0"
os.environ["HF_HUB_OFFLINE"] = "1"
import hashlib
import json
import math
from pathlib import Path
import numpy as np
import onnx
import onnxruntime as ort
from onnxruntime.quantization import quantize_dynamic, QuantType
import torch
from torch import nn
from PIL import Image
from semantic_shots import SemanticScorer, SUBJECTS, NEGATIVES, POLICY
from owner_city_experiment import expanded_boxes
from gaic import GaicScorer
from evaluate import camera_boxes, verify, crop
from fetch_assets import ROOT
from fetch_semantic_assets import REPOSITORY, REVISION
ort.disable_telemetry_events()


class ImageEncoder(nn.Module):
    def __init__(self, model):
        super().__init__(); self.vision = model.vision_model; self.projection = model.visual_projection

    def forward(self, pixels):
        pooled = self.vision(pixel_values=pixels)[1]
        return torch.nn.functional.normalize(self.projection(pooled), dim=-1)


def session(path):
    options = ort.SessionOptions(); options.intra_op_num_threads=2; options.inter_op_num_threads=1
    return ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])


def box_inputs(image, normalized):
    pixels, w, h = GaicScorer.prepare(image)
    boxes = torch.tensor([[math.floor(b[0]*w), math.floor(b[1]*h), math.ceil(b[2]*w), math.ceil(b[3]*h)] for b in normalized], dtype=torch.float32)
    return pixels, boxes


def main():
    torch.set_num_threads(2)
    target = ROOT/".local/shot-discovery-assets"; target.mkdir(exist_ok=True)
    scenes = [s for s in json.loads((ROOT/"research/benchmark.json").read_text())["scenes"] if s["split"] == "development"]
    gaic, semantic = GaicScorer(), SemanticScorer()
    semantic.model.set_attn_implementation("eager")
    example = box_inputs(verify(scenes[0]), camera_boxes())
    gaic_path = target/"gaic-dynamic.onnx"
    torch.onnx.export(gaic.model, example, str(gaic_path), input_names=["image","boxes"], output_names=["scores"],
        opset_version=17, dynamo=False, dynamic_axes={"image":{2:"height",3:"width"},"boxes":{0:"crops"},"scores":{0:"crops"}})
    onnx.checker.check_model(str(gaic_path)); gaic_onnx = session(gaic_path)
    errors = []
    for scene in scenes:
        image = verify(scene)
        for boxes in [camera_boxes(), expanded_boxes()]:
            pixels, rois = box_inputs(image, boxes)
            predicted = gaic_onnx.run(None,{"image":pixels.numpy(),"boxes":rois.numpy()})[0]*.8+2.95
            errors.append(float(np.max(np.abs(predicted-gaic.score(image,boxes)))))
    if max(errors) > .001:
        raise ValueError(f"Dynamic GAIC parity failed: {max(errors)}")
    encoder = ImageEncoder(semantic.model).eval()
    float_path = target/"tinyclip-image-f32.onnx"; quant_path = target/"tinyclip-image-int8.onnx"
    torch.onnx.export(encoder, torch.zeros(1,3,224,224), str(float_path), input_names=["pixels"], output_names=["embedding"],
        opset_version=17, dynamo=False, dynamic_axes={"pixels":{0:"batch"},"embedding":{0:"batch"}})
    onnx.checker.check_model(str(float_path))
    quantize_dynamic(str(float_path), str(quant_path), weight_type=QuantType.QInt8, per_channel=True, op_types_to_quantize=["MatMul","Gemm"])
    float_model, quant_model = session(float_path), session(quant_path)
    float_errors, cosines, similarity_errors, top_matches = [],[],[],[]
    for scene in scenes:
        image = verify(scene)
        images = [crop(image,b) for b in [camera_boxes()[0],expanded_boxes()[107],expanded_boxes()[161]]]
        inputs = semantic.processor(images=[im.resize((224,224), Image.Resampling.BICUBIC) for im in images],
            do_resize=False, do_center_crop=False, return_tensors="pt")["pixel_values"]
        with torch.inference_mode(): expected=encoder(inputs).numpy()
        floating = float_model.run(None,{"pixels":inputs.numpy()})[0]
        quantized = quant_model.run(None,{"pixels":inputs.numpy()})[0]
        float_errors.append(float(np.max(np.abs(expected-floating))))
        cosines.extend(np.sum(expected*quantized,axis=-1).tolist())
        a,b = expected@semantic.text.numpy().T, quantized@semantic.text.numpy().T
        similarity_errors.append(float(np.max(np.abs(a-b))))
        top_matches.extend((a.argmax(1)==b.argmax(1)).tolist())
    report={"repository": REPOSITORY,"revision":REVISION,"policy":POLICY,
        "dynamicGaicMaxError":max(errors),"dynamicGaicSizesTested":[9,295],"developmentScenes":len(scenes),
        "floatEncoderMaxError":max(float_errors),"quantizedMinEmbeddingCosine":min(cosines),
        "quantizedMaxTextSimilarityError":max(similarity_errors),"quantizedSameTopClass":float(np.mean(top_matches)),
        "quantizationApproved":min(cosines)>.99 and max(similarity_errors)<.02,
        "androidPerformance":"pending", "humanPreference":"pending", "releaseApproved":False}
    for p in [gaic_path,float_path,quant_path]:
        report[p.name]={"bytes":p.stat().st_size,"sha256":hashlib.sha256(p.read_bytes()).hexdigest()}
    (target/"text-embeddings.json").write_text(json.dumps({"subjects":SUBJECTS,"negatives":NEGATIVES,
        "vectors":semantic.text.tolist(),"preprocessing":"whole crop bicubic resize to 224x224; CLIP normalization; no center crop"}),encoding="utf-8")
    (target/"conversion.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
    (ROOT/"research/results/shot-model-conversion.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
    print(json.dumps(report,indent=2),flush=True)
    if not report["quantizationApproved"]:
        quant_path.unlink()
        raise ValueError("Quantized encoder failed fidelity gate; rejected binary removed")


if __name__ == "__main__":
    main()
