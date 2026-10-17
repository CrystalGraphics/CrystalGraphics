# Sequences: a clip on every particle

A particle may play a clip: a prefab ([`prefabs.md`](prefabs.md)) whose nodes move, fade and change their material's
numbers over frames, starting child emitters at clip times. A Rasen Shuriken's blades spin and its rings flash this way,
each particle its own copy at its own age; CC2's particle-played clips are these. Everything runs on the GPU: the clip
is baked once into a buffer, the parts are drawn from it at each particle's age, and its children are spawned from the
particles' steps. Unreal's equivalent is AnimToTexture (an animation baked into a texture, sampled per instance), with
Niagara's GPU emitters spawning from another's particles.

## A clip

```java
static final CgVfxLayer.Builder BLADE = CgVfxLayer.of("crystalgraphics:shaders/vfx/sequence/part.shader")
        .texture("_MainTex", BLADE_TEXTURE);

static final CgVfxPrefab SPINNER = CgVfxPrefab.builder("mymod:spinner")
        .node("hub")
        .node("blade").parent("hub").at(1.5f, 0f, 0f).part(BLADE, "mymod:models/blade.glb")
        .node("tip").parent("blade").at(0.8f, 0f, 0f)
        .build();

static final CgVfxNodeClip SPIN = CgVfxNodeClip.of(SPINNER, 60)                     // 60 frames, 30 a second
        .channel("hub", Channel.ROTATION_Y, Interp.LINEAR, 0, 0f, 60, 720f)         // (frame, value) pairs
        .channel("blade", Channel.OPACITY, Interp.LINEAR, 0, 1f, 45, 1f, 60, 0f)
        .material("blade", "_U0_LocX", Interp.CONSTANT, 0, 0f, 15, 0.25f, 30, 0.5f) // a UV window stepping on
        .rest("blade", "_Glare", 0.6f)                                               // held all clip long
        .burst("tip", SPARKS, 20, 8)                                                 // 8 sparks at the tip on frame 20
        .stream("tip", SMOKE, 10, 50, 30f)                                           // 30 a clip second, frames 10 to 50
        .build();
```

| Call | Keys |
|---|---|
| `channel(node, channel, interp, keys...)` | a transform channel (`LOCATION_X` to `SCALE_Z`, rotations in degrees) or `OPACITY` |
| `material(node, scalar, interp, keys...)` | one of the material's numbers by CC2's name (`CgVfxNodeClip.MATERIAL_SCALARS`) |
| `rest(node, scalar, value)` | a material number held for the whole clip |
| `burst(node, child, frame, count)` | `count` children of `child` born at the node on `frame` |
| `stream(node, child, from, until, perSecond)` | children born at the node from frame `from` to `until`, `perSecond` a clip second |

- **Rotations are Euler degrees, x then y then z intrinsic** (CC2's order, JOML's `rotateXYZ`). A channel no key sets is
  the node's rest; before its first key a channel holds that key's value, after its last the last's.
- **`LINEAR` keys interpolate, `CONSTANT` keys step** at each key's frame.
- **A material number no key sets is the material's own**: the layer's value, which the clip leaves alone.

## Playing it on particles

```java
static final CgVfxEmitter SHURIKENS = CgVfxEmitter.builder("shurikens").sequence(SPIN)   // or sequence(SPIN, 0.5f)
        .state(CgVfxEmitterState.ONCE).capacity(4).burst(0f, 3).life(2f, 2f).size(1.5f, 1.5f)
        .shape(CgSphere.of(2)).speed(1f, 2f)
        .build();

static final CgVfxLook LOOK = CgVfxLook.builder(CgVfxBurst.SCHEMA)
        .with(CgVfxKit.of("mymod:shurikens").emitter(SHURIKENS)
                .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/particle/spark.shader").on(SPARKS).build())
                .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/particle/dust.shader").on(SMOKE).build())
                .build(), SPINNER.kit())
        .build();

VFX.play(new CgVfxBurst(LOOK, x, y, z));
```

- **The children are drawn by layers on their slots**, not listed as emitters: a burst starts every emitter its look
  lists.

- **The look draws the prefab's kit**: each part is drawn with its material on every particle, where its node is at the
  particle's age times the play rate, held at the clip's last frame.
