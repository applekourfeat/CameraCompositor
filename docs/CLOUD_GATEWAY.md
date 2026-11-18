# AI access without user API keys

Date checked: 18 November 2026. No Firebase project was provisioned or deployed during this research. No payment method or billing account was linked. The current primary local research feature already runs without a user API key.

## Evaluated route

Use the Android Firebase AI Logic SDK with the Gemini Developer API, a dedicated Firebase **Spark** project with **no linked Cloud Billing account**, and enforced App Check. Current [Firebase pricing documentation](https://firebase.google.com/docs/ai-logic/pricing) describes this no-payment-method free tier as limited access to certain models. Linking billing upgrades the project to pay-as-you-go and changes that constraint.

Firebase's [authorization FAQ](https://firebase.google.com/docs/ai-logic/faq-and-troubleshooting) explains that its managed service account authorizes provider calls. Users would install the app and allow an optional image upload; they would not create or paste provider credentials. Firebase app configuration identifies the project and is different from a provider secret. Enforce [App Check](https://firebase.google.com/docs/ai-logic/app-check) before distributing the app. Verify the [Play Integrity requirements](https://firebase.google.com/docs/app-check/android/play-integrity-provider) for both Play-distributed and sideloaded installations; debug bypass tokens belong only to a development build.

## Concrete deployment constraints

- Keep local camera, local suggestions and local tracking independent of the service.
- Create a separate unbilled project rather than silently reusing an existing billed project.
- Allow only a tested free-tier image-input model. `gemini-3.8-flash` is a candidate supported by current documentation, not a guarantee of project access or capacity.
- Set the gateway's per-user request allowance to 2 requests per minute initially. Provider-wide quotas still apply. Firebase's [quota documentation](https://firebase.google.com/docs/ai-logic/quotas) distinguishes these shared project quotas from per-user controls. Do not describe this as an enforced daily allowance.
- A timeout, rejected attestation, missing configuration or exhausted quota returns a typed unavailable result. Preserve local controls and use no silent paid-provider fallback.
- Preserve the explicit image-upload disclosure and cancellation path. Never stream the preview by default. Do not send saved full-resolution photos for composition planning.
- Test the signed build with enforcement enabled, including an unauthorized caller, exhausted quota and offline operation. Confirm the project remains on Spark without linked billing before sharing it.

These limits can make optional assistance available for a small test audience at $0. They cannot promise unlimited shared cloud inference. The model allowance and capacity must be checked in the actual project at deployment time. A budget alert on a billed project would not satisfy the chosen strict-$0 constraint.

## Image-processing terms

The [Gemini pricing page](https://ai.google.dev/gemini-api/docs/pricing) distinguishes free-tier and paid-tier data treatment and links the current terms. Free-tier submissions may be used to improve Google's products. An app-owned gateway does not change the provider's treatment of submitted images. The existing research consent explains that possibility. A public deployment needs its actual provider, region, retention/data-use terms and applicable age/region restrictions checked before enabling uploads. Do not promise automatic provider deletion based on a competitor's privacy policy.

The recommended sequence is to validate local photographic quality, validate optional online quality separately, then configure and test the managed gateway. A working service endpoint alone does not establish composition quality.
