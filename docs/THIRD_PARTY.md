# Third-party components

The APK includes upstream license/notice texts in `assets/third-party/`.

- CameraX and AndroidX: [AndroidX source](https://github.com/androidx/androidx), Apache 2.0.
- OpenCV 4.12.0: [source and license](https://github.com/opencv/opencv/tree/4.12.0), Apache 2.0. Used for local feature tracking.
- Kotlin runtime: [source and notices](https://github.com/JetBrains/kotlin/tree/v2.3.20/license), Apache 2.0 and the accompanying upstream notices.
- OkHttp 4.12.0: [versioned source](https://github.com/square/okhttp/tree/parent-4.12.0), Apache 2.0. Used for bounded, cancellable research service requests.
- ONNX Runtime 1.23.2: [versioned source](https://github.com/microsoft/onnxruntime/tree/v1.23.2), MIT and bundled third-party notices. Used for the CPU local research scorer. Runtime telemetry is disabled explicitly.
- Google Gemini is an optional remote service under the owner's Google account and provider terms. No Gemini model weights or credentials are included in the APK.

The large language models from the earlier experiments remain excluded. Version 0.3's private debug research build injects the 11.7 MB exported GAIC checkpoint from ignored `.local/research-assets`; release builds exclude research model assets. The GAIC CPU port is derived from the [author's MIT-licensed source](https://github.com/HuiZeng/Grid-Anchor-based-Image-Cropping-Pytorch). Its code notice is preserved in `research/GAIC-LICENSE.txt` and the generated debug assets.

Checkpoint redistribution and dataset-image rights are separately unverified. Do not infer those rights from a source-code license or publish the research APK/model until cleared. NIMA weights/data and the source photos remain outside the APK and Git. Its rejected FP16 export was removed. ShutterMuse and SPAS contribute references rather than shipped weights or source. See [COMPOSITION_RESEARCH.md](COMPOSITION_RESEARCH.md) for provenance, revision/hash records and limits.
