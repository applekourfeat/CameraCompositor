"""Synthetic geometric evidence only. Real Vivo reconstruction remains untested."""
import unittest
import numpy as np
import cv2
from environment_geometry import estimate_pair


def scene(translation,angle=.025,noise=.05):
    generator=np.random.default_rng(8017)
    xyz=np.column_stack((generator.uniform(-2,2,180),generator.uniform(-1.2,1.2,180),generator.uniform(3,9,180)))
    k=np.array([[650,0,320],[0,650,240],[0,0,1]],np.float64)
    rotation=cv2.Rodrigues(np.array([0.,angle,0.]))[0]
    def project(points):
        raw=points@k.T
        return raw[:,:2]/raw[:,2,None]+generator.normal(0,noise,(len(points),2))
    return project(xyz),project(xyz@rotation.T+translation),k,rotation


class EnvironmentGeometryTests(unittest.TestCase):
    def test_recovers_translation_perspective_and_relative_geometry(self):
        translation=np.array([.35,.01,.02]); a,b,k,rotation=scene(translation)
        cv2.setRNGSeed(82); result=estimate_pair(a,b,k)
        self.assertEqual("sparse_3d_pair",result["status"])
        self.assertGreater(result["inlierPoints"],120)
        self.assertGreater(float(np.dot(result["translationCameraAToBUnitScale"],translation/np.linalg.norm(translation))),.99)
        np.testing.assert_allclose(result["rotationCameraAToB"],rotation,atol=.003)
        self.assertFalse(result["metricScaleKnown"])
        self.assertFalse(result["walkOrHeightGuidanceAllowed"])

    def test_rotation_or_small_baseline_cannot_claim_reliable_depth(self):
        for translation in [np.zeros(3),np.array([.0001,0,0])]:
            a,b,k,_=scene(translation); cv2.setRNGSeed(82)
            result=estimate_pair(a,b,k)
            self.assertEqual("observe_more",result["status"])
            self.assertNotIn("pointsCameraAUnitScale",result)

    def test_outlier_correspondences_are_rejected_by_geometry(self):
        a,b,k,_=scene(np.array([.35,.01,.02]))
        b[:35]=b[:35][::-1]; cv2.setRNGSeed(82)
        result=estimate_pair(a,b,k)
        self.assertEqual("sparse_3d_pair",result["status"])
        self.assertGreater(result["inlierPoints"],100)
        self.assertLess(result["inlierPoints"],150)
        self.assertLess(result["medianReprojectionErrorPixels"],.5)

    def test_invalid_calibration_and_correspondences_fail(self):
        a,b,k,_=scene(np.array([.35,0,0]))
        for bad in [np.zeros((3,3)),np.eye(4),k*float("nan")]:
            with self.assertRaises(ValueError): estimate_pair(a,b,bad)
        with self.assertRaises(ValueError): estimate_pair(a,b[:-1],k)
        self.assertEqual("observe_more",estimate_pair(a[:10],b[:10],k)["status"])


if __name__ == "__main__": unittest.main()
