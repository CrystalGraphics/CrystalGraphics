# Transitions and blending

**What you will have:** state changes that blend the way you choose: a slow crossfade into a run, an S-curve into a
landing, and a hit reaction that snaps in at once yet never pops, at the cost of one pose.

## In plain words

When the character goes from standing to running, it should not jump from one pose to the other in a single frame:
that pops. A **crossfade** fades one into the other over a moment, the way a DJ fades one song out while the next
fades in. **Inertialization** is a cleverer trick: it switches at once, but lets each body part keep moving the way it
was moving and ease into the new pose, like a car that rolls on a little after you lift your foot off the pedal.

## The code

```java
CgAnimTransition.of("idle", "run", rule)
        .crossfade(0.25f)                                    // seconds; 0.2 unless said
        .blend(CgAlphaBlend.CUBIC_IN_OUT);                   // the curve; HERMITE_CUBIC unless said
```

```yaml
transitions:
  - { from: idle, to: run, rule: { variable: speed, op: '>', value: 0.1 }, crossfade: 0.25, blend: cubic_in_out }
```

## How a crossfade runs

- **It runs its `crossfade` seconds** along its `blend` curve, a `CgAlphaBlend`: `LINEAR`, `CUBIC`, `HERMITE_CUBIC`,
  `SINUSOIDAL`, `QUADRATIC_IN_OUT`, `CUBIC_IN_OUT`, `QUARTIC_IN_OUT`, `QUINTIC_IN_OUT`, `CIRCULAR_IN`, `CIRCULAR_OUT`,
  `CIRCULAR_IN_OUT`, `EXP_IN`, `EXP_OUT`, `EXP_IN_OUT`; lower-case in a file.
- **One taken while another runs** blends over that one's result.
- **Back into a state that still shows**, the crossfade is shortened by how much of it shows.
- **An `automatic` transition** that fires late (the frame went past its trigger time) is shortened by the overshoot,
  unless `overshootCorrection(false)`.

| File key | Default |
|---|---|
| `crossfade` | 0.2 |
| `blend` | `hermite_cubic` |
| `logic` | `standard`, or `inertialization` |
| `priority` | 1 |
| `trigger_time` | the crossfade |
| `overshoot_correction` | true |
| `min_reentry` | none |
| `inertialize_self` | false |

## Variations

### Inertialization: snap in, never pop

A crossfade plays both states for its length. A transition marked `inertialize()` switches at once and hands its time
to the nearest `CgInertializationNode` above the machine. That node carries each bone's offset and velocity from the
old pose into the new one and decays it to zero along a curve that never overshoots. Only the new state runs, so it
costs one pose where a crossfade costs two: the choice for sudden moves such as a hit, a dodge or a cancel.

```java
CgAnimStateMachine moves = CgAnimStateMachine.builder("moves")
        .state(CgAnimState.of("guard", guard)).state(CgAnimState.of("hit", hit).alwaysResetOnEntry())
        .transition(CgAnimTransition.of("guard", "hit", CgAnimRule.event("hit")).crossfade(0.2f).inertialize())
        .transition(CgAnimTransition.automatic("hit", "guard").inertialize())
        .build();
CgAnimGraph.builder("mymod:fighter", skeleton)
        .output(CgInertializationNode.of(CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT, CgAnimStateMachineNode.of(moves))))
        .build();
```

```yaml
output: out
nodes:
  out: { kind: inertialization, source: body }
  ...
transitions:
  - { from: guard, to: hit, rule: { event: hit }, logic: inertialization, crossfade: 0.2 }
```

### Blend lists

`CgBlendListNode` picks one pose by a variable and eases linearly into it over that pose's blend time, scaled by how
far it has to go ([`graphs-and-variables.md`](graphs-and-variables.md)).

## What goes wrong

- **A machine that inertializes with no inertialization node above it** fails when an instance runs the graph. A
  machine reached through a cached pose cannot reach one.
- **Inertialization smooths bones, not the character**: a jump of the whole character is not smoothed, and material
  curves switch at once.
- **A crossfade longer than the clip it leaves** shows that clip ending mid-blend: shorten the crossfade or loop the
  clip.

## Where next

- The machine around it: [`state-machines.md`](state-machines.md).
- Swapping layers with the same smoothing: [`layers-and-masks.md`](layers-and-masks.md).
