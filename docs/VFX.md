# VFX — the guide

The VFX engine (`com.crystalgraphics.vfx`) plays visual effects in a world: beams, explosions, auras, hit sparks,
dust. It simulates their particles on the GPU, draws them through the engine's world renderer, sends them over the
network, and can run an effect on the server when gameplay depends on it.

This page is written for someone who knows none of that yet. Read sections 1 to 3 first: they give you the words and
the picture that everything else uses. After that, jump to what you need.

## Contents

1. [The words you need](#1-the-words-you-need)
2. [Quick start](#2-quick-start)
3. [Client, server, or both](#3-client-server-or-both)
4. [Playing effects on the client](#4-playing-effects-on-the-client)
5. [Looks and parameters](#5-looks-and-parameters)
6. [Effects over the network: cues](#6-effects-over-the-network-cues)
7. [Effects gameplay depends on: server authority](#7-effects-gameplay-depends-on-server-authority)
8. [Collisions: what an effect strikes](#8-collisions-what-an-effect-strikes)
9. [Particles](#9-particles)
10. [Writing an effect of your own](#10-writing-an-effect-of-your-own)
11. [Writing a particle module of your own](#11-writing-a-particle-module-of-your-own)
12. [Performance and scale](#12-performance-and-scale)
13. [Questions people ask](#13-questions-people-ask)
14. [Checking and measuring](#14-checking-and-measuring)

Where each class lives is `vfx/CLAUDE.md`, which loads itself for anyone editing a VFX file.

---

## 1. The words you need

| Word | What it is | Class |
|---|---|---|
| **Effect** | One thing playing in the world: one Kamehameha, one explosion. It has a position, an age, and code that runs every tick. It dies when it is done. | `CgVfxEffect` (and subclasses: `CgEnergyWave`, `CgVfxBurst`, ...) |
| **Look** | How an effect looks and behaves: its numbers and colours, its particle emitters and the layers that draw it. Shared, never changed by playing. One effect class can play many looks: the Kamehameha, Final Flash and Galick Gun are one class with three looks. | `CgVfxLook` |
| **Parameter** | One named value a look carries: a number (`radius`), a colour (`core`), a curve over time, or a camera shake. Declared on a **schema**. | `CgVfxParam`, `CgVfxSchema` |
| **Emitter** | One kind of particle: how many spawn and when, how each starts, what moves it, and its size and fade over life. A look holds its emitters. | `CgVfxEmitter` |
| **Module** | One piece of physics on a particle: gravity, drag, wind, a collision with blocks. An emitter stacks modules. | `CgVfxModule` |
| **Layer** | One draw of a look: a shader and the parameters it reads. A layer drawing particles names the emitter it draws (its **slot**). | `CgVfxLayer` |
| **Owner** | Your mod's handle on the VFX engine. You play, stop and clear your effects through it, and they are timed under your mod's name. | `CgVfxOwner`, from `CgVfx.owner("mymod")` |
| **System** | What updates and draws effects. The engine owns the one for the client's world; you rarely touch it. | `CgVfxSystem` |
| **Cue** | An effect named for the network, so the server can tell clients to play it. Each play is a networked object (`net.object`), so the network layer decides who sees it and sends its changes. | `CgVfxCue` |
| **Authority** | The server running an effect itself, because gameplay depends on what it does (what a beam hits). | `CgVfxCue.Builder.authoritative()`, `CgVfxAuthority` |
| **Attachment point** | A named point on an entity an effect can ride: feet, chest, hands, or one your mod declares. | `CgEntityAttachments` |

How they fit together:

```
CgVfxLook ─ schema: radius, core colour, ...      (the numbers an artist tunes)
          ─ emitters: sparks, smoke, ...          (each with modules: gravity, drag, collide)
          ─ layers: the shaders that draw it      (some draw an emitter's particles)

CgVfxEffect = a look + a position + code that runs each tick       → played through your owner
CgVfxCue    = a name for an effect + what builds it on each side   → sent by the server, built by every client
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
wave.stop();                     // done: the beam fades, what is in flight plays out
```

The engine also registers its effects by id, so you can play one without knowing its class:

```java
VFX.play("crystalgraphics:explosion", x, y, z, x, y, z);   // at a point, aimed at a target
```

Register your own the same way, and the spawn command and tools can play it too:

```java
VFX.register("hit_spark", "Hit spark", "Impacts", HitSpark.LOOK,
        (look, x, y, z, tx, ty, tz) -> new HitSpark(look, x, y, z));   // as "mymod:hit_spark"
```

### Make an effect that is only particles

A look with emitters and layers needs no effect class at all: `CgVfxBurst` starts every emitter once and dies when
they finish.

```java
VFX.play(new CgVfxBurst(PUFF_LOOK, x, y, z));
```

Building `PUFF_LOOK` is [§9](#9-particles).

### Show it to every player

Declare a cue once, in code both sides run, and send it from the server:

```java
// common, at init
public static final CgVfxCue PUFF = VFX.cue("puff", PUFF_LOOK,
        (look, cue) -> new CgVfxBurst(look, cue.x(), cue.y(), cue.z())).build();

// server, whenever
PUFF.at(level, x, y, z).send();
```

That is [§6](#6-effects-over-the-network-cues). If gameplay depends on the effect (a beam that damages what it hits),
the server runs it as well: [§7](#7-effects-gameplay-depends-on-server-authority).

---

## 3. Client, server, or both

**The client draws; the server decides.** This is the one idea to hold on to.

| What | Client | Server |
|---|---|---|
| Drawing an effect: its meshes, layers, glow | yes | never |
| Particles (simulated on the GPU) | yes | never |
| Post effects: flashes, colour shifts, impact frames | yes | never |
| Camera shakes | yes | never |
| An effect's logic: where a beam goes, when it fires, when it bursts | yes, for drawing | only for an **authoritative** cue |
| What a beam hits, for gameplay | no: it would differ per client | yes, with an authoritative cue |
| Sending an effect to players | no (a client can only *predict*, [§6](#predicting-from-a-client)) | yes, with a cue |

- **On a client**, effects play into the client world's system, which the engine updates every frame, draws, and
  clears when the world goes (logout, disconnect, a dimension change).
- **On a server**, nothing plays unless a cue is declared `authoritative()`. Then the server runs *the same effect
  class* with everything visual switched off ([§7](#7-effects-gameplay-depends-on-server-authority)).
- **In singleplayer**, Minecraft runs a server inside the client. An authoritative effect then exists twice: the
  server's copy decides, the client's copy draws. That is expected.
- **Particles and post effects never touch the server**, and never need to: each client derives them from the
  effect's state and its seed ([§13](#do-particles-and-post-effects-need-the-server)).

### Threads

| Call | Thread |
|---|---|
| `VFX.play(...)` | any: it starts at the next world frame |
| Anything on a playing effect (`aim`, `fire`, `stop`, `set`) | the render thread on a client; the server thread for a server's effect |
| A cue's `send()`, `start()`, and every `Playing` method | the server thread |
| A cue's `play()` and `predict()` | the client thread |
| Collision listeners | called on the render thread (client) or the server thread (an authoritative effect) |

---

## 4. Playing effects on the client

### Your owner

```java
public static final CgVfxOwner VFX = CgVfx.owner("mymod");

VFX.play(effect);       // returns the effect, so you can keep driving it
VFX.stop();             // every effect of this mod, gracefully: nothing new spawns, what is in flight plays out
VFX.clear();            // every effect of this mod at once, particles and all
VFX.enabled(false);     // a dev switch: ends them, and play() does nothing until enabled again
VFX.playing();          // how many of this mod's effects are alive
```

- **An effect plays once.** Playing the same object again, or into a second system, throws. Make a new one.
- **An effect that throws is killed and logged under your mod**; every other effect keeps playing.
- Your effects' time is traced as `vfx.effect.tick/mymod` and `vfx.effect.submit/mymod`, and counted as
  `vfx.effects/mymod` ([§14](#14-checking-and-measuring)).
- On a dedicated server, or a client with no world yet, `play` plays nothing.

### Ending an effect

| Call | What happens |
|---|---|
| `effect.stop()` | Graceful: emission ends, particles in flight live out their life, then it dies |
| `effect.kill()` | At once: the effect and its particles are gone by the next particle step |

### Following an entity

```java
VFX.play(new Aura(Aura.LOOK, x, y, z).follow(entityId, CgEntityAttachments.CENTRE));
VFX.play(new HitSpark(look, hitX, hitY, hitZ).followKeepingOffset(targetId, CgEntityAttachments.CHEST));
```

- `follow` moves the effect to the attachment point every frame; when the entity goes, the effect stops.
  `followKeepingOffset` keeps it where it was played, riding the point: a spark at the contact, not at the chest.
- **Built-in points**: `FEET`, `CENTRE`, `CHEST`, `HEAD`, `EYES`, `RIGHT_HAND`, `LEFT_HAND`, `RIGHT_HAND_FORWARD`,
  `LEFT_HAND_FORWARD`, `HANDS_FORWARD`. Their names are `crystalgraphics:feet` and so on.
- **What follows and what stays**:
  - **Particles** stay where they were spawned. An emitter marked `.local()` rides the effect instead, as an aura's
    sparkles orbit the player.
  - **What the effect draws itself** moves with it. An effect that throws parts into the world (a beam) overrides
    `followed(x, y, z)` to move only its source: the Kamehameha's muzzle rides the hands, its beam and blast stay put.
- **A point of your own, on any model** (playerAnimator, Emotecraft, GeckoLib):

  ```java
  public static final int PALM = CgEntityAttachments.declare("mymod:right_palm", CgEntityAttachments.RIGHT_HAND);

  // where the model is drawn, once a frame:
  CgEntityAttachments.mark(entityId, PALM, poseStack.last().pose(), x, y, z);   // a vanilla model's render layer
  CgEntityAttachments.mark(entityId, PALM, bone.getWorldPosition());            // GeckoLib
  ```

  A mark wins over a registered `Provider`, which wins over the fallback's proportions (`RIGHT_HAND` above). Marking
  a built-in point moves every effect already following it.

### Starting part-way through

```java
VFX.play(new Storm(Storm.LOOK, x, y, z).startAt(12f));   // as if it had been playing for 12 seconds
```

Call `startAt` before its first tick. The effect's own ticks run exactly up to that age (up to
`CgVfxSystem.MAX_START_SECONDS`; past it, its age jumps). Its particles are prewarmed over the last two seconds.
Shakes and moments stay silent until it reaches its age. This is how a player joining late sees a lasting cue.

### Other controls

- **`VFX.prepare(look)`** builds a look's shaders, meshes and kernels before it first plays, spread over frames within
  `crystalgraphics.warmBudget`; `VFX.warmed()` says when all of them are ready. A look played cold compiles on its first frame and can hitch.
- **`effect.ground(y)`** gives particles a floor where the host has no world (the debug harness). In Minecraft they
  land on the world's surfaces by themselves.
- **`system.timeScale(s)`** slows or speeds every effect in a system (1 by default).
- **The player's settings apply by themselves**: particle density, quality tier, pause, tick rate. An effect never
  reads them.

### A system of your own

A preview viewport, a test or a harness scene can keep its own system and drive it:

```java
CgVfxSystem vfx = new CgVfxSystem();
CgVfxOwner lab = CgVfx.owner("mymod", vfx);
lab.play(effect);
// every frame, before the world stages record (a CgWorldRenderer.onFrame listener):
vfx.update(seconds);
vfx.submit(CgWorldRenderer.get());
```

---

## 5. Looks and parameters

A look's parameters are the dials an artist turns: a beam's radius, its colours, how long it charges. Effects read
them every tick, so changing one changes the effect.

### A variant of a look

```java
public static final CgVfxLook THIN_KAMEHAMEHA = CgEnergyWave.kamehameha().toBuilder()
        .set(CgEnergyWave.RADIUS, 0.3f)
        .set(CgEnergyWave.STEER, 3f)
        .build();
```

A look never changes once built; a variant is a new look. The wave scales its blast and wake with `RADIUS`, so a
thinner wave bursts smaller.

### Changing one effect while it plays

```java
wave.set(CgEnergyWave.RADIUS, 1.2f);            // a number
wave.set(MyEffect.CORE, 1f, 0.4f, 0.1f, 1f);    // a colour: r, g, b, a
float r = wave.get(CgEnergyWave.RADIUS);
```

This changes that one effect, not the look. On a server's authoritative effect it also reaches every player
([§7](#syncing-parameters)).

### Declaring parameters for your own effect

```java
static final CgVfxSchema SCHEMA = new CgVfxSchema();
public static final CgVfxParam RADIUS = SCHEMA.scalar("radius", 0.6f);
public static final CgVfxParam CORE = SCHEMA.color("core", 1f, 1f, 1f, 1f);
public static final CgVfxParam GLOW = SCHEMA.curve("glow", CgKeyframes.start(0f, 0f).to(0.2f, 1f).to(1f, 0f).build());
public static final CgVfxParam SHOCK = SCHEMA.shake("shock", CgCameraShakes.EXPLOSION);
```

Declare the parameters before building the look: the builder takes the schema's defaults.

- **The name matters**: a cue carries each number and colour as a field named after it
  ([§6](#look-parameters-and-data-of-its-own)), so renaming one is a network change.
- **A camera shake is a parameter** too, so a look can change it. Never hard-code shake numbers in an effect.

### Tuning live

A tool can show a look's numbers and colours as console variables while it holds a tuning open. A change reaches the
look, every effect playing it at its next tick, and every effect played after:

```java
CgVfxTuning tuning = CgVfxTuning.open("vfx.crystalgraphics:kamehameha", CgEnergyWave.kamehameha());
CgVars.get("vfx.crystalgraphics:kamehameha.radius").setText(CgVar.Source.CONSOLE, "0.9");
CgVars.get("vfx.crystalgraphics:kamehameha.core.g").setText(CgVar.Source.CONSOLE, "0.8");   // colours: r, g, b, a
tuning.close();   // the variables go; the look keeps what was set for the run
```

- Curves and shakes are not variables. A tuned value wins over one an effect set for itself.
- One tuning per prefix at a time; clearing a variable restores the value it was opened with.

---

## 6. Effects over the network: cues

On a server you cannot call `VFX.play`: nothing draws there. Instead you send a **cue**, and every client that should
see it builds and plays the effect. The model is Unreal's gameplay cues.

Each play is a networked object (`net.object`, [`NETWORKING.md`](NETWORKING.md) § 6): the network layer
decides who sees it, sends what changed once a tick, and starts a player who comes into view at its age. You rarely
touch that layer; `Playing.object()` reaches it when you need it.

### Which kind do I want?

| You want | Use | Late joiners see it? |
|---|---|---|
| A one-off: a hit spark, a puff, an explosion | `cue.at(...).send()` | no: it is gone once played |
| Something that lasts: an aura, a beam, a burning crater | `cue.at(...).start()`, which returns a `Playing` | yes, started at its current age |
| A client showing its own action at once, without waiting for the server | `cue.at(...).predict()` on the client | the server decides |
| An effect gameplay depends on (a beam that damages what it hits) | an `authoritative()` cue, `start()`ed | yes |

### Declaring a cue

Declare it **once, as a constant, in code both sides run** (common code), at init, before any player joins:

```java
public static final CgVfxCue HIT = VFX.cue("hit_spark", HitSpark.LOOK,
        (look, cue) -> new HitSpark(look, cue.x(), cue.y(), cue.z())).build();
```

The factory gets the look and the `Play`: what the server sent (`x()`, `dx()`, `tx()`, `seed()`, its fields).
Before `build()`, the declaration takes:

| Call | Does |
|---|---|
| `.range(96)` | How far round its point a play reaches when it follows nothing; 64 blocks unless said |
| `.fields(LEVEL, GUARDED)` | Data of its own, beyond its look ([below](#look-parameters-and-data-of-its-own)) |
| `.authoritative()` | The server runs it too ([§7](#7-effects-gameplay-depends-on-server-authority)) |
| `.persisted()` | Lasting plays are saved with the world and resume when the server starts again. Not with `follow`, not with `authoritative()` |

- **Finish with `build()`**: a cue is a network type, fixed once built.
- **When what it plays uses client-only classes**, declare the cue alone in common and say what it plays on the
  client:

  ```java
  public static final CgVfxCue HIT = VFX.cue("hit_spark").fields(LEVEL).build();                     // common
  VFX.register(HIT, HitSpark.LOOK, (look, cue) -> new HitSpark(look, cue.x(), cue.y(), cue.z()));   // client init
  ```

  Declared without its look, a cue carries none of the look's parameters: send what varies as fields of its own.
- **A cue a client has nothing registered for** is dropped on that client, with one warning per id.

### Sending a one-off

```java
HIT.at(level, x, y, z).send();
HIT.at(level, x, y, z).toward(dx, dy, dz).follow(target, MyMod.BODY).keepOffset().set(HitSpark.SIZE, 2f).send();
```

- `level` is a level, an entity in it, or a dimension id.
- **Who receives it**: the players tracking what it follows; otherwise everyone within the cue's range.
- **It goes with the tick's other changes**, at the end of the server tick it was sent in.
- **A player who predicted it** ([below](#predicting-from-a-client)) is not sent it again.

### Starting a lasting one

```java
CgVfxCue.Playing aura = AURA.on(player, CgEntityAttachments.CENTRE).start();
aura.set(Aura.INTENSITY, 3f);   // a look number: every viewer, at the end of this tick
aura.stop();                    // gracefully on every viewer; kill() for at once
```

- **`on(entity, point)`** is the short way to say "at this entity, following it at this point, in its level".
- **A player who comes into view later** starts it at its current age.
- **It ends** by `stop()`, `kill()`, the play's `.lifetime(seconds)`, or the entity it follows dying.

### Changing a lasting one

| Call | Viewers get |
|---|---|
| `toward(dx, dy, dz)` | a new direction |
| `target(x, y, z)` | a new target point |
| `set(PARAM, v)`, `set(PARAM, r, g, b, a)` | a number or colour of its look |
| `set(FIELD, v)` | one of its own fields |
| `moveTo(x, y, z)` | a new position, and who is in range is worked out again |

A field is sent only when its value changed, and one tick's changes go together at its end. On each viewer, look
numbers and colours are set onto its effect as they arrive; then an effect implementing `CgVfxCue.Live` takes the rest,
once a frame (the wave takes its aim and target).

### Look parameters and data of its own

Every number and colour of a cue's look is a field of the cue, so `set(Blast.RADIUS, 6f)` on a play reaches each
viewer's effect with no code in the factory. Curves and camera shakes do not travel.

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
  (`SCHEMA.networked(...)`, [§7](#making-your-own-effect-authoritative)).

### Every client draws the same thing

- **The seed travels with the cue**, so every client's random choices match.
- **An effect following an entity** finds the attachment point from the entity as that client draws it, and jumps
  there; `.keepOffset()` keeps it where it was played, riding the point: a hit spark at the contact.
- Particles still collide with each client's own world, so tiny differences are possible and harmless
  ([§13](#do-particles-and-post-effects-need-the-server)).

### Predicting from a client

A client can show its own action at once, and ask the server to show everyone else:

```java
HIT.at(x, y, z).set(HitSpark.SIZE, 2f).predict();                    // client: plays here now
HIT.fromClients((player, play) -> withinReach(player, play));        // server, at init: who may
```

- The server refuses unless the cue allows it (`fromClients`), the player is within the rate (20 a second, in bursts
  of 20) and the point is within `range` of them. `refused()` counts refusals.
- **Accepted**, the server's play takes over the client's own, so the predicting player sees it once.
  **Refused**, the client's copy plays out alone.
- Only one-offs are predicted, so an authoritative cue never is.

### Cues are for showing, not for state

A plain cue is cosmetic. Anything a player must not miss (damage, health, a status) is the game's to replicate; the
cue only shows it. If the effect itself decides gameplay, make the cue authoritative (next section).

---

## 7. Effects gameplay depends on: server authority

Some effects *are* the gameplay: a beam damages whatever it reaches. If each client decided what the beam hit, every
client would see a different result. So the server runs the effect too, as the referee, and every client draws what
the server decided.

The server runs **the same effect class** the clients play, headless against the server's world (`CgVfxAuthority`):

- it decides where the effect goes and what it strikes;
- the server hears its collisions, so you apply damage there;
- it publishes what clients must draw, and each client follows it.

Unreal and Unity Netcode run one actor on both sides the same way, with its cosmetic parts off on the server.

### The whole thing, with the Kamehameha

```java
// common, at init: one declaration, one factory for both sides
public static final CgVfxCue WAVE = VFX.cue("kamehameha", CgEnergyWave.kamehameha(),
        CgEnergyWave::fromCue).range(96).authoritative().build();

// server: start it, then drive its effect exactly as you would on a client
CgVfxCue.Playing wave = WAVE.on(caster, CgEntityAttachments.HANDS_FORWARD).start();
CgEnergyWave beam = wave.effect();
beam.hold().aim(look.x, look.y, look.z);   // key down: before its first tick

// what it hits, decided here: each part its own listener
wave.onCollide(CgEnergyWave.PART_BEAM, c -> { if (c.hit() == CgVfxCollision.Hit.ENTITY) stagger(c.entity()); })
    .onCollide(CgEnergyWave.PART_BLAST, c -> explode(c.x(), c.y(), c.z(), c.radius()));

beam.aim(look.x, look.y, look.z);          // each tick, as the caster turns
beam.set(CgEnergyWave.RADIUS, 1.2f);       // powered up: every viewer's beam widens, and the server's ray with it
beam.fire();                               // key up
beam.stop();                               // every viewer's beam fades; the blast is still heard as it bursts
```

```java
// client, at init: the hits the server counted, for feedback that must agree with the damage
WAVE.onConfirmedCollide(CgEnergyWave.PART_BLAST, c -> decals.scorch(c.x(), c.y(), c.z(), c.radius()));
```

### What happens each server tick

1. The server steps its copy of the effect on the same clock clients use (120 ticks a second, six per server tick).
2. The wave tests its aim against the server's world and finds where the beam ends and what entity is there.
3. Everything that changed is written into the cue's fields and sent to every viewer at the end of the tick: where
   the beam ends, the entity it hit, the aim, hold and fire, and any number or colour set on the effect. What it
   struck goes too, as a confirmed collision.
4. Each viewer's copy homes on that end point rather than testing its own world, so every player sees the beam end
   where the server says. A viewer's wave reports the hit as an `ENTITY` with the server's id.

### Driving it

- **`wave.effect()`** is the server's copy. Call it exactly as on a client: `aim`, `fire`, `set`, `stop`.
- **Stopping the effect stops every viewer's copy.** The cue lasts until the server's copy has played out, so what it
  strikes meanwhile (the blast) still reaches viewers. `wave.stop()` does the same from the `Playing` side.
- `wave.toward(...)` and `wave.set(...)` still work and reach the server's copy too.
- A play started during a tick is published before any viewer hears of it, so the first state viewers receive
  already includes what you set right after `start()` (the hold, the aim).

### Syncing parameters

**Any number or colour you `set` on the server's effect reaches every viewer**, and anyone who joins later:

```java
beam.set(CgEnergyWave.RADIUS, 1.2f);
beam.set(MyEffect.CORE, 1f, 0.2f, 0.2f, 1f);
```

This works for every authoritative effect, with no code of its own: each value set on it is written into the cue's
field for it, which sends only what changed. Curves and camera shakes do not travel.

### What is switched off on the server

| Part | On the server |
|---|---|
| Particles | not simulated: emitters do nothing |
| Post effects (flashes, impact frames) | not applied: the effect gets a detached volume, so its timing logic runs the same |
| Camera shakes, moments | silent |
| Collisions | heard only by the cue's own listeners (`wave.onCollide`), on the server thread, and sent to viewers as confirmed collisions; never heard by `CgVfx.onCollide` or the owner's |

An effect that ends when its particles finish therefore ends itself on the server.

### Limits and rules

- **Lasting cues only** (`start()`). A one-off `send()` never runs on the server.
- **Never `persisted()`**: `build()` refuses it.
- **`at(level, ...)` must be a level or an entity in one**: that is the world it tests. Given something else, it warns
  once and strikes nothing.
- **The cue must be declared with its factory in common code**: `build()` refuses an authoritative cue without one.
- **It follows its entity by the attachment point's proportions**: a server has no model, so marked points
  ([§4](#following-an-entity)) are a client-only refinement.
- **It plays out for at most 10 seconds** after it is stopped, so a blast is still heard, then it is killed.
- What it costs the server: [§12](#on-the-server).

### Making your own effect authoritative

Most effects need nothing: declare the cue `authoritative()` and parameters sync by themselves. An effect whose own
state must reach viewers (like the wave's end point) adds two small interfaces:

```java
public final class Bolt extends CgVfxEffect implements CgVfxCue.Live, CgVfxCue.Authoritative {
    private final CgWorldQueries.Hit hit = new CgWorldQueries.Hit();
    private double endX = Double.NaN, endY, endZ;

    @Override protected void tick(float dt) {
        // raycastAll reads the server's world on the server and the client's world on a client
        if (raycastAll(originX, originY, originZ, dirX, dirY, dirZ, 64, 0.3, following(), hit)) {
            endX = hit.x; endY = hit.y; endZ = hit.z;
        }
    }

    // server, after each tick: write what viewers draw; a value written unchanged sends nothing
    @Override public void publish(CgVfxCue.Play cue) {
        if (!Double.isNaN(endX)) cue.target(endX, endY, endZ);
    }

    // every viewer, once a frame that changed the cue (and the server, for a change through Playing)
    @Override public void cueChanged(CgVfxCue.Play cue) {
        if (!authoritative() && cue.hasTarget()) endAt(cue.tx(), cue.ty(), cue.tz());
    }
}
```

State with no place in a play (the wave's hold, its release time) is fields its schema declares, which every cue
playing the effect then carries:

```java
public static final CgNetField<Boolean> CHARGING = CgNetField.ofBool("bolt/charging");
static { SCHEMA.networked(CHARGING); }

cue.setBool(CHARGING, charging);                 // in publish
if (cue.getBool(CHARGING)) ...                   // in cueChanged, and in the factory
```

- **`authoritative()`** on the effect is true for the server's copy. Guard anything client-only with it.
- **`cue.authoritative()`** in a factory is true while the server builds its copy: one factory can configure both
  sides (`CgEnergyWave.fromCue` turns on its own collision this way).
- **A factory reads `hasTarget()` at build time**: the first publish lands before viewers build their copy.
- Report hits with `collided(...)` ([§8](#8-collisions-what-an-effect-strikes)); on the server they reach
  `wave.onCollide`.

### When it never hits

- **Is the target in the beam?** A target summoned under a hovering caster falls out of it. Summon test targets with
  `{NoGravity:1b}`.
- **Was `at(...)` given a level or an entity?** If not, the log says the authority tests no world.
- **Trace it**: `-Dcrystalgraphics.vfx.traceAuthority` (or the console, `docs/DEBUG_FLAGS.md`) logs every server
  raycast: from where, along what, what it met, and every entity the level lists along the first 32 blocks.

---

## 8. Collisions: what an effect strikes

Everything an effect or its particles strike reaches one listener type, `CgVfxCollisionListener`, at whichever scope
you need:

```java
// One effect: a wave that finds its own target
CgEnergyWave wave = VFX.play(new CgEnergyWave(look, x, y, z).follow(playerId, HANDS_FORWARD).collide());
wave.onCollide(c -> { if (c.hit() == CgVfxCollision.Hit.ENTITY) flash(c.entity()); });

// Every effect of your mod, particles included: scorch marks wherever anything lands on a block
CgVfxCollision.Registration scorch = VFX.onCollide(c -> {
    if (c.hit() == CgVfxCollision.Hit.BLOCK) decals.scorch(c.x(), c.y(), c.z(), c.nx(), c.ny(), c.nz());
});
scorch.close();   // stop listening

// Every effect of every mod
CgVfx.onCollide(listener);
```

### Parts

An effect with more than one thing that strikes names each as a **part** (`CgVfxPart`): the Kamehameha's beam
contact is `CgEnergyWave.PART_BEAM`, its explosion `PART_BLAST`. Subscribe to a part and hear it alone:

```java
wave.onCollide(CgEnergyWave.PART_BEAM, c -> flash(c.entity()))
    .onCollide(CgEnergyWave.PART_BLAST, c -> shatter(c.x(), c.y(), c.z(), c.radius()));
```

A listener on the whole effect hears every part, and `c.part()` says which (compare with `==`).

Each `CgVfxCollision` says:

| Field | Meaning |
|---|---|
| `source()` | `EFFECT` (the effect's own test) or `PARTICLE` |
| `hit()` | `BLOCK`, `ENTITY`, `GROUND`, `SCENE`, `SHAPE`, `TARGET` (reached the point it was aimed at), `OTHER` |
| `entity()` | the entity's id, or -1 |
| `x() y() z()`, `nx() ny() nz()` | where, and the surface normal |
| `part()` | which part struck, for an effect with several (`CgEnergyWave.PART_BEAM`, `PART_BLAST`); null otherwise |
| `radius()` | how far an area hit reaches (the blast) |

- **Order**: the effect's listeners, then its owner's, then `CgVfx.onCollide`'s. The event is valid only during the
  call: copy what you keep.
- **An effect reports a contact as it starts**, and again when what it strikes changes, never every tick.
- **Particles report only from emitters that ask**, and a few frames late (they come back from the GPU), possibly
  after their effect died:

  ```java
  .module(new CgVfxModule.CollideWorld(0.3f, 0.4f, 0.4f))
  .module(new CgVfxModule.CollideEntities(0.3f, 0.4f, 0.4f, 0.5f))
  .reportCollisions(32)   // at most 32 rows a step, shared by every effect playing this emitter
  ```

  A landing an emitter reports (`onLanding().readback(n)`) is heard as `GROUND`.

- **Particle collisions are client-side only**: never base gameplay on them ([§13](#do-particles-and-post-effects-need-the-server)).
- **The Kamehameha** reports `PART_BEAM` when its head reaches what it hits, then `PART_BLAST` once as the explosion
  bursts, with the blast's radius. Damage the contact, the blast, or both.

### Damage on the server, sparks on every client

What the server's copy strikes reaches every viewer as a **confirmed collision**, the same event the server heard:

```java
// server
WAVE.on(caster, CgEntityAttachments.HANDS_FORWARD).start()
        .onCollide(CgEnergyWave.PART_BEAM, c -> { if (c.hit() == CgVfxCollision.Hit.ENTITY) damage(c.entity()); });

// client, at init
WAVE.onConfirmedCollide(CgEnergyWave.PART_BEAM, c -> VFX.play(new CgVfxBurst(SPARKS, c.x(), c.y(), c.z())));
```

A confirmed collision arrives a network trip after the server's. For feedback timed to what this client draws, listen
to the viewer's own copy instead: the factory builds both copies, and `cue.authoritative()` tells them apart.

```java
public static final CgVfxCue WAVE = VFX.cue("kamehameha", CgEnergyWave.kamehameha(), (look, cue) -> {
    CgEnergyWave wave = CgEnergyWave.fromCue(look, cue);
    if (!cue.authoritative()) wave.onCollide(CgEnergyWave.PART_BEAM, c -> flash(c.x(), c.y(), c.z()));   // each viewer
    return wave;
}).range(96).authoritative().build();
```

- **Both land where the damage does**: a viewer's copy reports where the server published the beam ending.
- **A late joiner gets no sparks for hits it missed**: collisions are silent while an effect fast-forwards to its age.
- **An effect of your own** can start the emitter itself when it reports the contact, with no listener at all.
- **A visual that depends on the outcome** (blocked, a critical hit, a kill) is something only the server knows: send
  a one-off cue from the server's listener instead, `HIT.at(level, c.x(), c.y(), c.z()).send()`.

---

## 9. Particles

Particles are simulated entirely on the GPU, so hundreds of thousands are affordable. You describe them as data (an
emitter), and the engine compiles that into GPU programs.

### An emitter

```java
static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("sparks").renderer(CgVfxEmitter.Renderer.QUADS)
        .capacity(1200)                        // the most alive at once
        .rate(120f, 0f, 4f)                    // 120 a second, from 0 s to 4 s
        .shape(1.2f)                           // born anywhere in a ball of radius 1.2
        .launch(-1f, -0.7f, 1f).speed(0.5f, 2f)
        .life(2.5f, 3f)                        // each lives 2.5 to 3 seconds
        .size(0.08f, 0.14f, 1f).heat(1f)
        .module(new CgVfxModule.Gravity(9.8f))
        .module(new CgVfxModule.CollideWorld(0.35f, 0.4f, 0.4f))   // bounces off blocks
        .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
        .layer("sparks")                       // the slot a layer draws
        .build();
```

### Putting it in a look

The look holds the emitter and a layer that draws its slot with a shader:

```java
static final CgVfxSchema SCHEMA = new CgVfxSchema();
static final CgVfxParam EMBER = SCHEMA.color("ember", 1.2f, 0.35f, 0.05f, 1f);
static final CgVfxParam FLAME = SCHEMA.color("flame", 1.4f, 1.1f, 0.5f, 1f);

static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).emitter(SPARKS)
        .layer(CgVfxLayer.builder("crystalgraphics:shaders/vfx/particle/spark.shader").slot("sparks")
                .colors(EMBER, FLAME).build())
        .build();

VFX.play(new CgVfxBurst(LOOK, x, y, z));   // particles alone need no effect class
```

**Shared particle shaders** in `shaders/vfx/particle/`, ready to use:

| Shader | Draws |
|---|---|
| `spark` | a glowing disc streaked along its motion |
| `speck` | ragged debris |
| `dust` | soft ground dust that pools into haze |
| `arc` | an ink stroke |
| `ray` | a hot streak along its velocity |
| `sprite`, `sprite_glow` | a frame of a flipbook sheet, lit or glowing |

Their parameters are listed in `vfx/CLAUDE.md`. Reusable kits (a whole explosion: billows, dust, debris, embers) live
in `vfx.element` and join a look with `.add(kit)`.

### Things to know

- **Capacity is a hard limit**: a spawn into a full emitter is dropped. Size it to the burst plus what is still alive.
- **Variants**: `SPARKS.toBuilder()...build()`. **The same burst bigger or smaller**: `SPARKS.scaled(s)` scales shape,
  speed, size and forces, keeping the timing, children too. A module scales only if its kind overrides `scaled`; so
  far the explosion kit's kinds do.
- **`.optional()`** marks detail: halved at the Low quality tier. The player's density setting thins every emitter.
- **`shape(inner, outer)`** spawns on a shell; **`sweep(speed)`** runs that shell outward, so particles are born on a
  moving front (dust lifted by a shock wave).
- **Each look draws as one batch**, so two looks layer as wholes, not particle by particle. A solid look's shader writes
  depth (`"Depth" = "Clip"`); soft smoke writes a depth prepass where it is solid (`"Depth" = "Prepass"`);
  only something soft throughout relies on blending (`docs/SHADERS.md` § *Depth from a transparent material*).

### Spawning particles without an effect

Today a particle always belongs to an effect: one-off particles are a `CgVfxBurst` each. That is fine for dozens. For
Minecraft-style spam (footstep dust, drips, a spark on every hit every tick) each burst is a whole effect with its own
GPU slot, which wastes work. A shared emit call (`VFX.emit(...)` into one running simulation, like Unity's
`ParticleSystem.Emit`) is planned as plan `vfx-gpu` step X15.

### Modules: what moves a particle

Modules run in order every step. Forces come first and are integrated together; a module after the solver
(collisions, kills) sees where the particle went.

| Module | Does |
|---|---|
| `Gravity`, `Force` | A constant pull down; a constant push in any direction |
| `Drag`, `Damping`, `LimitSpeed` | Linear and quadratic drag; velocity decaying by a rate; a speed cap |
| `Wind`, `Turbulence`, `Buoyancy`, `Updraft` | The system's gusting air; curl noise; hot particles rising as they cool; a column of rising air |
| `Attract`, `Orbit`, `Vortex`, `Conform` | Pull toward a `Volume`; circling an axis; a vortex's pull; settling onto a sphere's shell |
| `VectorField` | A 3D field (`CgVfxField`) in a box, setting velocity |
| `Spin` | Spin rate decaying |
| `Ground` | Landing on the effect's ground: bounce, friction, rest |
| `Current` | Dust carried along the ground by a blast's gravity current |
| `Collide` | A sphere, box, plane or container (`Volume`): bounce, friction, or kill on contact |
| `CollideWorld` | The world's blocks round the camera |
| `CollideDepth` | What is on screen (mobs and players), from the depth buffer |
| `CollideEntities` | The boxes of the 64 living entities nearest the camera; struck only on entering, so a particle born inside its caster flies out |
| `Kill` | Dies inside (or outside) a `Volume` |

```java
.module(new CgVfxModule.Attract(CgVfxModule.Volume.sphere(2f).at(0f, 3f, 0f), 12f, 1f, 0f, 0f, 1f, 0f))
.module(new CgVfxModule.Collide(CgVfxModule.Volume.sphere(1.8f).at(0f, -5.2f, 0f), 0.6f, 0.05f, 0.3f))
.module(new CgVfxModule.Kill(CgVfxModule.Volume.box(4f, 1f, 4f).at(0f, -2f, 0f), true))
```

### Events: particles spawning particles

An event fires per particle (landing, death, an age, each collision, every period) and spawns children of another
emitter where it fired, reports to the CPU, or both:

```java
.event(CgVfxEvent.onCollision().spawn(FLASH, 1))                 // a flash at each bounce
.event(CgVfxEvent.onCollision().spawn(CHIPS, 4).inherit(0.3f))   // four chips taking 30% of the parent's velocity
.event(CgVfxEvent.onLanding().readback(64))                      // each landing reported, a few frames later

vfx.onEvents((definition, event, rows) -> {
    for (int i = 0; i < rows.count(); i++) sounds.play(HISS, rows.x(i), rows.y(i), rows.z(i));
});
```

- **One level deep**: a child may report rows, never spawn children of its own.
- **Add every child to the look** (`.emitter(FLASH)` and a layer on its slot), or it simulates and draws nothing.
- At most `CgVfxEvent.MAX_EVENTS` events an emitter and `MAX_CHILDREN` children an event; a collision fires at most
  `COLLISION_FIRINGS` times per particle.
- Reports arrive a frame or two late: react to what arrived, never wait for it.

### Where particles run

Every tier the engine supports runs them: compute on modern GL and Vulkan, lowered to draws on older GL
(`docs/SHADERS.md` § *Every tier*). Particles step 60 times a second while effects tick 120 times, so a particle is
drawn at `frame.particleAlpha()`, never `frame.alpha()`.

A Java simulation (`-Dcrystalgraphics.vfx.sim=cpu`) remains only as the "before" of a comparison and will be deleted:
build nothing on it. It lacks depth collisions, the voxel world's fine shapes and per-particle light.

---

## 10. Writing an effect of your own

An effect is a class with two jobs: `tick` (logic, on a fixed 120 Hz clock) and `submit` (hand what to draw to the
frame).

```java
public final class Sparks extends CgVfxEffect {
    private final CgVfxEmitterInstance sparks;

    public Sparks(CgVfxLook look, double x, double y, double z) {
        super(look, x, y, z);
        sparks = new CgVfxEmitterInstance(SPARKS, seed);   // seed: this effect's own random number
        sparks.start(0f, 1f, 0f);                          // where it emits, relative to the effect
    }

    @Override protected void tick(float dt) {
        tick(sparks, dt);                                  // steps it on the GPU
        if (sparks.finished() && age > 1f) die();
    }

    @Override protected void submit(CgVfxFrame frame) {
        frame.particles(this, sparks);                     // drawn through every layer on its slot
    }
}
```

- **Call `tick(instance, dt)`, never `instance.tick(...)`**: only the effect's method schedules it on the GPU.
- **One instance per emitter per effect.** `moveTo(x, y, z)` moves a source without restarting it, `rateScale(s)`
  scales its rate, and `stop()` ends an open-ended rate.
- **Coordinates**: `originX/Y/Z` is where the effect is (moved by `follow`); everything an effect positions is
  relative to it.
- **`age`** is seconds since it started; **`seed`** is a stable random number in 0..1 that shaders read too.
- **Die** with `die()` when done; `stop()` from outside lets it finish gracefully.

### What a frame can draw

| Call | Draws |
|---|---|
| `frame.particles(this, instance)` | an emitter's particles, through every layer on its slot |
| `frame.billboard(this, layer, x, y, z, size, ...)` | a camera-facing quad |
| `frame.mesh(this, layer, x, y, z, transform, ...)` | a mesh at a transform |
| `frame.tube(this, path, row, layer)` | a tube along a path (a beam's body) |
| `frame.ribbons(...)`, `frame.pathRibbons(...)` | ribbons a shader places: lightning, streaks |

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

### Reporting what it hits

```java
collided(CgVfxCollision.Hit.BLOCK, -1, x, y, z, nx, ny, nz, vx, vy, vz);

// an effect with several parts that strike declares each once, and reports through it
public static final CgVfxPart PART_BOLT = CgVfxPart.of("bolt"), PART_BURST = CgVfxPart.of("burst");
collided(PART_BURST, CgVfxCollision.Hit.OTHER, -1, x, y, z, 0f, 1f, 0f, 0f, 0f, 0f, burstRadius);
```

Listeners then subscribe to a part ([§8](#parts)).

Test the world through `raycastAll(...)`, which works on both sides ([§7](#making-your-own-effect-authoritative)).

### Taking cue changes

If your effect is played by a lasting cue and must react to more than its look parameters (a new target, a
direction), implement `CgVfxCue.Live` ([§6](#changing-a-lasting-one)). To make it authoritative, see
[§7](#making-your-own-effect-authoritative).

### Rules that keep it fast and correct

- **Hot paths do not allocate**: `tick` and `submit` run every frame for every effect.
- **Never read player settings**: the system applies density, quality and pause for you. Mark detail with
  `.optional()` on an emitter or `.from(CgQuality.MEDIUM)` on a layer.
- **Announce your moments** as constants and call `moment(...)` as the effect crosses each, so it can be photographed
  at full speed (`vfx/CLAUDE.md` § *Seeing every moment*).
- Drawing rules for shader authors (depth, glow, distortion, the impact frame) are in `vfx/CLAUDE.md` § *Rules*.

---

## 11. Writing a particle module of your own

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
- **Return the arrays as constants**: they are asked every step.
- Write exactly the vec4s you declared, or the pool throws naming the kind.
- Every variation has an example in `CgVfxGpuModule`'s javadoc.

---

## 12. Performance and scale

### On the client

- **Particles cost the GPU, not the CPU**: a few stores per emitter per tick on the CPU, the simulation on the GPU.
  The `vfx-blasts` harness scene runs 360,000 particles.
- **An effect's own logic** (a beam's path, timers) runs on the CPU at 120 Hz, and its draws are built every frame.
- **A client pays only for effects it sees**: cues reach only players in range or tracking.
- What tends to cost: overdraw from large soft particles, many separate looks (each draws as its own batch), and
  shaders compiling on first use (`prepare(look)` ahead of time).

### On the server

Estimated from the code, not measured: profile before relying on these (`docs/PROFILING.md`). Per authoritative
Kamehameha, each server tick:

| Part | Cost |
|---|---|
| Six effect ticks at 120 Hz: the beam's centreline and timers, on the CPU as on a client | ~10-30 µs |
| One raycast: blocks out to its reach, about a dozen entity box queries along it | ~10-50 µs, depending on how crowded |
| Publishing: about 100 bytes to each viewer whenever the end or aim moves | ~0 CPU; network |

**About 50 µs a wave**: 100 waves take ~5 ms of a 50 ms server tick; 1,000 take all of it.

- It runs on the server thread, for every authoritative effect in every dimension, and cannot spread across threads:
  the world belongs to the server thread.
- A client spends more per wave (the same logic, plus drawing, emitters and the GPU) but only on the waves it sees, and
  its GPU runs out first at counts like that. Realistic play is dozens at once, where neither side notices.
- **Levers, cheapest first**, once a profile asks for them: step the server's copy at 20 Hz instead of 120; raycast
  only when the aim or the caster moved; replace the beam's path on the server with its arrival time worked out from
  speed and distance.

---

## 13. Questions people ask

### Does the server run my effect?

Only if its cue is declared `authoritative()` and started with `start()`. Then the server builds one copy per started
cue, with the same factory as clients, and steps it every server tick on the client's 120 Hz clock (six steps a tick).
It is never drawn; it decides and publishes ([§7](#what-happens-each-server-tick)).

### Do collision events run on the server?

Only an authoritative effect's own, and only for its own listeners. Each side hears its own copy's collisions, and
the server's travel to viewers as confirmed collisions.

| Collision | Server | Each client |
|---|---|---|
| An authoritative effect's (`PART_BEAM`, `PART_BLAST`) | yes: `playing.onCollide` / the effect's `onCollide`, on the server thread. **Apply gameplay here** | the server's, confirmed: `cue.onConfirmedCollide`. And its own copy's: the effect's, the owner's and `CgVfx.onCollide` listeners, on the render thread. Cosmetic: decals, sounds |
| A plain cue's, or a locally played effect's | never | yes, its own copy's; each client decides for itself |
| A particle's | never | yes, a few frames late, from the GPU |

- **A client's collisions never reach the server.** A viewer's copy reports where the server published the beam
  ending, so it agrees with the confirmed one: `ENTITY` with the server's entity id, or `TARGET` where it struck
  nothing.
- **`CgVfx.onCollide` and an owner's `onCollide` never hear the server's copy**, so a client-side listener
  (a scorch decal) never fires twice in singleplayer, where both copies run in one process.
- Damage on the server and an emitter on each client, for one cue: [§8](#damage-on-the-server-sparks-on-every-client).

### What shape does an effect hit with?

Whatever its own code tests: the engine has no hit volume of its own. Each effect calls the world queries it needs and
reports what they meet. The built-in ones:

| What | Shape | Against |
|---|---|---|
| The wave's beam (`PART_BEAM`) | a ray from the muzzle along the aim, out to `MAX_LENGTH`, `RADIUS` thick (`raycastAll`) | blocks along its centre line; each entity's box grown by `RADIUS` on every side. The first thing met stops it: it does not pierce. The caster is skipped |
| The wave's blast (`PART_BLAST`) | no test: one event at the impact point carrying a radius (`RADIUS` × `BLAST_RADIUS`) | nothing: your listener decides who is inside, as a game's radial damage does |
| Shock fronts, rings, dust | none: drawn only | — |
| Particles (`CollideEntities`) | each particle a point | the boxes of the 64 living entities nearest the camera; client only, cosmetic |

- The beam is reported when its head *reaches* the point, so the hit lands as the beam visibly arrives.
- On the server the beam tests once a server tick, against entity boxes at that tick's positions.
- **An area hit is your listener's**: query the entities within `c.radius()` of `c.x(), c.y(), c.z()`, then apply
  falloff and line of sight as your game needs:

  ```java
  wave.onCollide(CgEnergyWave.PART_BLAST, c -> damageWithin(c.x(), c.y(), c.z(), c.radius()));   // your game's
  ```

### If I change a parameter on the server's effect, do all clients see it?

Yes. `beam.set(PARAM, value)` on an authoritative effect reaches every viewer at the end of the tick, and anyone who
joins later. So does `playing.set(PARAM, value)`. Curves and camera shakes do not travel ([§7](#syncing-parameters)).

### How many effects can a server run?

Roughly 50 µs per authoritative Kamehameha per tick: hundreds are fine, a thousand fill the tick
([§12](#on-the-server)). Plain (non-authoritative) cues cost the server almost nothing: a message per send.

### Do particles and post effects need the server?

No, and they never touch it. They are an extension of the effect: each client works them out from the effect's state
(published by the server) and the seed, which every client shares. Three rules keep that true:

1. **Nothing gameplay depends on lives in particles.** A particle's collision is a client-side GPU readback the server
   never hears. Damage comes from the effect's own collisions (`PART_BEAM`, `PART_BLAST`) or logic the server runs.
2. **Everything that shapes the visuals reaches clients through the cue**: the published state and parameters. Server
   logic that changes something visible without publishing it makes clients drift.
3. **Small differences between clients are fine**: each client's particles collide with its own loaded world, and a
   late joiner's particles are prewarmed over only the last two seconds. None of it is gameplay.

### Is that how other engines do it?

Yes. In Unreal, gameplay cues are skipped on a dedicated server and Niagara does not simulate there; post-process
volumes are rendering only. In Unity, a dedicated-server build strips rendering, VFX Graph needs a GPU, Netcode for
Entities has presentation systems only in the client world, and effects are spawned per client from an RPC or a
network variable. A listen server or host draws effects because it is also a client, as Minecraft singleplayer does
here.

### Can I spawn particles without an effect?

Not yet without an effect object: use `CgVfxBurst` with a particles-only look, which needs no class of your own. A
shared emit call for high-volume one-off particles is planned ([§9](#spawning-particles-without-an-effect)).

### Why is it called a cue?

It is Unreal's term (Gameplay Ability System's gameplay cues): a one-off cue is their *Execute*, a lasting cue their
*Add* and *Remove*, and prediction works the same way. "Authority" is Unreal's word too, for the side that decides.

### What does a player who joins late see?

Lasting cues start at their current age, particles prewarmed. One-off cues already played are gone, as in every
engine.

### What happens in singleplayer?

The built-in server runs authoritative effects and the client draws them: two copies, as on a dedicated server with
one player.

### My authoritative beam never hits anything

See [§7](#when-it-never-hits): usually the target is not in the beam, or `at(...)` was not given a level or entity.

---

## 14. Checking and measuring

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
look at: `vfx-modules` (one station per module), `vfx-blasts` (360,000 particles), `vfx-spheres-stress`.

Unit tests for the network and server side: `CgVfxCueTest`, `CgVfxAuthorityTest`, `CgVfxCollisionsTest`,
`CgVfxOwnerTest`, `CgVfxStartAtTest` (`./gradlew :core:test --tests "<class>"`).

Profiling (`docs/PROFILING.md`), trace channel `crystalgraphics.vfx`: zones `vfx.tick`, `vfx.emitters.workers`,
`vfx.effect.tick/<namespace>`, `vfx.effect.submit/<namespace>`, and on the GPU `vfx.pool.step` and `vfx.pool.range`;
counters `vfx.gpu.slots-stepped`, `vfx.draws.particle-gpu`, `vfx.effects/<namespace>`.
