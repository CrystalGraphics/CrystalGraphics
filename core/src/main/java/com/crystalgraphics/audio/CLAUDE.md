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

## Rules

- **Unreal decides behaviour**: each piece cites the `AudioMixer` source it follows.
- **The frame thread never touches the mixer**: it posts commands, and the audio thread drains them each buffer.
- **The audio thread allocates nothing and never blocks** on I/O or a lock: what it plays is ready before it asks.
- **A dedicated server plays nothing**: with no `CgAudioOutput` the engine starts no thread.
- **The host's own sounds stay the host's**: nothing here calls a host's sound engine. Its sliders arrive through
  `platform.audio.CgHostVolume`, its sound files through `CgResourceService`.
