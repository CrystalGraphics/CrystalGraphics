# Audio: playing sound

Read this before your mod plays a sound: a click, an explosion at a point, a hum riding an entity, music, a voice line
with its subtitle, a mix that ducks the world for a cut. It says how to play each, how the engine places and weighs
what plays, and what to load ahead so the first play never hitches.

The engine plays its own sound, the way Unreal's audio mixer does. It runs a mixer on an audio thread of its own and
writes to its own output stream on the device the game plays to. It follows the game's master volume slider and holds
while the game is paused. Minecraft's own sound events play through it too, read from the resource packs, and
Minecraft's sound engine is left exactly as it is. The same calls run on every version and loader.

It comes in layers. Take the first that does what you need:

| Layer | For | Section |
|---|---|---|
| **`CgSounds`** | one call per job: play by name, at the listener or in the world or riding something; load, loop, stop, preload, mix | [2](#2-quick-start-cgsounds) |
| **Assets** | a cue's or a sequence's sounds, written in data and played with the effect | [10](#10-in-cues-and-sequences) |
| **`CgAudioEngine`** | everything else: attenuation, priority, concurrency, submixes and effects, subtitles, starting on a sample | [5](#5-classes-priority-and-voices) on |
| **`CgAudioSource`** | a sound you synthesize yourself | [12](#12-a-sound-you-make) |

---

## Contents

1. [The words you need](#1-the-words-you-need)
2. [Quick start: `CgSounds`](#2-quick-start-cgsounds)
3. [What a sound's name can be](#3-what-a-sounds-name-can-be)
4. [Loading sounds](#4-loading-sounds)
5. [Classes, priority and voices](#5-classes-priority-and-voices)
6. [Placing a sound, and sounds that follow](#6-placing-a-sound-and-sounds-that-follow)
7. [Distance, direction and walls](#7-distance-direction-and-walls)
8. [Mixes, and Minecraft's own sounds](#8-mixes-and-minecrafts-own-sounds)
9. [Subtitles](#9-subtitles)
10. [In cues and sequences](#10-in-cues-and-sequences)
11. [Starting on a sample](#11-starting-on-a-sample)
12. [A sound you make](#12-a-sound-you-make)
13. [Submixes and effects](#13-submixes-and-effects)
14. [On a server, in tests, without a device](#14-on-a-server-in-tests-without-a-device)
15. [Easy to get wrong](#15-easy-to-get-wrong)

---

## 1. The words you need

| Word | What it is | Unreal's |
|---|---|---|
| **sound** (`CgSound`) | a loaded file: decoded samples kept in memory, or compressed bytes it streams from | `USoundWave` |
| **name** (`CgSoundRef`) | what code or an asset names: a Minecraft event, a file, a game's cue, a random pick | a sound cue reference |
| **id** | a `long` for one play: what you stop, move or caption it by; 0 where nothing played | an active sound |
| **class** (`CgSoundClass`) | `SFX`, `VOICE`, `MUSIC`, `AMBIENT`, `UI`: the slider it follows, its submix, whether a pause holds it | `USoundClass` |
| **voice** | one of 64 playing slots; the loudest sounds by priority take them | a wave instance |
| **attenuation** (`CgSoundAttenuation`) | what distance does to a placed sound: fade, low-pass, occlusion, reverb, doppler | `FSoundAttenuationSettings` |
| **listener** | where sounds are heard from: the camera, each frame | the audio listener |
| **mix** (`CgSoundMix`) | class volume and pitch changes that fade in when pushed and out when popped | `USoundMix` |
| **submix** (`CgSubmix`) | where voices sum before the output, with effects over them: `world`, `reverb`, `ui`, `music`, `main` | a submix |
| **engine** (`CgAudioEngine`) | a world's sound: the mixer, its thread, everything playing | the audio device |

---

## 2. Quick start: `CgSounds`

`CgSounds` plays on the client world's engine, which it makes at the first ask. Each call mirrors Unreal's
`UGameplayStatics`.

```java
CgSounds.play2D("minecraft:ui.button.click");                                   // at the listener, the UI class
long boom = CgSounds.playAt("minecraft:entity.generic.explode", x, y, z);         // in the world, the SFX class
CgSounds.playAttached("minecraft:entity.blaze.ambient", CgEntityAttachments.of(blaze));   // riding it as it moves
CgSounds.stop(boom);
```

With a class, volume and pitch:

```java
CgSounds.play2D("mymod:sounds/ui/open.ogg", CgSoundClass.UI, 0.8f, 1.2f);       // quieter and higher
CgSounds.playAt("mymod:sounds/boom.ogg", CgSoundClass.SFX, x, y, z, 1f, 0.9f);
CgSounds.playAttached("mymod:sounds/charge.ogg", CgEntityAttachments.of(player, CgEntityAttachments.RIGHT_HAND),
        CgSoundClass.SFX, 1f, 1f, true);                                       // true: stops if the player goes
```

Your own files, loaded once and played as often as you like:

```java
CgSound hum = CgSounds.load("mymod:sounds/engine_hum.ogg");                     // decoded once, kept
CgSound theme = CgSounds.load("mymod:sounds/theme.ogg", true);                  // streamed: music, long ambience
long id = CgSounds.loop(hum, CgSoundClass.AMBIENT, 0.6f);                       // until stopped
long tip = CgSounds.loopAttached(hum, beamTip, CgSoundClass.SFX, 0.8f);         // looping on a moving point
CgSounds.play(theme, CgSoundClass.MUSIC, 1f);
```

Ahead of play, and a mix:

```java
CgSounds.preload("kakarot:sound/V_PL_SKL_01");                                  // any thread: a worker at load
CgSounds.push(UNDERWATER);                                                      // classes adjusted until popped
CgSounds.pop(UNDERWATER);
```

Anything past these is the engine's: `CgSounds.engine()` answers it, or null where nothing plays.

```java
CgAudioEngine audio = CgSounds.engine();
if (audio != null) audio.activeSounds().priority(id, 2f).group(id, SPARKS);
```

- `play2D` is unplaced and unattenuated, in `UI` unless told, which plays on through a pause.
- `playAt` is placed and attenuated as its sound says, in `SFX` unless told.
- Every play answers the sound's id, or 0 where nothing played: every voice slot taken, no such sound, no output.
- `CgSounds` runs on the frame thread, except `preload`, which a worker runs.

---

## 3. What a sound's name can be

A name is a `CgSoundRef`, compiled once and kept. The same names work in `CgSounds`, in Java and in assets.

| Name | Plays | Example |
|---|---|---|
| a Minecraft or resource-pack event | a variant from the packs' `sounds.json`, picked by weight | `minecraft:entity.generic.explode` |
| a file | that Ogg Vorbis file | `mymod:sounds/beam_loop.ogg` |
| a game's cue | the file its kit's `sounds.yaml` names for it | `kakarot:sound/S_battle_punch_sw` |
| a voice line | `{ cue, speaker }`: the speaker's `voice:` sheet in the kit's `cast.yaml` | `{ cue: kakarot:sound/V_PL_SKL_01, speaker: Goku }` |
| a pick | `{ pick: [a, b, c] }`, rolled from the play's seed, so every client hears the same one | |
| a file with settings | `{ path, stream, attenuation }` | below |

```java
CgSoundRef boom = CgSoundRef.of("minecraft:entity.generic.explode", "mymod:boom");
CgSoundRef beam = CgSoundRef.of(Map.of("path", "mymod:sounds/beam_loop.ogg", "stream", true,
        "attenuation", Map.of("inner", 4, "outer", 48, "falloff", "linear")), "mymod:beam");
long id = audio.play(beam, CgSoundClass.SFX, x, y, z, 1f, 1f, true, Double.NaN, seed);
```

**Minecraft's events.** They play through the engine, never Minecraft's sound engine. They are read from every
resource pack's `sounds.json` the way Minecraft merges them, at first use and again after a resource reload, so a
pack's sounds are heard at once.

```java
long boom = audio.playEvent("minecraft:entity.generic.explode", x, y, z, 4f, 1f);   // volume 4: heard to 64 blocks
```

- An event that names another event is followed to its file.
- It falls off linearly to 16 blocks times the larger of 1 and the volume, as Minecraft's do, with no reverb. Pitch
  is clamped to Minecraft's 0.5 to 2.

**Failures.**
- In `CgSounds`, a name with no namespace, or one nothing holds, plays nothing and is logged once.
- In an asset, it fails where the asset loads, naming it. A sequence skips that section instead (§10).

---

## 4. Loading sounds

Sounds are Ogg Vorbis, loaded by path, each read and decoded once and kept.

```java
CgSound click = audio.sounds().load("mymod:sounds/click.ogg");          // or CgSounds.load
CgSound theme = audio.sounds().load("mymod:sounds/theme.ogg", true);    // streamed
```

- **A short sound** is decoded to 16-bit samples when it loads.
- **A streamed sound** keeps its compressed bytes. The engine's decode thread keeps a second of it decoded ahead of the
  voice, so a hitch on the game's thread never cuts it.
  - A sound longer than 10 seconds streams whether asked or not.
- **Rate:** a sound plays at its own pitch and length whatever its sample rate. Each voice resamples it to the output's
  48 kHz.
- **Decoder:** the game's own (stb_vorbis from 1.13, JOrbis before), so the engine bundles none.
- **Errors:** a path that is not found, or not Ogg Vorbis, fails naming it.

**Load ahead of play.** `load` reads and decodes on the calling thread, and a play of a name not yet loaded loads it
on the frame. So:
- Load your mod's sounds behind the loading screen, or from a worker before they are needed ([`LOADING.md`](LOADING.md)).
- `preload` reads and decodes everything a name may play. It runs on any thread.
- A Minecraft event's variants are left to their first pick.

```java
CgJob.executor(CgJobLane.LATENCY).execute(() -> CgSounds.preload("mymod:sounds/beam_loop.ogg"));
```

A cue's and a sequence's sounds are loaded with the asset; you preload nothing for those.

---

## 5. Classes, priority and voices

Every sound plays in a class:

| Class | Follows the game's slider | Submix | Paused with the game |
|---|---|---|---|
| `SFX` | Players | `world` | yes |
| `VOICE` | Voice | `world` | yes |
| `MUSIC` | Music | `music` | yes |
| `AMBIENT` | Ambient | `world` | yes |
| `UI` | the host's UI category | `ui` | **no**: a menu's click plays on |

All of them sit under `MASTER`, which follows the master slider. A class of your own takes a parent and inherits its
slider, submix and pause unless given its own: `new CgSoundClass("mymod:engines", CgSoundClass.SFX)`.

**Voices and priority.**
- At most 64 sounds are heard at once.
- Each frame every sound is weighed by its priority times its volume, and the loudest take the voices (Unreal's
  `GetSortedActiveWaveInstances`).
- A looping sound that loses its voice, or leaves its range, goes virtual: its time runs on, and it comes back where
  it would have been.
- A one-shot that loses its voice ends.

```java
long hum = audio.play(engineHum, CgSoundClass.AMBIENT, 1f, 1f, true, Double.NaN);   // gain, pitch, loop, start
audio.activeSounds().priority(hum, 2f);                                              // weighed above an ordinary sound
```

**Concurrency.** A group caps how many of a kind play at once, and says which go:

```java
CgSoundConcurrency sparks = new CgSoundConcurrency(8, CgSoundConcurrency.Rule.STOP_OLDEST);
audio.activeSounds().group(id, sparks);

CgSoundConcurrency perPlayer = new CgSoundConcurrency(1, CgSoundConcurrency.Rule.STOP_OLDEST).limitToOwner(true);
audio.activeSounds().group(shout, perPlayer).owner(shout, player);                   // one shout per player
```

The rules are `PREVENT_NEW`, `STOP_OLDEST`, `STOP_FARTHEST`, `STOP_QUIETEST` and `STOP_LOWEST_PRIORITY`.

---

## 6. Placing a sound, and sounds that follow

A sound is either **placed**, at a point in the world, faded and panned from the listener, or **unplaced**, heard
everywhere at full volume, centred.

```java
long id = audio.play(boom, CgSoundClass.SFX, 1f);
audio.activeSounds().at(id, x, y, z).attenuation(id, CgSoundAttenuation.DEFAULT);   // placed, and heard as placed
audio.activeSounds().unplace(id);                                                    // back to everywhere
```

**Following something.** A sound can ride a `CgAttachment`, asked where it is each frame before the sound is weighed.
This is Unreal's `SpawnSoundAttached`. An attachment can be:

- an entity's point (`CgEntityAttachments`);
- a skinned character's socket (`model.attachment("hand_r")`);
- a sequence's spawnable or locator;
- your own lambda.

```java
CgSounds.playAttached("minecraft:entity.blaze.ambient", CgEntityAttachments.of(blaze));          // its centre
CgSounds.playAttached("mymod:sounds/charge.ogg", CgEntityAttachments.of(playerId, CgEntityAttachments.HANDS_FORWARD));
audio.activeSounds().follow(id, goku.attachment("hand_r"), false);                               // any playing sound

CgAttachment cart = (point, turn) -> {                                                            // your own
    point[0] = cartX; point[1] = cartY; point[2] = cartZ;
    return !cartRemoved;                                                                          // false: it is gone
};
```

**`CgEntityAttachments.of`.** It takes an entity by its network id or as the game's own entity object, on the
client's entities:
- `of(entity)` gives the entity's centre.
- `of(entity, point)` gives a named point: `FEET`, `CENTRE`, `CHEST`, `HEAD`, `EYES`, `RIGHT_HAND`, `LEFT_HAND`,
  `HANDS_FORWARD`, or one a mod declared.
- On a server's world, use `of(CgEntityQueries.of(level), id, point)`.

**When the attachment is gone** (`place` answers false):
- By default the sound plays on where it last was. This is Unreal's default, `bStopWhenAttachedToDestroyed` false.
- With `stopWhenGone`, the sound stops there.
- `loopAttached` always stops, or the loop would play forever.

**The listener** is the camera. Each frame the engine moves it to the camera's final pose, shakes included, so a cut
moves what you hear on the frame it happens. A host without a camera system places it with `audio.listener().set(...)`.

---

## 7. Distance, direction and walls

A placed sound's `CgSoundAttenuation` says what distance does to it. It uses Unreal's attenuation settings, with the
defaults in blocks:

```java
CgSoundAttenuation blast = new CgSoundAttenuation()
        .algorithm(CgSoundAttenuation.Algorithm.NATURAL_SOUND)        // or LINEAR, LOGARITHMIC, INVERSE, LOG_REVERSE
        .inner(4).falloff(60)                                        // full within 4 blocks, the curve over 60 more
        .lowPass(true).lowPassRange(20, 60).lowPassHz(20000, 2500)   // duller far off
        .occlusion(true).occlusionLowPass(900).occlusionVolume(0.5f) // muffled behind blocks
        .doppler(1f);                                                // pitch shifts as it moves; off by default
audio.activeSounds().at(id, x, y, z).attenuation(id, blast);
```

- **Panning** is Unreal's for stereo: equal power between speakers at 30° right and 30° left.
  - A sound ahead is centred, and one behind is centred too.
  - One at your side leans that way, but not fully.
- **Occlusion** traces a ray from the sound to the listener through the level's blocks, every tenth of a second of
  play.
  - While blocked, the sound's low-pass and volume move to the settings' over a tenth of a second.
  - They move back once the path is clear.
- **Reverb**: each placed sound sends some of itself to the `reverb` submix.
  - The send rises from 30% near the sound to 95% at the falloff's end (`reverbWet`, `reverbDistance`;
    `reverbSend(false)` turns it off).
  - The submix runs a room reverb, `CgReverb`.
- **Doppler** reads the sound's movement between frames, and the listener's.
- A sound placed with no attenuation is heard everywhere at full volume and is not panned. A name played at a point
  takes its own attenuation, else `CgSoundAttenuation.DEFAULT`.

---

## 8. Mixes, and Minecraft's own sounds

A mix adjusts classes, fading in when pushed and out when popped (Unreal's `USoundMix`):

```java
CgSoundMix duck = new CgSoundMix(0.3f, 1f)                           // fade in over 0.3 s, out over 1 s
        .adjust(CgSoundClass.MUSIC, 0.3f, 1f)                        // music to 30%, pitch unchanged
        .adjust(CgSoundClass.AMBIENT, 0.5f, 1f);
CgSounds.push(duck);
CgSounds.pop(duck);
```

- An adjustment applies to the class and every class within it.
- Two mixes on one class multiply.
- Pushing a mix that is fading out fades it back in.

A mix can also hush **Minecraft's own sounds**, by their category in the game's sound settings, faded with the mix:

```java
CgSoundMix cinematic = new CgSoundMix(0.3f, 1f)
        .duckHost(CgHostVolume.Category.BLOCKS, 0.2f)                // the game's block sounds to 20%
        .duckHost(CgHostVolume.Category.HOSTILE, 0.2f)
        .duckHost(CgHostVolume.Category.WEATHER, 0.3f);
```

The player's sliders are never touched. The game's sounds come back when the mix pops, and when the engine closes.

---

## 9. Subtitles

A sound can say what it is at the bottom of the screen. Queue a line as you play it:

```java
long id = audio.play(line, CgSoundClass.VOICE, 1f);
audio.subtitles().queue(id, "Kamehameha!", line.seconds());
```

Or several, each from its time into the sound until the next:

```java
audio.subtitles().queue(id, CgSubtitles.DEFAULT_PRIORITY, line.seconds(),
        List.of(new CgSubtitles.Cue("Kame...", 0f), new CgSubtitles.Cue("...hameha!", 1.4f)));
```

Nothing is captioned for you, Minecraft's own sounds included: a line shows only when you queue it. A Minecraft event's
own caption, in the player's language, is a key away (packs name one in `sounds.json` from 1.9):

```java
long id = audio.playEvent("entity.generic.explode", x, y, z, 1f, 1f);
String key = CgHostSoundEvents.current().subtitle("entity.generic.explode");   // "subtitles.entity.generic.explode"
if (key != null) audio.subtitles().queue(id, CgI18n.translate(key), seconds);
```

- **One line at a time.** The highest priority shows (`CgSubtitles.DEFAULT_PRIORITY` unless you say), and the latest
  queued among equals.
- **Timing.** A line goes when its sound ends or is stopped. It follows the sound's own playback, so a pause or a hitch
  never puts it out of step.
- **The setting.** Lines show while the player's **Subtitles** setting is on (`CgGraphicsSettings.SUBTITLES`, on by
  default). They are queued either way, so turning it back on mid-move shows the line under way.
- **Where it draws.** Over the HUD (`CgRenderStage.HUD`), never in Minecraft's own subtitle box.

### Lines in a sequence

A sequence captions its voices with a `subtitle` track.
- **Text:** a section's `text` is a string id, `<ns>:text/<id>`, held by the kit's `strings/<locale>.yaml`. There is
  one file per language, read in the player's (`en_us`, `fr_fr`), with `en_us` behind it.
- **Speaker:** a line may name its speaker, a string of its own.
- **Length:** an open end lasts as long as the `sound` section it names.

```yaml
# strings/en_us.yaml
_v: 1
strings:
  Cpl001A: Goku
  V_C001A_skl_01_Msg: { text: Kamehame..., speaker: kakarot:text/Cpl001A }
```

```yaml
# a sequence's tracks
- { id: voice, kind: sound, sections: [ { id: v1, range: [35189, null], sound: kakarot:sound/V_C001A_skl_01 } ] }
- { id: lines, kind: subtitle, sections: [ { id: l1, range: [35189, 125189], text: kakarot:text/V_C001A_skl_01_Msg, sound: v1 } ] }
```

- The line follows the sequence's clock: a pause holds it, and a seek joins it at its age.
- A section's `speaker` (a string id) replaces the string's own.

### A game's look

A kit's `subtitle_style.yaml` draws its lines as the game does: its own fonts, an outline, an optional band, and its
widget's animations in and out. Positions are in UI units, scaled from `reference_height` with the screen. A kit
without one draws in Unreal's canvas look: 13 px, white, a 1 px black outline, centred near the bottom.

```yaml
# subtitle_style.yaml
_v: 1
reference_height: 1080
line: { font: mykit:fonts/Line.otf, size: 20, outline: { size: 3, colour: 0xFF000000 },
        anchor: [0.5, 0.5], alignment: [0.5, 0], offset: [0, 328] }
name: { font: mykit:fonts/Name.otf, size: 22, colour: 0xFF000000, outline: { size: 3, colour: 0xFFFFFFFF },
        anchor: [0.5, 0.5], offset: [-425, 273] }
fade_in: { text: [[0, 0], [0.2, 1]], slide: [[0, 100], [0.2, 0]], interp: cubic }     # in from the right
fade_out: { text: [[0, 1], [0.2, 0]], slide: [[0, 0], [0.2, -100]], interp: cubic }   # out to the left
```

A widget with a bar behind its text adds a `band`, and animates its alpha as the text's:

```yaml
band: { colour: 0x80030305, anchor: [0.5, 0.5], height: 200, offset: [0, 330] }
fade_in: { band: [[0, 0], [0.067, 1]], text: [[0.167, 0], [0.3, 1]] }
```

- **Units.** A size is in points at 96 DPI (Slate's); everything else is in UI units. A text block sits at `anchor` on
  the screen (0 to 1), moved by `offset`, less `alignment` of its own size.
- **Animation keys** are `[seconds, value]`:
  - `text` and `band` are an alpha; `slide` is a move along x in UI units.
  - They are linear by default. `interp: cubic` eases them as Unreal's curves with automatic tangents, so two keys
    are a smoothstep.
- **One line at a time:** a new one waits for the last to animate out.
- **A file that does not validate** fails the kit's registration, naming each issue.

---

## 10. In cues and sequences

Sounds written into an effect play with it; you call nothing.

**A cue's `sounds`** play as Unreal's gameplay cue notifies play them:
- One in the cue's application or removal plays once.
- One in its looping block starts with the cue and fades at removal, over its `fade_out` to its `fade_volume`.
- Each is placed as its `placement` says: `attach: attach_to_target` follows the target's socket (or the target
  itself, if it has no such socket) for as long as it plays. Unattached, it stays where it started.

```yaml
looping:
  particles: [ ... ]
  sounds:
    - { sound: mymod:sounds/beam_loop.ogg, fade_out: 0.5, fade_volume: 0,
        placement: { socket: hand_r, attach: attach_to_target } }
```

**A sequence's `sound` track** plays each section's sound as Unreal's audio track does:

```yaml
- { id: t1, kind: sound, binding: { role: caster }, sections: [
    { id: shout, range: [150, 900], sound: { cue: kakarot:sound/V_PL_SKL_01, speaker: Goku } },
    { id: hum, range: [300, 1800], sound: mymod:sounds/beam_loop.ogg, looping: true, start_offset: 0 } ] }
```

- **Start and end.**
  - A section's sound starts where its tick fell in the frame, on its sample (§11).
  - It stops at the section's end unless it has `play_until_finished`.
- **Joined late**, by a seek or a late joiner: a looping section starts at its age in the section, and a one-shot
  already passed is not played.
- **Where it plays.** On a bound track it plays at the binding and moves with it.
- **Unbound, with `attach`.** A track that binds nothing can ride points instead, with `attach` keys `[tick, point]`
  (Unreal's `AttachActorData`):
  - A point is any binding form: `{ role }`, `{ role, socket }`, `{ role, node }`, `{ spawnable, node }`,
    `{ locator }`.
  - The keys step: the first holds before its tick, and the sound jumps to each next key's point at its tick.
  - While the point in force resolves nothing (a spawnable not spawned yet, or gone), the sound is unplaced, heard
    everywhere.
  - On a bound track, `attach` fails at load.
- **Unbound, without `attach`,** the sound is heard everywhere.
- **Volume.** A `volume` channel scales it as it plays.
- **What it cannot play.** A section with a `pitch` channel, or a cue or speaker no kit holds, is skipped with one
  warning naming it, and the rest of the sequence plays. A missing voice line never stops a cut.

```yaml
- { id: t2, kind: sound, sections: [
    { id: charge, range: [0, 900], sound: mymod:sounds/charge.ogg, looping: true,
      attach: [[0, { role: caster, socket: hand_r }], [300, { spawnable: beam, node: tip }]] } ] }
```

A clip's `sound_effects` markers play on the caster. A sound that must ride a hand belongs in a sequence.
[`vfx/sequences.md`](vfx/sequences.md) has tracks and bindings in full.

---

## 11. Starting on a sample

A sound can start at a time rather than at the next buffer. The engine's clock is the frame's audio time, and a start
at a time lands on its exact sample, however the frames fall:

```java
CgAudioClock clock = audio.clock();
audio.play(click, CgSoundClass.SFX, 1f, 1f, false, clock.now() + 0.5);   // half a second from this frame, to the sample
```

A sequence's events are heard together at the end of the frame that crossed them. Each knows how long before the
frame's end it fell, so its sound starts where the event was, not where the frame ended:

```java
sequencer.listen(CgSequenceEventListener.Side.OURS, (player, phase, event, args) ->
        audio.play(click, CgSoundClass.SFX, 1f, 1f, false, audio.clock().now() - sequencer.heardSecondsAgo()));
```

- **Latency.** A timed start plays about 85 ms after its time, the length of the output's queue, and stays exact to
  the sample.
- **Long frames.** It stays exact while a frame is shorter than about 64 ms (above 16 frames a second). A longer
  frame's sounds start as soon as they can.
- **Montages.** Across a montage section looping or handing on, the times stay on the play's own timeline.

---

## 12. A sound you make

A `CgAudioSource` adds its samples into each buffer the mixer renders: a synthesizer, a procedural engine note, a
stream from elsewhere.

```java
final class Click implements CgAudioSource {
    private int left = 480;                                          // 10 ms at 48 kHz
    @Override
    public boolean render(float[] out, int offset, int frames, int channels, int sampleRate, float gain) {
        for (int f = offset; f < offset + frames && left > 0; f++, left--) {
            for (int c = 0; c < channels; c++) out[f * channels + c] += gain;
        }
        return left > 0;                                             // false: done, its voice is freed
    }
}
audio.play(new Click(), 0.5f);
audio.playUnpaused(new Click(), 0.5f);                               // plays on while the game is paused: a menu's
```

```java
CgAudioTone tone = new CgAudioTone(440, 0.25f, 0);                   // A4 at a quarter of full scale, until stopped
audio.play(tone, 1f);
audio.gain(tone, 0.5f);
audio.stop(tone);
```

- **Threading.** `render` runs on the audio thread. Add into the buffer from its `offset`, never overwrite it, and
  allocate nothing.
- **No weighing.** A source played this way skips the classes and the weighing, and is refused past the 64th voice.

---

## 13. Submixes and effects

Every voice mixes into a submix, and the submixes into each other: `world` (with `reverb` inside it), `ui` and
`music`, all into `main`. A submix runs its effects over what reached it:

```java
CgLowPass muffle = new CgLowPass(800);                               // hertz
audio.submix("world").effect(muffle);                                // the world's sounds, muffled
muffle.cutoff(20000);                                                // open again
audio.submix("world").remove(muffle).volume(0.5f);
```

An effect is a `CgSubmixEffect`: it runs on the audio thread, in place over the buffer, and allocates nothing.

---

## 14. On a server, in tests, without a device

- **Where the engine comes from.** A world's engine is made at the first ask: `CgSounds`, a cue's sound, a sequence's
  sound track and `CgAudioEngine.active(world)` all make it. You never add one yourself.
- **A dedicated server plays nothing.** There is no audio output there, so no engine is made, every `CgSounds` play
  answers 0 and `load` answers null. A sequence's sound tracks and a cue's sounds are skipped there.
- **The harness, and a test.** With no output provided, they play nothing the same way. A test that needs sound
  gives a world an engine over a stream of its own: `world.add(new CgAudioEngine(output, decoder))`.
- **No device needed.** The mixer is plain Java over float buffers, so a test hears nothing and checks everything:

```java
CgAudioMixer mixer = new CgAudioMixer(48000, 2, 512, 64);
mixer.start(new CgAudioTone(1000, 0.5f, 0), 1f, true);
float[] buffer = new float[512 * 2];
mixer.render(buffer);                                                // 512 interleaved stereo frames in [-1, 1]
mixer.start(click, 1f, true, mixer.rendered() + 1000);               // 1000 frames into the next buffers
```

- **When the device goes.** If the output device goes away, the engine reopens it every second. Sounds wait
  meanwhile.
- **Garbage collection.** The output holds 85 ms of sound, so a collection pause does not cut it.

---

## 15. Easy to get wrong

- **Playing from a worker.** Everything here runs on the frame thread except `preload` and `load`. A play from
  another thread corrupts the active sounds.
- **Loading on the frame.** The first play of an unloaded name reads and decodes it right there. Preload at load
  (§4).
- **A name without a namespace.** `"button_click"` plays nothing; write `"mymod:ui.click"` or a file path.
- **Asking for a kit's cue before its kit loads.** `CgSounds` remembers a name that resolved to nothing for the rest
  of the session. Play kit cues after the kit's scan.
- **Placing without attenuation.** `activeSounds().at(...)` alone is heard everywhere at full volume. Give it an
  attenuation, or play by name at a point.
- **Stopping by a stale id.** An id names one play. Once that sound ends the id names nothing, and stopping it does
  nothing, which is safe. Keep the id each play returns.
- **Expecting a paused game to keep `SFX` going.** Only `UI` plays through a pause. A menu's sounds go in `UI`.
- **A looping sound on something that dies.** `playAttached` keeps playing where its parent was. Pass `stopWhenGone`,
  or use `loopAttached`, which stops on its own.
- **`attach` on a bound sequence track.** It fails at load. Bind nothing, and attach.
- **Calling Minecraft's sound engine.** Its sounds play beside ours, out of our classes and mixes, unable to ride a
  socket or start on a sample: only a mix's `duckHost` reaches them. Play the event through `CgSounds`.

---

## Where the code is

| Package / type | Role |
|---|---|
| `audio.CgSounds` | the shorthand: play by name, load, loop, stop, preload, mixes |
| `audio.CgAudioEngine` | a world's sound: the mixer's thread, the command queue, the listener, the clock; `active(world)` |
| `audio.CgActiveSounds` | everything playing: placement, `follow`, attenuation, priority, groups, virtual loops, mixes |
| `audio.CgSound`, `CgAudioSounds`, `CgSoundStream` | a loaded sound, the loader, a streamed sound's ring |
| `audio.CgSoundRef`, `CgHostSoundEvents`, `CgKitSounds` | names: Minecraft's events from `sounds.json`, a kit's cues and voice lines |
| `audio.CgSoundClass`, `CgSoundMix`, `CgSoundConcurrency`, `CgSoundAttenuation` | classes, mixes, caps, distance |
| `audio.CgSubmix`, `CgSubmixEffect`, `CgLowPass`, `CgReverb` | the submix tree and its effects |
| `audio.CgSubtitles`, `CgSubtitleOverlay`, `CgSubtitleStyle` | subtitles and a game's look |
| `audio.CgAudioMixer`, `CgAudioSource`, `CgAudioTone`, `CgAudioClock` | the mixer, a source, a test tone, audio time |
| `world.CgAttachment`, `CgEntityAttachments` | what a sound follows |
| `sequence.sound.CgSequenceSoundEvaluator` | a sequence's `sound` track |
| `platform.audio.CgAudioOutput`, `CgAudioDecoder`, `CgHostVolume`, `CgHostDuck` | what a host provides: the output, the decoder, the sliders, the duck of its own sounds |

Package guide: `audio/CLAUDE.md`.
