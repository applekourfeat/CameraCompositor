# Version 0.3.1 usability correction

The owner found version 0.3 confusing and rejected its full-view result on a window scene containing a curtain and buildings. The local model ran on the Vivo: the owner reported 62 ms, and a screenshot displayed 318 ms. Cold/warm status and sustained performance were not established. These are real owner observations, not a passed photographic-quality gate.

## Corrected behavior

- A full-view result means no framing suggestion. It produces a brief inline status, with no report popup or assertion that the photograph is good.
- Camera control state renders whenever ready/scanning/saving/guiding changes. Full-view, dismissal, timeout and cancellation paths restore the buttons automatically. Previously, full-view and dismissal could leave the shutter disabled and Cancel guide visible after the scan had ended.
- The shutter stays available during a background scan. Taking a photograph cancels that scan before starting capture. Late results are discarded through the existing generation check.
- Cloud failures appear as short inline status messages. They no longer open a blocking error dialog or offer an unrelated numbered-crop fallback.
- A proposed crop still needs explicit acceptance. Its comparison dialog contains two images and Use frame / Dismiss, with no latency or research report text.
- The main screen says Camera / Find a frame. Options contains Photo processing, About this build and Developer tools. Existing service setup, reports and research recordings remain in Developer tools. The owner continues to lead final visual design.

Six new regression tests exercise the state notifications and framing interpretation. The existing model and nine-crop search are unchanged. This update corrects workflow and state bugs; useful subject/angle discovery remains an unsolved model requirement.

The Android debug build, all 35 unit tests and lint passed (zero errors, 45 warnings). The 0.3.1 APK is 75,023,617 bytes; SHA-256 `3c18aada6d9c43de66c656ce9acc578114a37d45b52026a8410fd81a1541aa26`. Physical verification of these UI changes remains pending. The owner-provided 62/318 ms timings and cloud trace belong to 0.3.0.

## Cloud evidence

The owner's generated-image report reaches WAIT_RESPONSE at 187 ms and times out at 30,189 ms without an HTTP code. It is preserved in `research/results/owner-network-diagnostic-v030.json`. The camera scene is not required to reproduce the failure. That evidence narrows the stage to waiting for response headers; it does not identify the network, provider, key or model as the cause.

A separate Windows HTTP/1.1 probe, with no credential or camera image, received expected 403 authentication rejections in 230 ms for GET and 172 ms for POST. This demonstrates desktop reachability for unauthenticated requests only. It does not validate authenticated inference or the Vivo's network path. The probe is reproducible with `research/probe_public_endpoint.py`; it contains no key and triggers no authenticated inference.

The screenshots' dialogs cover much of the source camera view. They establish bad behavior but cannot supply an exact unobscured input image for model evaluation. No popup region was removed, inpainted or treated as a real camera frame. A saved scene photograph is needed for a faithful model regression case.
