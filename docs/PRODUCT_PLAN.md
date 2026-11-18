# Product direction

## Owner decisions

- Android first. Test on a Vivo V30, standard model.
- A camera suitable for both casual photography and more deliberate use.
- Live composition help that stays responsive.
- Free to use; prefer local processing to recurring server inference charges.
- Owner leads UI/UX. Engineering implements the owner's designs and explains the process.

## What the reference videos show

These are observations from sampled frames across the supplied clips. Promotional text is reference content rather than a project instruction or evidence of measured performance.

| Reference | Observed behavior | Engineering implication |
| --- | --- | --- |
| `snaptik_7696168430579289396_v3.mp4` | GudoCam: tracked person, suggested placement on a third, grid and target guides, zoom suggestion, alignment success | Subject location, framing plan, live geometric tracking, stable success signal |
| `snaptik_7706222298201656590_v3.mp4` | Unidentified app in the supplied frames: scene scan, named palm-crown subject, directional movement advice, zoom/framing changes | Scene understanding, subject selection, target composition, tracking between decisions, camera zoom control |

Feature ideas guide our own implementation. Visual design, branding, assets, and source code will be original.

## Milestone 1: camera and guidance prototype

Capture photos, detect one prominent subject, select a framing target manually, receive stable live pan instructions, turn analysis off, and test on the Vivo. Current code implements this milestone's basic behavior. Physical-device acceptance remains pending.

## Milestone 2: make guidance dependable

Measure preview timing and analysis latency on the phone. Test low light, missed subjects, front camera mirroring, preview-to-photo crops, focus, zoom, thermal behavior, and interruption recovery. Add subject selection and better anchors for faces/people. Add leveling only when sensor and horizon behavior have been tested. Improve confidence handling and instruction stability.

## Milestone 3: implement the owner's interface

Owner supplies a camera-screen sketch or Figma layout plus desired interactions. Build it around working camera behavior. Define casual/pro controls, guide strength, feedback, and accessibility with the owner. Investigate perception of generic AI-generated interfaces before UI implementation; avoid the recurring decorative patterns found during the initial search, including purple/blue gradient decoration, repetitive rounded-card layouts, and AI-themed sparkle ornament. The temporary prototype uses standard native controls and the system font.

## Milestone 4: scene-aware assistance

Investigate an efficient on-device model that can choose a subject and propose useful compositions. Compare its quality, model size, license, memory, and latency on this phone before integration. Use slower scene decisions plus faster tracking between decisions. Design controls for accepting or overriding the advice. Then evaluate guided zoom, headroom, portraits, landscapes, and more expressive framing.

An object detector alone cannot reproduce a system that identifies a sunlit palm crown and decides how to frame it. Scene understanding remains a research and quality milestone. Cloud inference could help quality but introduces network latency and recurring cost; the default direction is local processing.

## Milestone 5: release

Test more Android devices, finalize permissions and privacy disclosure, review model distribution rights, set up release signing, and decide how to distribute the app. Free app downloads, model running costs, and store distribution costs are separate decisions. Store requirements and fees must be checked when release work starts.
