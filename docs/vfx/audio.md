# Audio

The engine plays its own sound: a mixer on an audio thread of its own, writing to an output stream beside the
host's. On Minecraft it opens its own output on the device the game plays to, follows the game's master volume
slider, and holds while the game is paused. Minecraft's own sounds are left exactly as they are.

## Playing a sound file

Sounds are Ogg Vorbis files, loaded by path and kept: each is read and decoded once.

```java
CgSound click = audio.sounds().load("mymod:sounds/click.ogg");          // short: decoded once, kept in memory
CgSound theme = audio.sounds().load("mymod:sounds/theme.ogg", true);    // streamed
long id = audio.play(click, CgSoundClass.SFX, 1f);                      // its id: what it is placed and stopped by
long music = audio.play(theme, CgSoundClass.MUSIC, 0.8f, 1f, true, audio.clock().now());  // pitch, looping, on its sample
audio.activeSounds().stop(id);
```

- A sound longer than 10 seconds streams whether asked or not. Its compressed bytes stay in memory, and the engine's
  decode thread keeps a second of it decoded ahead of the voice, so a hitch on the game's thread never cuts it.
- A short sound is decoded to 16-bit samples when it loads. Load ahead of play: loading reads and decodes on the
  calling thread.
- A sound plays at its own pitch and length whatever its sample rate: each voice resamples it to the output's 48 kHz.
- Decoding uses the game's own decoder (stb_vorbis on 1.13 and later, JOrbis before), so the engine bundles none.
- A path that is not found, or not Ogg Vorbis, fails naming it.

## Minecraft's sounds, and a game's

Minecraft's own sound events play through the engine too, read from every resource pack's `sounds.json` the way
Minecraft merges them. Minecraft's sound engine is never called.

```java
long boom = audio.playEvent("minecraft:entity.generic.explode", x, y, z, 4f, 1f);   // volume 4: heard to 64 blocks
```

- A variant is picked by its weight, an event that names another event followed to its file.
- It falls off linearly to 16 blocks times the larger of 1 and the volume, as Minecraft's do, with no reverb.
- The table is read at first use and again after a resource reload, so a resource pack's sounds are heard at once.
- An unknown event fails, naming it.

A sound an asset names is a `CgSoundRef`: a host event, a file with its settings, a game's cue name, or a pick.

```java
CgSoundRef boom = CgSoundRef.of("minecraft:entity.generic.explode", "mymod:boom");
CgSoundRef beam = CgSoundRef.of(Map.of("path", "mymod:sounds/beam_loop.ogg", "stream", true,
        "attenuation", Map.of("inner", 4, "outer", 48, "falloff", "linear")), "mymod:beam");
CgSoundRef punch = CgSoundRef.of("kakarot:sound/S_battle_punch_sw", "mymod:hit");   // through the kit's table
CgSoundRef line = CgSoundRef.of(Map.of("cue", "kakarot:sound/V_PL_SKL_01", "speaker", "Goku"), "mymod:shout");
long id = audio.play(punch, CgSoundClass.SFX, x, y, z, 1f, 1f, false, Double.NaN, seed);
```

- **A game's cue name** resolves through its kit's `sounds.yaml`: the game's `namespace`, and each cue's file. A
  kit's scan reads it.
- **A voice line** names its speaker. The speaker's `voice:` sheet in the kit's `cast.yaml` finds it in the table as
  `<sheet>/<cue>`.
- **A pick**, `{ pick: [a, b, c] }`, is rolled from the play's seed, so every client hears the same one.
- A cue name no table holds, or a speaker no cast holds, fails where its asset loads, naming it: a cue fails to load.
  A sequence skips that section instead (below).

## In cues and sequences

A cue's `sounds` play as Unreal's gameplay cue notifies play them. One in its application or removal plays once. One
in its looping block starts with the cue, follows its target, and fades at removal over its `fade_out` to its
`fade_volume`:

```yaml
looping:
  particles: [ ... ]
  sounds: [ { sound: mymod:sounds/beam_loop.ogg, fade_out: 0.5, fade_volume: 0 } ]
```

A sequence's `sound` track plays each section's sound as Unreal's audio track does:

```yaml
- { id: t1, kind: sound, binding: { role: caster }, sections: [
    { id: shout, range: [150, 900], sound: { cue: kakarot:sound/V_PL_SKL_01, speaker: Goku } },
    { id: hum, range: [300, 1800], sound: mymod:sounds/beam_loop.ogg, looping: true, start_offset: 0 } ] }
```

- A section's sound starts where its tick fell in the frame, on its sample, and stops at the section's end unless
  it has `play_until_finished`.
- A looping one joined late, by a seek or a late joiner, starts at its age in the section. A one-shot already passed
  is not played.
- Bound to a role, it plays at the role and moves with it; unbound, it is heard everywhere.
- A `volume` channel scales it as it plays.
- A section the engine cannot play (a `pitch` channel, a cue or speaker no kit holds) is skipped with one warning
  naming it, and the rest of the sequence plays: a missing voice line never stops a cut.

