package com.crystalgraphics.audio;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorldSubsystem;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.audio.CgAudioDecoder;
import com.crystalgraphics.platform.audio.CgAudioOutput;
import com.crystalgraphics.platform.audio.CgAudioStream;
import com.crystalgraphics.platform.audio.CgHostVolume;

/**
 * The engine's sound, for a client world: a mixer on an audio thread of its own, fed from the frame by a command
 * queue, writing to the host's output. Unreal's {@code FMixerDevice} with its render thread and
 * {@code AudioRenderThreadCommand} queue. The main gain follows the host's master slider, and sounds that pause with
 * the game hold while the world is paused.
 *
 * <pre>{@code
 * CgAudioEngine audio = CgWorld.client().add(new CgAudioEngine());   // on the host's CgAudioOutput
 * CgAudioTone tone = new CgAudioTone(440, 0.25f, 0);
 * audio.play(tone, 1f);
 * audio.stop(tone);
 * audio.play(click, 1f, audio.clock().now() + 0.25);              // a quarter second on, to the sample
 * }</pre>
 *
 * A sound from a file, resident or streamed, in its class, weighed for a voice with every other:
 *
 * <pre>{@code
 * CgSound theme = audio.sounds().load("mymod:sounds/theme.ogg", true);
 * long id = audio.play(theme, CgSoundClass.MUSIC, 0.8f);
 * audio.activeSounds().at(id, x, y, z).range(id, 48);
 * audio.activeSounds().stop(id);
 * }</pre>
 *
 * <ul>
 *   <li>Frame thread only for {@link #play}, {@link #stop} and {@link #gain}: they are queued, not applied, and land
 *       within one buffer.</li>
 *   <li>Where the output is {@link CgAudioOutput#NONE} (a dedicated server) it starts no thread and plays nothing.</li>
 *   <li>A stream that cannot open, or whose device goes, is tried again every second; sounds wait meanwhile.</li>
 *   <li>A full queue drops the command and counts it ({@link #dropped()}).</li>
 *   <li>A streamed sound decodes on the engine's decode thread, never the frame's or the audio thread's.</li>
 *   <li>A {@code CgAudioSource} played as itself skips the voice limit and the classes: a test tone's way. A sound
 *       goes through {@link #play(CgSound, CgSoundClass, float)}.</li>
 * </ul>
 */
public final class CgAudioEngine extends CgWorldSubsystem {

    private static final Logger LOGGER = LogManager.getLogger("CgAudio");

    /**
     * Stereo at 48 kHz, 512 frames a buffer, 8 queued: 85 ms, longer than a client's G1 pauses, since a
     * stop-the-world pause stops this thread too.
     */
    public static final CgAudioOutput.Format FORMAT = new CgAudioOutput.Format(48000, 2, 512, 8);
    public static final int MAX_VOICES = 64;

    private static final int PLAY = 0, STOP = 1, GAIN = 2, MAIN = 3, PAUSE = 4;
    /** A start with no due frame: the next buffer. */
    private static final long NOW = Long.MIN_VALUE;
    private static final int QUEUE = 1024;
    private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** At most this many streams play at once. */
    public static final int MAX_STREAMS = 32;

    @Nullable
    private final CgAudioOutput given;
    @Nullable
    private final CgAudioDecoder givenDecoder;
    private CgAudioSounds sounds;
    private CgActiveSounds activeSounds;
    /** The submix tree, parents before children: {@code main}, {@code world}, {@code reverb}, {@code ui}, {@code music}. */
    private final CgSubmix[] submixes = new CgSubmix[5];
    private final CgSoundVoice[] voices = new CgSoundVoice[MAX_VOICES * 2];
    private double listenerX, listenerY, listenerZ;
    private CgAudioDecoder decoder = CgAudioDecoder.NONE;
    /** The streams the decode thread keeps filled; the frame thread adds, the decode thread drops. */
    private final CgSoundStream[] streams = new CgSoundStream[MAX_STREAMS];
    @Nullable
    private Thread decodeThread;
    private final int[] kinds = new int[QUEUE];
    private final CgAudioSource[] sources = new CgAudioSource[QUEUE];
    private final float[] values = new float[QUEUE];
    private final boolean[] flags = new boolean[QUEUE];
    private final long[] frames = new long[QUEUE];
    private final int[] routes = new int[QUEUE];
    /** The frame thread writes at head, the audio thread reads at tail. */
    private final AtomicInteger head = new AtomicInteger(), tail = new AtomicInteger();

