# Version 0.4.1 spatial planning prototype

18 November 2026. The owner's 0.4 export confirms that the standard Vivo V30 ran ARCore 1.57.0 and produced camera pose, sparse points and depth. A sanitized capability summary is in `research/results/owner-spatial-capability-v040.json`. The supplied text was truncated after the first visible sample, so total sample count, physical accuracy, stability and full-recording quality remain unknown. No room point cloud, depth grid, session identifier or device timestamp is retained in that diagnostic summary.

## Connected spatial observations

The primary **Find a composition** flow now prefers ARCore for supported rear cameras. It collects six views with SDK-estimated camera pose, per-frame image intrinsics and available depth. The ordinary CameraX image-overlap path remains for other cameras or devices where ARCore availability does not report support. A runtime check still precedes each spatial session; the owner's successful export supersedes the earlier uncertainty caused by absence of the model name from the public device list.

Images are copied from ARCore's synchronized CPU camera stream. Plane strides and offsets are respected when packing YUV420 into NV21 for local color conversion. SDK coordinate transforms map raw CPU-image pixels into the displayed view; rotation is not guessed from the phone model. The scene viewport keeps a 3:4 aspect ratio. A per-view raw-image-to-normalized-view matrix accompanies the raw camera intrinsics.

Available depth is sampled into a view-aligned 8x8 grid through SDK view-to-texture coordinate mapping. This matters on the owner's device: its reported CPU image is 640x480 while its depth image is 160x90. Directly stretching one raw grid onto the other would misalign it. A depth image more than 50 ms from the associated AR frame is omitted; invalid or unavailable samples remain null. Depth is an SDK estimate, most useful in the near field, and is not treated as reliable skyline distance. Sparse-point count is included, with no full point-cloud upload.

The photographic planner receives up to four actual images, the associated SDK pose/depth/intrinsics, observed subject identities and permitted target identities. Coordinates are ARCore session-local: x right, y up, -z forward, quaternion XYZW. Different sessions/cameras are rejected from a shared world frame. Upload metadata uses relative observation time and a request-local session group instead of the phone's uptime or actual session identifier. Null pose/depth remains explicit on the fallback path. Relationships returned by the visual model remain hypotheses.

## Photo-camera handoff and framing

After observation, ARCore releases the camera. CameraX reopens the same exposed camera ID for normal high-resolution photography. The photo camera must visually register to the last spatial image before a planning request proceeds. Analysis prefers 640x480 to match the owner's reported AR stream. An unavailable camera ID restores the normal rear selector rather than trapping the app on an unusable selector.

Registration also matches the field of view. CPU/GPU viewport cropping can differ between ARCore and CameraX. The app measures the reference-view rectangle in the photo preview, adjusts CameraX zoom, and waits for a stable framing match. That measured zoom becomes the baseline for the model's subsequent relative zoom. Camera coordinates and device zoom are never read from the model worker thread. Failed or stale registration cancels the plan.

This architecture keeps actual photographs on the existing full-resolution CameraX capture path. ARCore CPU snapshots are planning observations, not replacement saved photographs. It avoids concurrent camera ownership. The camera handoffs and color/coordinate alignment still need physical verification in this version.

## Constrained guidance

Guide targets remain actual observed views. SDK pose pairs may support rotation return links only within the same session/camera, no more than 20 degrees apart, 1.5 seconds apart and 5 cm apart. These are conservative engineering gates, not a validated movement model. Larger translations require a separate position-guidance implementation; walking and height commands remain absent. The model is given the permitted target IDs, and the parser rejects a guide outside them.

The recorded route and live photo-image tracking guide back toward a selected view. During the CameraX stage, live ARCore world pose is **not** running concurrently. This version is spatial observation followed by visual photo guidance, rather than a persistent live 3D mesh or a fully validated position controller. Lost tracking, manual zoom, cancellation and the 15-second unaccepted-target deadline retain their earlier behavior.

## Try the build

Install `artifacts/CameraCompositor-0.4.1-spatial-debug.apk` over the existing app. Version code is 6 and the signing certificate is unchanged. The stable `CameraCompositor-debug.apk` alias matches it. The older separate spatial probe remains available under Developer tools.

Tap **Find a composition**, accept the revised image/pose/depth upload notice if desired, and turn gently across a small overlapping scene. Hold the final view while the photo camera matches it and assessment runs. Review a selected view before using its guide. The saved personal research key is retained. The public product's managed keyless planner remains unimplemented.

The earlier online timeout remains unresolved. This version changes the scene evidence supplied to the planner; it does not prove that the provider will respond from the phone. No actual provider request has been made from this desktop with the owner's key. Quota and timeout responses still restore camera controls without retries. No paid service or billing project was enabled.

## Verification

The build, **55 Android unit tests**, Android lint with **zero errors/90 warnings**, and **20 Python research tests** pass. New tests cover actual spatial metadata serialization, private session-ID exclusion, invalid poses/depth, coordinate-frame consistency, conservative return links, current-view retention and calibrated relative zoom. Existing transport tests still cover quota, response size, cancellation and sanitized diagnostics. Native YUV conversion, SDK projection/depth alignment, CameraX/ARCore camera matching and the full new flow remain physical-device checks.

The APK signature and delivery checks pass. Research weights remain absent from release assets. The private debug APK still contains the legacy GAIC asset for its developer tool, so public redistribution remains gated on its checkpoint permissions. Human preference, subject preservation, the two-second physical selection target, five-minute performance and saved-photo comparison against Vivo's camera remain open. This is an integration prototype, with no claim of qualified photographic quality.
