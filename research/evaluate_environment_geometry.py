"""Real-image two-view test on a pinned public ETH3D training sequence.

Calibration and camera ground truth are from ETH3D, independently of the estimates.
This benchmark assesses sparse motion geometry, never photographic preference.
No sequence images, point clouds or upstream datasets are committed.
"""
import hashlib
import argparse
import json
import time
import urllib.request
import zipfile
from pathlib import Path
import cv2
import numpy as np
from scipy.spatial.transform import Rotation
from environment_geometry import reconstruct_images

URL="https://www.eth3d.net/data/slam/datasets/cables_2_mono.zip"
SHA256="cf6e2d74a606b021bb90d0426ad3fd651bf9efbc60220d7c462cf1c8836375ee"
PATH=Path(".local/composition-research/cables_2_mono.zip")


def dataset():
    if not PATH.exists():
        partial=PATH.with_suffix(".partial")
        with urllib.request.urlopen(URL,timeout=45) as source,partial.open("wb") as target:
            total=0
            while data:=source.read(1024*1024):
                total+=len(data)
                if total>64_000_000: raise ValueError("Geometry sequence exceeded download bound")
                target.write(data)
        partial.replace(PATH)
    if hashlib.sha256(PATH.read_bytes()).hexdigest()!=SHA256:
        raise ValueError("Geometry sequence archive changed")
    return zipfile.ZipFile(PATH)


def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--feature",choices=["orb","sift"],default="orb")
    args=parser.parse_args()
    cv2.setNumThreads(2)
    with dataset() as source:
        fx,fy,cx,cy=map(float,source.read("cables_2/calibration.txt").split())
        k=np.array([[fx,0,cx],[0,fy,cy],[0,0,1]],np.float64)
        rows=[line.split() for line in source.read("cables_2/rgb.txt").decode().splitlines() if line and not line.startswith("#")]
        ground=np.loadtxt(source.open("cables_2/groundtruth.txt"))
        def frame(index):
            info=source.getinfo("cables_2/"+rows[index][1])
            if info.file_size>2_000_000: raise ValueError("Unexpected image size")
            image=cv2.imdecode(np.frombuffer(source.read(info),np.uint8),cv2.IMREAD_GRAYSCALE)
            if image is None or image.size>2_000_000: raise ValueError("Unexpected image dimensions")
            return image
        def pose(index):
            stamp=float(rows[index][0]); closest=ground[np.argmin(abs(ground[:,0]-stamp))]
            if abs(stamp-closest[0])>.02: raise ValueError("No synchronized ground truth pose")
            return closest[1:4],Rotation.from_quat(closest[4:8]).as_matrix()
        results=[]
        # Fixed before running: every eighth start, six-frame baseline, no best-pair search.
        for index in range(0,len(rows)-6,8):
            a,b=frame(index),frame(index+6); cv2.setRNGSeed(82)
            started=time.perf_counter(); result=reconstruct_images(a,b,k,args.feature); elapsed=(time.perf_counter()-started)*1000
            ca,ra=pose(index); cb,rb=pose(index+6)
            expected_r=rb.T@ra; expected_t=rb.T@(ca-cb)
            row={name:value for name,value in result.items() if name not in ["pointsCameraAUnitScale","rotationCameraAToB","translationCameraAToBUnitScale","limitations"]}
            row.update({"frameIndices":[index,index+6],"desktopMs":elapsed,"groundTruthBaselineMeters":float(np.linalg.norm(expected_t))})
            if result["status"]=="sparse_3d_pair":
                row["rotationErrorDegrees"]=float(np.rad2deg(Rotation.from_matrix(np.asarray(result["rotationCameraAToB"])@expected_r.T).magnitude()))
                row["translationDirectionErrorDegrees"]=float(np.rad2deg(np.arccos(np.clip(np.dot(result["translationCameraAToBUnitScale"],expected_t/np.linalg.norm(expected_t)),-1,1))))
                if not any(r["status"]=="sparse_3d_pair" for r in results):
                    output=Path(f"artifacts/composition-research/environment-first-pair-{args.feature}.json")
                    output.write_text(json.dumps(result,indent=2),encoding="utf-8")
            results.append(row)
    accepted=[r for r in results if r["status"]=="sparse_3d_pair"]
    report={"dataset":URL,"archiveSha256":SHA256,"feature":args.feature,"datasetFrames":len(rows),"pairSelection":"start=0,8,16,...; end=start+6",
        "pairs":len(results),"acceptedPairs":len(accepted),"cameraMatrix":k.tolist(),
        "groundTruthSource":"ETH3D camera-to-world pose; nearest timestamp within 20ms",
        "medianRotationErrorDegrees":float(np.median([r["rotationErrorDegrees"] for r in accepted])) if accepted else None,
        "medianTranslationDirectionErrorDegrees":float(np.median([r["translationDirectionErrorDegrees"] for r in accepted])) if accepted else None,
        "desktopMedianMs":float(np.median([r["desktopMs"] for r in results])),
        "scope":"real images from a calibrated global-shutter laboratory rig; pair geometry only; not live mapping, phone performance or photographic selection",
        "guidanceAssessment":"rejected: substantial pose errors remain despite low reprojection errors",
        "metricScaleRecovered":False,"vivoValidated":False,"releaseApproved":False,"results":results}
    Path(f"research/results/environment-geometry-eth3d-{args.feature}.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
    print(json.dumps({k:v for k,v in report.items() if k!="results"},indent=2))


if __name__ == "__main__": main()
