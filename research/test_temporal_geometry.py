"""Temporal image tracking safeguards. These are synthetic images, not phone results."""
import unittest
import cv2
import numpy as np
from temporal_geometry import track_sequence


class TemporalGeometryTests(unittest.TestCase):
    def test_textureless_sequence_cannot_produce_a_depth_claim(self):
        k=np.array([[500,0,160],[0,500,120],[0,0,1]],np.float64)
        result=track_sequence([np.zeros((240,320),np.uint8)]*7,k)
        self.assertEqual("observe_more",result["status"])
        self.assertFalse(result["metricScaleKnown"])

    def test_geometry_change_is_rejected_before_motion_estimation(self):
        k=np.eye(3)
        with self.assertRaises(ValueError): track_sequence([np.zeros((24,32),np.uint8),np.zeros((48,64),np.uint8)],k)

    def test_static_textured_observation_cannot_invent_translation(self):
        rng=np.random.default_rng(71)
        image=rng.integers(0,256,(240,320),dtype=np.uint8)
        k=np.array([[500,0,160],[0,500,120],[0,0,1]],np.float64)
        result=track_sequence([image]*7,k)
        self.assertEqual("observe_more",result["status"])
        self.assertNotIn("pointsCameraAUnitScale",result)


if __name__ == "__main__": unittest.main()
