# Optional cloud research diagnostics

The primary local research comparison in version 0.3 requires no API key. The setup below applies only to the old online path, now in Options > Cloud research scan (personal key).

The saved personal key remains encrypted in Android Keystore. It is absent from the APK, source, diagnostics and GitHub. Never paste it into chat. The default model is `gemini-3.8-flash`; model existence and free quota must be verified for the actual project.

## Investigate the reported timeout

1. Install the research APK over the existing app. The existing encrypted key should remain available with the same signing key and package.
2. Open Options > Test service with a generated image. Confirm only if you want to spend one research scan attempt on this connectivity check. It sends a generated chart, not the camera view, using the same structured composition request.
3. Open Options > Scan diagnostics and Copy report. It records fixed event names, millisecond offsets, total elapsed time, result category and HTTP code. It contains no photo or credential.
4. If the chart works, optionally try Cloud research scan with the separate camera-upload disclosure. A valid chart response establishes connectivity and parsing, not photographic quality.

Events distinguish DNS, connection, TLS, upload, waiting for response, download and parsing. Connection has a 10-second timeout, writes 10 seconds, reads 30 seconds, and the complete scene call has a 35-second deadline. Cancel guide aborts the request. There are no automatic retries. HTTP 429 reports exhausted quota and preserves local camera tools.

The old popup confirms a timeout in the online planning path. It does not identify whether the network, provider latency or another transport stage caused it. Increasing a timeout would not establish reliable composition. The instrumented phone request and its report remain necessary for that diagnosis.

The metadata-only Check key and model button establishes access to a model description. It cannot establish successful image inference. Each online request or chart test consumes one of the existing personal-build allowances: 20 attempts per day, at least 15 seconds apart. These phone preferences are development guardrails, not server-enforced spending limits.

## Public app direction

Users of the local feature already need no credentials. For optional online features, see [the managed Firebase evaluation](CLOUD_GATEWAY.md). A production app should remove personal-key setup once a tested app-owned gateway exists. No provider secret should be embedded in a distributed APK. Free-tier image data treatment and limited shared capacity still apply.
