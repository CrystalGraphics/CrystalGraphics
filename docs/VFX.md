# VFX — the guide

The VFX engine (`com.crystalgraphics.vfx`) plays visual effects in a world: beams, explosions, auras, hit sparks,
dust, trails, smoke and fire. It simulates their particles and their gas on the GPU, draws them through the engine's
world renderer, sends them over the network, and can run an effect on the server when gameplay depends on it.

It splits an effect into **what it does** (an effect class and the elements it is made of: Java, ticking at 120 Hz)
and **how it looks** (a look: parameters, kits of renderers and emitters: data). The same Kamehameha class plays as the
realistic beam, as a toon beam in flat bands, or as Dragon Forge's anime wave, and only the look differs.

Read sections 1 and 2 first: they give you the words and the picture everything else uses. After that, jump to what
you need: the contents are grouped by what you are doing. Where each class lives is `vfx/CLAUDE.md`, which loads
itself for anyone editing a VFX file.

## Contents

**Start here**

1. [The words you need](#1-the-words-you-need)
2. [Quick start](#2-quick-start)
3. [Client, server, or both](#3-client-server-or-both)

**Playing effects**

4. [Playing effects](#4-playing-effects)
5. [The engine's effects](#5-the-engines-effects)

**How an effect looks**

6. [Parameters: what a look carries](#6-parameters-what-a-look-carries)
7. [Kits, renderers and slots: how a look draws](#7-kits-renderers-and-slots-how-a-look-draws)
8. [Meshes](#8-meshes)
9. [Tweens and endings](#9-tweens-and-endings)
10. [Tuning a look live, and saving it](#10-tuning-a-look-live-and-saving-it)

**Particles**

11. [Particles](#11-particles)
12. [Modules and events](#12-modules-and-events)
13. [Trails and ribbons](#13-trails-and-ribbons)

**Gas and smoke**

14. [Gas](#14-gas)
15. [Smoke that fills a space: CS2's grenades](#15-smoke-that-fills-a-space-cs2s-grenades)

**Over the network, and gameplay**

16. [Effects over the network: cues](#16-effects-over-the-network-cues)
17. [Effects gameplay depends on: server authority](#17-effects-gameplay-depends-on-server-authority)
18. [Hits: what an effect strikes](#18-hits-what-an-effect-strikes)

**Writing your own**

19. [Writing an effect of your own](#19-writing-an-effect-of-your-own)
20. [Writing a material's shader](#20-writing-a-materials-shader)
21. [Writing a particle module of your own](#21-writing-a-particle-module-of-your-own)

**Running well**

22. [Performance and scale](#22-performance-and-scale)
23. [Questions people ask](#23-questions-people-ask)
24. [Checking and measuring](#24-checking-and-measuring)

---

## 1. The words you need

| Word | What it is | Class |
|---|---|---|
| **Effect** | One thing playing in the world: one Kamehameha, one explosion. A position, an age, and code that runs every tick. It dies when it is done. | `CgVfxEffect`: `CgVfxSystemInstance` (the energy wave is its `CgEnergyWave` behaviour), yours |
| **Element** | A part many effects share, with its own parameters and slots: a charge ball, a blast, a beam's body. An effect is made of elements. | `CgVfxElement`: `CgVfxCharge`, `CgVfxBlast`, `CgBeamBody`, ... |
| **Look** | How an effect looks: its parameters' values, the kits that draw it, its emitters. Shared, never changed by playing. | `CgVfxLook` |
| **Schema**, **parameter** | A named set of typed values an effect, element or kit reads: a length, a colour, a palette, a curve, a camera shake. | `CgVfxSchema`, `CgVfxParam` |
| **Kit** | A named group of slot renderers and emitters, with the schemas they read: the realistic body, the toon body, an explosion. A look is kits added and dropped. | `CgVfxKit` |
| **Renderer**, **material** | One draw: a material asset (a `.shader`, its constants, textures and state) drawn by an emitter's renderer on its particles, or by a slot renderer on a slot, its properties bound to parameters. | `CgVfxRenderers`, `CgVfxSlotRenderer`, `CgMaterialDefinition` |
| **Slot** | A place an effect draws: its body along a path, its head on a sphere. An element declares its slots; a slot renderer names the one it draws on. | `CgVfxSlot` |
| **Emitter**, **module** | One kind of particle, and the physics pieces it stacks (gravity, drag, collision). Simulated on the GPU. | `CgVfxEmitter`, `CgVfxModule` |
| **Trail**, **ribbon** | A strip through where something moving has been: a fist's streak, a blade's swoosh, a line behind every spark. | the ribbon and anim trail renderers |
| **Gas** | Smoke, fire and steam as a fluid on the GPU (NVIDIA Flow's): coupled from emitters, burning, rising, curling round blocks, lit through itself. | `CgVfxFluid`, `CgVfxSmoke` |
| **Link** | A size that follows another, times a factor: the head is 1.45 times the radius, so setting the radius resizes it. | `SCHEMA.length("head", RADIUS, 1.45f)` |
| **Tween** | One value moved from where it is to a new one over some seconds, rather than jumping: a beam swelling as it powers up. | `effect.animate(...)` |
| **Easing** | The shape of a tween's motion: steady, slow then fast, fast then settling, overshooting and back. | `CgEasings.LINEAR`, `OUT_QUAD`, ... |
| **Ending** | A way to end an effect other than its own: some values tweened to where they end, then gone. Declared once under a name, which is how a server tells clients which ending to play. | `CgVfxEnding` |
| **Presence** | How present an effect is, 0 to 1. Every engine shader multiplies its light by it, so fading it fades the whole effect. | `effect.presence()`, `FX_PRESENCE` |
| **Scalability** | What happens to an effect too far away, one too many, or out of view: culled, drawn lower, or asleep. | `CgVfxScalability` |
| **Moment** | A named instant in an effect's life (the release, the impact), announced for capture tools. | `moment("impact", ...)` |
| **Owner** | Your mod's handle on the engine: play, stop and clear your effects, timed under your name. | `CgVfxOwner`, from `CgVfx.owner("mymod")` |
| **Cue** | An effect named for the network, so the server can tell clients to play it. | `CgVfxCue` |
| **Authority** | The server running an effect itself, because gameplay depends on what it does. | `CgVfxCue.Builder.authoritative()` |
| **Attachment point** | A named point on an entity an effect rides: feet, chest, hands, or one your mod declares. | `CgEntityAttachments` |

How they fit together:

```
CgVfxSystemInstance playing the CgEnergyWave behaviour (phases, aim, fire, hits)
  ├─ CgVfxCharge  ── schema crystalgraphics:charge  ── slots BALL, HAZE, STREAKS, ...
  ├─ CgBeamBody   ── schema crystalgraphics:wave/body ── slots BODY, ARCS, HEAD
  ├─ CgBeamContact, CgVfxBlast, CgBeamWake
  └─ plays a CgVfxLook:
        values      RADIUS 0.8, SHELL 0xFF1858FF × 1.6, ...      (every schema the look includes)
        kits        REALISTIC_BODY  → slot renderers on BODY, ARCS   (each: a material, its bindings)
                    REALISTIC_HEAD  → slot renderers on HEAD
                    CgVfxBlast.KIT  → FIREBALL, HEART, SHOCKWAVE, the explosion's emitters
        links       HEAD_WIDTH = RADIUS × 1.45, ...            (sizes follow the radius)
```

---

## 2. Quick start

### Play a built-in effect

```java
public static final CgVfxOwner VFX = CgVfx.owner("mymod");   // once, as a constant

// client: a Kamehameha at the player's hands, aimed where they look, charging until fired
CgVfxSystemInstance<CgEnergyWave> beam = VFX.play(CgEnergyWave.instance(CgEnergyWave.kamehameha(), x, y, z)
        .follow(playerId, CgEntityAttachments.HANDS_FORWARD)
        .set(CgEnergyWave.RELEASE, Float.POSITIVE_INFINITY));   // its user parameters drive it
beam.set(CgEnergyWave.AIM, lookX, lookY, lookZ);   // each tick
beam.set(CgEnergyWave.RELEASE, beam.age());        // key released
beam.stop();                     // its tail runs out into what it hit, which bursts
beam.end(CgEnergyWave.FADE);     // or: it thins away where it is, no blast
```

The engine registers its effects by id ([§5](#5-the-engines-effects)), so a tool or a command can play one without knowing its
class:

```java
VFX.play("crystalgraphics:kamehameha_toon", x, y, z, tx, ty, tz);   // at a point, aimed at a target
```

### A variant of a look

`derive()` copies a look into a builder; what you change there makes a new look, and the original stays as it was.

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

A look of emitters, their renderers drawing them, needs no effect class: a `CgVfxSystemInstance` (`vfx.system`) of it starts
every emitter once and dies when
they finish. Building `PUFF_LOOK` is [§11](#11-particles).

```java
VFX.play(new CgVfxSystemInstance<>(PUFF_LOOK, x, y, z));
```

### Show it to every player

```java
// common, at init: a cue, with what each side plays
public static final CgVfxCue PUFF = VFX.cue("puff", PUFF_LOOK,
        (look, cue) -> new CgVfxSystemInstance<>(look, cue.x(), cue.y(), cue.z())).build();

PUFF.at(level, x, y, z).send();   // server, whenever
```

That is [§16](#16-effects-over-the-network-cues). An effect gameplay depends on (a beam that damages what it hits)
runs on the server too: [§17](#17-effects-gameplay-depends-on-server-authority).

---

## 3. Client, server, or both

**The client draws; the server decides.**

| What | Client | Server |
|---|---|---|
| Drawing: meshes, materials, glow | yes | never |
| Particles (simulated on the GPU) | yes | never |
| Gas (simulated on the GPU) | yes | never: a smoke grenade's server copy floods its cells and answers sight with no fluid |
| Post effects: flashes, colour shifts, impact frames | yes | never |
| Camera shakes | yes | never |
| An effect's logic: where a beam goes, when it fires, when it bursts | yes, for drawing | only for an **authoritative** cue |
| What a beam hits, for gameplay | no: it would differ per client | yes, with an authoritative cue |
| Sending an effect to players | no (a client can only *predict*, [§16](#predicting-from-a-client)) | yes, with a cue |

- **On a client**, effects play into the client world's system, which the engine updates every frame, draws, and
  clears when the world goes (logout, disconnect, a dimension change).
- **On a server**, nothing plays unless a cue is `authoritative()`. Then the server runs *the same effect class*
  with everything visual switched off.
- **In singleplayer** an authoritative effect exists twice: the integrated server's copy decides, the client's draws.
- **Looks, schemas, kits, renderers and material assets are plain data** and are safe on a server: declare them in
  common code. Nothing touches the GPU until a material first draws.

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
VFX.prepare(SPIRAL, SHOCKWAVE, FLASH);   // every look a move plays, before the move
```

- **An effect plays once.** Playing the same object again, or into a second system, throws. Make a new one.
- **An effect that throws is killed and logged under your mod**; every other effect keeps playing.
- A look played cold compiles on its first frame and can hitch: `prepare` spreads the work over frames within
  `crystalgraphics.warmBudget`, and `VFX.warmed()` says when it is done. Once it is, the looks' first frames compile
  nothing: `CgMaterialShader.compileWaits()` (the trace counter `material.compile-waits`) counts every program a draw
  had to wait for, and `--mode=vfx-prepare` holds a move to 0.
- On a dedicated server, or a client with no world yet, `play` plays nothing.

### Ending an effect

| Call | What happens |
|---|---|
| `effect.stop()` | Its own graceful end: emission ends, what is in flight plays out (a wave's tail runs into its target and bursts) |
| `effect.end(ending)` | Another way you pick, for an interrupted or cancelled effect: values eased over the ending's seconds, then gone ([§9](#an-ending)) |
| `effect.kill()` | At once: the effect and its particles are gone by the next particle step |

### Following an entity

```java
VFX.play(new Aura(Aura.LOOK, x, y, z).follow(entityId, CgEntityAttachments.CENTRE));
VFX.play(new HitSpark(look, hitX, hitY, hitZ).followKeepingOffset(targetId, CgEntityAttachments.CHEST));
```

- `follow` moves the effect to the attachment point every frame; when the entity goes, the effect stops.
  `followKeepingOffset` keeps it where it was played, riding the point.
- **One follow for anything it rides**: an entity's point, a skinned character's socket and a sequence's locator are
  each a `CgAttachment` (Unreal's `AttachToComponent(parent, socket)`), and `follow(attachment)` takes any of them.
  `follow(entityId, point)` is `CgEntityAttachments.of(entities, entityId, point)` over the world it plays in:

  ```java
  VFX.play(new Flame(look, x, y, z).follow(goku.attachment("hand_r_kameha_socket_02")));   // a posed socket
  CgAttachment hands = CgEntityAttachments.of(CgEntityQueries.client(), playerId, CgEntityAttachments.HANDS_FORWARD);
  ```
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

  Where a point is comes from three places, the first that knows winning: a **mark**, from where the model was drawn
  this frame; a **provider** (`CgEntityAttachments.provide(provider)`), code that answers points for the entities it
  knows; else the **fallback**, the point estimated from the entity's box by a player's proportions. Marking a built-in
  point moves every effect already following it. A point at a share of the body's height:
  `CgEntityAttachments.declareAtHeight("mymod:strike", 0.7)`.

### Starting part-way through

```java
VFX.play(new Storm(Storm.LOOK, x, y, z).startAt(12f));   // as if it had been playing for 12 seconds
```

Call it before its first tick. The effect's ticks run exactly up to that age (up to
`CgVfxSystem.MAX_START_SECONDS`; past it, its age jumps). Its particles **prewarm**: the last two seconds of them are
simulated in a few quick steps, so they are already in the air when it appears. Its shakes and moments stay silent
until it gets there. This is how a player joining late sees a lasting cue.

### Other controls

- **`effect.ground(y)`** gives particles a floor where the host has no world (the debug harness).
- **`CgWorld.timeDilation(s)`** slows or speeds every effect on a world (0.25 is slow motion, 0 holds them).
- **A character's clock** holds and slows the effects that follow it, and only those:

  ```java
  CgCharacterClock clock = CgCharacterClock.of(attackerId);   // effects following attackerId find it by themselves
  clock.freeze(6);                                            // hitstop: their effects and particles hold six game ticks
  clock.rate(0.5f);                                           // slow motion for them alone
  VFX.play(new Aura(look, x, y, z).clock(clock));             // or name the clock
  ```
- **The player's settings apply by themselves**: particle density, quality tier, pause, tick rate. An effect never
  reads them. The quality tier is Ultra until the player chooses another.

### A system of your own

A preview viewport, a test or a harness scene keeps its own and drives it:

```java
CgVfxSystem vfx = new CgVfxSystem();
CgVfxOwner lab = CgVfx.owner("mymod", vfx);
lab.play(effect);
// every frame, before the world stages record (a tick function in `CgWorld.client().schedule()`):
vfx.update(seconds);
vfx.submit(CgWorldRenderer.get());
```

---

## 5. The engine's effects

The engine ships these, each registered by id, so a tool, a command or a test plays one without naming its class:

| Id | Class | What it is |
|---|---|---|
| `crystalgraphics:kamehameha`, `final_flash`, `galick_gun`, `kamehameha_toon` | `CgEnergyWave` | a beam charged at a muzzle, fired, homing or steered, bursting into a blast ([below](#the-energy-wave)) |
| `crystalgraphics:explosion` | `CgVfxSystemInstance` | the realistic explosion on its own: billows, a base surge of dust, debris, embers and a shock front ([below](#an-explosion-on-its-own)) |
| `crystalgraphics:heat_haze` | `CgVfxHeatHaze` | the air shimmering over a point for as long as it plays ([below](#heat-haze)) |
| `crystalgraphics:smoke_grenade` | `CgSmokeGrenade` | Counter-Strike 2's smoke, flooding round what stands in its way, shot through and healing ([§15](#15-smoke-that-fills-a-space-cs2s-grenades)) |

```java
VFX.play("crystalgraphics:explosion", x, y, z, x, y, z);                          // at a point, aimed at a target

CgVfxRegistry.register("mymod:hit_spark", "Hit spark", "Impacts", HitSpark.LOOK,   // your own, once at startup
        (look, x, y, z, tx, ty, tz) -> new HitSpark(look, x, y, z));
```

- An id is registered once; a second registration under it throws.
- The factory takes the look to play, so a tool can hand it a variant (`entry.look().derive()...build()`).
- The target is where a beam homes and blasts. An effect that only faces takes the direction to it; one with neither
  ignores it.

### The energy wave

`CgEnergyWave` (`vfx.effect.beam`) charges at a muzzle, fires a beam that flows out, bends where its source turns,
homes on a target or is steered by a player's aim, stops where it hits, and bursts into a blast as its tail runs
out into what it hit.

```java
CgVfxSystemInstance<CgEnergyWave> beam = VFX.play(CgEnergyWave.instance(CgEnergyWave.kamehameha(), x, y, z)
        .follow(playerId, CgEntityAttachments.HANDS_FORWARD)
        .set(CgEnergyWave.RELEASE, Float.POSITIVE_INFINITY));        // charging until fired
beam.set(CgEnergyWave.AIM, dx, dy, dz).setPoint(CgEnergyWave.TARGET, tx, ty, tz);   // each tick, as the source turns
beam.set(CgEnergyWave.COLLIDE, 1f);                                  // or: find its own target along the aim
beam.set(CgEnergyWave.RELEASE, beam.age());                          // fire
beam.stop();                               // the tail runs out into the target, which bursts
float reach = beam.behaviour().blastRadius();   // the wave's own state, read typed
```

#### Made of elements

| Element | Owns | Its kit in the engine's looks |
|---|---|---|
| `CgVfxCharge` | the ball growing over `TIME` along `SIZE`, streaks and arcs, the release's flash and shock ring, the root it settles to | `REALISTIC_CHARGE` |
| `CgBeamBody` | the stream and its path, the body's profile (launch swell, throb, pulse, surges), the head | `REALISTIC_BODY`, `REALISTIC_HEAD` |
| `CgBeamContact` | where it hits while it hits: the orb, splash, rings, the hit shake | `REALISTIC_CONTACT` |
| `CgVfxBlast` | the blast, with its impact frame before it: beats, the cut, flash and impact volumes, the front hitbox | `CgVfxBlast.KIT`: `FIREBALL`, `HEART`, `SHOCKWAVE`, the explosion's emitters |
| `CgBeamWake` | dust torn off the ground under the body | `WAKE` |

Each element's schema, slots and moments are on its class. Any effect may use them ([§19](#built-from-elements)).

#### Its looks

| Look | What it is |
|---|---|
| `kamehameha()` | every realistic kit, blue-white |
| `finalFlash()` | the same kits, gold, `RADIUS` 1 and faster |
| `galickGun()` | the same kits, violet |
| `toon()` | the Kamehameha with `TOON` for its body, ball and head: three flat bands of `BANDS` and a soft halo, one draw a slot |

#### Its parameters

| Parameter | Default | Meaning |
|---|---|---|
| `RADIUS` | 0.8 | the body's radius: every link follows it |
| `SPEED` | 40 | how fast energy leaves the muzzle |
| `TURN_RATE`, `NAVIGATION` | 4, 3 | how hard it homes (proportional navigation) |
| `STEER` | 0 | above 0, steered: a curve from the muzzle along the aim into an end that chases the target, turning at most this many radians a second |
| `MAX_LENGTH` | 120 | how far the head flies when it hits nothing |
| `MUZZLE_FORWARD` | 0 | how far along the aim the drawn wave starts once fired: a caster's hands are the muzzle, the beam clears their body. Its casts still start at the muzzle |
| `AIM` | (1, 0, 0) | where the source points, any length: whoever plays it sets it, as every row below, and the wave reads it each tick |
| `TARGET`, `VIA` | NaN | what the body homes on, and a point it passes, from the origin (`setPoint` takes a point in the world); NaN for none |
| `COLLIDE` | 0 | 1: it finds its own target where a cast along the aim meets something; a cue's viewer never casts |
| `RELEASE` | NaN | the age it fires at: NaN at its charge's `TIME`, infinity held; once fired it stays fired |
| `CORE`, `CORE_RIM`, `SHELL`, `SHELL_HOT`, `SPIRAL`, `GLOW` | | the energy palette its realistic kits bind |
| `CgBeamBody.HEAD_WIDTH`, `HEAD_LENGTH` | `RADIUS` × 1.45, × 1.9 | the head |
| `CgVfxCharge.RADIUS`, `ROOT_SIZE` | `RADIUS` × 1.7, 0.8 | the ball, and the root it settles to once fired |
| `CgVfxBlast.RADIUS` | `RADIUS` × 12 | the blast |

#### A wave of your own drawing

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

- Bind your renderers' sizes to `RADIUS` (`bind("_Radius", CgEnergyWave.RADIUS)`), and `FADE` thins the whole
  of it.
- A look must carry its elements' schemas: derive it from one of the wave's looks, or add them with `params(...)`.
  The wave's constructor says which is missing.

### An explosion on its own

The blast a wave bursts into, as a burst of its own: every emitter of `CgVfxBlast.EXPLOSION`'s kit started once.

```java
static final CgVfxLook EXPLOSION = CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).with(CgVfxBlast.EXPLOSION.kit()).build();
VFX.play(new CgVfxSystemInstance<>(EXPLOSION, x, y, z));
```

Its colours are its own schema's (`body`, `hot`, `debris`, `dust`, `sparkCore`); [§11](#in-a-look) lists what it is
made of.

### Heat haze

```java
CgVfxHeatHaze haze = VFX.play(new CgVfxHeatHaze(CgVfxHeatHaze.standard(), x, y, z));
haze.set(CgVfxHeatHaze.RADIUS, 3f);        // blocks; INTENSITY is how strongly it bends
haze.stop();                                // fades out over FADE seconds, then ends
```

The air over a fire, a lava pool or a cooling crater, with nothing else drawn. It fades in and out, so starting or
stopping it never pops, and it draws nothing at the Low quality tier.

---

## 6. Parameters: what a look carries

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
- **A name is a network field's** ([§16](#look-parameters-and-data-of-its-own)): renaming one is a network change.
- **A curve is keyframes**: `start(time, value)`, then `.to(time, value, easing)` for each key after, eased from the
  one before ([§9](#easings) has the easings). An effect reads it at a time of its choosing, usually its progress 0 to
  1: `curve(GLOW).at(t)`. Before the first key it holds the first value, after the last the last.
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

A look never changes once built (except under a tuning, [§10](#10-tuning-a-look-live-and-saving-it)); a variant is a new
look. A value set on a server's authoritative effect reaches every viewer ([§17](#syncing-parameters)).

### Many schemas in one look

A look lays out every schema it includes (`CgVfxLayout`): its effect's first, then each kit's, then any it adds with
`params(...)`. The Kamehameha's carries the wave's, the charge's, the body's, the contact's, the blast's and the
explosion's. A parameter is read the same way whichever schema it is on: `wave.get(CgVfxCharge.RADIUS)`.

`look.describe()` prints every value and every draw's hint, material and binds: the first thing to read when a look does
not draw what you expect.

---

## 7. Kits, renderers and slots: how a look draws

A look draws through **renderers**, each drawing a material asset: an emitter's renderers draw its particles, and a
**slot renderer** draws on one of its behaviour's slots. How a material blends, what it samples and where a draw goes
among the rest is [`vfx/effects.md`](vfx/effects.md); every renderer kind is [`vfx/particles.md`](vfx/particles.md)
§ *Renderers*.

### Composing a look from kits

```java
static final CgMaterialDefinition FLAT = CgMaterialDefinition.of("mymod:materials/flat",
        "mymod:shaders/vfx/flat.shader").order(CgVfxLook.SURFACE).build();

public static final CgVfxKit BANDS = CgVfxKit.of("mymod:wave/bands")
        .slot(CgVfxSlotRenderer.of("outer", CgBeamBody.BODY).material(FLAT).size(1.8f)
                .bind("_Color", CgEnergyWave.SPIRAL).build())
        .slot(CgVfxSlotRenderer.of("inner", CgBeamBody.BODY).material(FLAT).size(1.4f)
                .bind("_Color", CgEnergyWave.SHELL).build())
        .build();

CgVfxLook mine = CgEnergyWave.kamehameha().derive()
        .without(CgEnergyWave.REALISTIC_BODY, CgEnergyWave.REALISTIC_HEAD)
        .with(BANDS)
        .build();

CgVfxLook swapped = CgEnergyWave.kamehameha().derive().replace(CgEnergyWave.REALISTIC_BODY, BANDS).build();
CgVfxLook quieter = CgEnergyWave.kamehameha().derive().without(CgVfxBlast.SHOCKWAVE).build();   // a kit inside a kit
```

- **A kit** holds slot renderers, emitters (each with its renderers), the schemas they read (`params(...)`) and other
  kits (`kit(...)`).
- **`without(kit)`** drops its slot renderers and emitters, inside other kits or not; its parameters stay, since effect
  code may read them. Dropping a kit the look does not hold throws.
- **`replace(a, b)`** puts `b` where `a` was in the draw order.
- A look may also take a loose `slot(...)` or `emitter(...)`; `emitter` replaces one of the same name where it is,
  which is how a look changes one element's physics or drawing.

### A slot renderer

```java
static final CgMaterialDefinition SHELL = CgMaterialDefinition.of("mymod:materials/shell",
        "crystalgraphics:shaders/vfx/beam/body_shell.shader").value("_Flow", 22f).order(CgVfxLook.SURFACE).build();

CgVfxSlotRenderer shell = CgVfxSlotRenderer.of("shell", CgBeamBody.BODY)   // the slot it draws on
        .material(SHELL)
        .size(1.15f)                                       // times the slot's own size
        .bind("_Shell", CgEnergyWave.SHELL)                // a colour, read every draw
        .bind("_Radius", CgEnergyWave.RADIUS, 2.6f)        // a number times 2.6: it shrinks as the wave fades
        .build();
```

| Call | Does |
|---|---|
| `of(id, slot)` | the slot it draws on, and its id in the look |
| `material(definition)` | the material asset it draws: its shader, constants, textures, state and `order` |
| `size(times)` | its size, times its slot's own |
| `bind(property, param)`, `bind(property, param, factor)` | read from the effect's own values every draw |
| `mesh(id)` | a mesh slot only: a file or provided mesh drawn in place of the unit sphere ([§8](#8-meshes)) |
| `quality(CgQuality.MEDIUM)` | drawn only at that quality tier and above: detail a lower tier drops |

- **Nothing binds by name**: a property reads a user parameter only where it is bound. A bind the look does not
  declare fails at load, naming the parameters it has.
- **Bound values are written once a frame per effect and draw into a row**: a small block of numbers that travels with
  each draw ([§20](#20-writing-a-materials-shader)). So two effects playing different values share one material and batch.
- **A material is made once per material definition**, so looks built from the same kits share them.
- **A tube slot draws the geometry its material's shader names** (`Tags { "Tube" = "Volume" | "Closed" }`).

### An emitter's renderers

```java
static final CgMaterialDefinition SPARK = CgMaterialDefinition.of("mymod:materials/spark",
        "crystalgraphics:shaders/vfx/particle/spark.shader").usage(CgMaterialUsage.SPRITES).build();

static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("sparks").capacity(1200).rate(120f)
        .renderer(CgVfxRenderers.sprite("sparks").material(SPARK).bind("_Color", EMBER).bind("_Hot", FLAME)
                .sortOrderHint(CgVfxLook.SURFACE).build())
        .build();
```

An emitter's renderers say how its particles draw; their kinds set how the pool lays them out (quads, meshes, arcs).
A material serves the usages it lists (`sprites`, `mesh_particles`, `ribbons`), each one its shader declares.

### Slots

An element declares where it draws, and its kit's slot renderers say what draws there:

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
| `SEQUENCE` | a prefab's part on each particle of a `sequence(clip)` emitter, where its node is at the particle's age ([`vfx/sequences.md`](vfx/sequences.md)) | the system |

- **A frame draws a slot**: every slot renderer of the look on that slot draws, in the look's order. A slot nothing
  draws on costs nothing, and `frame.draws(this, slot)` skips the work of preparing it.
- **One shader on several kinds**: its material is made per kind, enabling the kind's keyword (`FX_ON_TUBE`,
  `FX_ON_MESH`, ...) where the shader declares it.
- **Every draw of a slot carries four numbers of its element's choosing** (`ex ey ez ew` above: the head's half width,
  half length and strength), which its materials' shaders read. Each element documents what its slots' four mean;
  what each kind passes besides is on `CgVfxSlot.Kind`.

### Draw order

Within an effect every draw ranks by its hint, a renderer's `sortOrderHint`, a slot renderer's material `order`, in
bands of 64: smoke, light, distortion, then the surface (`CgVfxLook.SURFACE`, 192), which draws after its own effect's
distortion so its haze never smears it ([`vfx/effects.md`](vfx/effects.md) § *Where a draw goes*). Effects that must
stack as one, a move made of several, share a `CgVfxSortGroup`, which ranks all their draws together. A look draws at
most 256.

---

## 8. Meshes

A slot renderer draws on what its slot gives it: a tube along a path, the unit sphere at a transform. Give it a mesh
and it draws that instead: a sword slash's crescent, a shield's dome; a mesh renderer draws one for every particle of
debris. A mesh is data (`CgMesh`, [`ENGINE_API.md`](ENGINE_API.md) § *Meshes*): built on any thread, from a file or
from a few numbers, and named for a renderer by its file's path or an id it is provided under.

### On a mesh slot

```java
public static final CgVfxSlot SLASH = CgVfxSlot.mesh("mymod:slash");
static final CgMesh CRESCENT = CgProceduralMesh.revolve(CgProceduralMesh.cylinder(1.25f, 1.6f, 0.06f))
        .angles(-75f, 75f).divisions(48, 3).build();
static { CgMeshLoader.provide("mymod:models/crescent", () -> CgMeshLods.of(CRESCENT)); }

static final CgMaterialDefinition FLOW = CgMaterialDefinition.of("mymod:materials/slash",
        "crystalgraphics:shaders/vfx/mesh/flow.shader").value("_Scroll", 2.5f, 0f, 0f, 0f).order(CgVfxLook.SURFACE).build();

public static final CgVfxKit KIT = CgVfxKit.of("mymod:slash")
        .slot(CgVfxSlotRenderer.of("slash", SLASH).material(FLOW).mesh("mymod:models/crescent").bind("_Color", KI).build())
        .build();

// in submit: placed by the slot's transform, as the sphere would be. flow.shader's four numbers are how far its
// reveal has swept along u, its strength, and how much fades behind the reveal
frame.mesh(this, SLASH, 0f, 1.2f, 0f, transform, reveal, 1f, 0.3f, 0f);
```

- **Two ways to name it**: a path, an OBJ or glTF file loaded at its first draw, so a constant in common code costs a
  server nothing; or an id `CgMeshLoader.provide`s a mesh made in code under, once.
- **Only a mesh slot takes one**; on any other slot it fails at load.
- **`shaders/vfx/mesh/flow.shader`** is the engine's look for one: energy flowing over its UVs (Effekseer's UV-scroll
  and erosion material), white-hot where a reveal sweeps along it, added. `_Scroll`, `_Tiling`, `_Erosion`, `_Edges`
  and `_Rim` shape it.

### One for every particle

```java
static final CgVfxEmitter ROCKS = CgVfxEmitter.builder("rocks").capacity(40)
        .state(CgVfxEmitterState.ONCE).burst(0.5f, 24).shape(CgSphere.of(0.5)).hemisphere().direction(0, 1, 0, 60f)
        .speed(4f, 7f).life(3f, 4f).size(0.12f, 0.3f, 1f).spin(1f, 4f)
        .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Ground(0.2f, 0.4f, 0.6f))
        .renderer(CgVfxRenderers.mesh("rocks").material(ROCK).field("meshes", List.of(Map.of("mesh", "mymod:models/rock.glb")))
                .build())
        .build();
```

Each particle draws the mesh turned as it spins and sized by its size, drawn as instances and culled on the GPU. A
mesh renderer naming no mesh draws the unit sphere's levels; its material lists `mesh_particles`.

### From a few numbers: procedural meshes

`CgProceduralMesh` (Effekseer's generator, ported) revolves a profile round +Y, or runs ribbons along it:

```java
// a crescent for a slash: a thin band of a cylinder through 150 degrees
CgMesh slash = CgProceduralMesh.revolve(CgProceduralMesh.cylinder(1.25f, 1.6f, 0.06f)).angles(-75f, 75f).build();

// a dome over an impact, swaying as it rises
CgMesh dome = CgProceduralMesh.revolve(CgProceduralMesh.sphere(1.6f, 0f, 1.6f)).divisions(40, 12)
        .wave(1.5f, 0.15f).build();

// three ribbons spiralling up a cone, wide at the base and pinched at the tip
CgMesh swirl = CgProceduralMesh.ribbons(CgProceduralMesh.cone(1.3f, 3.2f)).count(3).twist(1.25f)
        .width(0.5f, 0.05f).build();

// a rough rock: a sphere worn by curl noise
CgMesh rock = CgProceduralMesh.revolve(CgProceduralMesh.sphere(1f, -1f, 1f)).divisions(12, 8).curl(1.3f, 0.25f).build();

// a flame's body: a spline through four points
CgMesh flame = CgProceduralMesh.revolve(CgProceduralMesh.spline(0.35f, -0.05f, 0.95f, 0.9f, 0.7f, 2.2f, 0f, 3.4f))
        .build();
```

| Profile | Its shape, as (radius, height) |
|---|---|
| `sphere(radius, depthMin, depthMax)` | a sphere's arc between two heights: `(r, -r, r)` the whole sphere, `(r, 0, r)` a dome |
| `cone(radius, depth)` | from its apex at the origin out to `radius` at `depth` |
| `cylinder(radius1, radius2, depth)` | `radius1` at the bottom to `radius2` at `depth`: a band, a ring (`depth` 0), a cut cone |
| `spline(r1, h1, r2, h2, r3, h3, r4, h4)` | a natural cubic spline through four points |

| Revolved (`revolve`) | Ribbons (`ribbons`) |
|---|---|
| `angles(begin, end)`: degrees round, 0 on +Z toward +X; 0 to 360 unless given | `count(n)`, spaced evenly round: 1 unless given; `vertices(n)` along each: 32 |
| `divisions(around, along)`: 32 and 8 unless given | `width(start, end)` in blocks; `roll(start, end)` about its length, in degrees |
| `twist(turns)` from the profile's start to its end: a swirl | `twist(turns)`; `section(PLANE \| CROSS \| POINT)`; `scatter(angle, depth)` and `seed(s)` to stray from even |

Both take `tilt(...)`, a lean as it rises; `wave(frequency, power)`, a sway; `curl(frequency, power)`, roughness by
curl noise (`noiseSeed(seed)`); `axis(X | Y | Z)`, built round another axis; and `uv(u0, v0, width, height)`, where its
UVs land.

- **Its UVs**: u runs round it (along a ribbon), v from 1 at the profile's start to 0 at its end. `flow.shader`
  scrolls its noise along them.
- **Faces wind counter-clockwise seen from outside**. A ribbon has two sides, so its shader draws with culling off.
- **Data only**: build it once, as a constant, on any thread. Each `build()` makes a new mesh.

### A model of parts: prefabs

Meshes that move and fade together as one model (a lens of nested balls, a shuriken's rings and blades) are a
`CgVfxPrefab`: a node tree, each node's meshes drawn with a material, each node's opacity its own. The look takes
its `kit()`, and an effect plays it through a `CgVfxPrefabInstance`: [`vfx/prefabs.md`](vfx/prefabs.md). A prefab
moving over frames on every particle of an emitter is a clip: [`vfx/sequences.md`](vfx/sequences.md).

### Particles born on a mesh

An emitter's `mesh(...)` spawns on a mesh's surface, a triangle picked by area and launched along its normal, and
`vertices()` at its vertices ([§11](#where-particles-are-born-and-which-way-they-fly)): sparks along a sword, glints
on a shield.

```java
CgVfxEmitter.builder("glints").mesh(SWIRL).vertices().ordered(96)...    // 96 points through its vertices, in order
CgVfxEmitter.builder("glow").mesh("mymod:models/sword.obj")...           // off a sword's surface
```

---

## 9. Tweens and endings

### A tween

A tween moves one value of a playing effect from where it is now to a new value over some seconds, rather than
jumping there: a beam swelling as it powers up, an aura reddening as its owner weakens. The word is animation's, from
*in-betweening*: you give the end and how long, and the engine fills in every tick between.

```java
//            the value            to    seconds  how it moves
wave.animate(CgEnergyWave.RADIUS,  1.4f, 0.5f,    CgEasings.OUT_BACK);   // powering up: past 1.4, then settling there
aura.animate(Aura.CORE, 0xFFFF3030,      0.25f,   CgEasings.LINEAR);     // a colour: its four channels together
```

- It steps every tick, so it is smooth at any frame rate. What follows the value by a link follows the tween.
- A second tween of the same value takes over from wherever the first had got to. `set` of it stops it where it is.
- Tweening a linked value breaks its link: the tween drives it now.
- It changes this one effect, never its look.
- On a server's authoritative effect, or through a lasting cue's `aura.animate(...)`, it runs on every viewer at once:
  sent as one event (where to, how long, which easing), not a value a tick.

### Easings

An easing is the shape of a tween's motion over its seconds. `CgEasings` holds the usual set, each drawn at
[easings.net](https://easings.net):

| Easing | Moves | Reads as |
|---|---|---|
| `LINEAR` | at one speed | mechanical; right for colours |
| `IN_QUAD`, `IN_CUBIC`, `IN_EXPO` | slow, then faster and faster | giving way, falling in |
| `OUT_QUAD`, `OUT_CUBIC`, `OUT_EXPO` | fast, then settling in | most growth and motion |
| `IN_OUT_QUAD`, `IN_OUT_SINE` | slow at both ends | a drift |
| `OUT_BACK` | past the end, then back to it | a punch, a power-up |
| `OUT_ELASTIC`, `OUT_BOUNCE` | ringing or bouncing into place | cartoon |

`QUAD` to `QUINT` and `EXPO` are the same shape, steeper each; CSS's `EASE`, `EASE_IN` and `EASE_OUT` are there too.
An easing travels the network by its place among these constants, so one you make yourself (`cubicBezier(...)`,
`bake(...)`) reaches viewers as linear.

### An ending

An effect ends one of three ways:

| Call | Ends | For |
|---|---|---|
| `stop()` | the effect's own way: emission stops, its tail runs out, it bursts | the normal end |
| `end(ending)` | an ending's way: a few values tweened to where they end over its seconds, then gone. What the graceful end plays (the burst) is skipped | interrupted, cancelled, dispelled |
| `kill()` | at once | clearing up |

An **ending** is the second: a bolt that fizzles rather than bursts, an aura that fades when its buff is dispelled.
It is declared once, as a constant with a name:

```java
public static final CgVfxEnding FIZZLE = CgVfxEnding.over("mymod:bolt/fizzle", 0.4f)   // its name and its seconds
        .animate(Bolt.RADIUS, 0f, CgEasings.IN_QUAD)        // each value: where it ends, and how it gets there
        .animate(Bolt.CORE, 0xFFFF3020, CgEasings.LINEAR)  // reddening as it goes
        .fadeOut(CgEasings.IN_QUAD)                         // and the whole effect to nothing
        .build();
```

Then any effect whose look has those values can end by it:

```java
bolt.end(FIZZLE);                  // an effect you played
playing.end(FIZZLE);               // a lasting cue: every viewer's copy fizzles
aura.end(CgVfxEnding.FADE_OUT);    // the engine's: any effect faded out over 0.3 s
wave.end(CgEnergyWave.FADE);       // the wave's own: it thins where it is, no blast
```

**Why it has a name.** An ending crosses the network as its name alone: the server sends `"mymod:bolt/fizzle"`, and
each client finds the ending declared under that name in its own copy of your code (`CgVfxEnding.named`). So:

- **Declare it as a `static final` on a class every client has loaded** by the time it arrives: the effect's own
  class, or the look's. On a class only the server touches, a client never declares it and the ending does nothing
  there.
- **Prefix the name with your mod's id**: a second ending of one name throws.
- A player who joins while it plays starts it where it has got to.

**Easy to get wrong:**

- An ending animating a value its effect's look lacks throws at `end`.
- A second `end`, or one after the effect died, is ignored: the first ending plays out.
- `fadeOut` fades the effect's **presence**, which every engine shader multiplies its light by (`FX_PRESENCE`). A
  shader of your own fades only if it does the same ([§20](#20-writing-a-materials-shader)).
- A fade of another length is an ending of its own: `CgVfxEnding.over("mymod:slow_fade", 1.2f).fadeOut(CgEasings.IN_QUAD).build()`.

**In an effect of your own**, override `ending(CgVfxEnding)` to drop what the graceful end would play; it is heard
before `stop()`. `ending()` says which one is playing, or null.

```java
@Override protected void ending(CgVfxEnding ending) {
    burstOnStop = false;   // stop() would burst; an ending never does
}
```

---

## 10. Tuning a look live, and saving it

A look tuned live is a system asset: its file is what you edit, and the engine plays what you save. Write a look made
in code out as a file once ([`vfx/assets.md`](vfx/assets.md) § *A system, as a file and in Java*), play it from its
ref, and every saved change reaches what is playing it at the next frame: a value in place, a structural change
restarting it at its age ([`vfx/assets.md`](vfx/assets.md) § *Playing an asset, and changing it while it plays*).

```java
Files.write(path, CgAssetYaml.write(CgVfxSystemAssets.write(PUFF), CgParticleKinds.SYSTEM).getBytes(UTF_8));   // once
static final CgAssetRef<CgVfxSystemDefinition> TUNED = CgVfxAssets.ref(CgParticleKinds.SYSTEM, "mymod:puff");
VFX.play(new CgVfxSystemInstance<>(TUNED, x, y, z));                        // follows the file as it is saved
```

```yaml
# assets/mymod/vfx/systems/puff.yaml, edited while it plays
user:
  radius: { type: float, quantity: length, default: 0.9 }
```

- A tool edits the same file through `CgVfxAssets.edit`, with exact undo ([`vfx/assets.md`](vfx/assets.md) §
  *Reading and editing an asset in Java*).
- One playing effect alone: `effect.set(PARAM, value)` ([§6](#6-parameters-what-a-look-carries)); its look stays as
  it was.

---

## 11. Particles

Particles are simulated entirely on the GPU, so hundreds of thousands are affordable. You describe them as data (an
emitter), and the engine compiles that into GPU programs.

### An emitter

```java
static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("sparks")   // its name in a look
        .renderer(CgVfxRenderers.sprite("sparks").material(SPARK).build())   // a camera-facing quad each
        .capacity(1200)                        // the most alive at once
        .state(CgVfxEmitterState.once(4f))     // one loop of 4 s: unset, an emitter loops for ever
        .rate(120f)                            // 120 a second while it runs
        .shape(CgSphere.of(1.2))               // born anywhere in a ball 1.2 blocks round the source
        .hemisphere()                          // in its upper half, each flying out from its middle: up and out
        .speed(0.5f, 2f)                       // blocks a second, a random speed between
        .life(2.5f, 3f)                        // seconds
        .size(0.08f, 0.14f)                    // blocks; a third number above 1 makes most small and a few large
        .heat(1f)                              // 0 to 1, which Buoyancy turns into lift
        .module(new CgVfxModule.Gravity(9.8f))
        .module(new CgVfxModule.CollideWorld(0.35f, 0.4f, 0.4f))   // bounces off blocks
        .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
        .build();                              // ^ over its life, 0 to 1: in quickly, out slowly
```

Every range (`speed(min, max)`, `life`, `size`) is a random value between, a different one each particle.

**When particles spawn** (its loops, their duration and delay, rates, bursts, and stopping or killing it) is
[`vfx/particles.md`](vfx/particles.md) § *When particles spawn: emitter time*.

### Where particles are born, and which way they fly

The shape is one of the collision engine's ([`COLLISION.md`](COLLISION.md) §4), filled uniformly: a ball by volume, a
surface by area, an edge by length.

```java
.shape(CgCone.of(3, Math.toRadians(25))).pointing(0, 1, 0)       // a spray aimed up
.shape(CgRing.of(2.8, 3, 0)).edge().ordered(32)                   // 32 evenly round a circle on the ground
.shape(CgSphere.of(2)).surface().hemisphere()                     // on a dome, flying outward
.shape(CgBox.of(2, 0.05, 2)).direction(0, 1, 0, 10f)              // a sheet, launched up within 10 degrees
.shape(CgCapsule.between(0, 0, 0, 0, 0, 12, 0.3)).edge()          // along a line 12 blocks forward
.shape(CgShell.of(9, 11))                                         // between two spheres: rolling out from an edge
.mesh("mymod:models/sword.obj")                                   // off a sword's surface, along its normals
.mesh(RUNE).vertices().ordered(64)                                // at 64 of a mesh's vertices, in order
.entity().surface()                                               // on the box of the entity its effect follows
```

| Call | Means |
|---|---|
| `shape(shape)` | a sphere, shell, box, cylinder, ring, cone or capsule; a point unless given |
| `surface()`, `edge()` | on its surface, or its edge: a sphere's equator, a box's edges, a cylinder's or ring's outer circles, a cone's rim, a capsule's segment |
| `hemisphere()`, `arc(degrees)` | the half above its middle along its axis; part of the way round its axis |
| `ordered(n)` | spawn `k` at the `k mod n`-th of `n` even places round its axis (along a capsule's edge), not at random |
| `pointing(x, y, z)`, `at(x, y, z)` | turns its axis that way; moves it from the source |
| `outward()` (the default), `inward()` | launched away from its middle (its centre, or a cylinder's or capsule's axis), or toward it |
| `direction(x, y, z, spread)`, `direction(x, y, z, inner, spread)` | launched that way, within `spread` degrees, or between `inner` and `spread`: Niagara's cone, its angle from the axis drawn evenly |
| `mesh(mesh)`, `mesh(path)` | on a mesh's surface, a triangle picked by area, launched along its normal; a path loads at its first spawn |
| `vertices()` | at the mesh's vertices instead; with `ordered(n)`, the `k mod n`-th of `n` spread through them in order |
| `entity()` | in the box of the entity its effect follows, as it stands each step (`surface()` and `edge()` as a box's); at the source while it follows none |

A shape's axis is +Y, a cone's +Z from its apex, a capsule's its segment. An event's children are born in their
emitter's shape about the event's normal.

### A source that moves

```java
CgVfxEmitter.builder("wake").capacity(600).ratePerBlock(6f).inherit(0.4f)...   // six a block; 40% of its velocity

wake.moveTo(x, y, z);       // in the effect's tick, before tick(wake, dt)
```

- **A moving source leaves an even line**: each step's rate spawns are spread from where the source was as the step
  began to where it ended, each stepped for the part of the step after its birth. Bursts are born where the step began.
- **`ratePerBlock(n)`** spawns by distance while its loops run. A step farther than `maxStep(blocks)`, 8 unless given,
  is a teleport: it spawns nothing by distance and spreads nothing.
- **`inherit(share)`** gives each particle that share of its source's velocity, as an event's `inherit` gives children
  their parent's.
- What a source follows moves it too: an effect that `follow`s an entity carries its emitters' sources with it.

### In a look

The emitter goes in a kit or a look, and its renderers draw its particles:

```java
static final CgVfxSchema SCHEMA = CgVfxSchema.of("mymod:sparks");
static final CgVfxParam.Color EMBER = SCHEMA.color("ember", 0xFFFF4A0B, 1.2f);
static final CgVfxParam.Color FLAME = SCHEMA.color("flame", 0xFFFFC85B, 1.4f);

static final CgMaterialDefinition SPARK = CgMaterialDefinition.of("mymod:materials/spark",
        "crystalgraphics:shaders/vfx/particle/spark.shader").usage(CgMaterialUsage.SPRITES).build();
static final CgVfxKit KIT = CgVfxKit.of("mymod:sparks")
        .emitter(SPARKS.toBuilder().renderer(CgVfxRenderers.sprite("sparks").material(SPARK)
                .bind("_Color", EMBER).bind("_Hot", FLAME).sortOrderHint(CgVfxLook.SURFACE).build()).build())
        .build();
static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).with(KIT).build();

VFX.play(new CgVfxSystemInstance<>(LOOK, x, y, z));   // particles alone need no effect class
```

**Shared particle shaders** in `shaders/vfx/particle/`, each reading `_Color` and, where it has one, `_Hot` (bind
them):

| Shader | Draws |
|---|---|
| `spark` | a glowing disc streaked along its motion |
| `speck` | ragged debris (`_Color` only) |
| `dust` | soft ground dust that pools into haze |
| `arc` | an ink stroke |
| `ray` | a hot streak along its velocity |
| `ring` | a shock front's foot glowing as it runs out |
| `sprite`, `sprite_glow` | a frame of a flipbook sheet, lit or glowing (`_Color` only); which frame is the renderer's `sub_image` ([`vfx/effects.md`](vfx/effects.md) § *Flipbooks*) |

The explosion (`CgVfxBlast.EXPLOSION`, a `CgVfxExplosion`) is a whole kit of them: billows, surge, specks, puffs,
sparkles, ink, rays, rings and skirt, with its own colours (`body`, `hot`, `debris`, `dust`, `sparkCore`).

### Things to know

- **Capacity is a hard limit**, 256 unless given: a spawn that would pass it is dropped. Size it to the burst plus what
  is still alive; a rate per block needs one, since how fast its source moves is unbounded.
- **Variants**: `SPARKS.toBuilder()...build()`. **The same burst bigger or smaller**: `SPARKS.scaled(s)` scales shape,
  speed, size, forces and a rate per block's spacing, keeping the timing.
- **`.optional()`** marks detail: halved at the Low quality tier. The player's density setting thins every emitter.
- **`sweep(speed)`** runs the shape outward, so particles are born on a moving front: a shell's, a ring's.
- **Each renderer's material on particles draws as one batch for the whole system**, sorted far to near within
  itself, so two materials order as wholes. A solid look writes depth (`"Depth" = "Clip"`); soft smoke a depth prepass where it is
  solid (`"Depth" = "Prepass"`); only something soft throughout relies on blending
  ([`SHADERS.md`](SHADERS.md) § *Depth from a transparent material*).
- Effects and their emitters' books tick 120 times a second; the GPU steps the particles once a frame, over the
  frame's time, each tick's births at their own moment within it. A particle is drawn where that step left it.
- A particle always belongs to an effect: one-off particles are a `CgVfxSystemInstance` each. A shared emit call for
  Minecraft-style spam is planned (plan `vfx-gpu` X15).

### Every emitter call

| Call | Sets |
|---|---|
| `renderer(renderer)` | how its particles draw: a sprite, mesh, ribbon, anim trail, arc or sequence renderer ([`vfx/particles.md`](vfx/particles.md) § *Renderers*); its kinds set the layout |
| `sequence(clip)`, `sequence(clip, playRate)` | a clip on each particle: its prefab's parts where its nodes are at the particle's age, its bursts and streams spawning children ([`vfx/sequences.md`](vfx/sequences.md)) |
| `capacity(n)` | the most alive at once: 256 unless given |
| `state(state)` | its loops, their duration and delay, and what stopping does ([`vfx/particles.md`](vfx/particles.md)) |
| `rate(perSecond)`, `rate(perSecond, curve)` | a rate while each loop runs, scaled by a curve over the loop's seconds |
| `burst(time, count)`, `burst(CgVfxSpawnBurst)` | `count` at once at `time` seconds into each loop; a count range, a probability |
| `ratePerBlock(n)`, `maxStep(blocks)` | spawns by the distance its source moves ([above](#a-source-that-moves)) |
| `shape`, `surface`, `edge`, `hemisphere`, `arc`, `ordered`, `pointing`, `at`, `mesh`, `vertices`, `entity` | where they are born ([above](#where-particles-are-born-and-which-way-they-fly)) |
| `outward`, `inward`, `direction` | which way they fly |
| `speed(min, max)`, `inherit(share)` | blocks a second; a share of the source's velocity |
| `life(min, max)` | seconds |
| `size(min, max)`, `size(min, max, skew)`, `size(curve)` | blocks; a skew above 1 makes most small and a few large; a curve over life, a share of the starting size |
| `opacity(curve)` | 0 to 1 over life |
| `color`, `colorOverLife`, `intensityOverLife`, `scale`, `scaleOverLife` | its colour and its size on each axis, multiplying what its renderers draw ([`vfx/particles.md`](vfx/particles.md) § *How each particle looks*) |
| `spin(min, max)` | radians a second, either way: a quad's turn, a mesh's tumble |
| `heat(h)` | 0 to 1 at birth: `Buoyancy` lifts it, and hot shaders (`spark`) glow by it |
| `module(m)`, `clearModules()` | a physics piece, run in order ([§12](#12-modules-and-events)) |
| `event(e)` | children spawned or rows reported when something happens to a particle ([§12](#12-modules-and-events)) |
| `rasterize(x, y, z, w)` | drawn into its instance's grid each frame, a soft ball as wide as it is drawn holding that density in each channel: a body of particles a grid's slot renderer draws lit through itself (`CgVfxGrid3D.channel(c)`) |
| `sweep(speed)` | born on a front running out at that speed over each loop: the shape's radius by the loop's seconds |
| `local()` | its particles ride the effect as it moves, rather than staying where they were born |
| `optional()` | detail: halved at the Low quality tier |
| `name(name)` | its name in a look, which `look.emitter(name)` finds and a look's `emitter` replaces by |
| `toBuilder()`, `scaled(s)` | a variant; the same emitter bigger or smaller, its timing kept |

### Where particles run

Every tier the engine supports runs them: compute on modern GL and Vulkan, lowered to draws on older GL
([`SHADERS.md`](SHADERS.md) § *Every tier*). Nothing simulates them in Java: an emitter instance keeps only the
books of what spawns when, which is how it knows it has finished without asking the GPU.

---

## 12. Modules and events

Modules move particles: forces, collisions, kills, flocking, a fluid's flow. Events fire when something happens to a
particle and spawn children or report it.

### Every module

Modules run in order every step. Forces come first and are integrated together; a module after the solver
(collisions, kills) sees where the particle went.

| Module | Does |
|---|---|
| `Gravity`, `Force` | A constant pull down; a constant push in any direction |
| `Drag`, `Damping`, `LimitSpeed` | Linear and quadratic drag; velocity decaying by a rate; a speed cap |
| `Wind`, `Turbulence`, `Buoyancy`, `Updraft` | The system's gusting air; curl noise; hot particles rising as they cool; a column of rising air |
| `Attract`, `Orbit`, `Vortex`, `Conform` | Pull toward a sphere or box; circling an axis; a vortex's pull; settling onto a sphere's shell |
| `Flock` | Reynolds' boids among an instance's own particles: apart from the nearest, along with their heading, toward their middle |
| `Feed` | Pours smoke and heat into the fluid its instance is in, where each particle is, and pushes it: sparks trailing smoke, a flock carving wakes ([§14](#14-gas)) |
| `Flow` | Carried by the fluid its instance is in (`instance.inGas(fluid)`, [§14](#14-gas)): embers riding a plume, debris whirled through a cloud |
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

### Flocking

`Flock` is Reynolds' boids among an instance's own particles: each steers apart from those nearest it, along with
their heading and toward their middle. A flock of spirits round a heart, a school of fish round a vortex, a murmuration
of embers.

```java
static final CgVfxEmitter SPIRITS = CgVfxEmitter.builder("spirits").state(CgVfxEmitterState.ONCE).capacity(300).burst(0f, 300)
        .shape(CgSphere.of(3)).speed(1f, 2f).life(30f, 30f).publishes(true)    // a trail behind each: §13
        .module(new CgVfxModule.Flock(1.6f, 0.5f, 3f, 1.5f, 0.8f))       // radius, apart, separation, alignment, cohesion
        .module(new CgVfxModule.Attract(CgSphere.of(1), 2f))             // round a heart
        .module(new CgVfxModule.LimitSpeed(4f))                           // boids want a top speed
        .build();
```

| Argument | Means |
|---|---|
| `radius` | blocks: the neighbours it sees |
| `apart`, `separation` | it is pushed from each neighbour nearer than `apart` blocks, harder the nearer, by `separation` |
| `alignment` | how fast a second its velocity matches its neighbours' |
| `cohesion` | its pull toward their middle, blocks a second squared a block |
| `most` | at most this many neighbours counted, 24 unless given: a dense flock costs what a sparse one does |

- **Its neighbours are its own instance's**: two effects' flocks never mix.
- **A pool builds a grid of its particles after each step** while a kind in it takes neighbours, at every tier; a
  newborn joins at the next step.
- A module of your own reads the same grid ([§21](#21-writing-a-particle-module-of-your-own)).

### Events: particles spawning particles

An event fires per particle (its birth, landing, death, an age, each collision, every period, every step) or per batch
its emitter spawns, and spawns children of another emitter where it fired, reports to the CPU, or both:

```java
.event(CgVfxEvent.onCollision().spawn(FLASH, 1))                    // a flash at each bounce
.event(CgVfxEvent.onCollision().spawn(CHIPS, 3, 5).inherit(0.3f))   // three to five chips, 30% of the parent's velocity
.event(CgVfxEvent.onLocation().spawn(SPARK, 1).maxPerFrame(256))    // a spark each step, 256 a frame at most
.event(CgVfxEvent.onBurst().spawn(RING, 2).fromEmitter())           // two rings at the effect's origin each batch
.event(CgVfxEvent.onLanding().readback(64))                         // each landing reported, a few frames later
```

A system file says the same as handlers on the emitter they spawn, each naming its source by handle
(`FNiagaraEventScriptProperties`):

```yaml
emitters:
  - id: h1
    emitter: { ... }                  # the debris
  - id: h2
    emitter:
      ...
      events:
        - { id: e1, trigger: collision, source: h1, spawn: { emitter: h2, count: { range: [3, 5] }, inherit_velocity: [0.3, 0.3, 0.3] }, max_per_frame: 64 }
```

| Trigger | Fires | In Java |
|---|---|---|
| `spawn` | its first step | `onAge(0)`'s, from the file |
| `death` | the step it dies | `onDeath()` |
| `collision` | each impact, its first 8 | `onCollision()` |
| `location` | every step it lives | `onLocation()` |
| `burst` | each batch its emitter spawns, at the batch's source (Cascade's) | `onBurst()` |
| none: Java's alone, Unreal having none | the step it comes to rest on the ground | `onLanding()` |

- **Three levels deep**: a child's events may spawn children, and theirs, and theirs may report rows but not spawn.
- **At most `max_per_frame` firings a frame spawn children**, 64 unless told (Unreal's `MaxEventsPerFrame`), counted
  over every instance of the emitter in its pool; past it the GPU's order decides which. Up to 1,000 children a firing.
- **What a file refuses, by name**: a spawn loop. `inherit_velocity` is a share
  per axis. Rows to the CPU, a landing, an age past birth, a rate and a clip node are Java's
  alone: a system holding one cannot be written to a file.
- **At a clip's node, inside a window**: on a `sequence(clip)` emitter an event fires `at(node)`, and a rate
  `between(from, until)` seconds of the particle's age ([`vfx/sequences.md`](vfx/sequences.md)).
- **Give every child its renderers where it is made**, before the parent's `event(...)` names it: the parent holds
  the child it was given, and draws it through that child's renderers.
- At most `CgVfxEvent.MAX_EVENTS` events an emitter and `MAX_CHILDREN` children an event.

---

## 13. Trails and ribbons

Trails and ribbons are Unreal's, drawn by an emitter's renderers: Niagara's ribbon through an instance's particles,
Cascade's ribbon trail behind a source its effect moves or behind each of another emitter's particles, and Cascade's
anim trail swept by an edge between two points. All of it, with the looks in `shaders/vfx/trail/`, is
[`vfx/particles.md`](vfx/particles.md) § *Ribbons and trails: strips through particles*.

```java
// a streak behind a hand: a Cascade trail whose source the effect moves each tick
static final CgVfxEmitter STREAK = CgVfxEmitter.builder("streak").capacity(64)
        .ratePerBlock(CgVfxSpawnPerUnit.every(1f, 0.12f)).speed(0f, 0f).life(0.5f, 0.5f).size(0.2f, 0.2f, 1f)
        .renderer(CgVfxRenderers.ribbon("glow").material(GLOW).field("trail", Map.of("max_particles", 64)).build())
        .build();

streak.moveTo(handX, handY, handZ);   // in the effect's tick, from its origin
tick(streak, dt);
```

## 14. Gas

Smoke, fire, steam, a plume off a chimney: these are a fluid, not particles. `CgVfxFluid` (`vfx.fluid`) is an element
owning one, NVIDIA Flow's grid simulation ported to the GPU (`THIRD-PARTY.md`): a grid of cells about where it was
placed, which its emitters couple toward their targets. Fuel above its ignition temperature burns into heat and smoke,
heat rises, confinement keeps its curl, it flows round the world's blocks, and the sun and the sky light it through
itself. The grid is **sparse**: it holds cells only where it is active (Flow's summary: smoke past 0.02, or smoke moving
faster than a block a second), in tiles of 16 drawn from one pool, so smoke flows as far as it goes. Its parameters
are Flow's, by Flow's names, its lengths in blocks where Flow's are centimetres; detail finer than its cells is
Mantaflow's wavelet noise, carried with the flow.

### A fluid in an effect

```java
public final class Campfire extends CgVfxEffect<Campfire> {
    private final CgVfxFluid fire = new CgVfxFluid(this).place(0f, 2f, 0f);      // its middle, from the effect's origin
    private final CgVfxFluid.Emitter logs = fire.emitter(0f, 0.3f, 0f, 0.5f);   // Flow's sphere: hot fuel, rising

    public Campfire(CgVfxLook look, double x, double y, double z) {
        super(look, x, y, z);
    }

    @Override protected void tick(float dt) {
        fire.tick(dt);                                     // every tick: it steps on the effect's clock
        if (age > 30f) logs.off();
        if (fire.faded()) die();
    }

    @Override protected void submit(CgVfxFrame frame) {
        fire.submit(frame);
    }
}

public static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).with(CgVfxFluid.FIRE)   // its schema and the fire's slot renderer
        .set(CgVfxFluid.CELL, 0.15f).build();
```

```java
// smoke with no fire: no fuel, a little heat to lift it
smoke.emitter(0f, 0f, 0f, 0.6f).combustible(false).temperature(0.3f, 2f).smoke(1f, 3f).velocity(0f, 2f, 0f, 2f);
// Mantaflow's noisy inflow: smoke and heat raised to wavelet noise where below it
fire.emitter(0f, 0.2f, 0f, 1f).box(0.8f, 0.3f, 0.8f).temperature(1f, 1f).fuel(1f, 1f).noise(0.75f);
fluid.blast(x, y, z, 4f, 25f, 1f);      // thrown out at 25 blocks a second, cleared within 4
```

| Call | Does |
|---|---|
| `new CgVfxFluid(effect)`, `(effect, slot)` | A fluid drawn on `CgVfxFluid.SLOT`, or on a fluid slot of the effect's own (`CgVfxSlot.fluid(name)`) |
| `place(x, y, z)` | Where its grid's middle goes, from the effect's origin; taken when it first gets a place in the atlas, fixed in the world after |
| `domain(hx, hy, hz)` | The box round its middle it may hold cells in, as Mantaflow's scenes size their grids: smoke leaving it is gone, as through open boundaries. Unset, a fluid whose smoke lingers follows it across the pool |
| `emitter(x, y, z, radius)` | Flow's sphere emitter: everything in it coupled toward its targets. `box(hx, hy, hz)`, `cylinder(radius, halfHeight)` for other shapes; `at`, `on()`, `off()`. At most 8 |
| `velocity(vx, vy, vz, couple)`, `temperature`, `fuel`, `burn`, `smoke`, `divergence` | A target and the rate it is coupled at a second (Flow's `coupleRate`: the share of the way there a second, so it holds a region at a value and never overfills it). Unset: Flow's defaults, rising at 4 blocks a second, temperature 0.5 and fuel 0.8, each coupled at 2 |
| `combustible(false)` | No fuel: what it emits only rises and thins |
| `noise(offset)` | Mantaflow's `densityInflow`: each channel it couples raised to its target times wavelet noise plus `offset` where below it, never lowered |
| `blast(x, y, z, radius, speed, carve)` | Everything within `radius` thrown out at about `speed`, through Flow's divergence, and `carve` (0 to 1) of its smoke and fuel cleared there |
| `tick(dt)`, `submit(frame)` | From the effect's own, every tick and every frame |
| `faded()` | Nothing emitted for as long as its smoke's fade and damping take whole smoke to a fiftieth: what the effect waits for to die |
| `release()`, `bounds(out)`, `cellSize()` | Its place and tiles given back now; the box its tiles cover; a cell's side in blocks at this tier |

### Its parameters

Flow's defaults unless said; each Flow centimetre is a hundredth of a block.

| Parameter (`CgVfxFluid`) | Default | Meaning |
|---|---|---|
| `CELL` | 0.25 | a cell's side in blocks at High and Ultra (1.5 times at Medium, twice at Low): its finest detail, and how many of the pool's tiles its smoke takes |
| `COMBUSTION` | 1 | 0 for smoke alone: nothing burns |
| `IGNITION_TEMP`, `BURN_PER_TEMP`, `FUEL_PER_BURN` | 0.05, 4, 0.25 | the least temperature fuel burns at; how fast it burns per degree over; fuel a unit of burn takes |
| `TEMP_PER_BURN`, `SMOKE_PER_BURN`, `DIVERGENCE_PER_BURN` | 5, 3, 0 | what a unit of burn makes: heat, smoke, and expansion in blocks a second (a fireball's burst) |
| `BUOYANCY_PER_TEMP`, `BUOYANCY_PER_SMOKE`, `BUOYANCY_MAX_SMOKE`, `GRAVITY` | 2, 0, 1, 1 | lift per unit of temperature and of smoke (up to its clamp), times gravity in blocks a second a second |
| `COOLING_RATE` | 1.5 | the share of its temperature lost a second |
| `*_DAMPING`, `*_FADE` | velocity 0.01, 0.01; smoke 0.3, 0.65; the rest 0 | per channel, the share lost a second and the amount lost a second, the larger each step |
| `VORTICITY` and its masks | 0.6, velocity mask 1 | confinement's strength and what weighs it: velocity, constant, temperature, fuel, burn, smoke |
| `TURBULENCE`, `TURBULENCE_SIZE` | 0, 2 | the engine's curl noise where there is smoke, and its swirls' size in blocks |
| `SHADOW_ATTENUATION`, `SHADOW_MIN_INTENSITY` | 4.5, 0.125 | light lost a block through whole smoke toward the sun; the least a cell keeps |
| `ATTENUATION`, `SHADOW_FACTOR` | 5, 1 | opacity a block through whole smoke; how much the light reaching a cell colours it |
| `COLORMAP_MIN`, `COLORMAP_MAX` | 0, 1 | the temperatures the colour map spans: smoke with no heat sits at its start, nearly clear, so a cold look sets `COLORMAP_MIN` -1 |
| `SCATTERING` | 0.6 | forward scattering, 0 to 0.9 |
| `DETAIL`, `DETAIL_SIZE`, `DETAIL_PERIOD` | 0.6, 0.5, 2 | detail below the cells, its size in blocks, and the seconds each of Mantaflow's two UV grids lives before it resets |

Temperature is Flow's, normalized: 0 is the air's, 1 the hottest.

### How it is drawn

A slot renderer on its fluid slot draws it. `CgVfxFluid.SMOKE` and `FIRE` are Flow's two colour maps on `vfx/fluid/fluid.shader`,
Flow's ray march: steps of three quarters of a cell, each taking the colour map's colour and opacity at its temperature,
the opacity times its smoke and `1 - exp(-ATTENUATION * step)`. A colour up to 1 is what the fluid scatters, lit by the
sun through the fluid under a two-lobe phase and by the sky; past 1 it is light the fluid gives off, the same by day and
night, and only that blooms: white-hot fire does, smoke never. A colour map of your own is a kit of its own:

```java
static final CgVfxColormap EMBERS = CgVfxColormap.builder()
        .at(0f, 0.02f, 0.02f, 0.02f, 0.5f).at(0.5f, 1f, 0.2f, 0.02f, 0.8f).at(1f, 0xFFFFC060, 20f, 0.7f)
        .colorScale(2.5f).build();
static final CgVfxKit EMBERS_KIT = CgVfxFluid.kit("mymod:fluid/embers", EMBERS);
```

- **It is drawn at half size and blended with its last frames** (the world renderer's volumes): each frame its march
  starts elsewhere, and the frames average out its grain. `H` in the harness draws it at full size.
- **What is inside it is hidden by it**: what is drawn over the volumes is dimmed by the smoke between it and the eye
  (the world's media, [`SHADERS.md`](SHADERS.md) § *Dimmed by the smoke in front*). `ATTENUATION` sets how fast.
- It reaches `CgVfxFluidAtlas.SPAN / 2` cells each way from where it was placed, and stays there: a moving emitter
  trails through it.

### Where it lives: the pool, the clock, the world

- **At most sixteen fluids at once** (`CgVfxFluidAtlas.CAPACITY`); one asking past that waits, drawing nothing.
- **Every fluid shares one pool of tiles**: 1024 at Ultra, 512 at High, 256 at Medium, 128 at Low. Once the pool is
  full each fluid alive is kept an even share of it; the counters `vfx.fluid.tiles` and `vfx.fluid.pool-full` say how
  full it runs. Changing tier starts every fluid again empty. Give each fluid a `domain` that holds what it shows, and
  many share the pool with none refused: `vfx-fluid`'s nine fit Ultra's 1024 tiles.
- **It steps 60 times a second on its effect's clock** (Flow's time stepper), and draws the time past its last step by
  carrying its smoke on (Flow's interpolation), so slow motion moves every frame. An effect left unticked for two
  seconds gives its tiles back. Under the GPU's budget (`CgVfxFluidAtlas.GPU_BUDGET`, 2 ms) its pressure solve takes
  fewer Jacobi iterations.
- **It flows round the voxel window's blocks** and stands on its effect's `ground` where the host has no world.

### Particles and entities in it

**Particles ride it, pour into it and push it.** `CgVfxModule.Flow` draws their velocity toward the fluid's where they
are; `CgVfxModule.Feed` makes each pour smoke and heat a second where it is, spread over the cells round it and taken at
the fluid's next step, and with a push pulls the fluid there toward the particle's own velocity (NVIDIA Flow's point
emitter: where many push one cell it takes their mean, weighted by nearness). The instance names its fluid:

```java
embers = new CgVfxEmitterInstance(look().emitter("embers"), seed).inGas(fire);   // .module(new Flow(2.5f))
sparks = new CgVfxEmitterInstance(look().emitter("sparks"), seed).inGas(fire);   // .module(new Feed(4f, 0.5f))
.module(new CgVfxModule.Feed(0f, 0f, 10f))      // pushing alone: a flock dragging wakes through fog
.module(new CgVfxModule.Feed(0.2f, 3f, 9f))     // a stream of particles that is the flame: hot, and driving it
```

- What particles pour lands where the fluid holds tiles: the engine asks for tiles round each instance feeding it, and
  `fluid.wantTiles(x, y, z, radius)` asks round a point of your own.
- At most `CgVfxFluidAtlas.DEPOSITS` particles pour between two steps, every instance's together; the rest pour nothing.
- **Entities wade through it**: the living entities in a fluid's box (`CgVfxFluidAtlas.MAX_ENTITIES`, every fluid's
  together) drag it to their velocity. None on a host with no level.

### Showcases

`CgVfxFluidDemo` (the harness's `vfx-fluid`; N and Shift+N fly between stations, M back to the whole grid) plays:

- scenes the ported engines ship as code: Flow's editor stage `sphere` at a block for every 10 cm, Mantaflow's
  `fire.py` and `simpleplume.py` in their grids' sizes, each station's javadoc naming the file it is set up from;
- a CS2 smoke grenade ([§15](#15-smoke-that-fills-a-space-cs2s-grenades)) with fireflies riding its churn;
- a fire whirl: a burning pool's swirl drawn in and spun up into a thin turning column;
- particles and fluids pushing each other: a dragon's breath that is only particles feeding and pushing, spirits carving wakes through a fog bank, a comet's sparks feeding its trail;
- particles splatting grids: a slime mould, smoke made of particles beside the same particles as sprites, a nebula;
- a mushroom cloud: a fireball expanding as it burns, rising into a ring, its stem lifted by afterwinds, debris riding
  the updraft, a surge of dust particles pushing the fluid out along the ground.

## 15. Smoke that fills a space: CS2's grenades

`CgSmokeGrenade` floods smoke from where it lands as Counter-Strike 2's does: half-block cells (`CgVfxSmokeFill.CELL`,
a block's eighth, CS2's twenty units) spreading round walls, down stairs and through doors, never through a block,
cheaper down than up, until a budget of cells is spent; in the open it settles as a squat dome. It holds for eighteen
seconds and thins away; shots and blasts carve it open and it heals. A fluid fills the cells and gives them motion: its
smoke is held to the cells revealed, thinning over its outer three cells so noise wears its surface into billows, and
drained outside them, so the shape is the flood's and the churn the fluid's ([§14](#14-gas)).

### Playing one

```java
// common, at init
public static final CgVfxCue SMOKE = VFX.cue("smoke", CgSmokeGrenade.LOOK, CgSmokeGrenade::fromCue)
        .range(128).authoritative().build();

// server
CgVfxCue.Playing smoke = SMOKE.at(level, x, y, z).start();     // floods the server's world, sends every client the cells
CgSmokeGrenade grenade = smoke.effect();
```

```java
VFX.play(new CgSmokeGrenade(CgSmokeGrenade.LOOK, x, y, z));     // one client alone: it floods that client's world
```

An authoritative cue is the one to use when the smoke hides players: every client draws the server's cells, and the
server answers what they hide.

### What it hides: sight, shots and blasts

```java
if (grenade.blocksSight(eyeX, eyeY, eyeZ, tx, ty, tz)) hidden();   // gameplay: the same answer on every side
grenade.shot(eyeX, eyeY, eyeZ, hitX, hitY, hitZ);                  // a tunnel healing in 1.5 s; every viewer's opens too
grenade.blast(x, y, z, 3.5f);                                       // a hole healing over 5 s, CS2's curve
```

- **A line is hidden** once it crosses `SIGHT` blocks of whole smoke, counted cell by cell through the cells revealed,
  thinning as it fades and where it has been carved.
- **Positions are absolute world coordinates**, as gameplay holds them.
- **A shot** carves a capsule along the line, a third of a block round it; **a blast** clears a sphere and throws the
  smoke out, then heals as CS2's smoke does (`pow(smoothstep(0.5, 5, t), 1.8)`).
- Called on the server's copy, a carve reaches every viewer; the server keeps its sixteen latest.

### Its parameters

| Parameter (`CgVfxSmoke`) | Default | Meaning |
|---|---|---|
| `SPREAD` | 1.6 | seconds to reach its last cell, fast at first |
| `DURATION`, `FADE` | 18, 2.5 | seconds it holds, then thins away over |
| `CELLS`, `REACH` | 2600, 8 | the most cells it fills; the farthest it spreads across, blocks |
| `DENSITY`, `HOLD` | 2.5, 3 | the smoke its cells hold its fluid at, and how fast a second it is pulled there |
| `SIGHT` | 1.5 | blocks of whole smoke a line crosses before it is hidden |

The look (`CgSmokeGrenade.LOOK`) also sets the fluid's own (`CgVfxFluid.CELL` at the cells' size, no fire,
`SMOKE_DAMPING` and `SMOKE_FADE` 0 since the cells hold its smoke, `COLORMAP_MIN` -1 for cold smoke): derive it to
change either.

### Who floods, and what travels

- **An authoritative cue's server copy floods** the server's blocks and sends the cells (`CgSmokeGrenade.CELLS`) with
  the first state every viewer gets, so every client draws the same smoke. A plain cue, or a local play, floods the
  client's own world.
- **A block is solid** where its collision box holds a cell's middle; an unloaded block counts as solid.
- **Carves travel** (`CgSmokeGrenade.CARVES`), each healing on each viewer's clock.
- **A server's copy runs no fluid**: it floods, carves and answers sight alone.

### In an effect of your own

`CgVfxSmoke` is the element under the grenade: give your effect one and call it from `tick` and `submit`.

```java
private final CgVfxSmoke smoke = new CgVfxSmoke(this);

@Override protected void tick(float dt) {
    if (smoke.fill() == null) smoke.flood();      // the effect's world, from its origin; or fill(cells) from a cue
    smoke.tick(dt);
    if (smoke.done()) die();
}

@Override protected void submit(CgVfxFrame frame) {
    smoke.submit(frame);
}
```

- Its look carries `CgVfxSmoke.SCHEMA` and `CgVfxFluid.SCHEMA`: build it with `CgVfxFluid.SMOKE` and `params(CgVfxSmoke.SCHEMA)`.
- `revealed()` and `presence()` say how far it has spread and how present it is, for sound or a HUD.
- **The flood alone**, headless, for a server rule or a test:
  `CgVfxSmokeFill.flood(CgVfxSmokeFill.blocks(world, floorY), x, y, z, cells, reach)`; `encode()` and `decode` carry
  it over a wire.

---

## 16. Effects over the network: cues

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
| `.authoritative()` | The server runs it too ([§17](#17-effects-gameplay-depends-on-server-authority)) |
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

A field is sent only when its value changed. On each viewer, look values are set on its effect as they arrive. The
rest (a new direction, target or field of its own) reaches only an effect that implements `CgVfxCue.Live`: its
`cueChanged(cue)` runs once a frame after a change, and reads what it wants (the wave takes its aim and target).

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
  (`SCHEMA.networked(...)`, [§17](#making-your-own-effect-authoritative)).

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

## 17. Effects gameplay depends on: server authority

Some effects *are* the gameplay: a beam damages whatever it reaches. If each client decided what it hit, every client
would see a different result. So the server runs the effect too, as the referee, and every client draws what the
server decided.

The server runs **the same effect class**, headless (nothing drawn) against the server's world (`CgVfxAuthority`): it decides where
the effect goes and what it strikes, hears its hits, and publishes what clients must draw. Unreal and Unity Netcode
run one actor on both sides the same way.

### The whole thing, with the Kamehameha

```java
// common, at init: one declaration, one factory for both sides
public static final CgVfxCue WAVE = VFX.cue("kamehameha", CgEnergyWave.kamehameha(),
        CgEnergyWave::fromCue).range(96).authoritative().build();

// server: start it, then drive its effect exactly as on a client
CgVfxCue.Playing wave = WAVE.on(caster, CgEntityAttachments.HANDS_FORWARD).start();
CgVfxSystemInstance<CgEnergyWave> beam = wave.effect();
beam.set(CgEnergyWave.RELEASE, Float.POSITIVE_INFINITY).set(CgEnergyWave.AIM, look.x, look.y, look.z);   // key down

wave.onHit(CgEnergyWave.PART_BEAM, h -> { if (h.kind() == CgHit.Kind.ENTITY) stagger(h.entity()); })
    .onHit(CgEnergyWave.PART_BURST, h -> explode(h.x(), h.y(), h.z(), beam.behaviour().blastRadius()))
    .onHit(CgEnergyWave.PART_BLAST, h -> damage(h.entity()));

beam.set(CgEnergyWave.AIM, look.x, look.y, look.z);   // each tick, as the caster turns
beam.set(CgEnergyWave.RADIUS, 1.2f);       // powered up: every viewer's beam widens, and the server's ray with it
beam.set(CgEnergyWave.RELEASE, beam.age());           // key up
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

- **`wave.effect()`** is the server's copy. Call it exactly as on a client: `set` (its user parameters too), `animate`, `end`,
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
- What it costs: [§22](#on-the-server).

### Making your own effect authoritative

Most effects need nothing: declare the cue `authoritative()` and values sync by themselves. An effect whose own state
must reach viewers (the wave's end point) adds two interfaces:

```java
public final class Bolt extends CgVfxEffect<Bolt> implements CgVfxCue.Live, CgVfxCue.Authoritative {
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

## 18. Hits: what an effect strikes

An effect strikes through the collision engine ([`COLLISION.md`](COLLISION.md)): the queries it makes through
`world()` and the hitboxes it owns. Each thing it strikes is a `CgHit`, and `onHit` hears them:

```java
CgVfxSystemInstance<CgEnergyWave> wave = VFX.play(CgEnergyWave.instance(look, x, y, z).follow(playerId, HANDS_FORWARD));
wave.set(CgEnergyWave.COLLIDE, 1f);
wave.onHit(h -> { if (h.kind() == CgHit.Kind.ENTITY) flash(h.entity()); });
```

### Parts

An effect with more than one thing that strikes declares each as a **part** on its schema, and every hit names its
part:

```java
wave.onHit(CgEnergyWave.PART_BEAM, h -> flash(h.x(), h.y(), h.z()))
    .onHit(CgEnergyWave.PART_BURST, h -> shatter(h.x(), h.y(), h.z(), wave.behaviour().blastRadius()))
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
WAVE.onConfirmedHit(CgEnergyWave.PART_BLAST, h -> VFX.play(new CgVfxSystemInstance<>(SPARKS, h.x(), h.y(), h.z())));
```

A confirmed hit arrives a network trip after the server's. For feedback timed to what this client draws, listen to the
viewer's own copy: the factory builds both copies, and `cue.authoritative()` tells them apart.

```java
public static final CgVfxCue WAVE = VFX.cue("kamehameha", CgEnergyWave.kamehameha(), (look, cue) -> {
    CgVfxSystemInstance<CgEnergyWave> wave = CgEnergyWave.fromCue(look, cue);
    if (!cue.authoritative()) wave.onHit(CgEnergyWave.PART_BEAM, h -> flash(h.x(), h.y(), h.z()));   // each viewer
    return wave;
}).range(96).authoritative().build();
```

- Both land where the damage does: a viewer's copy reports where the server published the beam ending.
- A late joiner gets no sparks for hits it missed: hits are silent while an effect fast-forwards to its age.
- A visual that depends on the outcome (blocked, a critical hit) is something only the server knows: send a one-off
  cue from the server's listener.

---

## 19. Writing an effect of your own

An effect has two jobs: `tick` (logic, on a fixed 120 Hz clock) and `submit` (hand what to draw to the frame, by
slot). How it looks is its look's.

```java
public final class Bolt extends CgVfxEffect<Bolt> {
    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("mymod:bolt");
    public static final CgVfxParam.Scalar RADIUS = SCHEMA.length("radius", 0.4f);
    public static final CgVfxParam.Scalar LIFE = SCHEMA.time("life", 2f);
    public static final CgVfxParam.Color CORE = SCHEMA.color("core", 0xFFFFE9A0, 1.5f);
    public static final CgVfxSlot ORB = CgVfxSlot.mesh("mymod:bolt/orb");

    static final CgMaterialDefinition BODY = CgMaterialDefinition.of("mymod:materials/bolt_orb",
            "mymod:shaders/vfx/bolt_orb.shader").order(CgVfxLook.SURFACE).build();
    public static final CgVfxKit KIT = CgVfxKit.of("mymod:bolt")
            .slot(CgVfxSlotRenderer.light("light", ORB).intensity(4f).attenuationRadius(6f).bind("light_color", CORE).build())
            .slot(CgVfxSlotRenderer.of("orb", ORB).material(BODY).bind("_Core", CORE).build())
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
        frame.mesh(this, ORB, 0f, 1f, 0f, transform, r, r, 1f, 0f);   // every slot renderer on ORB
    }
}
```

- **Coordinates**: `originX/Y/Z` is where the effect is (moved by `follow`); everything it positions is relative to it.
- **`age`** is seconds since it started; **`seed`** a stable random number in 0..1 that shaders read too.
- **Die** with `die()` when done; `stop()` from outside lets it finish gracefully.
- **Read every number from its parameters**, never a constant in code: then a look, a tuning and a cue reach it.
- **Name the class in its declaration** (`extends CgVfxEffect<Bolt>`): every setter (`follow`, `startAt`, `set`,
  `onHit`, ...) then answers a `Bolt`, so `VFX.play(new Bolt(LOOK, x, y, z).follow(id, CHEST).charge(2f))` chains into
  its own methods and stays a `Bolt`. Code holding any effect writes `CgVfxEffect<?>`.

### What a frame draws

| Call | Draws |
|---|---|
| `frame.mesh(this, slot, x, y, z, transform, ex, ey, ez, ew)` | a sphere at a transform, or its slot renderers' own mesh ([§8](#8-meshes)); four numbers the slot documents |
| `frame.billboard(this, slot, x, y, z, size, ex, ey, ez, ew)` | a camera-facing quad |
| `int row = frame.path(path, seed, age)`, then `frame.tube(this, slot, path, row)` | a tube along a path |
| `frame.ribbons(...)`, `frame.pathRibbons(this, slot, path, row, intensity)` | ribbons a shader places |
| `fluid.submit(frame)` | a fluid, on its fluid slot ([§14](#14-gas)) |

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
```

Take the emitter from the look (`look.emitter(name)`), so a look that replaced it plays its own. One instance per
emitter per effect; `moveTo(x, y, z)` moves a source without restarting it, `rateScale(s)` scales its rate, `stop()`
ends an open-ended rate.

- **The system draws its particles**, through every renderer of its emitter, as long as the effect steps it and
  after its effect stops stepping it until its last particle dies. An effect of particles alone needs no `submit`.
- **Hide one** with `sparks.drawn(false)`: it still simulates, its events' children hidden with it.
- Its draws are made once, when it first draws, and kept until its slot closes: each frame writes only its values,
  and an emitter with no particle alive writes nothing.

### Built from elements

Reuse what the engine has: a charge, a blast, a beam's body. An element reaches its effect's values, shakes, post
volumes, hits and moments as code inside the effect would, and the effect calls it in the order it wants:

```java
public final class KiBlast extends CgVfxEffect<KiBlast> {
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
flash = openVolume(10, new CgPostSettings().flash(1.5f), hitX, hitY, hitZ)   // 10: over lower priorities it overlaps
        .radius(24f).blend(64f);                     // full within 24 blocks, fading to nothing 64 past that
flash.weight(curve(FLASH).at(t));                    // each tick, 0 to 1

playShake(SHOCK, hitX, hitY, hitZ, radius);          // once
holdShake(RUMBLE, 0f, 0f, 0f, radius, level);        // every tick while it lasts

moment("impact", hitX, hitY, hitZ, radius);          // a named point in its life, for capture tools
```

- **A screen-wide moment is a post volume**, never a draw round the camera. A volume also fades to a colour
  (`new CgPostSettings().fade(0xFF000000, 1f)`); a look that is its own shader is a `CgPostMaterial`, and a cut's
  dissolve is `CgPostStack.get().crossfade(seconds)` ([`ENGINE_API.md`](ENGINE_API.md) § *The post stack*).
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
  emitter or `quality(CgQuality.MEDIUM)` on a renderer.
- Drawing rules for shader authors (depth, glow, distortion, the impact frame) are `vfx/CLAUDE.md` § *Rules*.

---

## 20. Writing a material's shader

A VFX material's shader is an ordinary `.shader` material ([`SHADERS.md`](SHADERS.md)) with three additions:

```glsl
#type spatial
#pragma cg_use rows                                        // its numeric properties readable per draw
#pragma cg_feature FX_ON_TUBE                              // drawn on a tube slot too, beside a sphere
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"   // the draw header

Properties {
    _Color  ("Its colour; its alpha a strength", color) = (1, 1, 1, 1)      // bound by the renderer
    _Core   ("The effect's core colour", color) = (1, 1, 1, 1)               // bound too: bind("_Core", CORE)
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
  material's value or the draw's row, whichever the renderer chose. Nothing changes in how the shader reads it.
- **The header** (`fx_draw.glsl`, `CG_OBJECT_CUSTOM3`): `FX_ROW`, `FX_AGE` (the effect's age), `FX_SEED`,
  `FX_PRESENCE` (1 unless an ending fades it). The first three customs are the slot kind's (`CgVfxSlot.Kind`).
- **Name colours for what they are** (`_Core`, `_Shell`, `_Hot`); a renderer binds each to a parameter. Nothing binds
  by name.
- **One shader on several geometries**: declare `FX_ON_TUBE`, `FX_ON_MESH` and so on as features and branch on them.
  `banded.shader` (`shaders/vfx/toon/`) and Dragon Forge's `flat.shader` draw on a tube and a sphere.
- **Colour is authored in sRGB, above 1 allowed**, and a glowing material names its glow (`CG_GLOW`) and ends with a
  codeless Emissive pass. The glow, depth and distortion rules: `vfx/CLAUDE.md` § *Rules*.
- **Libraries** in `shaders/lib/vfx/`: `fx_tube` (the path, tube placement), `fx_volume` (tracing the view ray
  through a tube's rings), `fx_bands` (flat bands by distance from an axis), `fx_ribbon`, `fx_lightning`,
  `fx_particle`, `fx_billow`, `fx_ring`, `fx_haze`; each listed in `vfx/CLAUDE.md`.

### A volume's shader

A shader drawing a fluid of your own look declares the fluid's four volumes first, includes its libraries, and reads
what `CgVfxSlot.Kind.FLUID` documents:

```glsl
#include "crystalgraphics:shaders/lib/vfx/fx_sparse.glsl"   // fx_sparse_slot_of, fx_sparse_pool_uvw
#include "crystalgraphics:shaders/lib/vfx/fx_wavelet.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_fluid.glsl"    // FX_FLUID_STEP_CELLS, fx_fluid_detail

Properties {
    _FluidShade ("Smoke, temperature, sun, sky", sampler3D) = "black"   // these four first, in this order
    _FluidRestA ("Its first UV grid", sampler3D) = "black"
    _FluidRestB ("Its second", sampler3D) = "black"
    _FluidPages ("Its page table", sampler3D) = "black"
    ...
}
```

- **Blend `ONE ONE_MINUS_SRC_ALPHA`**, premultiplied: that is what the world renderer draws at half size and blends
  with its last frames.
- **Name the depth its light came from**, or its last frame cannot follow the camera; and start the march somewhere
  new each frame, so the frames average out its grain:

  ```glsl
  CG_VOLUME_DEPTH(transmit < 0.99 ? FX_EYE_DEPTH(ray, weightedDistance) : 0.0);   // what its last frame is reprojected by
  #ifdef CG_VOLUME_TARGET
      jitter = fract(jitter + 0.618034 * float(CG_FRAME_INDEX % 64));              // a new start each frame
  #endif
  ```

- **Hide it behind the scene** from `cg_DepthBuffer` with `DepthTest ALWAYS`: its half-size target has no depth.
- **What it hides from what draws over it** comes from the fluid's smoke through the world's media, not from
  this shader: naming `CG_VOLUME_DEPTH` keeps it from being dimmed by itself.
- `vfx/fluid/fluid.shader` is the whole of one, from the box's back faces through the page table and the colour map.

---

## 21. Writing a particle module of your own

A module is a Java record implementing `CgVfxGpuModule`, its inputs declared once (`CgVfxModuleTypes.declare`), plus
a GLSL function `fx_<kind>`, which the generated GPU step calls. Its inputs, in the order declared, are its numbers:
packed into `vec4 m0, m1, ...` (a scalar takes the next lane, a `vec3` or `vec4` a new vec4), laid out once per
emitter definition; nothing recompiles when they change.

```java
record Swirl(float strength, float height) implements CgVfxGpuModule {
    static {
        CgVfxModuleTypes.declare("mymod_swirl", Swirl.class,
                t -> t.property("strength", CgValueType.FLOAT).property("height", CgValueType.FLOAT),
                (m, in) -> in.f("strength", m.strength()).f("height", m.height()));
    }
    public String gpuKind() { return "mymod_swirl"; }
    public boolean afterSolve() { return false; }
    public String gpuSource() {                          // or a file: shaders/lib/vfx/sim/fx_mymod_swirl.glsl
        return """
                void fx_mymod_swirl(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) {
                    f.accel += cross(vec3(0.0, 1.0, 0.0), p.position) * m.x * step(p.position.y, m.y);
                }
                """;
    }
}
```

| Needs | Declares | Its function then takes |
|---|---|---|
| more numbers | more inputs in its declaration | `vec4 m0, m1, ...` once they pass four lanes |
| an input an effect drives | nothing: the emitter binds it by name, `CgVfxUserBinding.module(i, "strength", PARAM, scale, offset)` | the same argument, read from the instance each step |
| a value per instance each step (a moving centre) | `instanceLanes()`, `writeInstance` | one argument per lane, after the numbers |
| the world after the solver (floor, blocks, light) | `afterSolve()` true, `dataInterfaces()` | the world values, after the lanes; `FxWorld` for the voxel window |
| a texture (a field, a heightfield) | `textures()` | a `sampler2D` or `sampler3D` each, after the world |
| an impact that fires `onCollision` | after the solver; call `fx_hit(p, n, FX_HIT_BLOCK)` on a punctual impact | — |
| the entities round the camera | `dataInterfaces()` of `ENTITIES` | `FxEntities` (`fx_entities_at.glsl`) |
| the particles near it, as a force before the solver | `dataInterfaces()` of `NEIGHBOURS`, `neighbourRadius()` | `FxNeighbours` (`fx_neighbours_at.glsl`: 27 cells to walk) |
| the fluid its instance is in, before or after the solver | `dataInterfaces()` of `GAS` | `FxGas` (`fx_gas_at.glsl`: `fx_gas_flow`, its velocity and 1 inside its box) |
| to pour into that fluid, or push it | `dataInterfaces()` of `GAS_FEED` | `FxGas`, and `step_feed(gas, p.position, p.velocity, density, heat, couple, s.dt)` (`fx_gas_feed.glsl`) |

- `fx_types.glsl` defines `FxParticle`, `FxForces` and `FxStep`. Kill a particle with `p.life = p.age`.
- **A pool stores only what outlives a step** (`CgVfxRecord`, a layout per shape): `p.resting` and `p.collisions`
  are kept where some kind of the shape writes them or calls `fx_hit`, and read false and 0 elsewhere; `p.previous` is
  the step's start, set before your function runs, so reading it costs nothing.
- **Prefix the kind with your mod's name**: two kinds of one name with different source throw.
- **Declare before its first definition opens** (a static initialiser runs when the first module is made). An
  undeclared kind throws as its shape is made, naming it.
- Write one vec4 per lane you declared, or the pool throws naming the kind. Every variation has an example in
  `CgVfxGpuModule`'s javadoc.

---

## 22. Performance and scale

### On the client

- **Particles cost the GPU, not the CPU**: a few stores per emitter per tick, the simulation on the GPU. The
  `vfx-blasts` harness scene runs 360,000 particles.
- **Each shape is a pool with a fixed cost**: about 15.5 µs of GPU and 36 µs of render thread a frame while it plays, on
  an RTX 4070 SUPER under Vulkan. Emitters differing only in how their inputs are given share one once coalesced:
  [`vfx/performance.md`](vfx/performance.md).
- **An effect's logic** runs on the CPU at 120 Hz, and its draws are built every frame. A bound value costs a vec4 in
  the effect's row, written once a frame per draw; a constant costs nothing per draw.
- **A slot costs only the slot renderers on it**: a look without a kit draws none of it.
- What tends to cost: overdraw from large soft materials (one banded draw where four stacked bands were four), many
  separate particle materials (each its own batch), and shaders compiling on first use (`prepare(look)`).

### Scalability and the CPU's budget

What is too far, too many or out of view, and what every effect may cost the CPU before the scalabilities scale down:
[`vfx/performance.md`](vfx/performance.md) § *Scalability* and § *The CPU's budget*.

### Budgets on the GPU

| What | Budget | Over it |
|---|---|---|
| Fluid (`CgVfxFluidAtlas.GPU_BUDGET`) | 2 ms a frame | fewer pressure iterations |

It is measured on the GPU a frame or two late and eased, so a heavy moment loses detail gradually. It lowers a
solver's quality, never what appears.

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

## 23. Questions people ask

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
wave.onHit(CgEnergyWave.PART_BURST, h -> CgCollisionWorld.of(level).overlap(CgSphere.of(wave.behaviour().blastRadius()))
        .at(h.x(), h.y(), h.z()).exposure().forEach(e -> damage(e.entity(), 20f * e.falloff(2, wave.behaviour().blastRadius()))));
```

### Where do I put a number: a parameter, a material constant, or a constant in code?

| It is | Put it |
|---|---|
| read by the effect's logic or by more than one material, or something a game or look changes | a **parameter** on the schema of the effect or element that reads it |
| one material's look, and only that material reads it | a material **constant** (`value`): saved in its asset |
| a size a draw should follow as the effect grows or fades | a **binding** to the length (`bind("_Radius", RADIUS)`) |
| how the code works, never a look's choice | a constant in code |

### Why did my colour not bind?

Nothing binds by name: a property reads a parameter only where a renderer binds it (`bind("_Color", SHELL)`), and a
bind of a parameter the look does not declare fails at load, naming those it has. `look.describe()` lists every draw's
binds.

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

## 24. Checking and measuring

Harness gates (`./gradlew :gl-debug-harness:runHarness --args="--mode=<gate>"`, from CrystalGUI's root):

| Gate | Holds |
|---|---|
| `vfx-hashes` | the spawn hash, `fx_rand`, bit for bit against the Java the books use |
| `vfx-events` | events: every row heard once, every child where expected |
| `vfx-sequence` | clips on particles: each part where its node is, a node's event, children and grandchildren from the clip |
| `vfx-collide` | collisions and rates off a ground and a wall |
| `vfx-inputs` | vector fields, heightfields, the depth pyramid |
| `vfx-world` | the voxel window, its distance field and per-particle light |
| `vfx-range` | drawing by slot, and size over speed |
| `vfx-forms` | module inputs and spawn values in forms: each form on the GPU against its Java evaluation |

Each must pass at every forced tier (`-Dcrystalgraphics.compute.tier=G43|G40|G33`) and on `--device=vulkan`. Scenes to
look at: `vfx-spheres` (the waves' lanes, `crystalgraphics.harness.vfx.moments` for a contact sheet of every moment),
`vfx-showcase`, `vfx-modules` (a station per feature, below), `vfx-fluid` (gas and smoke: the ported engines' scenes,
a smoke grenade, particles pushing fluids, grids, a mushroom cloud), `vfx-blasts` (360,000 particles), `vfx-lab` (CrystalGUI's
inspector, every look's values grouped by schema).

In game and in the harness, `vfx-modules` is a station per feature, replayed every few seconds:

| Row | Its stations |
|---|---|
| Modules | one per module: gravity to kill volumes, collisions against the world, the screen and entities |
| Spawning | one per spawn shape; a source moving 30 blocks a second; a wake by distance; procedural meshes on mesh slots with sparks born on them; rocks as meshes; an aura round a mannequin (`entity()`) |
| Trails | Cascade's trails behind moving things (a fighter's wake and limb streaks, ki blasts with smoke trails, cel streaks, lightning, a meteor's fire and smoke) and its anim trails (blade swooshes, scorches torn along the ground) |
| Particle strips | a Cascade trail behind every particle (sparks, embers, fireworks); Niagara's ribbons through particles |
| Flocking | spirits round a heart, a school round a vortex, a murmuration of embers |

Unit tests (`./gradlew :core:test --tests "<class>"`): `CgVfxLookTest`, `CgVfxEndingTest`,
`CgPropertyRowsTest`, `CgVfxCueTest`, `CgVfxAuthorityTest`, `CgVfxHitsTest`, `CgVfxOwnerTest`, `CgVfxStartAtTest`,
`CgVfxSmokeFillTest`, `CgVfxSortGroupTest`.
Every shipped shader, each keyword variant, links under shaderc in `:runtime:lwjgl:vulkan:test --tests
com.crystalgraphics.vulkan.EngineOnTrackedBackendTest`.

Profiling ([`PROFILING.md`](PROFILING.md)), trace channel `crystalgraphics.vfx`: zones `vfx.tick`,
`vfx.emitters.workers`, `vfx.effect.tick/<namespace>`, `vfx.effect.submit/<namespace>`, and on the GPU
`vfx.pool.step`, `vfx.pool.range`, `vfx.fluid`, and the world renderer's `world.volume` and `world.volumeResolve`;
counters `vfx.gpu.slots-stepped`, `vfx.draws.particle-gpu`, `vfx.effects/<namespace>`, `vfx.fluid.tiles`,
`vfx.fluid.pool-full` and `world.volumeDraws`.
