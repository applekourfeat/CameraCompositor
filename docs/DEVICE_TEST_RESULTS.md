# Physical-device test results

Version 0.1 owner feedback: the basic app ran, but detection sometimes stopped momentarily and the pan-left/right UX felt poor. No timing, camera-quality comparison, or manufacturer-capability measurements were supplied.

Version 0.2 owner feedback: the manual framing guide appeared to work; the AI scan failed with `Scene scan could not finish: timeout`. No provider response, transport-stage timing, paired stock-camera photos or physical performance measurements were available. This is owner-reported behavior, not an automated device run.

Version 0.3 owner feedback: the local model ran on the Vivo, with 62 ms reported in text and 318 ms visible in a screenshot; it selected the full current view on the obstructed window scene. The owner found the flow confusing and the result poor. Cold/warm status was not established. The generated-image cloud test timed out in WAIT_RESPONSE at 30,189 ms, with client upload ending at 187 ms and no HTTP response code. See V031_USABILITY_FIX.md and the preserved sanitized report.

Version 0.3.1 status: source and regression checks address the reported control-state and popup problems. Physical verification of this correction is pending. The Vivo remains unavailable for a debugging connection.

| Item | Result / notes |
| --- | --- |
| Phone / Android / Funtouch OS version | Vivo V30 / pending / pending |
| Build version | Owner screenshots: 0.3.0-research; usability update: 0.3.1 |
| Camera permission and recovery | Pending |
| Rear preview / focus / zoom | Rear preview visible in owner screenshots; focus/zoom measurements pending |
| Photo saving and gallery | Pending |
| Tracking and guide direction | Pending |
| Subject lost / alignment reset | Pending |
| Front camera and mirroring | Pending |
| Background / lock recovery | Pending |
| Personal API setup / first scene scan | Pending |
| Manual guide / offline tracking | Pending |
| Manufacturer Auto / HDR / Night exposure | Pending |
| Five-minute heat / responsiveness | Pending |
| Preview-to-photo framing match | Pending |
| Local GAIC cold/warm timing and numerical parity | Runs on Vivo; reported 62 ms and screenshot 318 ms. Cold/warm classification and parity pending |
| Eight-view local sweep and export | Pending |
| Generated-image diagnostic / failing network stage | Owner report: timeout waiting for response, 30,189 ms, no HTTP code; underlying cause unresolved |
| Matched Vivo stock-camera quality comparison | Pending |
| Human preference / old heuristic comparison | Pending |

Automated compilation and unit tests validate code and geometric rules. They do not establish camera quality, runtime model availability, coordinate alignment, or performance on the physical phone.
