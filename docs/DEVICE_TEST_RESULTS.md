# Physical-device test results

Version 0.1 owner feedback: the basic app ran, but detection sometimes stopped momentarily and the pan-left/right UX felt poor. No timing, camera-quality comparison, or manufacturer-capability measurements were supplied.

Version 0.2 owner feedback: the manual framing guide appeared to work; the AI scan failed with `Scene scan could not finish: timeout`. No provider response, transport-stage timing, paired stock-camera photos or physical performance measurements were available. This is owner-reported behavior, not an automated device run.

Version 0.3 owner feedback: the local model ran on the Vivo, with 62 ms reported in text and 318 ms visible in a screenshot; it selected the full current view on the obstructed window scene. The owner found the flow confusing and the result poor. Cold/warm status was not established. The generated-image cloud test timed out in WAIT_RESPONSE at 30,189 ms, with client upload ending at 187 ms and no HTTP response code. See V031_USABILITY_FIX.md and the preserved sanitized report.

After version 0.3.1 delivery, the owner supplied clear city screenshots with the status "No framing suggestion. Try another angle." The owner reports finding proposals only after moving and repeatedly pressing Find, with poor results. Exact build metadata, complete control-state testing and numerical measurements are absent from these new screenshots. [Environment research](ENVIRONMENT_COMPOSITION_RESEARCH.md) reproduces the full-view selection on their image. The Vivo remains unavailable for a debugging connection.

Version 0.4.0-environment implements a primary six-view scene observation/planning flow and a separate optional ARCore spatial test. It includes the new image timestamps/transforms and camera-calibration/depth inventory. No new recording, environment-guidance session or spatial test has been measured on the Vivo. Standard V30 ARCore compatibility and the old provider timeout remain unconfirmed/unresolved respectively. Desktop ETH3D temporal motion estimates are separate development evidence, not phone measurements. [Build scope and remaining work](V040_ENVIRONMENT_PROTOTYPE.md).

New owner evidence supersedes that earlier ARCore uncertainty: the 0.4 export reports `outcome: recorded`, `depthEnabled: true`, camera pose, sparse points and nonempty depth samples. The first visible sample has CPU image size 640x480 and depth size 160x90. The supplied export is truncated, so its total sample count and full-session quality are unknown. This confirms SDK output on the standard Vivo V30, not physical accuracy. Version [0.4.1-spatial](V041_SPATIAL_PLANNING.md) now attaches corresponding spatial evidence to the primary planner; that integrated camera handoff, registration, AI response and photo guidance remain untested on the phone.

| Item | Result / notes |
| --- | --- |
| Phone / Android / Funtouch OS version | Vivo V30 / pending / pending |
| Build version | Earlier owner screenshots recorded; 0.4.0-environment physical test pending |
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
| Six-view planning / measured return route | Pending |
| Native temporal tracker and observation throughput | Pending |
| ARCore availability / actual pose and depth | Owner 0.4 export confirms session pose, sparse points and available depth; accuracy pending |
| 0.4.1 spatial capture, CameraX framing match and integrated planning | Pending |

Automated compilation and unit tests validate code and geometric rules. They do not establish camera quality, runtime model availability, coordinate alignment, or performance on the physical phone.
