# Test on the Vivo V30

## Quickest route: install the APK

1. Copy `artifacts/CameraCompositor-debug.apk` to the phone over USB, Quick Share, or another file-transfer method you already use.
2. Open the APK from the phone's Files app. If Android asks, allow that file manager to install this app. You can revoke that permission afterward.
3. Install **Camera Compositor** and open it.
4. Grant camera access. Keep internet available for the first launch so the subject detector can download its model.
5. Point at a clearly separated object in decent light. The first detection can take several seconds. If necessary, close and reopen the app after the model download finishes.

This is a debug build for development. Each later APK signed with the same local debug key can update it in place. A build from another computer can use a different debug key; Android will then require uninstalling the old development app first.

## Faster iteration with a cable

Android's instructions are here: [Run apps on a hardware device](https://developer.android.com/studio/run/device).

On the Vivo, open Settings and search for the software/build number. In About phone, tapping the software version or build number seven times typically enables Developer options. Exact Funtouch OS menu wording varies. Open Developer options and enable USB debugging.

Connect a USB **data** cable and approve this computer's debugging prompt on the phone. In Android Studio, select the Vivo in the device menu and press Run. For the portable toolchain in this folder, use:

```powershell
./scripts/install-phone.ps1
```

The script requires exactly one authorized phone and installs the current debug APK. It never changes phone security settings for you. Disable USB debugging after testing if you prefer.

## First test session

Record your observations in [DEVICE_TEST_RESULTS.md](DEVICE_TEST_RESULTS.md).

1. **Permission:** launch without granting camera access, then grant it from Camera access. Confirm the preview appears.
2. **Capture:** take a photo. Confirm it opens with Last photo and exists in your gallery under Pictures/CameraCompositor. Guides should appear only in the app, not the photo.
3. **Target:** choose Center. Frame a clear object, then pan right and left. Check the box follows the object and the instructions lead back to the target. Repeat on left and right thirds.
4. **Alignment:** hold the subject at the target. Success should appear after about 650 ms. Move away and verify success clears.
5. **Clipping:** move close enough to cut off the subject. Guidance should ask for space.
6. **Lost subject:** point at an empty surface. The old box and green success state should clear promptly.
7. **Gestures:** tap a subject to focus and pinch to zoom. Confirm the outline follows the visible object after zooming.
8. **Front camera:** flip, test tracking, capture, and compare preview/photo orientation. The front preview may be mirrored while the photo is saved in the camera's normal orientation.
9. **Lifecycle:** open another app, lock/unlock the phone, and return. Check that preview and capture recover.
10. **Offline:** after detection has worked online, enable airplane mode and test again.
11. **Load:** run for five minutes with Guide on, then off. Record perceived smoothness and heat. Performance targets will be set from measurements.

Known scope limits: one prominent object, no tap-to-select subject yet, no horizon detection, portrait screen only, and geometric alignment rather than scene-aware art direction. A generic object detector can miss small, featureless, distant, or overlapping subjects. Capture remains useful even when guidance has no detection.
