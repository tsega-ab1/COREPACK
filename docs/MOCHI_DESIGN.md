# Mochi: design analysis and how the pet uses it

The pet's look was adapted from six short design clips (720x900, 30 fps, 2,160 frames in total).
This note records what was measured, how it maps onto the pet, and what is not done.

## What the clips contain

| Clip | Length | Shows |
|---|---|---|
| Expressions | 8 s | 20 labelled expressions and 10 accessories |
| Design | 10 s | outfits and hats, an agent-status panel (permission prompt, question, "migration applied") |
| State machine | 12 s | the 11 states, switched one at a time, with a transition graph |
| Animation | 20 s | ten named animations in sequence: Greeting, Working, Thinking, Upload, Slap, Dizzy, Question, Error, Finished, Dance |
| Implementation | 10 s | the mascot living in a laptop notch that expands into a panel |

States (11): idle, working, thinking, searching, approval, question, error, finished, rate limit,
sleeping, dizzy. Expressions: greeting, love, surprised, proud, wink, yawn, annoyed, upload, dancing.
Accessories: beanie, santa hat, party hat, crown, witch hat, sunglasses, glasses, scarf, pumpkin, bow.

## Measured (reference pixels, body = 228 x 176)

Every frame of the Animation clip was tracked (body outline, colour, eye holes).

- **Body:** 228 x 176 (aspect 1.295). The outline fits a superellipse with exponent 2.75.
- **Shading:** vertical gradient from near-white at the top to the state tint at the bottom, a soft
  darker rim, a glossy highlight at the upper right, pink blush under the eyes in tinted states.
- **Eyes:** dots 24 x 27 at +-41 px from centre; happy arcs 47 x 25; dashes 34 x 10; spirals for dizzy;
  hearts and stars for love and proud. No mouth.
- **Badge:** top-left, thick near-black outline. A pill with three dots (working, thinking, searching),
  a small dot (error, finished, rate limit), a circle with "?" (question) or "!" (approval).
- **Glow:** soft halo in the state colour, mostly gone about 50-60 px beyond the body edge.
- **Timing of the ten animations:** Greeting 2.6 s, Working 1.8, Thinking 1.4, Upload 3.2, Slap 1.6,
  Dizzy 1.8, Question 1.4, Error 1.4, Finished 1.8, Dance 3.0.
- **Motion:** Working and Thinking leave the body completely still (only badge, eyes and glow change).
  Dance bounces about 21 px at 2.04 Hz (0.49 s per beat) with a tilt that alternates each beat.
  Slap squashes the body (about +16% wide, -20% tall) and rings for about 0.5 s.

Colours (body top / bottom / badge) were sampled per state from the Expressions grid; see `Looks` in
`mochi/Mochi.kt`.

## How the pet uses it

What the agent is doing picks the state; the mood the model sets only colours the calm state.

| Pet is | Mochi state |
|---|---|
| idle (neutral) | idle |
| idle, mood happy / sad / curious / sleepy / excited | happy / sad / question / yawn / dancing |
| waiting behind the rate limit | rate limit |
| waiting for the first word | thinking |
| running a tool | searching |
| streaming the reply | working |
| reply finished | finished |
| error | error |
| gateway unreachable | sleeping |
| engine throttling | dizzy |
| overlay just started | greeting (2.6 s), then idle |

`happy` and `sad` are our own additions; the reference has no equivalent.

## How it is built

- `mochi/Gfx.kt`: a tiny drawing interface. The character is drawn once against it; the phone uses
  `AndroidGfx` (Canvas) and the desktop preview uses `Java2DGfx`. That is why the preview images
  match what the phone draws.
- `mochi/Mochi.kt`: states, looks, and `MochiAnimator.pose(state, secondsInState, clock)`, a pure
  function (same inputs, same pose), plus `MochiPlayer` for cross-fades and one-off expressions.
- `mochi/MochiRenderer.kt`: draws one pose. About 30 draw calls per frame.
- `PetView.kt`: keeps time and hands frames to the renderer.

## Not done yet

- Accessories (hats, glasses, scarf): could be a `set_outfit` tool.
- Upload and Slap animations: Slap would suit a tap or poke reaction.
- The notch/island panel from the Implementation clip.
- Sound: the reference app has a sound engine; none of it is used.

## Attribution

The clips come from a design presentation of a mascot called Mochi, apparently part of a commercial
macOS app. Only measurements (proportions, timings, palette, state vocabulary) were taken. All
drawing and animation code here was written from scratch, and no footage, image or sound is included.
Because a mascot's look can be protected, check the original's licence or ask its author before
publishing the pet publicly, or change the character's identity (silhouette, face, palette) to your
own design; the whole look lives in `mochi/` and is easy to change.
