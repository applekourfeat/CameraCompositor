# Third-party components

The APK includes upstream notices in `assets/third-party/`.

- **CameraX and AndroidX** handle the camera preview, focus, zoom, photo capture, and Android permissions. [AndroidX source and Apache 2.0 license](https://github.com/androidx/androidx).
- **OpenCV 4.12.0** follows matching points in the camera preview so the framing outline can track the scene as you move. [Source and Apache 2.0 license](https://github.com/opencv/opencv/tree/4.12.0).
- **Kotlin 2.3.20** is the language the Android app is written in. [Runtime source and notices](https://github.com/JetBrains/kotlin/tree/v2.3.20/license).
- **ONNX Runtime 1.23.2** runs the AI models on the phone’s CPU. The app disables its telemetry. [Source and MIT notices](https://github.com/microsoft/onnxruntime/tree/v1.23.2).
- **NanoDet Plus** finds objects and gives the app a box around each detected subject. [Pinned model and Apache 2.0 license](https://github.com/opencv/opencv_zoo/tree/48b10fa505aeb51fbd127fc60a5c660040e823b7/models/object_detection_nanodet).
- **TinyCLIP ViT-8M** helps recognize parts of the scene, such as trees and buildings. [Pinned model and MIT license](https://huggingface.co/wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M/tree/a2a8c6eaa2549ad66eb7c31b85022bf58273a26c), [upstream code notice](https://github.com/microsoft/Cream/blob/main/TinyCLIP/LICENSE).
- **GAIC** scores possible framings in the private test build. [Author’s source](https://github.com/HuiZeng/Grid-Anchor-based-Image-Cropping-Pytorch), [code notice](../research/GAIC-LICENSE.txt).
- **ZipDepth** estimates which parts of a photo are closer or farther away for the research sweep. [Pinned model and MIT license](https://github.com/fabiotosi92/ZipDepth/tree/91f3fd21e131641f51e8d35736d1958350180e3a).
- **Google ARCore 1.57.0** is an optional tool for testing camera movement and depth on supported phones. The app includes `ARCore-NOTICE.txt`. [Terms](https://developers.google.com/ar/develop/terms), [privacy requirements](https://developers.google.com/ar/develop/privacy-requirements).
- **Google Gemini** gives framing suggestions in an optional cloud experiment. **OkHttp 4.12.0** sends those requests and handles timeouts. The main camera works offline. [Cloud setup](AI_SETUP.md), [OkHttp source and Apache 2.0 license](https://github.com/square/okhttp/tree/parent-4.12.0).

The GAIC model files are private debug assets. Permission to redistribute the checkpoint and benchmark photos remains unverified. [Model sources and research results](COMPOSITION_RESEARCH.md).
