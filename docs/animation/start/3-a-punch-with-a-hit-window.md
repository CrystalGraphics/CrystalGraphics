# Tutorial 3: a punch with a hit window

**What you will have at the end:** your hero from tutorial 2 throwing a punch over whatever its legs are doing each time
you swing, and your server dealing the hit inside the punch's hit window, seen by every player near you.

## 1. Arms only: an upper-body slot

The punch should play over the running legs. Give the graph a slot that covers the upper body:

```yaml
# assets/mymod/anim_graphs/hero.yaml: tutorial 2's, its output changed
output: out
nodes:
  out:   { kind: layered_blend, base: base, layers: [ { pose: upper, mask: { branches: [ { bone: spine_01, depth: 0 } ] } } ] }
  base:  { kind: use_cached_pose, save: body }
  upper: { kind: slot, slot_name: UpperBody, source: base2 }
  base2: { kind: use_cached_pose, save: body }
  body:  { kind: save_cached_pose, pose: whole }
  whole: { kind: slot, source: loco }
  # loco and the rest as before
```

## 2. The move: a sequence

```yaml
# assets/mymod/sequences/moves/punch.yaml
_v: 1
tick_resolution: 60000
range: [0, 48000]
composite_sections:
  - { id: q1, name: jab, range: [0, 24000], next: q2 }
  - { id: q2, name: recover, range: [24000, 48000] }
roles: [ { id: b1, name: caster, kind: caster } ]
tracks:
  - id: t1
    kind: animation
    binding: { role: caster }
    sections:
      - { id: s1, range: [0, 24000], clip: mymod:hero/jab, ease: { in: 6000 } }
      - { id: s2, range: [24000, 48000], clip: mymod:hero/jab_recover, ease: { out: 12000 } }
  - { id: t2, kind: event, side: rpg, binding: { role: caster }, sections: [
      { id: h1, range: [8000, 18000], event: mymod:hit_window } ] }
```

At 60,000 ticks a second: the jab lasts 0.4 s and its hit window opens 0.13 s in. The jab runs on into its recovery,
which blends out over 0.2 s.

## 3. Play it on each swing

```yaml
# assets/mymod/actors/hero.yaml
_v: 1
prefab: mymod:hero
anim_graph: mymod:hero
reactions:
  - { event: swing_started, sequence: mymod:moves/punch, slot: UpperBody }
```

## 4. The hit, on the server

The hit window is a gameplay event: your server hears it begin and end.

```java
public final class MyModLifecycle implements CgLifecycleListener {
    @Override public void onServerStarting(Object server) {
        CgWorld world = CgWorld.serverWorld();
        CgSequencer moves = CgSequencer.of(world);
        if (moves == null) moves = world.add(new CgSequencer());
        moves.listen(CgSequenceEventListener.Side.RPG, (play, phase, event, args) -> {
            if (event.equals("mymod:hit_window") && phase == CgSequenceEventListener.Phase.BEGIN) {
                hitWhatIsInFront(play);              // your mod's damage
            }
        });
    }
}
```

## 5. Try it

Run and swing: the arms jab while the legs keep running, and what stands in front is hit as the window opens.

## What goes wrong

| You see | Because |
|---|---|
| The whole body punches, legs too | the punch plays on `DefaultSlot`: give the reaction `slot: UpperBody` |
| Nothing plays | the graph has no `UpperBody` slot, or the sequence id has a typo (the log names it) |
| The hit never lands | the listener was added on the client, or before the server started: use `onServerStarting` |
| The arms snap back | the last section has no `ease.out` |

## Where next

[Tutorial 4](4-your-blockbench-model.md): the same with a GeckoLib model. A combo (a second swing jumping to a
second strike) and montages in full:
[`../guides/montages-and-slots.md`](../guides/montages-and-slots.md).
