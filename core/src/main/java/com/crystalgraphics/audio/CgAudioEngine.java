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
 * }</pre>
 *
 * <ul>
 *   <li>Frame thread only for {@link #play}, {@link #stop} and {@link #gain}: they are queued, not applied, and land
 *       within one buffer.</li>
 *   <li>Where the output is {@link CgAudioOutput#NONE} (a dedicated server) it starts no thread and plays nothing.</li>
 *   <li>A stream that cannot open, or whose device goes, is tried again every second; sounds wait meanwhile.</li>
 *   <li>A full queue drops the command and counts it ({@link #dropped()}).</li>
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
    private static final int QUEUE = 1024;
    private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);

    @Nullable
    private final CgAudioOutput given;
    private final int[] kinds = new int[QUEUE];
    private final CgAudioSource[] sources = new CgAudioSource[QUEUE];
    private final float[] values = new float[QUEUE];
    private final boolean[] flags = new boolean[QUEUE];
    /** The frame thread writes at head, the audio thread reads at tail. */
    private final AtomicInteger head = new AtomicInteger(), tail = new AtomicInteger();

    private CgAudioOutput output = CgAudioOutput.NONE;
    private CgAudioMixer mixer;
    private CgTickFunction submit;
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

    /** On the host's {@link CgAudioOutput}. */
    public CgAudioEngine() {
        this(null);
    }

    /** On {@code output}: the harness's device, or a test's. */
    public CgAudioEngine(@Nullable CgAudioOutput output) {
        this.given = output;
    }

    @Override
    protected void initialize() {
        output = given != null ? given : CgPlatform.get(CgAudioOutput.SERVICE);
        if (output == CgAudioOutput.NONE) return;
        mixer = new CgAudioMixer(FORMAT.sampleRate(), FORMAT.channels(), FORMAT.frames(), MAX_VOICES);
        submit = world().schedule().add(CgTickGroup.SUBMIT, "crystalgraphics:audio", this::submit).whilePaused(true);
        running = true;
        // A thread of its own, as Unreal's render thread: a job worker would wait behind the frame's jobs.
        thread = new Thread(this::run, "crystalgraphics-audio");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        thread.start();
    }

    @Override
    protected void deinitialize() {
        running = false;
        if (submit != null) submit.close();
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
        post(PLAY, source, gain, true);
    }

    /** Plays a sound the game's pause leaves playing: a menu's. */
    public void playUnpaused(CgAudioSource source, float gain) {
        post(PLAY, source, gain, false);
    }

    public void stop(CgAudioSource source) {
        post(STOP, source, 0, false);
    }

    public void gain(CgAudioSource source, float gain) {
        post(GAIN, source, gain, false);
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
            post(MAIN, null, main, false);
        }
        boolean paused = world().paused();
        if (paused != postedPaused) {
            postedPaused = paused;
            post(PAUSE, null, 0, paused);
        }
    }

    private void post(int kind, @Nullable CgAudioSource source, float value, boolean flag) {
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
                case PLAY -> mixer.start(s, values[i], flags[i]);
                case STOP -> mixer.stop(s);
                case GAIN -> mixer.gain(s, values[i]);
                case MAIN -> mixer.main(values[i]);
                case PAUSE -> mixer.paused(flags[i]);
                default -> { }
            }
            tail.lazySet(t + 1);
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
