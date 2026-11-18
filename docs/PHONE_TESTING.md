# Vivo V30 research checks

Use the standard Vivo V30. A cable or mirroring session is optional. Transfer the research APK and install it on the phone when convenient. Keep the earlier APKs for comparisons. Results remain pending until actually measured.

## Short first session

Install `artifacts/CameraCompositor-0.4.1-spatial-debug.apk`. This is an experimental implementation, with no physical photographic-quality approval. The owner's 0.4 export already confirms ARCore pose/point/depth output on the standard Vivo; the integrated spatial-photo flow remains to test.

1. Tap **Find a composition**, allow the revised image/pose/depth upload only if desired, and turn gently across a small overlapping scene. Supported rear cameras collect six spatial observations in a separate ARCore view. Hold the final view while CameraX reopens, matches its field of view and requests assessment. The saved personal research key is reused. Record keep, observe-more, a selected view, alignment failure, timeout or another failure.
2. For a selected view, test **Use this view** and **Dismiss**. A target outside the current field of view should guide through the recorded intermediate views. Pinching cancels the environment route. Cancel, photo capture, gallery/background and camera switching must restore controls. Tracking loss should clear a guide after three seconds.
3. Open **Options > Developer tools > Test spatial tracking (ARCore)**. Report the actual support state for the standard Vivo V30. If supported, try a five-second recording near textured nearby objects. Pose/point/depth export is optional and contains no camera images. Returning must restore the normal camera. This isolated spatial test is not yet fused into the photographic planner.
4. **Options > Developer tools > Camera capability report** inventories exposed cameras, JPEG sizes, calibration, depth formats and vendor processing. Record Android/Funtouch version too. Optional legacy local crop ranking and the manual guide remain under Developer tools.
5. The explicit **Record a local evaluation sweep** still writes eight JPEGs and approximate relative device-rotation readings. Inspect before export. They are not calibrated camera pose or translation. The generated-chart cloud diagnostic uses the older single-image request and can investigate the still-unresolved timeout separately from multi-view inference.

## Composition acceptance

Use the local browser comparison tool on the frozen development/evaluation images. Record which starting views already have good composition, then compare framings with model labels hidden. Download the real answers. Missing judgments remain pending. Ties do not count as improvements. At least 70% of improvable held-out scenes must prefer the suggestion, with enough improvable cases to support that conclusion.

The fixed crop control in that tool is a limited static baseline. To compare the actual old heuristic, make matched physical captures with the version 0.1 APK and this research build from the same starting scene and phone position. Preserve the intended subject and equivalent lens/zoom. Randomize the final photo order before judging. Do not replace that comparison with the static crop control's result.

Include buildings, streets, landscapes, plants and objects. Add clutter, nearby subjects, people near edges, motion, backlight and real low light. The first dataset is scenery-heavy. Reject subject cuts and arbitrary zoom even when the model score increases. Physical viewpoint and subject-preservation behavior need real examples beyond the unit-tested policy.

## Camera-quality comparison

Use a fixed support and take pairs in the app and Vivo's stock camera. Match lens, framing, zoom, aspect ratio, lighting and focus point. Test standard capture, exposed Auto/HDR, and Night separately. Take at least three pairs each of daylight detail, backlit highlights and low-light scenes so transient exposure differences are visible.

Compare saved photo dimensions, detail, noise, highlights, color and framing at equal display scale. Inspect EXIF exposure/ISO and capture time. Preview appearance alone cannot establish saved-photo quality. A reported vendor extension is a capability, not proof that its result matches the stock camera.

## Five-minute performance session

Separate cold model load from warm selection time. Target selection within two seconds after observation. Record repeated scans and a five-minute preview/guidance session, checking responsiveness, thermal warnings, camera interruptions and crashes. Scan diagnostics exposes bounded analyzer timing samples; that statistic is not rendered preview FPS. Measure preview behavior separately before claiming an FPS target.

Check front-camera mirroring, phone rotations, camera switching, denied/restored permission, background/lock recovery, cloud timeout/quota and photo saving. Record elapsed time, build, scene and actual result in DEVICE_TEST_RESULTS.md. A successful desktop build or ONNX parity run cannot fill in those rows.
