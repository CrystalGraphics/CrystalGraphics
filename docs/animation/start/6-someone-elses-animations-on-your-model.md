# Tutorial 6: someone else's animations on your model

**What you will have at the end:** a clip pack made for another character (a full set of humanoid moves) playing on
your own model at its size, feet on the floor and hands where they should be. Your own clips still win where you have
them.

## 1. What each skeleton is: rigs

A **rig** reads a skeleton as a body: its root, pelvis and chains. For the common shapes the engine finds it from the
bone names:

```java
CgSkeletalMesh source = CgMeshLoader.skeletal("mymod:models/fighter_pack.glb");   // the pack: its own skeleton, many clips
CgSkeletalMesh hero = CgMeshLoader.skeletal("mymod:models/hero.glb");             // your model

CgRig from = CgRigs.detect(source.skeleton(), CgRigAdapters.CRYSTALGRAPHICS);     // the standard humanoid names
CgRig to = CgRigs.detect(hero.skeleton(), CgRigAdapters.CRYSTALGRAPHICS);
```

A GeckoLib model takes `CgRigAdapters.GECKOLIB`. A skeleton of no known shape needs a rig file
([`../guides/retargeting.md`](../guides/retargeting.md) § *A rig of your own*).

## 2. One on the other: a retargeter

```java
CgRetargetProcessor toHero = new CgRetargetProcessor(
        CgRetargeter.auto(source.skeleton(), from, hero.skeleton(), to), source.skeleton(), hero.skeleton());
```

`auto` maps chains by name and lines up the two rest poses.

## 3. Which clip plays: your own, else the pack's

```java
CgClipResolver clips = new CgClipResolver(hero::sequence, source::sequence, toHero);
clips.bakeAll(List.of("punch", "kick", "roll", "victory"));      // at load, across workers
```

`resolve("punch")` is your model's own `punch` if it has one, else the pack's baked onto your model once and kept.

## 4. Play them

On your actor's body (tutorial 4's `HeroActor`, `GRAPH` tutorial 2's graph), give its graph the resolver:

```java
@Override protected void onSpawn(CgEntity player) {
    CgSkeletalMeshComponent body = add(scene().skeletal(hero).materials(slot -> CgMaterial.of("mymod:hero")));
    body.anim().graph(GRAPH.get().graph(hero.skeleton(), id -> clips.resolve(id.substring(id.lastIndexOf('/') + 1))));
}
```

Or one clip, straight:

```java
body.anim().play(clips.resolve("victory"), false);
```

## 5. Try it

Play the pack's `roll` on your model: it rolls at your model's size, its feet on the floor at its own leg length.

## What goes wrong

| You see | Because |
|---|---|
| `detect` returns null | the skeleton matches no known shape: write a rig ([`../guides/retargeting.md`](../guides/retargeting.md)) |
| Arms through the body | the two rest poses differ (an A-pose against a T-pose): set the retargeter's retarget pose |
| A clip neither has, named in an error | the name is in neither model nor pack |
| A hitch the first time a move plays | the clip was not in `bakeAll`: it baked on first use |

## Where next

- A vanilla zombie in the pack's pose: [`../guides/vanilla-models.md`](../guides/vanilla-models.md).
- Retargeting in full: [`../guides/retargeting.md`](../guides/retargeting.md).
- [Tutorial 7](7-a-minecraft-mob-that-moves-like-your-character.md): your model on a mob, with no code.
