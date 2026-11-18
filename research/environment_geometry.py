"""Calibrated two-view geometry prototype, separate from photographic selection.

OpenCV camera convention: x right, y down, z forward. Reconstructed points are in
the first camera's coordinates. Translation and points have a shared UNKNOWN scale.
This is a sparse pair reconstruction, not live SLAM or a complete environment map.
"""
import argparse
import json
from pathlib import Path
import cv2
import numpy as np


def estimate_pair(points_a, points_b, intrinsics, minimum_parallax_degrees=.8):
    a,b=np.asarray(points_a,np.float64),np.asarray(points_b,np.float64)
    k=np.asarray(intrinsics,np.float64)
    if k.shape != (3,3) or not np.isfinite(k).all() or k[0,0] <= 0 or k[1,1] <= 0 or not np.allclose(k[2],[0,0,1]) or abs(k[0,1])+abs(k[1,0])>1e-9:
        raise ValueError("A validated pinhole calibration for these exact pixels is required")
    if not np.isfinite(minimum_parallax_degrees) or minimum_parallax_degrees <= 0:
        raise ValueError("Parallax threshold must be positive and finite")
    if a.shape != b.shape or a.ndim != 2 or a.shape[1] != 2 or not np.isfinite(a).all() or not np.isfinite(b).all():
        raise ValueError("Correspondences must be matching finite Nx2 arrays")
    report={"status":"observe_more","reason":"insufficient_matches","metricScaleKnown":False,
            "matchedPoints":len(a),"walkOrHeightGuidanceAllowed":False}
    if len(a) < 30: return report
    normalized_a=cv2.undistortPoints(a[:,None,:],k,None).reshape(-1,2)
    normalized_b=cv2.undistortPoints(b[:,None,:],k,None).reshape(-1,2)
    essential,mask=cv2.findEssentialMat(normalized_a,normalized_b,np.eye(3),method=cv2.RANSAC,prob=.999,threshold=1.5/max(k[0,0],k[1,1]))
    if essential is None or essential.shape != (3,3):
        return {**report,"reason":"no_stable_epipolar_geometry"}
    inliers,rotation,translation,mask=cv2.recoverPose(essential,normalized_a,normalized_b,np.eye(3),mask=mask)
    keep=mask.ravel()!=0
    if inliers < 30:
        return {**report,"reason":"insufficient_translation_evidence","inlierPoints":int(inliers)}
    aa,bb=normalized_a[keep],normalized_b[keep]
    ray_a=np.column_stack((aa,np.ones(len(aa)))); ray_b=np.column_stack((bb,np.ones(len(bb))))@rotation
    ray_a/=np.linalg.norm(ray_a,axis=1,keepdims=True); ray_b/=np.linalg.norm(ray_b,axis=1,keepdims=True)
    angles=np.rad2deg(np.arccos(np.clip(np.sum(ray_a*ray_b,axis=1),-1,1)))
    median_parallax=float(np.median(angles))
    if median_parallax < minimum_parallax_degrees:
        return {**report,"reason":"insufficient_parallax","inlierPoints":int(inliers),"medianParallaxDegrees":median_parallax}
    p1=np.column_stack((np.eye(3),np.zeros(3))); p2=np.column_stack((rotation,translation))
    homogeneous=cv2.triangulatePoints(p1,p2,aa.T,bb.T).T
    finite=np.abs(homogeneous[:,3])>1e-9
    xyz=np.zeros((len(homogeneous),3)); xyz[finite]=homogeneous[finite,:3]/homogeneous[finite,3,None]
    xyz_b=xyz@rotation.T+translation.ravel()
    valid=finite & np.isfinite(xyz).all(1) & (xyz[:,2]>.001) & (xyz_b[:,2]>.001)
    projected_a=xyz[valid,:2]/xyz[valid,2,None]
    projected_b=xyz_b[valid,:2]/xyz_b[valid,2,None]
    def pixels(points):
        raw=np.column_stack((points,np.ones(len(points))))@k.T
        return raw[:,:2]/raw[:,2,None]
    error=np.maximum(np.linalg.norm(pixels(projected_a)-a[keep][valid],axis=1),
                     np.linalg.norm(pixels(projected_b)-b[keep][valid],axis=1))
    good=error<2.
    if good.sum()<30:
        return {**report,"reason":"unstable_triangulation","inlierPoints":int(good.sum())}
    return {**report,"status":"sparse_3d_pair","reason":"reprojection_checked_correspondences",
        "inlierPoints":int(good.sum()),"medianParallaxDegrees":median_parallax,
        "medianReprojectionErrorPixels":float(np.median(error[good])),
        "rotationCameraAToB":rotation.tolist(),"translationCameraAToBUnitScale":translation.ravel().tolist(),
        "pointsCameraAUnitScale":xyz[valid][good].tolist(),
        "limitations":["Unknown metric scale; no distances in metres are inferred.",
            "Requires validated calibration and distortion correction; excludes arbitrary zoom changes.",
            "Pairwise sparse geometry has no global bundle adjustment, loop closure or semantic understanding.",
            "A moving object, glass, repeated patterns or low texture can invalidate a reconstruction."]}


