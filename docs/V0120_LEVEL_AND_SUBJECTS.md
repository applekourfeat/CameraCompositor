# Level and subject framing on the Vivo V30

The moving line below the center of the viewfinder follows gravity. Turn the phone until it meets the two fixed marks. When aiming nearly straight up or down, center the dot inside the circle. The manual shutter works throughout. Eligible automatic shots require both a verified image match and a fresh level reading for the countdown. A tilt outside the tolerance restarts the hold.

The previous guide used the top edge of the selected rectangle as its reference. A rectangle selected while the camera was tilted could line up with the screen in that same tilted position. This build uses the phone’s rotation vector to orient the rectangle at selection. The live outline, tracking seed and thumbnail use the same four corners. Horizontal alignment uses a 1.5-degree roll tolerance and works when aiming above or below the horizon. For overhead shots, the two-axis tolerance is about two degrees. Manual capture remains available if the orientation reading drops out.

## How the camera chooses zoom

The planner compares multiple distinct detector boxes in one model pass. A smaller item can compete even when a piece of furniture fills much of the room. The planner also looks for four connected-looking sides around a region that differs in brightness from its surroundings, such as a picture on a wall. A close shot has to leave a margin around the subject and clear the framing score threshold. Lighting, local detail, current lens zoom and the device’s available zoom limit digital enlargement. The camera can still choose a whole-room view.

The existing subject and aesthetic models run on the device. This version changes the selection policy and guide geometry. It uses the same model weights as 0.11.

## Replays

The indoor replay uses 16 views from 11 rooms. Four views went beyond 1.4× in this build, up from one in 0.11. The desk moved from 1.61× to 2.38×, the portrait desk from 1.19× to 1.82×, and the portrait corridor from 1.10× to 1.43×. The other 12 views stayed near 1.10×. The skyline selected the towers at 1.61×. The palm opening went from 0.50× to 0.91×, and the already-framed skyline, later palm and flat control kept their opening views. [Source scenes](V0110_ADAPTIVE_COMPOSITION.md) and [individual results](../research/results/level-indoor-replay.json).

The paired replay sends identical detector and scene-model outputs to both compiled policies on the frozen 60 GAICv2 evaluation photographs. It compares existing human ratings only when each proposed frame overlaps a rated crop by at least 0.85 intersection over union. A rating on a nearby crop is an approximation. The photographs do not contain sensor readings or live camera motion. [Paired results](../research/results/level-heldout-replay.json).

Sixteen of the 60 choices changed. Zero changed pairs met the overlap threshold for both suggestions, so this evaluation yielded no usable comparison of photographic preference. Live leveling, exposure-time alignment and camera performance still need a Vivo session. The signed ARM64 package passed 112 Android unit tests, Android lint with zero errors, and the delivery checks. [Verification](../research/results/delivery-verification-v0120_level.json).

To reproduce the paired replay, build the debug classes at revision `ca8f589` and at this revision. Run `research/replay_level_comparison.py` with `--java`, `--baseline-driver` pointing to the older `ReplayContextShots.java`, `--baseline-classpath`, `--classpath` and `--output`. Each classpath needs its compiled Android classes and Kotlin standard library. Use the local benchmark images and model assets described in the [research setup](../research/README.md).

## What the tighter framing leaves out

In a visual inspection of the 16 changed stills, the planner sometimes chose a plausible subject and lost the relationship that made the scene interesting. In `gaic-test-475676`, 2.38× keeps the people on the path and removes the lone tree. In `gaic-test-309808`, 2.38× keeps one beachgoer and removes the other player and the object between them. In `gaic-test-396968`, 2.08× shows the lone bird and its perch clearly. These examples suggest testing a wide candidate that keeps related subjects together alongside the single-subject close-up. Any tuning should use separate development scenes.

The scoring code offers one explanation. It rewards enlarging a small detected subject and penalizes including a second strong subject elsewhere in the frame. Two people playing together can trigger the same penalty as a person and a background distraction. The replay records the final crop, so the contribution of this penalty to any individual decision is still unknown. [Framing rules](../app/src/main/java/dev/cameracompositor/ContextShots.kt).

The local blind review compares 0.12 with the starting photograph, 0.11, and the historical Thirds framing on all 60 evaluation scenes. Pixel-identical crops account for 18, 44, and 14 ties respectively. That leaves 104 distinct pairs to review. Human preference and live performance remain unmeasured by this comparison. [Review setup](../research/README.md#reviewing-the-vivo-planners-framing).
