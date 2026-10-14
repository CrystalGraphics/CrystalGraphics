# VFX — the guide

The VFX engine (`com.crystalgraphics.vfx`) plays visual effects in a world: beams, explosions, auras, hit sparks,
dust. It simulates their particles on the GPU, draws them through the engine's world renderer, sends them over the
network, and can run an effect on the server when gameplay depends on it.

It splits an effect into **what it does** (an effect class and the elements it is made of: Java, ticking at 120 Hz)
and **how it looks** (a look: parameters, kits of layers, emitters: data). The same Kamehameha class plays as the
realistic beam, as a toon beam in flat bands, or as Dragon Forge's anime wave, and only the look differs.

Read sections 1 and 2 first: they give you the words and the picture everything else uses. After that, jump to what
you need. Where each class lives is `vfx/CLAUDE.md`, which loads itself for anyone editing a VFX file.

## Contents

1. [The words you need](#1-the-words-you-need)
2. [Quick start](#2-quick-start)
3. [Client, server, or both](#3-client-server-or-both)
4. [Playing effects](#4-playing-effects)
5. [Parameters: what a look carries](#5-parameters-what-a-look-carries)
6. [Kits, layers and slots: how a look draws](#6-kits-layers-and-slots-how-a-look-draws)
7. [Tweens and endings](#7-tweens-and-endings)
8. [Tuning a look live, and saving it](#8-tuning-a-look-live-and-saving-it)
9. [The energy wave](#9-the-energy-wave)
10. [Effects over the network: cues](#10-effects-over-the-network-cues)
11. [Effects gameplay depends on: server authority](#11-effects-gameplay-depends-on-server-authority)
12. [Hits: what an effect strikes](#12-hits-what-an-effect-strikes)
13. [Particles](#13-particles)
14. [Writing an effect of your own](#14-writing-an-effect-of-your-own)
15. [Writing a layer's shader](#15-writing-a-layers-shader)
16. [Writing a particle module of your own](#16-writing-a-particle-module-of-your-own)
17. [Performance and scale](#17-performance-and-scale)
18. [Questions people ask](#18-questions-people-ask)
19. [Checking and measuring](#19-checking-and-measuring)

---

## 1. The words you need

| Word | What it is | Class |
|---|---|---|
| **Effect** | One thing playing in the world: one Kamehameha, one explosion. A position, an age, and code that runs every tick. It dies when it is done. | `CgVfxEffect`: `CgEnergyWave`, `CgVfxBurst`, yours |
| **Element** | A part many effects share, with its own parameters and slots: a charge ball, a blast, a beam's body. An effect is made of elements. | `CgVfxElement`: `CgVfxCharge`, `CgVfxBlast`, `CgBeamBody`, ... |
| **Look** | How an effect looks: its parameters' values, the kits that draw it, its emitters. Shared, never changed by playing. | `CgVfxLook` |
| **Schema**, **parameter** | A named set of typed values an effect, element or kit reads: a length, a colour, a palette, a curve, a camera shake. | `CgVfxSchema`, `CgVfxParam` |
| **Kit** | A named group of layers and emitters, with the schemas they read: the realistic body, the toon body, an explosion. A look is kits added and dropped. | `CgVfxKit` |
| **Layer** | One draw: a `.shader` on one or more slots, its properties set, bound to parameters, or bound by name. | `CgVfxLayer` |
| **Slot** | A place an effect draws: its body along a path, its head on a sphere, its sparks. An element declares its slots; a layer names the ones it draws on. | `CgVfxSlot` |
| **Emitter**, **module** | One kind of particle, and the physics pieces it stacks (gravity, drag, collision). Simulated on the GPU. | `CgVfxEmitter`, `CgVfxModule` |
| **Ending** | A way an effect ends: values eased somewhere over some seconds, then gone. | `CgVfxEnding` |
| **Owner** | Your mod's handle on the engine: play, stop and clear your effects, timed under your name. | `CgVfxOwner`, from `CgVfx.owner("mymod")` |
| **Cue** | An effect named for the network, so the server can tell clients to play it. | `CgVfxCue` |
| **Authority** | The server running an effect itself, because gameplay depends on what it does. | `CgVfxCue.Builder.authoritative()` |
| **Attachment point** | A named point on an entity an effect rides: feet, chest, hands, or one your mod declares. | `CgEntityAttachments` |

How they fit together:

```
CgEnergyWave (the effect: phases, aim, fire, hits)
  ├─ CgVfxCharge  ── schema crystalgraphics:charge  ── slots BALL, HAZE, STREAKS, ...
  ├─ CgBeamBody   ── schema crystalgraphics:wave/body ── slots BODY, ARCS, HEAD
  ├─ CgBeamContact, CgVfxBlast, CgBeamWake
  └─ plays a CgVfxLook:
        values      RADIUS 0.8, SHELL 0xFF1858FF × 1.6, ...      (every schema the look includes)
        kits        REALISTIC_BODY  → layers on BODY, ARCS     (each layer: a shader, its bindings)
                    REALISTIC_HEAD  → layers on HEAD
                    CgVfxBlast.KIT  → FIREBALL, HEART, SHOCKWAVE, the explosion's emitters
        links       HEAD_WIDTH = RADIUS × 1.45, ...            (sizes follow the radius)
```

---

## 2. Quick start

### Play a built-in effect

```java
public static final CgVfxOwner VFX = CgVfx.owner("mymod");   // once, as a constant

// client: a Kamehameha at the player's hands, aimed where they look, charging until fired
CgEnergyWave wave = VFX.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z)
        .follow(playerId, CgEntityAttachments.HANDS_FORWARD).hold());
wave.aim(lookX, lookY, lookZ);   // each tick
wave.fire();                     // key released
wave.stop();                     // its tail runs out into what it hit, which bursts
wave.end(CgEnergyWave.FADE);     // or: it thins away where it is, no blast
```

The engine registers its effects by id, so a tool or a command can play one without knowing its class:

```java
VFX.play("crystalgraphics:kamehameha_toon", x, y, z, tx, ty, tz);   // at a point, aimed at a target
```

### A variant of a look

```java
public static final CgVfxLook THIN = CgEnergyWave.kamehameha().derive()
        .set(CgEnergyWave.RADIUS, 0.3f)                     // every size follows the radius
        .set(CgEnergyWave.SHELL, 0xFFFF7A1A, 1.4f)          // a colour: 0xAARRGGBB, its rgb × 1.4
        .build();

public static final CgVfxLook MINE = CgEnergyWave.kamehameha().derive()
        .without(CgEnergyWave.REALISTIC_BODY, CgEnergyWave.REALISTIC_HEAD)   // drop the engine's drawing...
        .with(MyKits.BANDS)                                                  // ...and draw it your way
        .build();
```

### An effect that is only particles

A look with emitters and layers on them needs no effect class: `CgVfxBurst` starts every emitter once and dies when
they finish. Building `PUFF_LOOK` is [§13](#13-particles).

```java
VFX.play(new CgVfxBurst(PUFF_LOOK, x, y, z));
```

### Show it to every player

```java
// common, at init: a cue, with what each side plays
public static final CgVfxCue PUFF = VFX.cue("puff", PUFF_LOOK,
        (look, cue) -> new CgVfxBurst(look, cue.x(), cue.y(), cue.z())).build();

PUFF.at(level, x, y, z).send();   // server, whenever
```

That is [§10](#10-effects-over-the-network-cues). An effect gameplay depends on (a beam that damages what it hits)
runs on the server too: [§11](#11-effects-gameplay-depends-on-server-authority).

---

## 3. Client, server, or both

**The client draws; the server decides.**

| What | Client | Server |
|---|---|---|
| Drawing: meshes, layers, glow | yes | never |
| Particles (simulated on the GPU) | yes | never |
| Post effects: flashes, colour shifts, impact frames | yes | never |
| Camera shakes | yes | never |
| An effect's logic: where a beam goes, when it fires, when it bursts | yes, for drawing | only for an **authoritative** cue |
| What a beam hits, for gameplay | no: it would differ per client | yes, with an authoritative cue |
| Sending an effect to players | no (a client can only *predict*, [§10](#predicting-from-a-client)) | yes, with a cue |

- **On a client**, effects play into the client world's system, which the engine updates every frame, draws, and
  clears when the world goes (logout, disconnect, a dimension change).
- **On a server**, nothing plays unless a cue is `authoritative()`. Then the server runs *the same effect class*
  with everything visual switched off.
- **In singleplayer** an authoritative effect exists twice: the integrated server's copy decides, the client's draws.
- **Looks, schemas, kits and layers are plain data** and are safe on a server: declare them in common code. Nothing
  touches the GPU until a layer first draws.

### Threads

| Call | Thread |
|---|---|
| `VFX.play(...)` | any: it starts at the next world frame |
| Anything on a playing effect (`aim`, `fire`, `set`, `animate`, `end`, `stop`) | the render thread on a client; the server thread for a server's effect |
| A cue's `send()`, `start()`, and every `Playing` method | the server thread |
| A cue's `play()` and `predict()` | the client thread |
| Hit listeners | the render thread (client) or the server thread (an authoritative effect) |
| A tuning's variables | any: a playing effect takes a change at its next tick |

---

## 4. Playing effects

### Your owner

```java
public static final CgVfxOwner VFX = CgVfx.owner("mymod");

VFX.play(effect);       // returns the effect, so you can keep driving it
VFX.stop();             // every effect of this mod, gracefully: nothing new spawns, what is in flight plays out
VFX.clear();            // every effect of this mod at once, particles and all
VFX.enabled(false);     // a dev switch: ends them, and play() does nothing until enabled again
VFX.playing();          // how many of this mod's effects are alive
VFX.prepare(LOOK);      // builds a look's shaders, meshes and kernels ahead of its first play
```

- **An effect plays once.** Playing the same object again, or into a second system, throws. Make a new one.
- **An effect that throws is killed and logged under your mod**; every other effect keeps playing.
- A look played cold compiles on its first frame and can hitch: `prepare` spreads the work over frames within
  `crystalgraphics.warmBudget`, and `VFX.warmed()` says when it is done.
- On a dedicated server, or a client with no world yet, `play` plays nothing.

### Ending an effect

| Call | What happens |
|---|---|
| `effect.stop()` | Its own graceful end: emission ends, what is in flight plays out (a wave's tail runs into its target and bursts) |
| `effect.end(ending)` | An ending the game picks: values eased over its seconds, then gone ([§7](#7-tweens-and-endings)) |
| `effect.kill()` | At once: the effect and its particles are gone by the next particle step |

### Following an entity

```java
VFX.play(new Aura(Aura.LOOK, x, y, z).follow(entityId, CgEntityAttachments.CENTRE));
VFX.play(new HitSpark(look, hitX, hitY, hitZ).followKeepingOffset(targetId, CgEntityAttachments.CHEST));
```

- `follow` moves the effect to the attachment point every frame; when the entity goes, the effect stops.
  `followKeepingOffset` keeps it where it was played, riding the point.
- **Built-in points**: `FEET`, `CENTRE`, `CHEST`, `HEAD`, `EYES`, `RIGHT_HAND`, `LEFT_HAND`, `RIGHT_HAND_FORWARD`,
  `LEFT_HAND_FORWARD`, `HANDS_FORWARD` (names `crystalgraphics:feet` and so on).
- **What follows and what stays**: particles stay where they were spawned unless their emitter is `.local()`; what
  the effect draws itself moves with it. An effect that throws parts into the world overrides
  `followed(x, y, z)` to move only its source: the wave's muzzle rides the hands, its beam and blast stay put.
- **A point of your own, on any model** (playerAnimator, Emotecraft, GeckoLib):

  ```java
  public static final int PALM = CgEntityAttachments.declare("mymod:right_palm", CgEntityAttachments.RIGHT_HAND);

  // where the model is drawn, once a frame:
  CgEntityAttachments.mark(entityId, PALM, poseStack.last().pose(), x, y, z);   // a vanilla model's render layer
  CgEntityAttachments.mark(entityId, PALM, bone.getWorldPosition());            // GeckoLib
  ```

  A mark wins over a registered `Provider`, which wins over the fallback's proportions. Marking a built-in point
  moves every effect already following it. A point at a share of the body's height:
  `CgEntityAttachments.declareAtHeight("mymod:strike", 0.7)`.

### Starting part-way through

```java
VFX.play(new Storm(Storm.LOOK, x, y, z).startAt(12f));   // as if it had been playing for 12 seconds
```

Call it before its first tick. The effect's ticks run exactly up to that age (up to
`CgVfxSystem.MAX_START_SECONDS`; past it, its age jumps), its particles prewarm over the last two seconds, and its
shakes and moments stay silent until it gets there. This is how a player joining late sees a lasting cue.

### Other controls

- **`effect.ground(y)`** gives particles a floor where the host has no world (the debug harness).
- **`system.timeScale(s)`** slows or speeds every effect in a system.
- **The player's settings apply by themselves**: particle density, quality tier, pause, tick rate. An effect never
  reads them.

### A system of your own

A preview viewport, a test or a harness scene keeps its own and drives it:

```java
CgVfxSystem vfx = new CgVfxSystem();
CgVfxOwner lab = CgVfx.owner("mymod", vfx);
lab.play(effect);
// every frame, before the world stages record (a CgWorldRenderer.onFrame listener):
vfx.update(seconds);
vfx.submit(CgWorldRenderer.get());
```

---

## 5. Parameters: what a look carries

A parameter is a dial an artist turns: a beam's radius, its colours, how long it charges. Effects and their
elements read them every tick and shaders read them every draw, so changing one changes the effect.

### Declaring them

```java
public static final CgVfxSchema SCHEMA = CgVfxSchema.of("mymod:bolt");
public static final CgVfxParam.Scalar RADIUS = SCHEMA.length("radius", 0.4f);             // blocks
public static final CgVfxParam.Scalar REACH = SCHEMA.length("reach", RADIUS, 6f);         // RADIUS × 6 unless set
public static final CgVfxParam.Scalar SPEED = SCHEMA.speed("speed", 30f);                 // blocks a second
public static final CgVfxParam.Scalar LIFE = SCHEMA.time("life", 0.8f);                   // seconds
public static final CgVfxParam.Scalar SPIN = SCHEMA.rate("spin", 4f);                     // radians a second
public static final CgVfxParam.Scalar FLICKER = SCHEMA.scalar("flicker", 0.3f);           // no unit
public static final CgVfxParam.Color CORE = SCHEMA.color("core", 0xFFFFFFFF);
public static final CgVfxParam.Color SHELL = SCHEMA.color("shell", 0xFF1858FF, 1.6f);     // rgb × 1.6: past white
public static final CgVfxParam.Palette BANDS = SCHEMA.palette("bands", 0xFFFFFFFF, 0xFF66D9FF, 0xFF1A73FF);
public static final CgVfxParam.Curve GLOW = SCHEMA.curve("glow",
        CgKeyframes.start(0f, 0f).to(0.2f, 1f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.LINEAR).build());
public static final CgVfxParam.Shake HIT = SCHEMA.shake("hit", CgCameraShakes.IMPACT);
```

- **Five kinds**, each its own type: `Scalar`, `Color`, `Palette`, `Curve`, `Shake`. A colour handed where a number
  belongs does not compile.
- **Every number has a unit** (`CgVfxUnit`: `LENGTH`, `TIME`, `SPEED`, `ANGLE`, `RATE`, `NONE`). Tools show it, and
  `scaled(s)` scales lengths and speeds alone.
- **Colours are `0xAARRGGBB`**, each channel 0 to 255 as 0 to 1, in the space shaders author colour in (sRGB, as
  Minecraft's). A colour past white, which is what blooms, is the int and an intensity multiplying its rgb, as
  Unity's HDR colours are. The alpha is whatever the reader decides: usually a strength. Schemas, looks, endings and
  cues take colours this way alone; a playing effect and its values also take four floats, for colours worked out in
  code.
- **Declare every parameter before the first look including its schema is built**: building one freezes the schema,
  and a declaration after throws.
- **A name is a network field's** ([§10](#look-parameters-and-data-of-its-own)): renaming one is a network change.
- **A camera shake is a parameter**, so a look can change it. Never hard-code shake numbers in an effect.

### Links: sizes that follow

A scalar can follow another, times a factor: the wave's head, charge ball, contact and blast all follow `RADIUS`, so
a look sets the radius and the rest follows.

```java
public static final CgVfxParam.Scalar REACH = SCHEMA.length("reach", RADIUS, 6f);   // declared linked

CgVfxLook look = BOLT.derive()
        .link(CgVfxCharge.RADIUS, CgEnergyWave.RADIUS, 0.8f)   // linked in this look
        .set(REACH, 3f)                                          // set outright: no longer follows
        .build();
```

- A link follows on the look, on a playing effect, under a tween and through a cue.
- Setting a linked number breaks its link; `link` makes one again.
- **`scaled(s)`** multiplies every length and speed as they stand and every emitter of the look by `s`: a look
  authored at one size played at another. A kit whose emitters scale with one of its lengths
  (`scalesWith(RADIUS, 0.8f)`) is left to that length, so it is not scaled twice.

### On a look, or on one effect

```java
CgVfxLook big = LOOK.derive().set(RADIUS, 1.2f).build();   // a new look: every effect played from it
wave.set(CgEnergyWave.RADIUS, 1.2f);                        // this one effect, from now on
wave.set(CgEnergyWave.SHELL, 0xFFFF2020, 1.5f);
float r = wave.get(CgEnergyWave.RADIUS);
```

A look never changes once built (except under a tuning, [§8](#8-tuning-a-look-live-and-saving-it)); a variant is a new
look. A value set on a server's authoritative effect reaches every viewer ([§11](#syncing-parameters)).

### Many schemas in one look

A look lays out every schema it includes (`CgVfxLayout`): its effect's first, then each kit's, then any it adds with
`params(...)`. The Kamehameha's carries the wave's, the charge's, the body's, the contact's, the blast's and the
explosion's. A parameter is read the same way whichever schema it is on: `wave.get(CgVfxCharge.RADIUS)`.

`look.describe()` prints every value and every layer's resolved bindings: the first thing to read when a look does
not draw what you expect.

---

## 6. Kits, layers and slots: how a look draws

### Composing a look from kits

```java
public static final CgVfxKit BANDS = CgVfxKit.of("mymod:wave/bands")
        .layer(CgVfxLayer.of("mymod:shaders/vfx/flat.shader").on(CgBeamBody.BODY, CgBeamBody.HEAD)
                .size(1.8f).bind("_Color", CgEnergyWave.SPIRAL).build())
        .layer(CgVfxLayer.of("mymod:shaders/vfx/flat.shader").on(CgBeamBody.BODY, CgBeamBody.HEAD)
                .size(1.4f).bind("_Color", CgEnergyWave.SHELL).build())
        .build();

CgVfxLook mine = CgEnergyWave.kamehameha().derive()
        .without(CgEnergyWave.REALISTIC_BODY, CgEnergyWave.REALISTIC_HEAD)
        .with(BANDS)
        .build();

CgVfxLook swapped = CgEnergyWave.kamehameha().derive().replace(CgEnergyWave.REALISTIC_BODY, BANDS).build();
CgVfxLook quieter = CgEnergyWave.kamehameha().derive().without(CgVfxBlast.SHOCKWAVE).build();   // a kit inside a kit
```

- **A kit** holds layers, emitters, the schemas they read (`params(...)`) and other kits (`kit(...)`).
- **`without(kit)`** drops its layers and emitters, inside other kits or not; its parameters stay, since effect code
  may read them. Dropping a kit the look does not hold throws.
- **`replace(a, b)`** puts `b` where `a` was in the draw order.
- A look may also take a loose `layer(...)` or `emitter(...)`; `emitter` replaces one of the same name where it is,
  which is how a look changes one element's physics.

### A layer

```java
CgVfxLayer shell = CgVfxLayer.of("crystalgraphics:shaders/vfx/beam/body_shell.shader")
        .on(CgBeamBody.BODY)                  // the slots it draws on: one or several
        .size(1.15f)                          // times the slot's own size
        .set("_Flow", 22f)                    // a constant
        .build();                             // _Shell and _ShellHot bind to SHELL and SHELL_HOT by name

CgVfxLayer flames = CgVfxLayer.of("mymod:shaders/vfx/ends_aura.shader")
        .on(CgBeamBody.ARCS)
        .bind("_Color", CgEnergyWave.SHELL)            // a colour, read every draw
        .bind("_Radius", CgEnergyWave.RADIUS)          // a number, read every draw: it shrinks as the wave fades
        .bind("_Reach", CgEnergyWave.RADIUS, 2.6f)     // RADIUS × 2.6
        .texture("_Aura", AURA_FRAMES)
        .set("_Spread", 0.25f, 0.7f, 0f, 0f)
        .build();
```

| Call | Does |
|---|---|
| `on(slot...)`, `on(emitter...)` | The slots it draws on, or the emitters whose particles it draws |
| `size(times)`, `size(param)` | Its size, times its slot's own; or following a number, which can then be tuned, tweened and sent |
| `set(property, ...)` | A constant: one float, two, four, or `setArray` for an array property |
| `bind(property, param)`, `bind(property, number, factor)` | Read from the effect's own values every draw |
| `texture(property, texture)` | A texture ([below](#textures)) |
| `role(role)` | Where it draws in its effect ([below](#draw-order)) |
| `from(CgQuality.MEDIUM)` | Drawn only at that quality tier and above: detail a lower tier drops |
| `volume()` | A tube chunk drawn on a sphere round it, for a shader summing its own chunk's light (`body_light`) |
| `closed()` | A tube chunk closed at both ends, for a shader tracing the view ray through it (`body_core`) |
| `keyword(name)` | A `#pragma cg_feature` keyword on its materials |
| `unbound(property)` | Keeps a colour the shader's own, never bound by name |
| `named(name)` | What tools and tunings call it; its shader's file name unless given |

**How a property gets its value**, resolved once when the layer first draws in a look:

1. `set` makes it a constant of the layer's material.
2. `bind` reads it from the effect's values every draw.
3. Else a **colour or palette** property reads the look's colour or palette of its name, the underscore dropped and
   the first letter lowered: `_ShellHot` reads `shellHot`, `_Bands` reads `bands`. **A number never binds by name**:
   a shader's `_Speed` is not the effect's `speed`.
4. Else the shader's default.

- A name the shader does not declare, or a value of the wrong kind, throws at the layer's first draw and names the
  properties the shader has.
- Bound values are written once a frame per effect and layer into a row the shader reads ([§15](#15-writing-a-layers-shader)),
  so effects playing different values still batch.
- A layer's constants are its material's: two looks sharing a kit share its layers' materials.

### Slots

An element declares where it draws, and a kit's layers say which of those they draw on:

```java
public static final CgVfxSlot BODY = CgVfxSlot.tube("crystalgraphics:wave/body");
public static final CgVfxSlot HEAD = CgVfxSlot.mesh("crystalgraphics:wave/head");
public static final CgVfxSlot ARCS = CgVfxSlot.pathRibbons("crystalgraphics:wave/body/arcs");
```

| Kind | Geometry | Drawn with |
|---|---|---|
| `TUBE` | chunked tube draws along a path | `frame.tube(this, slot, path, row)` |
| `MESH` | a unit sphere at a transform | `frame.mesh(this, slot, x, y, z, transform, ex, ey, ez, ew)` |
| `RIBBONS` | stateless ribbons a shader places, in a unit cube at a transform | `frame.ribbons(...)` |
| `PATH_RIBBONS` | ribbons riding a path | `frame.pathRibbons(this, slot, path, row, intensity)` |
| `BILLBOARD` | one camera-facing quad | `frame.billboard(...)` |
| `PARTICLES` | an emitter's particles: the emitter is its slot (`emitter.slot()`) | `frame.particles(this, instance)` |

- **A frame draws a slot, not a layer**: every layer of the look on that slot draws, in the look's order. A slot no
  layer draws on costs nothing, and `frame.draws(this, slot)` skips the work of preparing it.
- **One shader on several kinds**: a layer on a tube and a sphere makes a material per kind and enables the kind's
  keyword (`FX_ON_TUBE`, `FX_ON_MESH`, ...) where the shader declares it.
- Each kind's per-draw customs are documented once, on `CgVfxSlot.Kind`; each element documents what its slots' four
  numbers mean.

### Draw order

Within an effect, layers draw by **role**, then in the order the look holds them. Nothing is numbered by hand.

| Role | Draws | For |
|---|---|---|
| `SMOKE` | first | smoke, dust, soft matter |
| `LIGHT` | next | light pools on what is round the effect |
| `DISTORTION` | next | haze and shock fronts: a shader with a Distortion pass, bending what drew before it |
| `SURFACE` | last (the default) | the effect itself: its body, glows, sparks. Drawn after its own effect's distortion, so its haze never smears it |

A look draws at most 256 layers. A layer whose shader has a Distortion pass must be `DISTORTION`, and only one
that has may be; the first draw says which is wrong.

### Textures

```java
static final CgTexture2D SLEEVE = CgTexture2D.create("mymod:textures/vfx/sleeve.png", CgTextureSpec.RGBA8_LINEAR_REPEAT);
static final CgTexture2DArray FLAMES = CgTexture2DArray.frames(CgTextureSpec.RGBA8_LINEAR,
        "mymod:textures/vfx/flame_%d.png", 3);                                                // sampler2DArray

layer.texture("_Strip", SLEEVE).texture("_Aura", FLAMES).texture("_Sheet", madeAtRunTime)   // yours to free
```

- A layer takes any `CgTexture`. One from files is named in common code and loads at its first use, so a server
  that names it never decodes it.
- Its sampling is its spec's: a file is cached once per spec, so one image sampled two ways is two textures.
- `CgTexture2DArray.frames`, or `create(spec, paths...)`, makes one `sampler2DArray`: a flipbook, or a set of paintings
  to pick from, past a material's sampler limit. Images of different sizes are each centred on a layer the largest
  one's size.
- An image that will not load is logged and drawn as the checkerboard, a missing layer as clear: `isMissing()`.

---

## 7. Tweens and endings

### A tween

```java
wave.animate(CgEnergyWave.RADIUS, 1.4f, 0.5f, CgEasings.OUT_BACK);         // powering up
aura.animate(Aura.CORE, 0xFFFF3030, 0.25f, CgEasings.LINEAR);               // a colour
```

- It steps every tick, so it is smooth at any frame rate; a tween on a number moves what follows it by a link.
- Setting the value (`set`) stops the tween on it.
- On a server's authoritative effect it plays on every viewer at once, sent as one event rather than a value a tick.

### An ending

```java
// declared once, by name, in code both sides run
public static final CgVfxEnding FIZZLE = CgVfxEnding.over("mymod:bolt/fizzle", 0.4f)
        .animate(Bolt.RADIUS, 0f, CgEasings.IN_QUAD)
        .animate(Bolt.CORE, 0x00FFFFFF, CgEasings.LINEAR)
        .build();

bolt.end(FIZZLE);                         // eased, then gone
aura.end(CgVfxEnding.FADE_OUT);           // any effect: its presence to nothing, over 0.3 s
wave.end(CgEnergyWave.FADE);              // the wave's own: it thins away where it is, no blast
```

- **An ending is not `stop()`**: `stop` is the effect's own graceful end (the wave's tail runs out and bursts). An
  ending is one a look or a game picks; the effect hears it in `ending(CgVfxEnding)` to skip what a graceful end would
  play.
- `FADE_OUT`, and any ending built with `.fadeOut(easing)`, eases the effect's **presence** to 0, which every engine
  shader multiplies its light by. A fade of another length is an ending of its own, declared as above.
- Its name travels: declare it before a cue ends with it. Its easings travel by their place in `CgEasings`: one made
  by a factory reaches viewers as linear.
- On a cue: `playing.end(FIZZLE)`; a late joiner starts it where it is.

---

## 8. Tuning a look live, and saving it

Open a look for tuning and every number and colour of it, and every constant its layers set, becomes a variable:
set from the console, a tuning panel or a remote tool. The look takes a change at once, every effect playing it at
its next tick, every effect played after starts from it.

```java
CgVfxTuning tuning = CgVfxTuning.open("vfx.kamehameha", CgEnergyWave.kamehameha());
CgVars.get("vfx.kamehameha.radius").setText(CgVar.Source.CONSOLE, "0.9");
CgVars.get("vfx.kamehameha.shell.g").setText(CgVar.Source.CONSOLE, "0.8");                      // r, g, b, a
CgVars.get("vfx.kamehameha.crystalgraphics.charge.radius").setText(CgVar.Source.CONSOLE, "1.5");  // a kit's schema
CgVars.get("vfx.kamehameha.layer.body_shell._Flow").setText(CgVar.Source.CONSOLE, "30");         // a layer constant
tuning.save(Paths.get("src/main/resources/assets/mymod/vfx/kamehameha.yaml"));                  // what changed
tuning.close();
```

**Overrides in a file**, shipped with the mod and edited live in a dev run:

```java
CgVfxTuning.open("vfx.kamehameha", LOOK).follow("mymod:vfx/kamehameha.yaml");   // loaded now, and on each save
```

```yaml
radius: 0.9
shell.g: 0.8
layer.body_shell._Flow: 30.0
```

| Variable | Named |
|---|---|
| A parameter of the effect's own schema | as declared: `radius`, `core.r` |
| Another schema's | after its schema, `:` and `/` as dots: `crystalgraphics.charge.radius` |
| A palette's entry | `bands.1.g` |
| A layer's constant | `layer.<shader name>.<property>`, `_2` and on where two layers share a name; a vector's `x y z w` |

- Hot reload is the engine's own ([`ENGINE_API.md`](ENGINE_API.md) § *Hot reload*): saving the file in a dev run's
  source folder applies it at the next frame. A file that will not parse keeps the values it had.
- A file's values are `CgVar.Source.FILE`'s, under the console's; a key the look has not is logged.
- A layer's constant is the layer's: every look drawing that layer takes it. Only constants the layer `set`s are
  variables; a shader's own defaults are not.
- Curves and shakes are not variables. One tuning a prefix at a time; clearing a variable puts back the value it was
  opened with.

---

## 9. The energy wave

`CgEnergyWave` (`vfx.effect.beam`) charges at a muzzle, fires a beam that flows out, bends where its source turns,
homes on a target or is steered by a player's aim, stops where it hits, and bursts into a blast as its tail runs
out into what it hit.

```java
CgEnergyWave wave = VFX.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z)
        .follow(playerId, CgEntityAttachments.HANDS_FORWARD).hold());   // charging until fired
wave.aim(dx, dy, dz).target(tx, ty, tz);   // each tick, as the source turns
wave.collide();                            // or: find its own target along the aim
wave.fire();
wave.stop();                               // the tail runs out into the target, which bursts
```

### Made of elements

| Element | Owns | Its kit in the engine's looks |
|---|---|---|
| `CgVfxCharge` | the ball growing over `TIME` along `SIZE`, streaks and arcs, the release's flash and shock ring, the root it settles to | `REALISTIC_CHARGE` |
| `CgBeamBody` | the stream and its path, the body's profile (launch swell, throb, pulse, surges), the head | `REALISTIC_BODY`, `REALISTIC_HEAD` |
| `CgBeamContact` | where it hits while it hits: the orb, splash, rings, the hit shake | `REALISTIC_CONTACT` |
| `CgVfxBlast` | the blast, with its impact frame before it: beats, the cut, flash and impact volumes, the front hitbox | `CgVfxBlast.KIT`: `FIREBALL`, `HEART`, `SHOCKWAVE`, the explosion's emitters |
| `CgBeamWake` | dust torn off the ground under the body | `WAKE` |

Each element's schema, slots and moments are on its class. Any effect may use them ([§14](#built-from-elements)).

### Its looks

| Look | What it is |
|---|---|
| `kamehameha()` | every realistic kit, blue-white |
| `finalFlash()` | the same kits, gold, `RADIUS` 1 and faster |
| `galickGun()` | the same kits, violet |
| `toon()` | the Kamehameha with `TOON` for its body, ball and head: three flat bands of `BANDS` and a soft halo, one draw a slot |

### Its parameters

| Parameter | Default | Meaning |
|---|---|---|
| `RADIUS` | 0.8 | the body's radius: every link follows it |
| `SPEED` | 40 | how fast energy leaves the muzzle |
| `TURN_RATE`, `NAVIGATION` | 4, 3 | how hard it homes (proportional navigation) |
| `STEER` | 0 | above 0, steered: a curve from the muzzle along the aim into an end that chases the target, turning at most this many radians a second |
| `MAX_LENGTH` | 120 | how far the head flies when it hits nothing |
| `MUZZLE_FORWARD` | 0 | how far along the aim the drawn wave starts once fired: a caster's hands are the muzzle, the beam clears their body. Its casts still start at the muzzle |
| `CORE`, `CORE_RIM`, `SHELL`, `SHELL_HOT`, `SPIRAL`, `GLOW` | | the energy palette its realistic kits read by name |
| `CgBeamBody.HEAD_WIDTH`, `HEAD_LENGTH` | `RADIUS` × 1.45, × 1.9 | the head |
| `CgVfxCharge.RADIUS`, `ROOT_SIZE` | `RADIUS` × 1.7, 0.8 | the ball, and the root it settles to once fired |
| `CgVfxBlast.RADIUS` | `RADIUS` × 12 | the blast |

### A wave of your own drawing

Dragon Forge's anime wave is the engine's wave with its realistic drawing swapped out:

```java
public static final CgVfxLook KAMEHAMEHA = CgEnergyWave.kamehameha().derive()
        .without(CgEnergyWave.REALISTIC_BODY, CgEnergyWave.REALISTIC_HEAD, CgEnergyWave.REALISTIC_CHARGE,
                CgEnergyWave.REALISTIC_CONTACT, CgEnergyWave.WAKE, CgVfxBlast.HEART, CgVfxBlast.SHOCKWAVE)
        .with(KiWaves.KI)                                                    // its bands, flames, sleeves, sparkles
        .set(CgEnergyWave.RADIUS, 0.3f)
        .link(CgBeamBody.HEAD_WIDTH, CgEnergyWave.RADIUS, 2.38f)
        .set(CgEnergyWave.MUZZLE_FORWARD, 1.2f)
        .set(CgBeamBody.THROB, 0f)
        .set(CgEnergyWave.SHELL, 0xFF00FFFF)
        .build();
```

- Bind your layers' sizes to `RADIUS` (`bind("_Radius", CgEnergyWave.RADIUS)`, `size(...)`), and `FADE` thins the whole
  of it.
- A look must carry its elements' schemas: derive it from one of the wave's looks, or add them with `params(...)`.
  The wave's constructor says which is missing.

---

## 10. Effects over the network: cues

On a server you cannot call `VFX.play`: nothing draws there. You send a **cue**, and every client that should see it
builds and plays the effect. The model is Unreal's gameplay cues.

Each play is a networked object (`net.object`, [`NETWORKING.md`](NETWORKING.md) § 6): the network layer decides who
sees it, sends what changed once a tick, and starts a player who comes into view at its age. `Playing.object()`
reaches it when you need it.

### Which kind do I want?

| You want | Use | Late joiners see it? |
|---|---|---|
| A one-off: a hit spark, a puff, an explosion | `cue.at(...).send()` | no: it is gone once played |
| Something that lasts: an aura, a beam, a burning crater | `cue.at(...).start()`, a `Playing` | yes, started at its current age |
| A client showing its own action at once | `cue.at(...).predict()` on the client | the server decides |
| An effect gameplay depends on | an `authoritative()` cue, `start()`ed | yes |

### Declaring a cue

Once, as a constant, in code both sides run, at init, before any player joins:

```java
public static final CgVfxCue HIT = VFX.cue("hit_spark", HitSpark.LOOK,
        (look, cue) -> new HitSpark(look, cue.x(), cue.y(), cue.z())).build();
```

| Call | Does |
|---|---|
| `.range(96)` | How far round its point a play reaches when it follows nothing; 64 blocks unless said |
| `.fields(LEVEL, GUARDED)` | Data of its own, beyond its look ([below](#look-parameters-and-data-of-its-own)) |
| `.authoritative()` | The server runs it too ([§11](#11-effects-gameplay-depends-on-server-authority)) |
| `.persisted()` | Lasting plays are saved with the world and resume when the server starts. Not with `follow` or `authoritative()` |

- **Finish with `build()`**: a cue is a network type, fixed once built.
- **When what it plays uses client-only classes**, declare the cue alone in common and register what it plays on the
  client:

  ```java
  public static final CgVfxCue HIT = VFX.cue("hit_spark").fields(LEVEL).build();                     // common
  VFX.register(HIT, HitSpark.LOOK, (look, cue) -> new HitSpark(look, cue.x(), cue.y(), cue.z()));   // client init
  ```

  Declared without its look, a cue carries none of the look's parameters: send what varies as fields of its own.
- **A cue a client has nothing registered for** is dropped there, with one warning per id.

### Sending a one-off

```java
HIT.at(level, x, y, z).send();
HIT.at(level, x, y, z).toward(dx, dy, dz).follow(target, MyMod.BODY).keepOffset().set(HitSpark.SIZE, 2f).send();
```

- `level` is a level, an entity in it, or a dimension id.
- **Who receives it**: the players tracking what it follows; otherwise everyone within the cue's range.
- It goes with the tick's other changes, at the end of the server tick. A player who predicted it is not sent it again.

### Starting a lasting one

```java
CgVfxCue.Playing aura = AURA.on(player, CgEntityAttachments.CENTRE).start();
aura.set(Aura.INTENSITY, 3f);                               // every viewer, at the end of this tick
aura.animate(Aura.INTENSITY, 0f, 2f, CgEasings.OUT_QUAD);   // a tween, run on every viewer at once
aura.end(CgVfxEnding.FADE_OUT);                             // or stop(), or kill()
```

- **`on(entity, point)`** is "at this entity, following it at this point, in its level".
- It ends by `stop()`, `end(ending)`, `kill()`, the play's `.lifetime(seconds)`, or the entity it follows dying.

### Changing a lasting one

| Call | Viewers get |
|---|---|
| `toward(dx, dy, dz)`, `target(x, y, z)` | a new direction, a new target point |
| `set(PARAM, value)`, `set(COLOUR, argb)` | a number or colour of its look |
| `animate(PARAM, to, seconds, easing)` | a tween, as one event |
| `set(FIELD, value)` | one of its own fields |
| `moveTo(x, y, z)` | a new position, and who is in range is worked out again |

A field is sent only when its value changed. On each viewer, look values are set on its effect as they arrive; then
an effect implementing `CgVfxCue.Live` takes the rest once a frame (the wave takes its aim and target).

### Look parameters and data of its own

Every number and colour of a cue's look, across every schema it includes, is a float field of the cue, so
`set(Blast.RADIUS, 6f)` reaches each viewer's effect with no code in the factory. Curves and camera shakes do not
travel.

Data the look has no parameter for is a field the cue declares:

```java
public static final CgNetField<Integer> LEVEL = CgNetField.ofInt("level");
public static final CgNetField<Boolean> GUARDED = CgNetField.ofBool("guarded");
public static final CgVfxCue HIT = VFX.cue("hit_spark").fields(LEVEL, GUARDED).build();

HIT.at(level, x, y, z).setInt(LEVEL, 2).setBool(GUARDED, true).send();              // server
VFX.register(HIT, HitSpark.LOOK, (look, cue) ->                                     // client
        new HitSpark(look, cue.x(), cue.y(), cue.z(), cue.getInt(LEVEL), cue.getBool(GUARDED)));
```

- A field never set reads as its fallback: 0, false, null, or what `.fallback(v)` said.
- `CgNetField.ofInt`, `ofFloat`, `ofDouble`, `ofBool`, and `of(name, codec)` for anything else.
- State an effect class publishes itself, whichever cue plays it, is declared on its schema instead
  (`SCHEMA.networked(...)`, [§11](#making-your-own-effect-authoritative)).

### Every client draws the same thing

- **The seed travels with the cue**, so every client's random choices match.
- **An effect following an entity** finds the attachment point from the entity as that client draws it.
- Particles collide with each client's own world, so tiny differences are possible and harmless.

### Predicting from a client

```java
HIT.at(x, y, z).set(HitSpark.SIZE, 2f).predict();                    // client: plays here now
HIT.fromClients((player, play) -> withinReach(player, play));        // server, at init: who may
```

- The server refuses unless the cue allows it, the player is within the rate (20 a second, in bursts of 20) and the
  point is within `range` of them. `refused()` counts refusals.
- Accepted, the server's play takes over the client's own, so the predicting player sees it once. Refused, the
  client's copy plays out alone.
- Only one-offs are predicted, so an authoritative cue never is.

### Cues are for showing, not for state

A plain cue is cosmetic. Anything a player must not miss (damage, health, a status) is the game's to replicate; the
cue only shows it. If the effect itself decides gameplay, make the cue authoritative.

---

## 11. Effects gameplay depends on: server authority

Some effects *are* the gameplay: a beam damages whatever it reaches. If each client decided what it hit, every client
would see a different result. So the server runs the effect too, as the referee, and every client draws what the
server decided.

The server runs **the same effect class**, headless against the server's world (`CgVfxAuthority`): it decides where
the effect goes and what it strikes, hears its hits, and publishes what clients must draw. Unreal and Unity Netcode
run one actor on both sides the same way.

### The whole thing, with the Kamehameha

```java
// common, at init: one declaration, one factory for both sides
public static final CgVfxCue WAVE = VFX.cue("kamehameha", CgEnergyWave.kamehameha(),
        CgEnergyWave::fromCue).range(96).authoritative().build();

// server: start it, then drive its effect exactly as on a client
CgVfxCue.Playing wave = WAVE.on(caster, CgEntityAttachments.HANDS_FORWARD).start();
CgEnergyWave beam = wave.effect();
beam.hold().aim(look.x, look.y, look.z);   // key down

wave.onHit(CgEnergyWave.PART_BEAM, h -> { if (h.kind() == CgHit.Kind.ENTITY) stagger(h.entity()); })
    .onHit(CgEnergyWave.PART_BURST, h -> explode(h.x(), h.y(), h.z(), beam.blastRadius()))
    .onHit(CgEnergyWave.PART_BLAST, h -> damage(h.entity()));

beam.aim(look.x, look.y, look.z);          // each tick, as the caster turns
beam.set(CgEnergyWave.RADIUS, 1.2f);       // powered up: every viewer's beam widens, and the server's ray with it
beam.fire();                               // key up
wave.end(CgEnergyWave.FADE);               // every viewer's thins away; or stop(), and the blast is still heard
```

```java
// client, at init: the hits the server counted, for feedback that must agree with the damage
WAVE.onConfirmedHit(CgEnergyWave.PART_BURST, h -> decals.scorch(h.x(), h.y(), h.z()));
```

### What happens each server tick

1. The server steps its copy on the client's clock (120 ticks a second, six a server tick).
2. The wave tests its aim against the server's world: where the beam ends and what entity is there.
3. What changed is written into the cue's fields and sent at the end of the tick: where the beam ends, the entity it
   hit, the aim, hold and fire, every value set on the effect, and its tweens and endings as events. What it struck
   goes too, as a confirmed hit.
4. Each viewer's copy homes on that end rather than testing its own world, so every player sees the beam end where
   the server says.

### Driving it

- **`wave.effect()`** is the server's copy. Call it exactly as on a client: `aim`, `fire`, `set`, `animate`, `end`,
  `stop`.
- **Stopping the effect stops every viewer's copy.** The cue lasts until the server's copy has played out (at most 10
  seconds), so what it strikes meanwhile still reaches viewers.
- A play started during a tick is published before any viewer hears of it, so the first state viewers receive
  includes what you set right after `start()`.

### Syncing parameters

Any number or colour you `set` on the server's effect reaches every viewer, and anyone who joins later; a tween or an
ending runs on every viewer as one event. This needs no code of the effect's own. Curves and camera shakes do not
travel.

### What is switched off on the server

| Part | On the server |
|---|---|
| Particles | not simulated: emitters do nothing |
| Post effects | not applied: the effect gets a detached volume, so its timing logic runs the same |
| Camera shakes, moments | silent |
| Hits | heard by the effect's own listeners on the server thread, and sent to viewers as confirmed hits |

An effect that ends when its particles finish therefore ends itself on the server.

### Limits and rules

- **Lasting cues only** (`start()`); never `persisted()`.
- **`at(level, ...)` must be a level or an entity in one**: that is the world it tests.
- **The cue must be declared with its factory in common code.**
- **It follows its entity by the attachment point's proportions**: a server has no model, so marked points are a
  client-only refinement.
- What it costs: [§17](#on-the-server).

### Making your own effect authoritative

Most effects need nothing: declare the cue `authoritative()` and values sync by themselves. An effect whose own state
must reach viewers (the wave's end point) adds two interfaces:

```java
public final class Bolt extends CgVfxEffect implements CgVfxCue.Live, CgVfxCue.Authoritative {
    private final CgHit hit = new CgHit();
    private final CgSphere bolt = CgSphere.of(0.3);
    private double endX = Double.NaN, endY, endZ;

    @Override protected void tick(float dt) {
        // world() is the server's world on the server and the client's on a client
        if (world().cast(bolt).at(originX, originY, originZ).along(dirX, dirY, dirZ, 64)
                .source(followingId()).blocksToo().first(hit)) {
            endX = hit.x(); endY = hit.y(); endZ = hit.z();
            hit(hit);                    // heard by onHit; on the server, confirmed to viewers
        }
    }

    // server, after each tick: write what viewers draw; a value written unchanged sends nothing
    @Override public void publish(CgVfxCue.Play cue) {
        if (!Double.isNaN(endX)) cue.target(endX, endY, endZ);
    }

    // every viewer, once a frame that changed the cue
    @Override public void cueChanged(CgVfxCue.Play cue) {
        if (!authoritative() && cue.hasTarget()) endAt(cue.tx(), cue.ty(), cue.tz());
    }
}
```

State with no place in a play is fields its schema declares, which every cue playing the effect then carries:

```java
public static final CgNetField<Boolean> CHARGING = CgNetField.ofBool("bolt/charging");
static { SCHEMA.networked(CHARGING); }

cue.setBool(CHARGING, charging);                 // in publish
if (cue.getBool(CHARGING)) ...                   // in cueChanged, and in the factory
```

- **`authoritative()`** on the effect is true for the server's copy: guard anything client-only with it.
- **`cue.authoritative()`** in a factory is true while the server builds its copy: one factory configures both sides
  (`CgEnergyWave.fromCue` turns on its own collision this way).

### When it never hits

- **Is the target in the beam?** A target summoned under a hovering caster falls out of it: summon test targets with
  `{NoGravity:1b}`.
- **Was `at(...)` given a level or an entity?** If not, the log says the authority tests no world.
- **Trace it**: `-Dcrystalgraphics.vfx.traceAuthority` (or the console, `docs/DEBUG_FLAGS.md`) logs every server cast.

---

## 12. Hits: what an effect strikes

An effect strikes through the collision engine ([`COLLISION.md`](COLLISION.md)): the queries it makes through
`world()` and the hitboxes it owns. Each thing it strikes is a `CgHit`, and `onHit` hears them:

```java
CgEnergyWave wave = VFX.play(new CgEnergyWave(look, x, y, z).follow(playerId, HANDS_FORWARD).collide());
wave.onHit(h -> { if (h.kind() == CgHit.Kind.ENTITY) flash(h.entity()); });
```

### Parts

An effect with more than one thing that strikes declares each as a **part** on its schema, and every hit names its
part:

```java
wave.onHit(CgEnergyWave.PART_BEAM, h -> flash(h.x(), h.y(), h.z()))
    .onHit(CgEnergyWave.PART_BURST, h -> shatter(h.x(), h.y(), h.z(), wave.blastRadius()))
    .onHit(CgEnergyWave.PART_BLAST, h -> knockBack(h.entity()));
```

A listener on the whole effect hears every part, and `h.part()` says which (compare with `==`).

| Field | Meaning |
|---|---|
| `kind()` | `ENTITY`, `HITBOX` (a hittable hitbox: `entity()` is its owner), `BLOCK`, `FLUID`, `UNLOADED`, `BORDER`, `POINT` (nothing: the point it was aimed at, or where it burst in the air) |
| `entity()`, `parent()` | Minecraft's entity met, and the whole one for a part of a multipart entity |
| `entityId()`, `parentId()` | the same by network id; -1 otherwise |
| `blockX() blockY() blockZ()` | the block met |
| `x() y() z()` | where |
| `normalX() normalY() normalZ()`, `distance()` | toward what it met, and how far it travelled; 0 on a confirmed hit |
| `part()` | which part struck |

- **The hit is valid during the call**: copy what you keep (`new CgHit().set(h)`).
- **An effect reports a contact as it starts**, and again when what it strikes changes, never every tick. A hitbox
  hits each target once.
- **Listeners hear on the thread the effect ticks on.** One that throws is logged and the others still hear.
- **The wave** reports `PART_BEAM` as its head reaches what it hits, `PART_BURST` once where it bursts after its
  impact frame, then `PART_BLAST` for each entity as the shock front reaches it, out to `blastRadius()`.

### Particles

A particle's contact is not a hit: it happens on the GPU, comes back a few frames late, and only on a client. An
emitter that wants its contacts reports them as an event, and a system listener hears the rows, each tagged with the
effect whose particle it was:

```java
.module(new CgVfxModule.CollideWorld(0.3f, 0.4f, 0.4f))
.event(CgVfxEvent.onCollision().readback(32))   // at most 32 rows a step, shared by every effect playing this emitter

CgVfx.world().onEvents((definition, event, rows) -> {   // render thread
    for (int i = 0; i < rows.count(); i++) {
        if (rows.hit(i) == CgVfxEventRows.HIT_BLOCK) decals.scorch(rows.x(i), rows.y(i), rows.z(i));
    }
});
```

Never base gameplay on them.

### Damage on the server, sparks on every client

```java
// server
WAVE.on(caster, CgEntityAttachments.HANDS_FORWARD).start()
        .onHit(CgEnergyWave.PART_BLAST, h -> damage(h.entity()));

// client, at init
WAVE.onConfirmedHit(CgEnergyWave.PART_BLAST, h -> VFX.play(new CgVfxBurst(SPARKS, h.x(), h.y(), h.z())));
```

A confirmed hit arrives a network trip after the server's. For feedback timed to what this client draws, listen to the
viewer's own copy: the factory builds both copies, and `cue.authoritative()` tells them apart.

```java
public static final CgVfxCue WAVE = VFX.cue("kamehameha", CgEnergyWave.kamehameha(), (look, cue) -> {
    CgEnergyWave wave = CgEnergyWave.fromCue(look, cue);
    if (!cue.authoritative()) wave.onHit(CgEnergyWave.PART_BEAM, h -> flash(h.x(), h.y(), h.z()));   // each viewer
    return wave;
}).range(96).authoritative().build();
```

- Both land where the damage does: a viewer's copy reports where the server published the beam ending.
- A late joiner gets no sparks for hits it missed: hits are silent while an effect fast-forwards to its age.
- A visual that depends on the outcome (blocked, a critical hit) is something only the server knows: send a one-off
  cue from the server's listener.

---

## 13. Particles

Particles are simulated entirely on the GPU, so hundreds of thousands are affordable. You describe them as data (an
emitter), and the engine compiles that into GPU programs.

### An emitter

```java
static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("sparks").renderer(CgVfxEmitter.Renderer.QUADS)
        .capacity(1200)                        // the most alive at once
        .rate(120f, 0f, 4f)                    // 120 a second, from 0 s to 4 s
        .shape(1.2f)                           // born anywhere in a ball of radius 1.2
        .launch(-1f, -0.7f, 1f).speed(0.5f, 2f)
        .life(2.5f, 3f)
        .size(0.08f, 0.14f, 1f).heat(1f)
        .module(new CgVfxModule.Gravity(9.8f))
        .module(new CgVfxModule.CollideWorld(0.35f, 0.4f, 0.4f))   // bounces off blocks
        .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
        .build();
```

### In a look

The emitter goes in a kit or a look, and a layer draws its particles:

```java
static final CgVfxSchema SCHEMA = CgVfxSchema.of("mymod:sparks");
static final CgVfxParam.Color EMBER = SCHEMA.color("ember", 0xFFFF4A0B, 1.2f);
static final CgVfxParam.Color FLAME = SCHEMA.color("flame", 0xFFFFC85B, 1.4f);

static final CgVfxKit KIT = CgVfxKit.of("mymod:sparks").emitter(SPARKS)
        .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/particle/spark.shader").on(SPARKS)
                .bind("_Color", EMBER).bind("_Hot", FLAME).build())
        .build();
static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).with(KIT).build();

VFX.play(new CgVfxBurst(LOOK, x, y, z));   // particles alone need no effect class
```

**Shared particle shaders** in `shaders/vfx/particle/`, each reading `_Color` and, where it has one, `_Hot`:

| Shader | Draws |
|---|---|
| `spark` | a glowing disc streaked along its motion |
| `speck` | ragged debris (`_Color` only) |
| `dust` | soft ground dust that pools into haze |
| `arc` | an ink stroke |
| `ray` | a hot streak along its velocity |
| `ring` | a shock front's foot glowing as it runs out |
| `sprite`, `sprite_glow` | a frame of a flipbook sheet, lit or glowing (`_Color` only) |

The explosion (`CgVfxBlast.EXPLOSION`, a `CgVfxExplosion`) is a whole kit of them: billows, surge, specks, puffs,
sparkles, ink, rays, rings and skirt, with its own colours (`body`, `hot`, `debris`, `dust`, `sparkCore`).

### Things to know

- **Capacity is a hard limit**: a spawn into a full emitter is dropped. Size it to the burst plus what is still alive.
- **Variants**: `SPARKS.toBuilder()...build()`. **The same burst bigger or smaller**: `SPARKS.scaled(s)` scales shape,
  speed, size and forces, keeping the timing.
- **`.optional()`** marks detail: halved at the Low quality tier. The player's density setting thins every emitter.
- **`shape(inner, outer)`** spawns on a shell; **`sweep(speed)`** runs that shell outward, so particles are born on a
  moving front.
- **Each layer on particles draws as one batch for the whole system**, sorted far to near within itself, so two
  layers order as wholes. A solid look writes depth (`"Depth" = "Clip"`); soft smoke a depth prepass where it is
  solid (`"Depth" = "Prepass"`); only something soft throughout relies on blending
  ([`SHADERS.md`](SHADERS.md) § *Depth from a transparent material*).
- Particles step 60 times a second while effects tick 120 times, so a particle is drawn at `frame.particleAlpha()`.
- A particle always belongs to an effect: one-off particles are a `CgVfxBurst` each. A shared emit call for
  Minecraft-style spam is planned (plan `vfx-gpu` X15).

### Modules: what moves a particle

Modules run in order every step. Forces come first and are integrated together; a module after the solver
(collisions, kills) sees where the particle went.

| Module | Does |
|---|---|
| `Gravity`, `Force` | A constant pull down; a constant push in any direction |
| `Drag`, `Damping`, `LimitSpeed` | Linear and quadratic drag; velocity decaying by a rate; a speed cap |
| `Wind`, `Turbulence`, `Buoyancy`, `Updraft` | The system's gusting air; curl noise; hot particles rising as they cool; a column of rising air |
| `Attract`, `Orbit`, `Vortex`, `Conform` | Pull toward a sphere or box; circling an axis; a vortex's pull; settling onto a sphere's shell |
| `VectorField` | A 3D field (`CgVfxField`) in a box, setting velocity |
| `Spin` | Spin rate decaying |
| `Ground` | Landing on the effect's ground: bounce, friction, rest |
| `Current` | Dust carried along the ground by a blast's gravity current |
| `Collide` | A sphere, box, plane or container: bounce, friction, or kill on contact |
| `CollideWorld` | The world's blocks round the camera |
| `CollideDepth` | What is on screen (mobs and players), from the depth buffer |
| `CollideEntities` | The boxes of the 64 living entities nearest the camera; struck only on entering |
| `Kill` | Dies inside (or outside) a sphere, box or plane |

```java
.module(new CgVfxModule.Attract(CgSphere.of(2), 12f).at(0f, 3f, 0f))
.module(new CgVfxModule.Collide(CgPlane.of(0.4, 1, 0, 5), 0.3f, 0f, 0f))      // a ramp 5 blocks round
.module(new CgVfxModule.Kill(CgBox.of(4, 1, 4), true).at(0f, -2f, 0f))
```

### Events: particles spawning particles

An event fires per particle (landing, death, an age, each collision, every period) and spawns children of another
emitter where it fired, reports to the CPU, or both:

```java
.event(CgVfxEvent.onCollision().spawn(FLASH, 1))                 // a flash at each bounce
.event(CgVfxEvent.onCollision().spawn(CHIPS, 4).inherit(0.3f))   // four chips taking 30% of the parent's velocity
.event(CgVfxEvent.onLanding().readback(64))                      // each landing reported, a few frames later
```

- **One level deep**: a child may report rows, never spawn children of its own.
- **Add every child to the look** (its emitter and a layer on it), or it simulates and draws nothing.
- At most `CgVfxEvent.MAX_EVENTS` events an emitter and `MAX_CHILDREN` children an event.

### Where particles run

Every tier the engine supports runs them: compute on modern GL and Vulkan, lowered to draws on older GL
([`SHADERS.md`](SHADERS.md) § *Every tier*). A Java simulation (`-Dcrystalgraphics.vfx.sim=cpu`) remains only as the
"before" of a comparison and will be deleted: build nothing on it.

---

## 14. Writing an effect of your own

An effect has two jobs: `tick` (logic, on a fixed 120 Hz clock) and `submit` (hand what to draw to the frame, by
slot). How it looks is its look's.

```java
public final class Bolt extends CgVfxEffect {
    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("mymod:bolt");
    public static final CgVfxParam.Scalar RADIUS = SCHEMA.length("radius", 0.4f);
    public static final CgVfxParam.Scalar LIFE = SCHEMA.time("life", 2f);
    public static final CgVfxParam.Color CORE = SCHEMA.color("core", 0xFFFFE9A0, 1.5f);
    public static final CgVfxSlot ORB = CgVfxSlot.mesh("mymod:bolt/orb");

    public static final CgVfxKit KIT = CgVfxKit.of("mymod:bolt")
            .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/beam/orb_light.shader").on(ORB).size(6f)
                    .bind("_Glow", CORE).role(CgVfxLayer.Role.LIGHT).build())
            .layer(CgVfxLayer.of("mymod:shaders/vfx/bolt_orb.shader").on(ORB).build())   // _Core binds by name
            .build();
    public static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).with(KIT).build();

    private final Matrix4f transform = new Matrix4f();

    public Bolt(CgVfxLook look, double x, double y, double z) {
        super(look, x, y, z);
    }

    @Override protected void tick(float dt) {
        if (age >= get(LIFE)) die();
    }

    @Override protected void submit(CgVfxFrame frame) {
        if (!frame.draws(this, ORB)) return;
        float r = get(RADIUS);
        transform.scaling(r);
        frame.mesh(this, ORB, 0f, 1f, 0f, transform, r, r, 1f, 0f);   // every layer on ORB
    }
}
```

- **Coordinates**: `originX/Y/Z` is where the effect is (moved by `follow`); everything it positions is relative to it.
- **`age`** is seconds since it started; **`seed`** a stable random number in 0..1 that shaders read too.
- **Die** with `die()` when done; `stop()` from outside lets it finish gracefully.
- **Read every number from its parameters**, never a constant in code: then a look, a tuning and a cue reach it.

### What a frame draws

| Call | Draws |
|---|---|
| `frame.particles(this, instance)` | an emitter's particles, through every layer on it |
| `frame.mesh(this, slot, x, y, z, transform, ex, ey, ez, ew)` | a sphere at a transform; four numbers the slot documents |
| `frame.billboard(this, slot, x, y, z, size, ex, ey, ez, ew)` | a camera-facing quad |
| `int row = frame.path(path, seed, age)`, then `frame.tube(this, slot, path, row)` | a tube along a path |
| `frame.ribbons(...)`, `frame.pathRibbons(this, slot, path, row, intensity)` | ribbons a shader places |

### Particles in an effect

```java
private final CgVfxEmitterInstance sparks;

public Sparks(CgVfxLook look, double x, double y, double z) {
    super(look, x, y, z);
    sparks = new CgVfxEmitterInstance(look.emitter("sparks"), seed);
    sparks.start(0f, 1f, 0f);                           // where it emits, relative to the effect
}

@Override protected void tick(float dt) {
    tick(sparks, dt);                                   // never sparks.tick(...): this schedules it on the GPU
    if (sparks.finished() && age > 1f) die();
}

@Override protected void submit(CgVfxFrame frame) {
    frame.particles(this, sparks);
}
```

Take the emitter from the look (`look.emitter(name)`), so a look that replaced it plays its own. One instance per
emitter per effect; `moveTo(x, y, z)` moves a source without restarting it, `rateScale(s)` scales its rate, `stop()`
ends an open-ended rate.

### Built from elements

Reuse what the engine has: a charge, a blast, a beam's body. An element reaches its effect's values, shakes, post
volumes, hits and moments as code inside the effect would, and the effect calls it in the order it wants:

```java
public final class KiBlast extends CgVfxEffect {
    public static final CgHitPart PART_BLAST = SCHEMA.part("blast");
    private final CgVfxCharge charge = new CgVfxCharge(this);
    private final CgVfxBlast blast = new CgVfxBlast(this, PART_BLAST);

    @Override protected void tick(float dt) {
        charge.tick(0f, 0f, 0f, aimX, aimY, aimZ, releaseAge, Float.NaN);
        if (age >= releaseAge && !blast.ignited()) blast.ignite(tx, ty, tz, -aimX, -aimY, -aimZ);
        blast.tick(dt);
        if (blast.done()) die();
    }

    @Override protected void submit(CgVfxFrame frame) {
        charge.submit(frame);
        blast.submit(frame);
    }
}

// its look: the elements' kits, linked to its own size
public static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA)
        .with(CgEnergyWave.REALISTIC_CHARGE, CgVfxBlast.KIT)
        .link(CgVfxBlast.RADIUS, KiBlast.RADIUS, 8f)
        .build();
```

A new element extends `CgVfxElement`: its own schema, slots and moments as constants, a `tick` and a `submit` its
effect calls. `instance(definition, kit, salt)` makes an emitter instance scaled the way its kit scales.

### Post effects, shakes and moments

```java
// A screen flash that fades with distance and the player's FLASHES setting; closed when the effect dies
flash = openVolume(10, new CgPostSettings().flash(1.5f), hitX, hitY, hitZ).radius(24f).blend(64f);
flash.weight(curve(FLASH).at(t));                    // each tick

playShake(SHOCK, hitX, hitY, hitZ, radius);          // once
holdShake(RUMBLE, 0f, 0f, 0f, radius, level);        // every tick while it lasts

moment("impact", hitX, hitY, hitZ, radius);          // a named point in its life, for capture tools
```

- **A screen-wide moment is a post volume**, never a layer drawn round the camera.
- **Shakes are parameters** (`SCHEMA.shake`), so looks can change them.
- All three do nothing on a server and while fast-forwarding (`startAt`).
- **Announce your moments** as constants and call `moment(...)` as the effect crosses each, so it can be photographed
  at full speed (`vfx/CLAUDE.md` § *Seeing every moment*).

### Reporting what it hits

```java
public static final CgHitPart PART_BOLT = SCHEMA.part("bolt"), PART_ARC = SCHEMA.part("arc");

if (world().ray().at(x, y, z).along(dx, dy, dz, 32).part(PART_BOLT).blocksToo().first(hit)) hit(hit);
hitbox(PART_ARC, CgSphere.of(1.5)).at(originX + x, originY + y, originZ + z).activeFor(4).source(followingId());
```

### Rules that keep it fast and correct

- **Hot paths do not allocate**: `tick` and `submit` run every frame for every effect.
- **Never read player settings**: the system applies density, quality and pause. Mark detail with `.optional()` on an
  emitter or `.from(CgQuality.MEDIUM)` on a layer.
- Drawing rules for shader authors (depth, glow, distortion, the impact frame) are `vfx/CLAUDE.md` § *Rules*.

---

## 15. Writing a layer's shader

A layer's shader is an ordinary `.shader` material ([`SHADERS.md`](SHADERS.md)) with three additions:

```glsl
#type spatial
#pragma cg_use rows                                        // its numeric properties readable per draw
#pragma cg_feature FX_ON_TUBE                              // drawn on a tube slot too, beside a sphere
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"   // the draw header

Properties {
    _Color  ("Its colour; its alpha a strength", color) = (1, 1, 1, 1)      // bound by the layer
    _Core   ("The effect's core colour, by name", color) = (1, 1, 1, 1)     // reads `core` with no binding
    _Bands  ("Its bands", color[3]) = (1, 1, 1, 1), (0, 1, 1, 1), (0, 0.5, 0.9, 1)   // a palette: _Bands(i)
    _Reach  ("How far it reaches, in blocks", float) = 1.0
}

struct v2f { vec3 world; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthWrite OFF
    }
    // void vertex(out v2f o) { ... }: on a tube, FX_ON_TUBE and fx_tube_vertex; on a sphere, CG_OBJECT_TO_WORLD
    void fragment(in v2f i, out vec4 fragColor) {
        float strength = _Color.a * FX_PRESENCE;         // every engine shader multiplies its light by its presence
        fragColor = vec4(_Color.rgb * strength, strength);
        CG_GLOW(_Color.rgb * strength);                  // what blooms
    }
}

// Codeless: its glow, drawn in its own draw
Pass {
    Tags { "LightMode" = "Emissive" }
    RenderState {
        Blend ONE ONE
        DepthWrite OFF
    }
}
```

- **`cg_use rows`** makes every numeric property (float, vector, colour, and arrays of vec4 or colour) read either the
  material's value or the draw's row, whichever the layer chose. Nothing changes in how the shader reads it.
- **The header** (`fx_draw.glsl`, `CG_OBJECT_CUSTOM3`): `FX_ROW`, `FX_AGE` (the effect's age), `FX_SEED`,
  `FX_PRESENCE` (1 unless an ending fades it). The first three customs are the slot kind's (`CgVfxSlot.Kind`).
- **Name colours for what they are** (`_Core`, `_Shell`, `_Hot`): a look's colour of that name binds itself.
  A number never does: bind it in the layer.
- **One shader on several geometries**: declare `FX_ON_TUBE`, `FX_ON_MESH` and so on as features and branch on them.
  `banded.shader` (`shaders/vfx/toon/`) and Dragon Forge's `flat.shader` draw on a tube and a sphere.
- **Colour is authored in sRGB, above 1 allowed**, and a glowing layer names its glow (`CG_GLOW`) and ends with a
  codeless Emissive pass. The glow, depth and distortion rules: `vfx/CLAUDE.md` § *Rules*.
- **Libraries** in `shaders/lib/vfx/`: `fx_tube` (the path, tube placement), `fx_volume` (tracing the view ray
  through a tube's rings), `fx_bands` (flat bands by distance from an axis), `fx_ribbon`, `fx_lightning`,
  `fx_particle`, `fx_billow`, `fx_ring`, `fx_haze`; each listed in `vfx/CLAUDE.md`.

---

## 16. Writing a particle module of your own

A module is a Java record implementing `CgVfxModule` plus a GLSL function `fx_<kind>`, which the generated GPU step
calls. Its numbers are written once per emitter definition; nothing recompiles when they change.

```java
record Swirl(float strength) implements CgVfxModule {
    public String gpuKind() { return "mymod_swirl"; }
    public boolean afterSolve() { return false; }
    public void writeParams(CgVfxWords out) { out.vec4(strength, 0f, 0f, 0f); }
    public String gpuSource() {                          // or a file: shaders/lib/vfx/sim/fx_mymod_swirl.glsl
        return """
                void fx_mymod_swirl(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) {
                    f.accel += cross(vec3(0.0, 1.0, 0.0), p.position) * m.x;
                }
                """;
    }
}
```

| Needs | Declares | Its function then takes |
|---|---|---|
| more numbers | `paramVectors()` | `vec4 m0, m1, ...` |
| a value per instance each step (a moving centre) | `instanceLanes()`, `writeInstance` | one argument per lane, after the numbers |
| the world after the solver (floor, blocks, light) | `afterSolve()` true, `worldInputs()` | the world values, after the lanes; `FxWorld` for the voxel window |
| a texture (a field, a heightfield) | `textures()` | a `sampler2D` or `sampler3D` each, after the world |
| an impact that fires `onCollision` | after the solver; call `fx_hit(p, n, FX_HIT_BLOCK)` on a punctual impact | — |
| the entities round the camera | `worldInputs()` of `ENTITIES` | `FxEntities` (`fx_entities_at.glsl`) |

- `fx_types.glsl` defines `FxParticle`, `FxForces` and `FxStep`. Kill a particle with `p.life = p.age`.
- **Prefix the kind with your mod's name**: two kinds of one name with different source throw.
- Write exactly the vec4s you declared, or the pool throws naming the kind. Every variation has an example in
  `CgVfxGpuModule`'s javadoc.

---

## 17. Performance and scale

### On the client

- **Particles cost the GPU, not the CPU**: a few stores per emitter per tick, the simulation on the GPU. The
  `vfx-blasts` harness scene runs 360,000 particles.
- **An effect's logic** runs on the CPU at 120 Hz, and its draws are built every frame. A bound value costs a vec4 in
  the effect's row, written once a frame per layer; a constant costs nothing per draw.
- **A slot costs only the layers on it**: a look without a kit draws none of it.
- What tends to cost: overdraw from large soft layers (one banded layer is one draw where four stacked bands were
  four), many separate particle layers (each its own batch), and shaders compiling on first use (`prepare(look)`).

### On the server

Estimated from the code, not measured: profile before relying on these ([`PROFILING.md`](PROFILING.md)). Per
authoritative Kamehameha, each server tick:

| Part | Cost |
|---|---|
| Six effect ticks at 120 Hz: the beam's centreline and timers | ~10-30 µs |
| One sphere cast: blocks out to its reach, and the entities along it | ~10-50 µs |
| Publishing: about 100 bytes to each viewer whenever the end or aim moves | ~0 CPU; network |

**About 50 µs a wave**: 100 waves take ~5 ms of a 50 ms server tick. It runs on the server thread and cannot spread
across threads. Levers, once a profile asks: step the server's copy at 20 Hz; cast only when the aim or caster moved.

---

## 18. Questions people ask

### Does the server run my effect?

Only if its cue is `authoritative()` and started with `start()`. Then it builds one copy per started cue with the
same factory as clients and steps it every server tick (six steps). It is never drawn; it decides and publishes.

### Do hits run on the server?

Only an authoritative effect's. Each side hears its own copy's hits, and the server's travel to viewers as confirmed
hits.

| Hit | Server | Each client |
|---|---|---|
| An authoritative effect's | yes, on the server thread: **apply gameplay here** | the server's, confirmed (`onConfirmedHit`), and its own copy's |
| A plain cue's, or a local effect's | never | its own copy's |
| A particle's contact | never | a few frames late, from the GPU, as an event row |

### What shape does an effect hit with?

Whatever its own code tests. The built-in ones:

| What | Shape | Against |
|---|---|---|
| The wave's beam (`PART_BEAM`) | a sphere of `RADIUS` cast from the muzzle along the aim, out to `MAX_LENGTH` | blocks and entity boxes; the first thing met stops it; the caster is skipped |
| The wave's burst (`PART_BURST`) | no test: one hit where it bursts | the block the beam last met, else a `POINT` |
| The wave's blast (`PART_BLAST`) | a shell hitbox expanding to `blastRadius()` (`CgVfxBlast.RADIUS`) | each entity once, as the front passes it; the caster is skipped |
| Shock fronts, rings, dust | none: drawn only | — |
| Particles (`CollideEntities`) | each particle a point | the 64 living entities nearest the camera; client only |

Damage of your own shape goes on the burst:

```java
wave.onHit(CgEnergyWave.PART_BURST, h -> CgCollisionWorld.of(level).overlap(CgSphere.of(wave.blastRadius()))
        .at(h.x(), h.y(), h.z()).exposure().forEach(e -> damage(e.entity(), 20f * e.falloff(2, wave.blastRadius()))));
```

### Where do I put a number: a parameter, a layer constant, or a constant in code?

| It is | Put it |
|---|---|
| read by the effect's logic or by more than one layer, or something a game or look changes | a **parameter** on the schema of the effect or element that reads it |
| one layer's look, and only that layer reads it | a layer **constant** (`set`): tunable live, saved in overrides |
| a size a layer should follow as the effect grows or fades | a **binding** to the length (`bind("_Radius", RADIUS)`) |
| how the code works, never a look's choice | a constant in code |

### Why did my colour not bind?

Colours bind by name only when the shader's property and the look's colour have the same name (`_Shell` and
`shell`). A generic `_Color` matches nothing: bind it (`bind("_Color", SHELL)`). `look.describe()` lists what every
property resolved to.

### If I change a parameter on the server's effect, do all clients see it?

Yes: `beam.set(PARAM, value)` on an authoritative effect reaches every viewer at the end of the tick, and anyone who
joins later. Tweens and endings travel as events. Curves and camera shakes do not travel.

### Do particles and post effects need the server?

No. Each client works them out from the effect's state (published by the server) and the seed every client shares.
Nothing gameplay depends on lives in particles; everything that shapes the visuals reaches clients through the cue;
small differences between clients (each collides with its own loaded world) are fine.

### Is that how other engines do it?

Yes. In Unreal, gameplay cues are skipped on a dedicated server and Niagara does not simulate there; Niagara's user
parameters bound into renderers are this engine's bound properties, and its systems of emitters are kits. In Unity, a
dedicated-server build strips rendering, VFX Graph exposes one property sheet per instance, and HDR colours are a
colour and an intensity, as here.

### What does a player who joins late see?

Lasting cues start at their current age, particles prewarmed, tweens and endings where they are. One-off cues already
played are gone, as in every engine.

### Why is it called a cue?

It is Unreal's term (the Gameplay Ability System's gameplay cues): a one-off cue is their *Execute*, a lasting cue
their *Add* and *Remove*. "Authority" is Unreal's word too.

---

## 19. Checking and measuring

Harness gates (`./gradlew :gl-debug-harness:runHarness --args="--mode=<gate>"`, from CrystalGUI's root):

| Gate | Holds |
|---|---|
| `vfx-sim-equivalence` | the GPU step's noise and hashes against Java |
| `vfx-events` | events: every row heard once, every child where expected |
| `vfx-collide` | collisions and rates off a ground and a wall |
| `vfx-inputs` | vector fields, heightfields, the depth pyramid |
| `vfx-world` | the voxel window, its distance field and per-particle light |
| `vfx-range` | drawing by slot, and size over speed |

Each must pass at every forced tier (`-Dcrystalgraphics.compute.tier=G43|G40|G33`) and on `--device=vulkan`. Scenes to
look at: `vfx-spheres` (the waves' lanes, `crystalgraphics.harness.vfx.moments` for a contact sheet of every moment),
`vfx-showcase`, `vfx-modules` (a station per module), `vfx-blasts` (360,000 particles), `vfx-lab` (CrystalGUI's
inspector, every look's values grouped by schema).

Unit tests (`./gradlew :core:test --tests "<class>"`): `CgVfxLookTest`, `CgVfxTuningTest`, `CgVfxEndingTest`,
`CgPropertyRowsTest`, `CgVfxCueTest`, `CgVfxAuthorityTest`, `CgVfxHitsTest`, `CgVfxOwnerTest`, `CgVfxStartAtTest`.
Every shipped shader, each keyword variant, links under shaderc in `:runtime:lwjgl:vulkan:test --tests
com.crystalgraphics.vulkan.EngineOnTrackedBackendTest`.

Profiling ([`PROFILING.md`](PROFILING.md)), trace channel `crystalgraphics.vfx`: zones `vfx.tick`,
`vfx.emitters.workers`, `vfx.effect.tick/<namespace>`, `vfx.effect.submit/<namespace>`, and on the GPU
`vfx.pool.step` and `vfx.pool.range`; counters `vfx.gpu.slots-stepped`, `vfx.draws.particle-gpu`,
`vfx.effects/<namespace>`.
