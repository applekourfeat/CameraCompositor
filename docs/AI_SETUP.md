# Set up the personal scene planner

The owner created a Google AI Studio key named **CameraCompositor** on 18 November 2026. Its value belongs only in the phone's private setup field. No key is supplied in this repository or APK.

1. Install the new APK and open Camera Compositor.
2. On the phone, open [Google AI Studio API keys](https://aistudio.google.com/api-keys) with the same account used on the computer.
3. Copy your key there. Open the app's **Options > AI setup**, paste the key, and press **Check key and model**. That checks provider metadata without uploading an image. Press Save after access is confirmed. The existing key stays saved if you later open setup and leave the key field blank.
4. Leave the model at `gemini-3.8-flash` for the first test. It was verified as a current stable model in Google's documentation on 18 November 2026. Model IDs and access can change, so the field is editable.
5. Point at a scene and press **Find a composition**. Review the upload disclosure before allowing scene scans.

The app sends one reduced 480-pixel-wide viewfinder image per scan attempt. A 3:4 view is approximately 480 × 640 pixels. It does not send a live video feed or the full-resolution saved photo. Feature tracking, zoom guidance, and capture work locally after the plan arrives.

The default model supports a free tier subject to provider limits and project eligibility. Google may use free-tier submissions for product improvement. Check your project's tier and [current provider pricing](https://ai.google.dev/gemini-api/docs/pricing). The app's 20-attempt daily limit is a local development guardrail, not a guarantee about your Google billing configuration.

## Error messages

| Result | What to check |
| --- | --- |
| Key/project access error | Correct complete key, correct project, permitted Gemini API/model access |
| Model unavailable | Current model ID and account availability |
| Request rejected | Supported model and request format; preserve the exact message/status for debugging |
| Rate limit or quota reached | Wait, then inspect AI Studio usage; the app does not retry automatically |
| Network timeout | Connection quality; retry deliberately after the cooldown |
| Invalid/truncated framing | The app rejects it and offers manual framing instead |

The connection timeout is 10 seconds and response-read timeout is 30 seconds. These are failure limits, not expected scan times. Cancel abandons the request and discards late responses; it cannot recall data already transmitted to Google.

## Public release architecture

This is personal development setup with the owner's own key. A public release should move the provider call behind a backend with authentication, rate limits, a real budget cap, and abuse controls. The public APK must contain no shared provider credential. Free app distribution and the ongoing cost of scene inference are separate decisions.

Provider documentation: [API key security](https://ai.google.dev/gemini-api/docs/api-key), [model](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash), [privacy/terms](https://ai.google.dev/gemini-api/terms).
