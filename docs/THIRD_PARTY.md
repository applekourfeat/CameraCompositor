# Third-party components

The APK includes upstream license/notice texts in `assets/third-party/`.

- CameraX and AndroidX: [AndroidX source](https://github.com/androidx/androidx), Apache 2.0.
- OpenCV 4.12.0: [source and license](https://github.com/opencv/opencv/tree/4.12.0), Apache 2.0. Used for local feature tracking.
- Kotlin runtime: [source and notices](https://github.com/JetBrains/kotlin/tree/v2.3.20/license), Apache 2.0 and the accompanying upstream notices.
- OkHttp 4.12.0: [versioned source](https://github.com/square/okhttp/tree/parent-4.12.0), Apache 2.0. Used for bounded, cancellable research service requests.
- ONNX Runtime 1.23.2 runs the on-device models on the CPU. The project disables runtime telemetry. [Versioned source and MIT notices](https://github.com/microsoft/onnxruntime/tree/v1.23.2).
- ZipDepth runs from RGB and estimates relative inverse depth. The MIT notice in the APK matches the pinned repository containing the source and included mobile checkpoint. The project converts that checkpoint to ONNX locally. [Pinned source, weights and license](https://github.com/fabiotosi92/ZipDepth/tree/91f3fd21e131641f51e8d35736d1958350180e3a).
- NanoDet Plus supplies subject boxes from one observed frame. The pinned OpenCV Zoo directory explicitly licenses all files there under Apache 2.0. The app copies the notice and removes frozen weights from the ONNX input list so ONNX Runtime can optimize them. [Pinned model, implementation and license](https://github.com/opencv/opencv_zoo/tree/48b10fa505aeb51fbd127fc60a5c660040e823b7/models/object_detection_nanodet).
- TinyCLIP ViT-8M supplies local patch similarities for trees, architecture, and other scene regions. The pinned checkpoint's model card declares MIT. The export includes the vision tower and fixed concept vectors. The APK includes the upstream MIT notice. [Pinned weights and model card](https://huggingface.co/wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M/tree/a2a8c6eaa2549ad66eb7c31b85022bf58273a26c), [upstream code notice](https://github.com/microsoft/Cream/blob/main/TinyCLIP/LICENSE).
- Google Gemini is an optional remote service under the owner's Google account and provider terms. No Gemini model weights or credentials are included in the APK.
- Optional Google ARCore SDK 1.57.0: its published Google Maven POM identifies [ARCore Additional Terms of Service](https://developers.google.com/ar/develop/terms), together with Google's API terms and [privacy requirements](https://developers.google.com/ar/develop/privacy-requirements). The APK includes `ARCore-NOTICE.txt` and in-app disclosure before the isolated test. Camera pose, sparse points and supported local depth use Google Play Services for AR. Cloud Anchors, Geospatial APIs and location access are absent. A mature SDK's presence does not establish support or calibrated accuracy on the owner's Vivo.

GAIC and the large models from earlier experiments are excluded from the current test APKs. The old GAIC checkpoint is available for private opt-in debug builds from ignored `.local/research-assets`. The GAIC code notice is preserved in `research/GAIC-LICENSE.txt`. [Author's source](https://github.com/HuiZeng/Grid-Anchor-based-Image-Cropping-Pytorch).

GAIC checkpoint redistribution and its dataset image rights remain unverified. NIMA weights, benchmark photos and the older semantic models stay outside the delivered APKs and Git. ShutterMuse and SPAS inform the research. [Provenance and earlier model results](COMPOSITION_RESEARCH.md).