    private CgAudioOutput output = CgAudioOutput.NONE;
    private CgAudioMixer mixer;
    private CgTickFunction submit, begin;
    private CgAudioClock clock;
    /** Frames the mixer has rendered: the write head the clock follows. */
    private volatile long written;
    @Nullable
    private Thread thread;
    private volatile boolean running;
    private volatile int underruns, opens;
    private volatile long buffers;
    /** The longest gap between two of the audio thread's wakes, in nanoseconds: a GC pause, or the OS. */
    private volatile long longestStall;
    private int dropped;
    private float postedMain = -1f;
    private boolean postedPaused;

    /** On the host's {@link CgAudioOutput} and {@link CgAudioDecoder}. */
    public CgAudioEngine() {
        this(null, null);
    }

    /** On {@code output}, and the host's decoder. */
    public CgAudioEngine(@Nullable CgAudioOutput output) {
        this(output, null);
    }

    /** On {@code output} and {@code decoder}: the harness's device, or a test's. */
    public CgAudioEngine(@Nullable CgAudioOutput output, @Nullable CgAudioDecoder decoder) {
        this.given = output;
        this.givenDecoder = decoder;
    }

    @Override
    protected void initialize() {
        output = given != null ? given : CgPlatform.get(CgAudioOutput.SERVICE);
        if (output == CgAudioOutput.NONE) return;
        decoder = givenDecoder != null ? givenDecoder : CgPlatform.get(CgAudioDecoder.SERVICE);
        sounds = new CgAudioSounds(decoder);
        mixer = new CgAudioMixer(FORMAT.sampleRate(), FORMAT.channels(), FORMAT.frames(), MAX_VOICES);
        submixes[0] = new CgSubmix("main", null, 0);
        submixes[1] = new CgSubmix("world", submixes[0], 1);
        submixes[2] = new CgSubmix("reverb", submixes[1], 2);
        submixes[3] = new CgSubmix("ui", submixes[0], 3);
        submixes[4] = new CgSubmix("music", submixes[0], 4);
        mixer.submixes(submixes);
        for (int i = 0; i < voices.length; i++) voices[i] = new CgSoundVoice();
        activeSounds = new CgActiveSounds(MAX_VOICES, new Sink());
        // Left alone within two buffers of the write head. A frame hears its events at its end, up to a frame after
        // they fell: a lead of the queue keeps their starts ahead of the head for frames up to the queue less the band.
        clock = new CgAudioClock(FORMAT.sampleRate(), (long) FORMAT.buffers() * FORMAT.frames(), 2L * FORMAT.frames(), () -> written);
        begin = world().schedule().add(CgTickGroup.BEGIN, "crystalgraphics:audio.clock", dt -> clock.advance(dt)).whilePaused(true);
        submit = world().schedule().add(CgTickGroup.SUBMIT, "crystalgraphics:audio", this::submit).whilePaused(true);
        running = true;
        // A thread of its own, as Unreal's render thread: a job worker would wait behind the frame's jobs.
        thread = new Thread(this::run, "crystalgraphics-audio");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        thread.start();
        // Unreal decodes on audio tasks of its own: a CgJob starts only on a loop thread, which the audio thread is not.
        decodeThread = new Thread(this::decode, "crystalgraphics-audio-decode");
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    @Override
    protected void deinitialize() {
        running = false;
        if (submit != null) submit.close();
        if (begin != null) begin.close();
        if (decodeThread != null) LockSupport.unpark(decodeThread);
        if (thread != null) {
            LockSupport.unpark(thread);
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Whether it plays: false on a server, where the host gave no output, or after its thread failed. */
    public boolean active() {
        return running;
    }

    public void play(CgAudioSource source, float gain) {
        post(PLAY, source, gain, true, NOW);
    }

    /** Plays {@code source} starting at audio time {@code seconds} ({@link #clock()}), on its sample. */
    public void play(CgAudioSource source, float gain, double seconds) {
        post(PLAY, source, gain, true, clock.frameAt(seconds));
    }

    /** Where sounds are loaded from, decoded by the host's decoder. Null where it is not {@link #active()}. */
    public CgAudioSounds sounds() {
        return sounds;
    }

    /** Plays {@code sound} in {@code soundClass} from the next update; its id, 0 where every slot is taken. */
    public long play(CgSound sound, CgSoundClass soundClass, float gain) {
        return activeSounds.play(sound, soundClass, gain, 1f, false, Double.NaN);
    }

    /** As {@link #play(CgSound, CgSoundClass, float)} at {@code pitch}, looping or not, from audio time {@code at}. */
    public long play(CgSound sound, CgSoundClass soundClass, float gain, float pitch, boolean loop, double at) {
        return activeSounds.play(sound, soundClass, gain, pitch, loop, at);
    }

    /** Every sound playing: what an id is placed, ranged, grouped, adjusted and stopped through. */
    public CgActiveSounds activeSounds() {
        return activeSounds;
    }

    /** Fades {@code mix} in over its classes. */
    public void push(CgSoundMix mix) {
        activeSounds.push(mix);
    }

    /** Fades {@code mix} out. */
    public void pop(CgSoundMix mix) {
        activeSounds.pop(mix);
    }

    /** {@code id}'s submix: {@code main}, {@code world}, {@code reverb}, {@code ui} or {@code music}. */
    public CgSubmix submix(String id) {
        for (CgSubmix s : submixes) if (s.id().equals(id)) return s;
        throw new IllegalArgumentException("No submix " + id);
    }

    /** Where the listener stands this frame: what a placed sound's range is measured from. */
    public void listener(double x, double y, double z) {
        listenerX = x;
        listenerY = y;
        listenerZ = z;
    }

    /** What the active sounds drive: pooled voices, and the command queue. */
    private final class Sink implements CgActiveSounds.Sink {
        @Override
        public CgAudioSource voice(CgSound sound, float pitch, boolean loop, double offset) {
            if (sound.streamed()) return stream(sound, pitch, loop, offset);
            for (CgSoundVoice v : voices) if (v.idle()) return v.reset(sound, FORMAT.sampleRate(), pitch, loop, offset);
            // Every pooled voice still in the mixer's hands: the limit was passed, a new one made.
            return new CgSoundVoice().reset(sound, FORMAT.sampleRate(), pitch, loop, offset);
        }

        @Override
        public boolean ended(CgAudioSource voice) {
            return voice instanceof CgSoundVoice v ? v.idle() : voice instanceof CgSoundStream s && s.done();
        }

        @Override
        public void start(CgAudioSource voice, float gain, CgSoundClass soundClass, double at) {
            post(PLAY, voice, gain, soundClass.pausesWithGame(), Double.isNaN(at) ? NOW : clock.frameAt(at),
                    submix(soundClass.submix()).index);
        }

        @Override
        public void gain(CgAudioSource voice, float gain) {
            post(GAIN, voice, gain, false, NOW);
        }

        @Override
        public void stop(CgAudioSource voice) {
            post(STOP, voice, 0, false, NOW);
        }
    }

    private CgAudioSource stream(CgSound sound, float pitch, boolean loop, double offset) {
        CgSoundStream s = new CgSoundStream(sound, decoder, FORMAT.sampleRate(), pitch, loop, offset);
        synchronized (streams) {
            int free = -1;
            for (int i = 0; i < streams.length && free < 0; i++) if (streams[i] == null) free = i;
            if (free < 0) {
                s.close();
                throw new IllegalStateException("More than " + MAX_STREAMS + " streams at once: " + sound);
            }
            streams[free] = s;
        }
        LockSupport.unpark(decodeThread);
        return s;
    }

    /** Plays a sound the game's pause leaves playing: a menu's. */
    public void playUnpaused(CgAudioSource source, float gain) {
        post(PLAY, source, gain, false, NOW);
    }

    public void stop(CgAudioSource source) {
        post(STOP, source, 0, false, NOW);
    }

    public void gain(CgAudioSource source, float gain) {
        post(GAIN, source, gain, false, NOW);
    }

    /** The frame's audio time, what a timed {@link #play} is on. Null where it is not {@link #active()}. */
    public CgAudioClock clock() {
        return clock;
    }

    /** Underruns since the engine started, over every stream it opened. */
    public int underruns() {
        return underruns;
    }

    /** Buffers written to the output. */
    public long buffers() {
        return buffers;
    }

    /**
     * The longest the audio thread went between two wakes, in milliseconds, past the half buffer it sleeps: what the
     * output's queue must outlast. A stop-the-world GC pause stops it with every other thread.
     */
    public double longestStallMillis() {
        return longestStall / 1.0e6;
    }

    /** Streams opened: 1, unless the device went and came back. */
    public int opens() {
        return opens;
    }

    /** The main gain last posted to the mixer: the host's master slider, once a frame has run; -1 before. */
    public float main() {
        return postedMain;
    }

    /** Commands dropped by a full queue. */
    public int dropped() {
        return dropped;
    }

    /** {@code SUBMIT}: the host's master slider and the world's pause, posted when they change. */
    private void submit(float dt) {
        float main = CgPlatform.get(CgHostVolume.SERVICE).master();
        if (main != postedMain) {
            postedMain = main;
            post(MAIN, null, main, false, NOW);
        }
        boolean paused = world().paused();
        if (paused != postedPaused) {
            postedPaused = paused;
            post(PAUSE, null, 0, paused, NOW);
        }
        activeSounds.update(dt, listenerX, listenerY, listenerZ, paused, CgPlatform.get(CgHostVolume.SERVICE));
    }

    private void post(int kind, @Nullable CgAudioSource source, float value, boolean flag, long frame) {
        post(kind, source, value, flag, frame, 0);
    }

    private void post(int kind, @Nullable CgAudioSource source, float value, boolean flag, long frame, int submix) {
        if (!running) return;
        int h = head.get();
        if (h - tail.get() >= QUEUE) {
            if (dropped++ == 0) LOGGER.warn("The audio command queue is full; commands are dropped");
            return;
        }
        int i = h & (QUEUE - 1);
        kinds[i] = kind;
        sources[i] = source;
        values[i] = value;
        flags[i] = flag;
        frames[i] = frame;
        routes[i] = submix;
        head.lazySet(h + 1);
    }

    /** The audio thread's: every command posted so far, applied to the mixer. */
    private void drain() {
        int h = head.get();
        for (int t = tail.get(); t != h; t++) {
            int i = t & (QUEUE - 1);
            CgAudioSource s = sources[i];
            sources[i] = null;
            switch (kinds[i]) {
                case PLAY -> {
                    mixer.start(s, values[i], flags[i], frames[i] == NOW ? Long.MIN_VALUE : frames[i], routes[i]);
                }
                case STOP -> mixer.stop(s);
                case GAIN -> mixer.gain(s, values[i]);
                case MAIN -> mixer.main(values[i]);
                case PAUSE -> mixer.paused(flags[i]);
                default -> { }
            }
            tail.lazySet(t + 1);
        }
    }

    /** The decode thread: every stream's ring filled ahead of its voice; a finished one closed and dropped. */
    private void decode() {
        // A tenth of the ring a wake: with the ring a second long, a stall of half a second is still covered.
        long wait = TimeUnit.MILLISECONDS.toNanos(20);
        while (running) {
            for (int i = 0; i < streams.length; i++) {
                CgSoundStream s = streams[i];
                if (s == null) continue;
                if (s.done()) {
                    s.close();
                    synchronized (streams) {
                        streams[i] = null;
                    }
                    continue;
                }
                try {
                    if (s.needs()) s.fill(Integer.MAX_VALUE);
                } catch (RuntimeException e) {
                    LOGGER.error("A sound stream failed to decode; it plays no more", e);
                    s.stopDecoding();
                }
            }
            LockSupport.parkNanos(wait);
        }
        synchronized (streams) {
            for (int i = 0; i < streams.length; i++) {
                if (streams[i] != null) streams[i].close();
                streams[i] = null;
            }
        }
    }

    /** {@code RunInternal}: render until the output's queue is full, then wait half a buffer. */
    private void run() {
        float[] buffer = new float[FORMAT.frames() * FORMAT.channels()];
        long half = (long) (FORMAT.bufferSeconds() * 0.5e9);
        CgAudioStream stream = null;
        int before = 0;
        long retryAt = 0;
        long woke = System.nanoTime();
        try {
            while (running) {
                long now = System.nanoTime();
                if (stream != null && now - woke - half > longestStall) longestStall = now - woke - half;
                woke = now;
                drain();
                if (stream == null) {
                    if (System.nanoTime() >= retryAt) {
                        stream = output.open(FORMAT);
                        retryAt = System.nanoTime() + RETRY_NANOS;
                        if (stream != null) {
                            opens++;
                            before = underruns;
                            // Opening a device is no stall of play's.
                            woke = System.nanoTime();
                        }
                    }
                    if (stream == null) {
                        LockSupport.parkNanos(half * 4);
                        continue;
                    }
                }
                // free() again before each write: a stream that ran dry and restarted takes back what it had played.
                while (running && stream.free() > 0) {
                    drain();
                    mixer.render(buffer);
                    written = mixer.rendered();
                    if (!stream.write(buffer)) {
                        LOGGER.info("The audio output went ({}); opening it again", stream);
                        underruns = before + stream.underruns();
                        stream.close();
                        stream = null;
                        retryAt = System.nanoTime() + RETRY_NANOS;
                        break;
                    }
                    buffers++;
                }
                if (stream != null) underruns = before + stream.underruns();
                LockSupport.parkNanos(half);
            }
        } catch (RuntimeException e) {
            LOGGER.error("The audio thread stopped", e);
            running = false;
        } finally {
            if (stream != null) stream.close();
        }
    }
}