- **A part is placed from its particle**: at the particle's position, turned with its effect, sized by the particle's
  size (its size curve and scales do not apply: the clip's own scale channels do).
- **A clip particle is a pure function of its seed and age**: its emitter takes no module reading the world (a floor,
  blocks, depth, entities, neighbours, gas, a grid), and `build()` throws naming one. Its children take any: sparks the
  clip throws can bounce.
- A clip's bursts and streams are events after the emitter's own, and count toward `CgVfxEvent.MAX_EVENTS`.

## Events at a node, and children's children

Any event may fire at a node of the emitter's clip rather than where the particle is: its place the node's at the
particle's age, its normal the node's +Y. A rate may fire only inside a window of the particle's age:

```java
.event(CgVfxEvent.onAge(1f).at(SPINNER.node("tip")).readback(4))                  // where the tip is at 1 s
.event(CgVfxEvent.every(0.05f).between(0.3f, 1.5f).spawn(SMOKE, 1).at(SPINNER.node("tip")))
```

Children may have children of their own, two levels in all: a spark the clip throws may burst into embers when it
dies, and the embers may report their deaths but spawn nothing.

```java
static final CgVfxEmitter EMBERS = CgVfxEmitter.builder("embers").life(0.3f, 0.5f).build();
static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("sparks").life(0.5f, 0.8f)
        .module(new CgVfxModule.Gravity(9.8f)).event(CgVfxEvent.onDeath().spawn(EMBERS, 3)).build();
```

- **An event at a node needs a node the clip bakes**: one with parts, or one a burst or stream starts at; the emitter
  throws otherwise. A node of a clip on any other renderer throws too.
- **A window is a rate's alone.** Its periods count from the window's start, never firing at it.
- A grandchild is keyed by `CgVfxEvent.grandchildKey`, its parent's whole id hashed in.

## Writing a part's material

`crystalgraphics:shaders/vfx/sequence/part.shader` is CC2's basic part: its texture through layer 0's UV window,
tinted, faded by its node's opacity, alpha-tested by `_Clip`, `_Glare` of it glowing, premultiplied. A material of your
own draws on clip particles by declaring the `SEQUENCE` kind and placing its vertices by the node:

```glsl
#pragma cg_feature FX_ON_MESH                      // drawn by a prefab instance too
#pragma cg_feature FX_ON_SEQUENCE
#include "crystalgraphics:shaders/lib/vfx/fx_sequence.glsl"

Properties { _U0_LocX ("Where its UV window starts", float) = 0.0 }   // named as CC2 names them
Buffers { FX_CLIPS ("Baked clips", vec4, readonly) }

void vertex(out v2f o) {
#ifdef FX_ON_SEQUENCE
    vec4 world = FX_SEQUENCE_TO_WORLD * vec4(cg_Position, 1.0);     // the particle, then the node at its age
    o.opacity = FX_SEQUENCE_OPACITY;                                 // the node's times its ancestors'
    o.u = FX_SEQ_SCALAR(0, _U0_LocX);                                // the clip's, else the material's
#else
    vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
#endif
    ...
}
```

- **Declare `FX_ON_SEQUENCE`**: a material drawn on a clip particle without it throws at its first draw, since its part
  would sit at the particle whatever the clip said.
- **Declare `FX_CLIPS` in its `Buffers { }`**: the engine binds the baked clips to every material that does.
- `FX_SEQ_SCALAR(i, own)` takes the scalar's index in `MATERIAL_SCALARS` and the material's own value.
- `CG_OBJECT_CUSTOM1` is the particle's (life 0 to 1, seed, opacity, heat), as for a `MESHES` particle.

## Things to know

- **The clip is baked once**, on the CPU, the first time an emitter plays it: each baked node's matrix from the clip's
  root, its opacity and its material numbers, a vec4 a frame (`CgVfxClipAtlas`). A part reads two frames and mixes them;
  a row any `CONSTANT` channel feeds takes the earlier.
- **A matrix is mixed between frames**, as AnimToTexture's is, so a node turning more than about 30 degrees a frame
  shrinks a little between them. Key fast spins on more frames.
- Every part of every node is one instanced draw a material, culled on the GPU, its bounds grown by how far the clip
  carries it.
- `--mode=vfx-sequence` is the gate: the part where its node is, a node's event, children and grandchildren, at every
  tier and on Vulkan.
