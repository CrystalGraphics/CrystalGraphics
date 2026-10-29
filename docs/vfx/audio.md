# Audio

The engine plays its own sound: a mixer on an audio thread of its own, writing to an output stream beside the
host's. On Minecraft it opens its own output on the device the game plays to, follows the game's master volume
slider, and holds while the game is paused. Minecraft's own sounds are left exactly as they are.

## Playing a sound

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
audio.play(click, 1f, clock.now() + 0.5);                            // half a second from this frame, to the sample
```

A sequence's events are heard together at the end of the frame that crossed them. Each knows how long before the
frame's end it fell, so its sound starts where the event was, not where the frame ended:

```java
sequencer.listen(CgSequenceEventListener.Side.OURS, (player, phase, event, args) ->
        audio.play(click, 1f, audio.clock().now() - sequencer.heardSecondsAgo()));
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

- `play`, `stop` and `gain` are called on the frame thread. They are queued and land within one buffer (about 11 ms).
- `render` runs on the audio thread: add into the buffer from its `offset`, never overwrite it, and allocate
  nothing.
- At most 64 voices play at once; a start past that is refused.
- The output holds 85 ms of sound, so a garbage collection pause does not cut it.
- If the output device goes, the engine opens it again every second. Sounds wait meanwhile.
- A dedicated server plays nothing: `CgAudioEngine.active()` is false there.
