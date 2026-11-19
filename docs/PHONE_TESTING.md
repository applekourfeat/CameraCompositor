# Vivo V30 research checks

Use the standard Vivo V30. A cable or mirroring session is optional. Transfer the research APK and install it on the phone when convenient. Keep the earlier APKs for comparisons. Results remain pending until actually measured.

## Short first session

Install `artifacts/CameraCompositor-0.8.0-discovery-debug.apk`. This is the ARM64 test build for the Vivo V30. Android 10 or later is required. The ARMv7 file is for 32-bit ARM phones.

1. Open the app and aim broadly at trees or a skyline. Follow the arrow when it appears. Check for newly visible content, a fresh assessment after the turn, and whether the final view keeps a subject you would actually photograph.
2. Try **Thirds** and **Centered** from the same starting angle. Use **Skip shot** if the subject makes little sense. Try a tap to choose a different subject, then check that a scan respects it.
3. Move slightly or cover then uncover the lens briefly during guidance. Check whether it recovers and whether the countdown restarts with a verified view. Open **Options** during another countdown and check that no exposure starts. Tap **Auto shutter on** to switch it off and check that guidance continues.
4. In a backlit scene and a dim room, compare **Camera JPEG** with any available manufacturer HDR or Night option. Take a matched shot with Vivo's camera app. Check saved color, highlights, noise and framing. The status beside **Last photo** and **Camera and scan details** record the processing path.

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
