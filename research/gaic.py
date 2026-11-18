"""CPU forward port of GAICv2's published MobileNetV2 checkpoint.

MIT upstream: HuiZeng/Grid-Anchor-based-Image-Cropping-Pytorch at the revision in fetch_assets.py.
The two gather operators implement the equations in its roi_align_kernel.cu and
rod_align_kernel.cu. In particular, this is NOT torchvision's different ROIAlign.
CUDA-to-CPU parity on actual GPU hardware remains untested. Weights are private debug assets only.
"""
from __future__ import annotations
import importlib.util
import math
import numpy as np
from PIL import Image
import torch
from torch import nn
from torch.nn import functional as F
from fetch_assets import CACHE, REVISIONS


def sample(features, h, w):
    """Match CUDA's floor/clamp plus extrapolation within the final pixel interval."""
    height, width = features.shape[-2:]
    valid = (h >= 0) & (h < height) & (w >= 0) & (w < width)
    y = h.floor().long().clamp(0, height - 2)
    x = w.floor().long().clamp(0, width - 2)
    dy, dx = h - y, w - x
    values = (features[0, :, y, x] * (1-dy) * (1-dx) + features[0, :, y, x+1] * (1-dy) * dx +
              features[0, :, y+1, x] * dy * (1-dx) + features[0, :, y+1, x+1] * dy * dx)
    return values * valid


def aligned_features(features, rois, discard=False):
    n = rois.shape[0]
    if discard:
        height, width = features.shape[-2:]
        y = torch.arange(10, dtype=torch.float32) * ((height - 1.001) / 9)
        x = torch.arange(10, dtype=torch.float32) * ((width - 1.001) / 9)
        h, w = torch.meshgrid(y, x, indexing="ij")
        h, w = h.expand(n, -1, -1), w.expand(n, -1, -1)
    else:
        fraction = torch.arange(10, dtype=torch.float32) / 9
        h = rois[:, 1, None, None] + fraction[None, :, None] * (rois[:, 3, None, None] - rois[:, 1, None, None] + 1)
        w = rois[:, 0, None, None] + fraction[None, None, :] * (rois[:, 2, None, None] - rois[:, 0, None, None] + 1)
        h, w = torch.broadcast_tensors(h, w)
    out = sample(features, h, w).permute(1, 0, 2, 3)
    if discard:
        inside = ((h >= rois[:, 1, None, None]) & (h <= rois[:, 3, None, None]) &
                  (w >= rois[:, 0, None, None]) & (w <= rois[:, 2, None, None]))
        out = out.masked_fill(inside[:, None], 0)
    return F.avg_pool2d(out, kernel_size=2, stride=1)


class GaicNetwork(nn.Module):
    def __init__(self):
        super().__init__()
        revision = REVISIONS["gaic"][1]
        source = CACHE / "sources" / f"Grid-Anchor-based-Image-Cropping-Pytorch-{revision}" / "mobilenetv2.py"
        spec = importlib.util.spec_from_file_location("gaic_upstream_mobilenet", source)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        mobile = module.MobileNetV2(width_mult=1.0)
        self.Feat_ext = nn.Module()
        self.Feat_ext.feature3 = nn.Sequential(mobile.features[:7])
        self.Feat_ext.feature4 = nn.Sequential(mobile.features[7:14])
        self.Feat_ext.feature5 = nn.Sequential(mobile.features[14:])
        self.DimRed = nn.Conv2d(448, 8, 1)
        self.FC_layers = nn.Sequential(
            nn.Sequential(nn.Conv2d(16, 768, 9), nn.ReLU(inplace=True)),
            nn.Sequential(nn.Conv2d(768, 128, 1), nn.ReLU(inplace=True)),
            nn.Conv2d(128, 1, 1))

    def forward(self, image, boxes):
        f3 = self.Feat_ext.feature3(image)
        f4 = self.Feat_ext.feature4(f3)
        f5 = self.Feat_ext.feature5(f4)
        features = self.DimRed(torch.cat((F.interpolate(f3, scale_factor=.5, mode="bilinear", align_corners=True), f4,
                                       .5 * F.interpolate(f5, scale_factor=2, mode="bilinear", align_corners=True)), 1))
        rois = boxes / 16
        return self.FC_layers(torch.cat((aligned_features(features, rois), aligned_features(features, rois, True)), 1)).flatten()


class GaicScorer:
    name = "gaic-mobilenet-cpu-port"

    def __init__(self):
        torch.set_num_threads(2)
        self.model = GaicNetwork()
        checkpoint = next((CACHE / "gaic-models").glob("mobilenet*.pth"))
        weights = torch.load(checkpoint, map_location="cpu", weights_only=True)
        self.model.load_state_dict(weights, strict=True)
        self.model.eval()
        with torch.inference_mode():
            self.model(torch.zeros(1, 3, 256, 256), torch.tensor([[0, 0, 240, 240]], dtype=torch.float32))

    @staticmethod
    def prepare(image):
        # Upstream uses cv2 INTER_LINEAR and RGB/256 normalization.
        import cv2
        factor = 256 / min(image.size)
        w, h = [round(length * factor / 32) * 32 for length in image.size]
        resized = cv2.resize(np.asarray(image), (w, h)).astype(np.float32) / 256
        resized = (resized - np.array([.485, .456, .406], np.float32)) / np.array([.229, .224, .225], np.float32)
        return torch.from_numpy(resized.transpose(2, 0, 1)[None]), w, h

    def score(self, image: Image.Image, boxes: list[list[float]]) -> np.ndarray:
        pixels, w, h = self.prepare(image)
        # Match upstream's floor starts, ceil ends when scaling annotated boxes.
        rois = torch.tensor([[math.floor(b[0]*w), math.floor(b[1]*h), math.ceil(b[2]*w), math.ceil(b[3]*h)]
                             for b in boxes], dtype=torch.float32)
        with torch.inference_mode():
            return self.model(pixels, rois).numpy() * .8 + 2.95
