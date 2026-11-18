"""Numerical/protocol safeguards, not a substitute for human composition evaluation."""
import json
import math
import unittest
import tempfile
import zipfile
import io
from pathlib import Path
from PIL import Image
import numpy as np
import torch
from torch.nn import functional as F
from gaic import aligned_features
from evaluate import camera_boxes
from fetch_assets import ROOT
from summarize_review import summarize
from evaluate_sweep import read_sweep


def scalar_reference(features, rois, discard):
    pixels = features.numpy()[0]
    channels, height, width = pixels.shape
    result = np.zeros((len(rois), channels, 10, 10), dtype=np.float32)
    for n, (x1, y1, x2, y2) in enumerate(rois.numpy()):
        for py in range(10):
            for px in range(10):
                h = py * (height-1.001)/9 if discard else y1 + py * max(y2-y1+1, 0)/9
                w = px * (width-1.001)/9 if discard else x1 + px * max(x2-x1+1, 0)/9
                if discard and y1 <= h <= y2 and x1 <= w <= x2:
                    continue
                if h < 0 or h >= height or w < 0 or w >= width:
                    continue
                y, x = min(math.floor(h), height-2), min(math.floor(w), width-2)
                dy, dx = h-y, w-x
                result[n, :, py, px] = (pixels[:, y, x]*(1-dy)*(1-dx) + pixels[:, y, x+1]*(1-dy)*dx +
                    pixels[:, y+1, x]*dy*(1-dx) + pixels[:, y+1, x+1]*dy*dx)
    return F.avg_pool2d(torch.from_numpy(result), 2, 1).numpy()


class ResearchTests(unittest.TestCase):
    def test_local_sweep_keeps_observed_id_zoom_and_image(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "views.zip"
            pixels = io.BytesIO(); Image.new("RGB", (120, 160), "gray").save(pixels, format="JPEG")
            with zipfile.ZipFile(path, "w") as target:
                target.writestr("observations.json", json.dumps({"schema": 1, "frames": [{"id": 0, "zoom": 1, "maxRelativeZoom": 1.8}]}))
                target.writestr("frames/00.jpg", pixels.getvalue())
            metadata, frames = read_sweep(path)
            self.assertEqual(0, frames[0][0]["id"])
            self.assertEqual((120, 160), frames[0][1].size)

    def test_local_sweep_rejects_unbounded_zoom_and_duplicate_id(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "views.zip"
            pixels = io.BytesIO(); Image.new("RGB", (120, 160), "gray").save(pixels, format="JPEG")
            for frames in [[{"id": 0, "zoom": 1, "maxRelativeZoom": 8}], [{"id": 0, "zoom": 1, "maxRelativeZoom": 1.8}]*2]:
                with zipfile.ZipFile(path, "w") as target:
                    target.writestr("observations.json", json.dumps({"schema": 1, "frames": frames}))
                    target.writestr("frames/00.jpg", pixels.getvalue())
                with self.assertRaises(ValueError):
                    read_sweep(path)

    def test_unreviewed_outputs_never_pass_human_gate(self):
        result = summarize({"manifestHash": "test"}, {"manifestHash": "test", "trials": []})
        self.assertEqual("pending", result["gaic"]["70PercentPreferenceGate"])
        self.assertFalse(result["gaic"]["releaseApproved"])

    def test_ties_count_as_no_improvement_and_other_gates_remain_pending(self):
        trials = [{"id": str(i), "sceneId": str(i), "model": "gaic", "split": "evaluation", "type": "original",
                   "candidateSide": "left", "cameraChoice": 1 if i < 20 else 0} for i in range(60)]
        labels = {str(i): {"answer": "needs_work" if i < 20 else "good"} for i in range(60)}
        votes = {str(i): {"answer": "left" if i < 14 else "tie"} for i in range(60)}
        result = summarize({"manifestHash": "test", "startingViews": labels, "answers": votes},
                           {"manifestHash": "test", "trials": trials})["gaic"]
        self.assertEqual(.7, result["preferenceRate"])
        self.assertEqual("pass", result["70PercentPreferenceGate"])
        self.assertFalse(result["releaseApproved"])
        votes.pop("59")
        result = summarize({"manifestHash": "test", "startingViews": labels, "answers": votes},
                           {"manifestHash": "test", "trials": trials})["gaic"]
        self.assertEqual("pending", result["70PercentPreferenceGate"])

    def test_review_rejects_answers_from_another_set(self):
        with self.assertRaises(ValueError):
            summarize({"manifestHash": "old"}, {"manifestHash": "new"})

    def test_roi_and_discard_match_published_kernel_equations(self):
        generator = torch.Generator().manual_seed(98)
        features = torch.randn(1, 3, 8, 12, generator=generator)
        rois = torch.tensor([[0, 0, 11, 7], [1.25, 2.4, 8.8, 5.6], [-1, -1, 5, 4], [9, 5, 11, 7]], dtype=torch.float32)
        for discard in [False, True]:
            np.testing.assert_allclose(aligned_features(features, rois, discard).numpy(), scalar_reference(features, rois, discard), atol=1e-5)

    def test_camera_candidates_have_same_aspect_and_stay_inside_source(self):
        boxes = camera_boxes()
        self.assertEqual(9, len(boxes))
        for l, t, r, b in boxes:
            self.assertTrue(0 <= l < r <= 1 and 0 <= t < b <= 1)
            self.assertAlmostEqual(r-l, b-t)
            self.assertLessEqual(1/(r-l), 1.80001)

    def test_manifest_has_distinct_development_and_evaluation_photos(self):
        scenes = json.loads((ROOT / "research" / "benchmark.json").read_text())["scenes"]
        self.assertEqual(20, sum(s["split"] == "development" for s in scenes))
        self.assertEqual(60, sum(s["split"] == "evaluation" for s in scenes))
        self.assertEqual(80, len({s["sha256"] for s in scenes}))
        self.assertTrue(all(s["sourceSplit"] != "train" for s in scenes))


if __name__ == "__main__":
    unittest.main()