## Classes, places and voices

Every sound plays in a class. A class follows its Minecraft slider (`SFX` the players', `MUSIC`, `AMBIENT`, `VOICE`
and `UI` their own) and routes to a submix. `UI` plays on while the game is paused.

```java
long hum = audio.play(engineHum, CgSoundClass.AMBIENT, 1f, 1f, true, Double.NaN);
audio.activeSounds()
        .at(hum, x, y, z)                                            // placed in the world
        .attenuation(hum, new CgSoundAttenuation().inner(2).falloff(30))   // full within 2 blocks, gone by 32
        .priority(hum, 2f);                                          // weighed above an ordinary sound
```

The listener is the camera: each frame the engine moves it to the camera's final pose, shakes included, so a cut
moves what you hear on the frame it happens. A test or a host without a camera system places it by hand with
`audio.listener().set(...)`.

At most 64 voices play at once. Each frame every sound is weighed by its priority times its volume, and the loudest
take the voices. A looping sound that loses its voice, or leaves its range, goes virtual: its time runs on, and it
comes back where it would have been. A one-shot that loses its voice ends.

A concurrency group caps how many of a kind play at once, and says which go:

```java
CgSoundConcurrency sparks = new CgSoundConcurrency(8, CgSoundConcurrency.Rule.STOP_OLDEST);
long id = audio.play(spark, CgSoundClass.SFX, 0.6f);
audio.activeSounds().group(id, sparks);
CgSoundConcurrency perPlayer = new CgSoundConcurrency(1, CgSoundConcurrency.Rule.STOP_OLDEST).limitToOwner(true);
audio.activeSounds().group(shout, perPlayer).owner(shout, player);   // one shout per player
```

The rules are `PREVENT_NEW`, `STOP_OLDEST`, `STOP_FARTHEST`, `STOP_QUIETEST` and `STOP_LOWEST_PRIORITY`.

## Distance, direction and walls

A placed sound's `CgSoundAttenuation` says what distance does to it, with Unreal's attenuation settings and
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

- **Panning** is Unreal's for stereo: equal power between speakers at 30° right and 30° left. A sound ahead is
  centred; one at your side leans that way, not fully; one behind is centred.
- **Occlusion** traces a ray from the sound to the listener through the level's blocks, every tenth of a second of
  play. Muffled, its low-pass and volume move to the settings' over a tenth of a second, and back once clear.
- **Reverb**: each placed sound sends some of itself to the `reverb` submix, from 30% near to 95% at its falloff's
  end (`reverbWet`, `reverbDistance`, `reverbSend(false)`). The submix runs a room reverb, `CgReverb`.
- **Doppler** reads the sound's movement between frames and the listener's.
- A sound with no attenuation, or not placed, is heard everywhere at full volume, unpanned.

## Mixes

A sound mix adjusts classes, fading in when pushed and out when popped:

```java
CgSoundMix duck = new CgSoundMix(0.3f, 1f)                           // fade in over 0.3 s, out over 1 s
        .adjust(CgSoundClass.MUSIC, 0.3f, 1f)                        // music to 30%, pitch unchanged
        .adjust(CgSoundClass.AMBIENT, 0.5f, 1f);
audio.push(duck);
audio.pop(duck);
```

An adjustment applies to the class and every class within it. Two mixes on one class multiply.

A mix can hush Minecraft's own sounds too, by their category in the game's sound settings, faded with the mix:

```java
CgSoundMix cinematic = new CgSoundMix(0.3f, 1f)
        .duckHost(CgHostVolume.Category.BLOCKS, 0.2f)                // the game's block sounds to 20%
        .duckHost(CgHostVolume.Category.HOSTILE, 0.2f)
        .duckHost(CgHostVolume.Category.WEATHER, 0.3f);
```

The player's sliders are never touched: the game's sounds come back when the mix pops, and when the engine closes.

## Subtitles

A sound can say what it is at the bottom of the screen. Queue a line for a
sound as you play it:

```java
long id = audio.play(line, CgSoundClass.VOICE, 1f);
audio.subtitles().queue(id, "Kamehameha!", line.seconds());
```

Or several, each from its time into the sound until the next:

```java
audio.subtitles().queue(id, CgSubtitles.DEFAULT_PRIORITY, line.seconds(),
        List.of(new CgSubtitles.Cue("Kame...", 0f), new CgSubtitles.Cue("...hameha!", 1.4f)));
```

Minecraft's own sounds bring their subtitles with them, in the player's language: an explosion played through the
engine reads *Explosion*, where the version's `sounds.json` names one (1.9 and later).

- One line shows at a time: the highest priority (`CgSubtitles.DEFAULT_PRIORITY` unless you say), the latest queued
  among equals. A line goes when its sound ends or is stopped, and follows the sound's own playback: a pause or a
  hitch never puts it out of step.
