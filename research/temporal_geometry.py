"""Track correspondences through every frame before estimating camera geometry."""
import cv2
import numpy as np
from environment_geometry import estimate_pair


def track_sequence(frames, intrinsics):
    if len(frames)<2 or any(im.shape != frames[0].shape for im in frames):
        raise ValueError("At least two frames with fixed image geometry are required")
    seed=cv2.goodFeaturesToTrack(frames[0],maxCorners=800,qualityLevel=.01,minDistance=8,blockSize=7)
    if seed is None: return estimate_pair(np.empty((0,2)),np.empty((0,2)),intrinsics)
    origin=seed.reshape(-1,2); live=origin.copy()
    counts=[len(live)]
    for previous,current in zip(frames,frames[1:]):
        if len(live)<30: break
        next_points,status,error=cv2.calcOpticalFlowPyrLK(previous,current,live[:,None,:],None,
            winSize=(21,21),maxLevel=3,criteria=(cv2.TERM_CRITERIA_EPS|cv2.TERM_CRITERIA_COUNT,30,.01))
        returned,back_status,_=cv2.calcOpticalFlowPyrLK(current,previous,next_points,None,
            winSize=(21,21),maxLevel=3,criteria=(cv2.TERM_CRITERIA_EPS|cv2.TERM_CRITERIA_COUNT,30,.01))
        next_points=next_points.reshape(-1,2); returned=returned.reshape(-1,2)
        keep=(status.ravel()!=0)&(back_status.ravel()!=0)&(error.ravel()<30)&(np.linalg.norm(returned-live,axis=1)<1)
        keep&=(next_points[:,0]>=0)&(next_points[:,1]>=0)&(next_points[:,0]<current.shape[1])&(next_points[:,1]<current.shape[0])
        origin=origin[keep]; live=next_points[keep]; counts.append(len(live))
    if len(counts)!=len(frames):
        return {"status":"observe_more","reason":"temporal_tracks_lost","metricScaleKnown":False,"walkOrHeightGuidanceAllowed":False,"trackCounts":counts}
    return {**estimate_pair(origin,live,intrinsics),"trackCounts":counts}
