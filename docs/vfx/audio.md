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
