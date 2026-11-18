# Test version 0.2 on the Vivo V30

Copy `artifacts/CameraCompositor-0.2-debug.apk` to the phone and open it in Files to install the update. USB debugging and phone mirroring are optional. The new build uses the same application ID and debug signing key as the first prototype.

## Test the camera first

1. Open the app, grant camera permission, tap to focus, and pinch to zoom.
2. Take a photo. Open Last photo and verify the saved image. The footer reports its pixel dimensions and processing path.
3. Under **Options > Camera and scan details**, inspect which manufacturer Auto/HDR/Night modes the phone exposes.
4. Use **Options > Photo processing** to compare Standard quality with Automatic manufacturer processing. Night appears only if supported. Manufacturer processing may briefly restart the preview at the shutter, because analysis pauses for that capture.
5. Photograph the same static scene in the Vivo stock app and this app, at similar lens/zoom/exposure, in daylight and low light. Compare saved photos rather than screenshots of their previews. Record detail, highlights, shadows, noise, color, focus, and shutter delay.

Quality priority and high-resolution JPEG are enabled. Access to Vivo's private processing depends on the APIs Vivo exposes. Exact stock-camera parity remains unverified.

## Test the visual guide without API setup

1. Choose **Options > Try the framing guide without AI**.
2. Inspect the nine framing previews and select one with the number picker. This is your choice, not an AI recommendation.
3. Follow the outlined frame and arrow. When aim is close, automatic zoom should adjust smoothly. Pinch if you want manual zoom control.
4. Hold alignment to get the settled signal, then press the shutter.
5. Move away and return to the scene. Tracking should reacquire the existing plan rather than switch to a different subject. A short gap dims the outline; a longer gap hides the live outline while keeping the target thumbnail.
6. Cancel guide. Confirm automatic zoom stops and the original scan zoom is restored.

Tracking follows scene features and assumes much of the scene is consistent. Fast movement, blur, low texture, repetitive patterns, independent moving subjects, and large depth/perspective changes can still defeat it.

## Test scene selection

Follow [AI_SETUP.md](AI_SETUP.md). Then test Find a composition on a portrait scene, a building/landscape, and a close object. Check that its proposed framing and explanation match the image, keep important edges, and improve the photograph according to your intent. Note the request time in Camera and scan details. Test cancellation during a request and after a plan arrives.

The model chooses a crop inside the scanned view and a relative zoom up to 1.8×, limited further by the camera's available zoom. It cannot look outside the view or physically move the phone. There is no automatic shutter.

## Recovery and load

Test camera access denial/recovery, rear/front switching, front-preview mirroring versus saved orientation, app background/return, lock/unlock, network failure, and five minutes of guide use. After receiving a plan, disconnect the internet and verify tracking continues locally. Capture and manual framing should also work offline.

Record results in [DEVICE_TEST_RESULTS.md](DEVICE_TEST_RESULTS.md). Automated builds do not establish camera quality, actual tracking alignment, API account access, or Vivo scan speed.

For later cable-based testing, see [Android's hardware-device instructions](https://developer.android.com/studio/run/device) and `scripts/install-phone.ps1`.