def reconstruct_images(image_a,image_b,intrinsics,feature="orb"):
    if image_a.shape != image_b.shape:
        raise ValueError("Pair must use the same verified image geometry")
    if feature not in ["orb","sift"]: raise ValueError("Unsupported feature experiment")
    detector=cv2.ORB_create(nfeatures=2000) if feature=="orb" else cv2.SIFT_create(nfeatures=2000)
    ka,da=detector.detectAndCompute(image_a,None); kb,db=detector.detectAndCompute(image_b,None)
    if da is None or db is None:
        return estimate_pair(np.zeros((0,2)),np.zeros((0,2)),intrinsics)
    matcher=cv2.BFMatcher(cv2.NORM_HAMMING if feature=="orb" else cv2.NORM_L2)
    forward=matcher.knnMatch(da,db,k=2); reverse=matcher.knnMatch(db,da,k=2)
    reverse_pairs={(m.trainIdx,m.queryIdx) for matches in reverse if len(matches)==2 for m,n in [matches] if m.distance < .75*n.distance}
    matches=[m for candidates in forward if len(candidates)==2 for m,n in [candidates]
             if m.distance < .75*n.distance and (m.queryIdx,m.trainIdx) in reverse_pairs]
    a=np.array([ka[m.queryIdx].pt for m in matches]).reshape(-1,2)
    b=np.array([kb[m.trainIdx].pt for m in matches]).reshape(-1,2)
    return estimate_pair(a,b,intrinsics)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("frame_a",type=Path); parser.add_argument("frame_b",type=Path)
    parser.add_argument("calibration",type=Path,help="Explicit verified calibration JSON, not guessed field of view")
    parser.add_argument("--output",type=Path,default=Path("artifacts/composition-research/environment-pair.json"))
    args=parser.parse_args(); calibration=json.loads(args.calibration.read_text())
    if calibration.get("verifiedForTheseFrames") is not True or calibration.get("distortionCorrected") is not True:
        raise ValueError("Calibration and distortion correction must be independently verified first")
    frames=[cv2.imread(str(path),cv2.IMREAD_GRAYSCALE) for path in [args.frame_a,args.frame_b]]
    if any(frame is None for frame in frames): raise ValueError("A supplied frame could not be read")
    if frames[0].shape[::-1] != tuple(calibration["imageSize"]): raise ValueError("Calibration pixel dimensions mismatch")
    result=reconstruct_images(*frames,calibration["cameraMatrix"])
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2),encoding="utf-8")
    print(json.dumps({k:v for k,v in result.items() if k != "pointsCameraAUnitScale"},indent=2))


if __name__ == "__main__": main()
