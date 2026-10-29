package com.crystalgraphics.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;

import org.junit.Test;

import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.platform.audio.CgAudioDecoder;
import com.crystalgraphics.platform.audio.CgAudioOutput;
import com.crystalgraphics.platform.audio.CgAudioStream;

/** Sounds from files, through a decoder: resident and resampled, streamed ahead of their voice on the decode thread. */
public class CgSoundTest {

    private static final int OUT = 48000;

    /**
     * A decoder whose "files" are sines: four bytes of a ByteBuffer give its rate, then its seconds and its hertz. A
     * read sleeps {@code slow} milliseconds, a decoder too slow for real time.
     */
    private static CgAudioDecoder sines(int slow) {
        return ogg -> {
            ByteBuffer b = ogg.duplicate();
            int rate = b.getInt(), seconds = b.getInt(), hertz = b.getInt();
            return new CgAudioDecoder.Stream() {
                long at;

                @Override public int channels() { return 1; }
                @Override public int sampleRate() { return rate; }
                @Override public long frames() { return (long) rate * seconds; }
                @Override public int read(float[] out, int offset, int frames) {
                    if (slow > 0) {
                        try {
                            Thread.sleep(slow);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    int n = (int) Math.min(frames, frames() - at);
                    for (int i = 0; i < n; i++, at++) out[offset + i] = (float) Math.sin(2 * Math.PI * hertz * at / rate) * 0.5f;
                    return n;
                }
                @Override public void seek(long frame) { at = frame; }
                @Override public void close() { }
            };
        };
    }

    private static ByteBuffer sine(int rate, int seconds, int hertz) {
        ByteBuffer b = ByteBuffer.allocate(12);
        b.putInt(rate).putInt(seconds).putInt(hertz).flip();
        return b;
    }

    /** A short sound is resident, and plays at 48 kHz at its own pitch and length though written at 44.1 kHz. */
    @Test
    public void aShortSoundIsResidentAndResampledToItsPitchAndLength() {
        CgSound s = new CgAudioSounds(sines(0)).decode("test:sine", sine(44100, 2, 441), false);
        assertFalse(s.streamed());
        CgSoundVoice voice = new CgSoundVoice(s, OUT, 1f, false);
        float[] buffer = new float[512];
        int crossings = 0, rendered = 0;
        float last = 0;
        while (voice.render(buffer, 0, 512, 1, OUT, 1f)) {
            for (int i = 0; i < 512; i++) {
                if (rendered + i < OUT && last < 0 && buffer[i] >= 0) crossings++;
                last = buffer[i];
                buffer[i] = 0;
            }
            rendered += 512;
        }
        assertEquals("441 periods in its first second", 441, crossings, 1);
        assertEquals("two seconds at 48 kHz", 2 * OUT, rendered, 512);
    }

    /** Over ten seconds, or asked, a sound streams. */
    @Test
    public void aLongSoundStreams() {
        CgAudioSounds sounds = new CgAudioSounds(sines(0));
        assertTrue(sounds.decode("test:long", sine(44100, 30, 220), false).streamed());
        assertTrue(sounds.decode("test:asked", sine(44100, 2, 220), true).streamed());
    }

    private static CgAudioOutput realTime() {
        return format -> new CgAudioStream() {
            int queued;
            long last = System.nanoTime();

            @Override public int free() {
                long now = System.nanoTime();
                int played = (int) ((now - last) / (long) (format.bufferSeconds() * 1e9));
                if (played > 0) {
                    queued = Math.max(0, queued - played);
                    last = now;
                }
                return format.buffers() - queued;
            }
            @Override public boolean write(float[] interleaved) {
                queued++;
                return true;
            }
            @Override public int underruns() { return 0; }
            @Override public double latency() { return queued * format.bufferSeconds(); }
            @Override public void close() { }
        };
    }

    /** {@code sound} played as music: its voice is given on the next frame. */
    private static CgSoundStream stream(CgWorld world, CgAudioEngine audio, CgSound sound) {
        long id = audio.play(sound, CgSoundClass.MUSIC, 1f);
        world.frame(null);
        return (CgSoundStream) audio.activeSounds().voice(id);
    }

    /** A long stream plays in real time with its ring never dry, decoded on the decode thread while the frame stalls. */
    @Test
    public void aStreamPlaysInRealTimeFedByTheDecodeThread() throws InterruptedException {
        CgWorld world = new CgWorld();
        CgAudioEngine audio = world.add(new CgAudioEngine(realTime(), sines(0)));
        CgSound long30 = audio.sounds().decode("test:long", sine(44100, 30, 220), false);
        CgSoundStream s = stream(world, audio, long30);
        // The frame thread stalls; the decode thread keeps the stream fed.
        Thread.sleep(200);
        long until = System.currentTimeMillis() + 3000;
        while (s.played() < 1.5 && System.currentTimeMillis() < until) Thread.sleep(10);
        assertTrue("played " + s.played() + " s", s.played() >= 1.5);
        assertEquals("never dry", 0, s.starved());
        world.close();
    }

    /** A decoder slower than real time leaves the ring dry: counted, not hidden. */
    @Test
    public void aDryRingIsCounted() throws InterruptedException {
        CgWorld world = new CgWorld();
        CgAudioEngine audio = world.add(new CgAudioEngine(realTime(), sines(400)));
        CgSound long30 = audio.sounds().decode("test:slow", sine(44100, 30, 220), false);
        CgSoundStream s = stream(world, audio, long30);
        long until = System.currentTimeMillis() + 3000;
        while (s.starved() == 0 && System.currentTimeMillis() < until) Thread.sleep(10);
        assertTrue("starved " + s.starved(), s.starved() > 0);
        world.close();
    }
}
