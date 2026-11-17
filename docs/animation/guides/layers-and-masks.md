# Layers and masks: one pose over part of the body

**What you will have:** arms that aim while the legs run, a face that talks over the body, a sword's stance swapped in
over the hero's arms at runtime, and a pose computed once and read in several places.

## In plain words

Sometimes one part of the body should do something different from the rest: the legs run while the arms aim a bow.
A **layer** is a second pose stacked on top of the first, and a **mask** is a stencil saying which bones the top pose
covers, like taping over everything but the sleeves before painting a shirt. A **linked layer** is a part of the graph
you can swap while the game runs, like changing the cartridge in a console.

## The code

A face slot over the face's bones, on top of a body pose read twice:

```java
CgAnimGraph.builder("mymod:hero", skeleton)
        .save("body", CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT, locomotion))      // evaluated once a frame
        .output(CgLayeredBlendNode.of(CgCachedPoseNode.use("body"))
                .layer(CgSlotNode.of("FacialSlot", CgCachedPoseNode.use("body")), CgBlendMask.branch("facialbase", 1)))
        .build();
```

```yaml
nodes:
  body_save: { kind: save_cached_pose, pose: body_slot }
  body_slot: { kind: slot, source: locomotion }
  out:
    kind: layered_blend
    base: body_a
    layers: [ { pose: face_slot, mask: { branches: [ { bone: facialbase, depth: 1 } ] } } ]
  body_a: { kind: use_cached_pose, save: body_save }
  body_b: { kind: use_cached_pose, save: body_save }
  face_slot: { kind: slot, slot_name: FacialSlot, source: body_b }
```

## Masks

```java
CgBlendMask face = CgBlendMask.branch("facialbase", 1);                       // the branch, all at once
CgBlendMask arms = CgBlendMask.branch("upperarm_l", 0).andBranch("upperarm_r", 0);
CgBlendMask spine = CgBlendMask.branch("spine_01", 3);                        // ramping in over three levels
CgBlendMask lean = CgBlendMask.bone("spine_02", 0.5f).andBone("head", 0.25f); // a weight per bone
CgLayeredBlendNode.of(run).layer(aim, spine, AIM_WEIGHT);                     // the layer's weight a variable
```

In a file: `mask: { branches: [{ bone, depth }], bones: [{ bone, weight }] }`, and a layer's `weight` a number or a
float variable.

- **A branch** weighs a bone `d` levels below its root `(d + 1) / depth`, at most 1; depth 0 takes the whole branch.
  Branches add, at most 1; a bone weight replaces what the branches gave it.
- **Material curves**: a layer's replace the base's; `curvesFromBase()` keeps the base's.

## Variations

### Linked layers: swap part of a graph at runtime

A **layer** is a named part of a graph that another graph may play instead: a sword's arms over a hero's locomotion.
The graph declares it with the poses it takes; a `CgLinkedAnimLayerNode` plays it, the graph's own until another graph
is linked.

```java
CgAnimGraph hero = CgAnimGraph.builder("mymod:hero", skeleton)
        .layer(CgAnimGraph.Layer.of("arms", CgLinkedInputPoseNode.of("body"), "body").blend(0.2f, 0.2f))   // its own: the body through
        .output(CgInertializationNode.of(CgLinkedAnimLayerNode.of("arms").input("body", locomotion)))
        .build();
CgAnimGraph swordArms = CgAnimGraph.builder("mymod:sword_arms", skeleton)
        .layer(CgAnimGraph.Layer.of("arms", CgLayeredBlendNode.of(CgLinkedInputPoseNode.of("body"))
                .layer(CgSequencePlayerNode.of(SWORD_IDLE).loop(), CgBlendMask.branch("clavicle_r", 0)), "body").blend(0.2f, 0.2f))
        .output(CgRefPoseNode.create())
        .build();

anim.graph(hero);
anim.linkLayers(swordArms);     // the sword's arms, blended in over 0.2 s
anim.unlinkLayers(swordArms);   // the hero's own again
```

```yaml
# the hero above, as a file
layers:
  arms: { inputs: [body], output: arms_own, blend_in: 0.2, blend_out: 0.2 }
nodes:
  arms_own: { kind: linked_input_pose, name: body }
  arms:     { kind: linked_anim_layer, layer: arms, inputs: { body: locomotion } }
```

An actor links them for you by what the entity holds: [`held-items-and-item-layers.md`](held-items-and-item-layers.md).

### Linked graphs: a graph as a node

A **linked graph** runs another graph as a node; a tag lets the game swap it.

```java
CgAnimGraph face = CgAnimGraph.builder("mymod:face", skeleton).inputs("body")
        .output(CgLayeredBlendNode.of(CgLinkedInputPoseNode.of("body")).layer(blink, CgBlendMask.branch("head", 0)))
        .build();
CgAnimGraph hero = CgAnimGraph.builder("mymod:hero", skeleton)
        .output(CgLinkedAnimGraphNode.of(face).input("body", locomotion).tag("face"))
        .build();
anim.linkGraph("face", ANGRY_FACE);    // null: the body passes through
```

```yaml
nodes:
  face: { kind: linked_anim_graph, graph: mymod:face, tag: face, inputs: { body: arms } }
```

- **A linked graph plays inside its owner**: its variables are the owner's of the same name and type, its slots the
  owner's, its players in the owner's sync groups.
- **Layers of one `group`** linked from one graph share one run of it; an ungrouped layer has its own.

## What goes wrong

- **A mask naming a bone the skeleton lacks** throws when an instance runs the graph.
- **A layer's blend times need an inertialization node above the layer node**; a swap with none fails as it is
  linked.
- **Fails at build or load**: a layer node naming a layer its graph does not declare, an input no layer or graph takes,
  a linked input pose naming an input its layer lacks, a `graph` that is no `anim_graph`.
- **A pose read twice without a cache** fails at build: a graph is a tree.

## Where next

- Additive poses and aim offsets: [`additive-and-aim-offsets.md`](additive-and-aim-offsets.md).
- Weapons choosing the layer: [`held-items-and-item-layers.md`](held-items-and-item-layers.md).
