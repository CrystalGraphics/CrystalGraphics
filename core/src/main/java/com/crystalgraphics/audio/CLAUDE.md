# audio — the engine's sound, as Unreal's AudioMixer

A mixer on an audio thread of its own, fed from the frame by a command queue, writing to the host's output stream
(`platform.audio.CgAudioOutput`). **Using it: `docs/vfx/audio.md`.**

```java
CgAudioEngine audio = CgWorld.client().add(new CgAudioEngine());
audio.play(new CgAudioTone(440, 0.25f, 1.0), 1f);
```

## Where things go

| Class | Owns |
|---|---|
| `CgAudioEngine` | the client world's sound: the audio thread (`FMixerDevice`'s render thread), the command queue, the host's master slider and the game's pause, posted each `SUBMIT` |
| `CgAudioMixer` | one buffer at a time: voices summed, the main gain, the clamp (`OnProcessAudioStream`). Plain Java: tests render it into an array |
| `CgAudioClock` | the frame's audio time (Quartz's clock): a start at a time lands on its sample |
| `CgAudioSource` | what a voice plays: samples added into the buffer, from a frame offset, on the audio thread |
| `CgAudioTone` | a sine: the test signal |
| `CgSound`, `CgAudioSounds` | a loaded sound (`USoundWave`: resident 16-bit samples, or compressed bytes it streams from) and the loader that reads and decodes each path once |
| `CgSoundVoice`, `CgSoundStream` | a resident sound playing, resampled linearly (pooled); a streamed one, played from a ring the engine's decode thread keeps a second ahead |
| `CgActiveSounds` | every sound playing, on the frame thread (`FActiveSound`s): weighed by priority × volume, culled by group, the top 64 voiced, loops virtual (`FAudioVirtualLoop`). Talks to the engine only through its `Sink` |
| `CgSoundClass`, `CgSoundMix` | the class chain (`USoundClass`), its host slider and submix; mixes that fade class adjustments in and out (`USoundMix`) |
| `CgSoundConcurrency` | a cap per kind and the rule for what goes (`USoundConcurrency`) |
| `CgSubmix`, `CgSubmixEffect`, `CgLowPass`, `CgReverb` | the submix tree the mixer renders leaf to root, its effect chains (`FMixerSubmix`), a one-pole low-pass, a room reverb (Freeverb) on `reverb` |
| `CgSoundRef`, `CgKitSounds`, `CgHostSoundEvents` | a sound as an asset names it, compiled at load; games' cue names through each kit's `sounds.yaml` and voice lines through its cast's sheets; the host's `sounds.json` merged across packs, each event flattened at its first pick |
| `CgSubtitles`, `CgSubtitleOverlay` | the sounds' subtitles (`FSubtitleManager`): queued per sound id over its duration, the highest priority shown; drawn over the HUD stage in Unreal's canvas look |
| `CgSoundAttenuation`, `CgAudioListener` | what distance does to a placed sound (`FSoundAttenuationSettings`); the listener, moved to the camera's final pose at `SUBMIT`, and the stereo pan law (`Get3DChannelMap`) |

## Rules

- **Unreal decides behaviour**: each piece cites the `AudioMixer` source it follows.
- **The frame thread never touches the mixer**: it posts commands, and the audio thread drains them each buffer.
- **The audio thread allocates nothing and never blocks** on I/O or a lock: what it plays is ready before it asks.
- **Decoding is never on the audio thread or the frame's**: streams fill on the engine's decode thread. A `CgJob`
  starts only on a loop's thread, and the audio thread is none.
- **The master slider is the mixer's main gain**, never a sound's: a class volume multiplies only its own slider.
- **A sound's state is frame-thread arrays**, never an object per play: `CgActiveSounds` is sized once.
- **A dedicated server plays nothing**: with no `CgAudioOutput` the engine starts no thread.
- **Nothing here calls a host's sound engine**: even Minecraft's own sound events play through this mixer, read from
  the packs' `sounds.json` through `CgResourceService`. The host's sliders arrive through `platform.audio.CgHostVolume`;
  a mix's hush on the host's own sounds leaves through `CgHostDuck`, posted only where it moved.
- **Sequences and cues find the engine in their world**: `CgSequencer.audio()`, the client world's for cues. Nothing
  adds one by default; whoever runs sequences adds it beside the sequencer.