- Shown only while the player's **Subtitles** setting is on (`CgGraphicsSettings.SUBTITLES`, off by default, as
  Minecraft's *Show Subtitles*); queued either way, so turning it on mid-move shows the line under way.
- Drawn over the HUD (`CgRenderStage.HUD`), never Minecraft's own subtitle box.

### Lines in a sequence

A sequence captions its voices with a `subtitle` track. A section's `text` is a string id, `<ns>:text/<id>`, held by
the kit's `strings/<locale>.yaml`: one file per language, read in the player's (`en_us`, `fr_fr`), with `en_us` behind
it. A line may name its speaker, a string of its own. An open end lasts the `sound` section it names:

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

The line follows the sequence's clock: a pause holds it, a seek joins it at its age. A section's `speaker` (a string
id) replaces the string's own.

### A game's look

A kit's `subtitle_style.yaml` draws its lines as the game does: its own fonts, outline, band and fades, in its UI
units scaled from `reference_height` with the screen. A kit without one draws in Unreal's canvas look (13 px, white, a
1 px black outline, centred near the bottom).

```yaml
# subtitle_style.yaml
_v: 1
reference_height: 1080
line: { font: mykit:fonts/Line.otf, size: 20, outline: { size: 3, colour: 0xFF000000 },
        anchor: [0.5, 0.5], alignment: [0.5, 0], offset: [0, 328] }
name: { font: mykit:fonts/Name.otf, size: 22, colour: 0xFF000000, outline: { size: 3, colour: 0xFFFFFFFF },
        anchor: [0.5, 0.5], offset: [-425, 273] }
band: { colour: 0x80030305, anchor: [0.5, 0.5], height: 200, offset: [0, 330] }
fade_in: { band: [[0, 0], [0.067, 1]], text: [[0.167, 0], [0.3, 1]] }
fade_out: { band: [[0.033, 1], [0.233, 0]], text: [[0, 1], [0.1, 0]] }
```

- A size is in points at 96 DPI (Slate's); everything else in UI units. A text block sits at `anchor` on the screen
  (0 to 1), moved by `offset`, less `alignment` of its own size.
- One line shows at a time: a new one waits for the last to fade out.
- A file that does not validate fails the kit's registration, naming each issue.

## Submixes

Every voice mixes into a submix, and the submixes into each other: `world` (with `reverb` inside it), `ui` and
`music`, all into `main`. A submix runs its effects over what reached it:

```java
CgLowPass muffle = new CgLowPass(800);                               // hertz
audio.submix("world").effect(muffle);                                // the world's sounds, muffled
muffle.cutoff(20000);                                                // open again
audio.submix("world").remove(muffle).volume(0.5f);
```

An effect is a `CgSubmixEffect`: it runs on the audio thread, in place over the buffer, and allocates nothing.

## Playing a sound you make

```java
CgAudioEngine audio = CgWorld.client().add(new CgAudioEngine());   // once, on a client
CgAudioTone tone = new CgAudioTone(440, 0.25f, 0);                 // A4 at a quarter of full scale, until stopped
audio.play(tone, 1f);
audio.gain(tone, 0.5f);
audio.stop(tone);
```

A sound is a `CgAudioSource`: it adds its samples into each buffer the mixer renders.

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

## Starting on a sample

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

- A timed start plays about 85 ms after its time, the length of the output's queue, and stays exact to the sample.
- It stays exact while a frame is shorter than about 64 ms (above 16 frames a second). A longer frame's sounds start
  as soon as they can.
- Across a montage section looping or handing on, the times stay on the play's own timeline.

## Rendering without a device

The mixer is plain Java over float buffers, so a test hears nothing and checks everything:

```java
CgAudioMixer mixer = new CgAudioMixer(48000, 2, 512, 64);
mixer.start(new CgAudioTone(1000, 0.5f, 0), 1f, true);
float[] buffer = new float[512 * 2];
mixer.render(buffer);                                                // 512 interleaved stereo frames in [-1, 1]
mixer.start(click, 1f, true, mixer.rendered() + 1000);               // 1000 frames into the next buffers
```

## Things to know

- Everything here is called on the frame thread. A play takes its voice at the next frame's update, and lands within
  one buffer of it (about 11 ms). Until then `activeSounds().voice(id)` is null.
- An id stays valid until its sound ends; a stale id is ignored. A play past 512 sounds at once returns 0.
- `render` runs on the audio thread: add into the buffer from its `offset`, never overwrite it, and allocate
  nothing.
- At most 64 voices play at once. A `CgAudioSource` played directly skips the classes and the weighing: it is
  refused past the 64th.
- The output holds 85 ms of sound, so a garbage collection pause does not cut it.
- If the output device goes, the engine opens it again every second. Sounds wait meanwhile.
- A dedicated server plays nothing: `CgAudioEngine.active()` is false there.
