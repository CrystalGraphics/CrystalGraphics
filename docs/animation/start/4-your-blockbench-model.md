# Tutorial 4: your Blockbench model

**What you will have at the end:** a GeckoLib model made in Blockbench, with its own `.animation.json`, drawn on your
player and run by tutorial 2's graph, its swing playing a slash effect and a whoosh on the frame you marked in
Blockbench. Your files stay as GeckoLib and Blockbench read them.

## 1. The files, where GeckoLib keeps them

```
assets/mymod/geo/hero.geo.json                    the model
assets/mymod/animations/hero.animation.json       its animations: idle, jog, sprint, jump, fall, land, swing
assets/mymod/textures/hero.png
```

- The model is a skinned mesh: each bone of the geometry a bone, each cube rigid on its bone.
- Each animation is a clip. Its id is the file's path under `animations/` without `.animation.json`, then `/` and the
  animation's key: `mymod:hero/swing`.

## 2. Mark the swing in Blockbench

In the animation's timeline, add keys (Blockbench's *Instructions* row, or the file directly):

```json
"swing": {
  "animation_length": 0.5,
  "timeline":         { "0.3": "hit;" },
  "particle_effects": { "0.25": { "effect": "mymod:systems/slash", "locator": "hand_r" } },
  "sound_effects":    { "0.25": { "effect": "mymod:swing" } }
}
```

At 0.25 s the slash plays at the `hand_r` locator and the whoosh sounds; at 0.3 s your mod hears the event `hit`
([`../guides/notifies-and-events.md`](../guides/notifies-and-events.md)).

## 3. The actor, binding the graph in code

A GeckoLib model's clips live in its animation file, so the actor gives its graph the clips by id:

```java
public final class HeroActor extends CgEntityActor {
    static final CgGeckoModel MODEL = CgGeckoModel.load("mymod:geo/hero.geo.json");
    static final Map<String, CgAnimSequence> CLIPS = new HashMap<>();
    static {
        for (CgGeckoAnimation a : MODEL.animations("mymod:animations/hero.animation.json")) {
            CLIPS.put("mymod:hero/" + a.name(), a.sequence());
        }
    }
    static final CgAssetRef<CgAnimGraphDefinition> GRAPH = CgVfxAssets.ref(CgBodyKinds.ANIM_GRAPH, "mymod:hero");

    @Override protected void onSpawn(CgEntity player) {
        CgSkeletalMeshComponent body = add(scene().skeletal(MODEL.mesh()).materials(slot -> CgMaterial.of("mymod:hero")));
        body.anim().graph(GRAPH.get().graph(MODEL.mesh().skeleton(), CLIPS::get));
    }
}
```

```java
// common code, at start-up
CgEntityActors.register("minecraft:player", HeroActor::new);
```

The graph is tutorial 2's file, its clip ids the GeckoLib ones: `mymod:hero/idle`, `mymod:hero/jog` and so on. The
actor feeds its `entity:` block as before.

## 4. Try it

Join a world and press F5. Walk, sprint, jump: the GeckoLib model moves by the graph. Swing: the slash appears at
its hand and the whoosh plays.

## What goes wrong

| You see | Because |
|---|---|
| A clip "not found" error naming `mymod:hero/...` | the graph's clip id does not match the animation's key: ids are `<path>/<key>` |
| Molang named in an error | a key's value is an expression that is not a constant. Bake it to keys in Blockbench |
| A `begin` with no `end` named at load | a `begin=` key without its `end=` in the same animation |
| Arms and legs that cannot bend | each cube is rigid. A bending limb needs a real skinned model ([tutorial 5](5-your-blender-character.md)) |

## Where next

[Tutorial 5](5-your-blender-character.md): a Blender character with bending limbs and root motion.
