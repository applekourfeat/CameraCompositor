# Product direction

## Owner decisions

- Android first. Test on a Vivo V30, standard model.
- A camera suitable for both casual photography and more deliberate use.
- Live composition help that stays responsive.
- Free to use is the product goal. Live tracking stays local. On-demand cloud scene planning was approved for personal testing after local model experiments failed their quality/speed checks.
- Owner leads UI/UX. Engineering implements the owner's designs and explains the process.

## What the reference videos show

These are observations from sampled frames across the supplied clips. Promotional text is reference content rather than a project instruction or evidence of measured performance.

| Reference | Observed behavior | Engineering implication |
| --- | --- | --- |
| `snaptik_7696168430579289396_v3.mp4` | GudoCam: tracked person, suggested placement on a third, grid and target guides, zoom suggestion, alignment success | Subject location, framing plan, live geometric tracking, stable success signal |
| `snaptik_7706222298201656590_v3.mp4` | Unidentified app in the supplied frames: scene scan, named palm-crown subject, directional movement advice, zoom/framing changes | Scene understanding, subject selection, target composition, tracking between decisions, camera zoom control |

Feature ideas guide our own implementation. Visual design, branding, assets, and source code will be original.

## Current architecture, version 0.2

The owner tested version 0.1 and reported detection gaps and poor directional-text UX. Version 0.2 replaces the detector-target loop with one explicit scene-planning request, local feature tracking, a target-frame preview, a visual arrow, and bounded automatic zoom. Quality-priority capture and runtime manufacturer-extension checks are implemented. The functional layout is temporary; the owner leads final visual design.

The owner created a personal Google AI Studio API key. Setup is private on the phone, with encrypted storage and first-upload disclosure. This is a personal test configuration; a public app needs a backend, quota/budget controls, and a cost plan. See AI_SETUP.md and SCENE_MODEL_FINDINGS.md.

## Milestone 1: initial camera and guidance prototype

Version 0.1 established basic capture and preview on the owner's phone. Its manually chosen targets and generic object detector were replaced in version 0.2. New pipeline acceptance remains pending.

## Milestone 2: make guidance dependable

Measure preview timing and analysis latency on the phone. Test low light, missed subjects, front camera mirroring, preview-to-photo crops, focus, zoom, thermal behavior, and interruption recovery. Add subject selection and better anchors for faces/people. Add leveling only when sensor and horizon behavior have been tested. Improve confidence handling and instruction stability.

## Milestone 3: implement the owner's interface

Owner supplies a camera-screen sketch or Figma layout plus desired interactions. Build it around working camera behavior. Define casual/pro controls, guide strength, feedback, and accessibility with the owner. Investigate perception of generic AI-generated interfaces before UI implementation; avoid the recurring decorative patterns found during the initial search, including purple/blue gradient decoration, repetitive rounded-card layouts, and AI-themed sparkle ornament. The temporary prototype uses standard native controls and the system font.

## Milestone 4: scene-aware assistance

Investigate an efficient on-device model that can choose a subject and propose useful compositions. Compare its quality, model size, license, memory, and latency on this phone before integration. Use slower scene decisions plus faster tracking between decisions. Design controls for accepting or overriding the advice. Then evaluate guided zoom, headroom, portraits, landscapes, and more expressive framing.

An object detector alone cannot reproduce a system that identifies a sunlit palm crown and decides how to frame it. Scene understanding remains a research and quality milestone. Cloud inference could help quality but introduces network latency and recurring cost; the default direction is local processing.

## Milestone 5: release

Test more Android devices, finalize permissions and privacy disclosure, review model distribution rights, set up release signing, and decide how to distribute the app. Free app downloads, model running costs, and store distribution costs are separate decisions. Store requirements and fees must be checked when release work starts.
