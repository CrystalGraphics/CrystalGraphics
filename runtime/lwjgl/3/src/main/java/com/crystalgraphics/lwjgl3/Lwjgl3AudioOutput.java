package com.crystalgraphics.lwjgl3;

import java.nio.ShortBuffer;
import java.util.function.Supplier;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTDisconnect;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.system.MemoryUtil;

import com.crystalgraphics.platform.audio.CgAudioOutput;
import com.crystalgraphics.platform.audio.CgAudioStream;

/**
 * {@link CgAudioOutput} on LWJGL 3's OpenAL: a device and context of our own, current on the audio thread alone
 * ({@code ALC_EXT_thread_local_context}), so the host's own OpenAL context, sources and capabilities are never
 * touched. One streaming source plays the engine's buffers as 16-bit PCM.
 *
 * <pre>{@code
 * CgPlatform.provide(CgAudioOutput.SERVICE, new Lwjgl3AudioOutput(() -> options.soundDevice()));   // null: the default
 * }</pre>
 *
 * <ul>
 *   <li>A device name that no longer opens falls back to the default device; no device at all opens nothing.</li>
 *   <li>Without {@code ALC_EXT_thread_local_context} it throws, naming it: making our context current process-wide
 *       would move the host's sound thread onto it.</li>
 *   <li>Written against LWJGL 3.1.6's OpenAL classes, the oldest a client ships: no {@code SOFTDeviceClock}.</li>
 * </ul>
 */
public final class Lwjgl3AudioOutput implements CgAudioOutput {

    private final Supplier<String> device;

    /** On {@code device}'s named output, read as each stream opens; null or empty for the system's default. */
    public Lwjgl3AudioOutput(Supplier<String> device) {
        this.device = device;
    }

    @Override
    public CgAudioStream open(Format format) {
        String name = device.get();
        long dev = name == null || name.isEmpty() ? 0 : ALC10.alcOpenDevice(name);
        if (dev == 0) dev = ALC10.alcOpenDevice((CharSequence) null);
        if (dev == 0) return null;
        ALCCapabilities alc = ALC.createCapabilities(dev);
        if (!alc.ALC_EXT_thread_local_context) {
            ALC10.alcCloseDevice(dev);
            throw new IllegalStateException("This OpenAL has no ALC_EXT_thread_local_context: the engine's sound cannot "
                    + "have a context of its own beside the host's");
        }
        long context = ALC10.alcCreateContext(dev, new int[] {ALC10.ALC_FREQUENCY, format.sampleRate(), 0});
        if (context == 0) {
            ALC10.alcCloseDevice(dev);
            return null;
        }
        EXTThreadLocalContext.alcSetThreadContext(context);
        // Current on this thread, so the capabilities are this thread's alone: AL.createCapabilities sets them so.
        AL.createCapabilities(alc);
        return new Stream(format, dev, context, alc.ALC_EXT_disconnect);
    }

    /** One streaming source and its buffers, in our own context. */
    private static final class Stream implements CgAudioStream {
        private final Format format;
        private final long device, context;
        private final boolean disconnect;
        private final int source;
        private final int[] buffers, idle;
        private int idleCount;
        private final ShortBuffer pcm;
        private final int format16;
        private boolean started;
        private int underruns;
        private String lost = "playing";

        Stream(Format format, long device, long context, boolean disconnect) {
            this.format = format;
            this.device = device;
            this.context = context;
            this.disconnect = disconnect;
            format16 = format.channels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            source = AL10.alGenSources();
            buffers = new int[format.buffers()];
            idle = new int[format.buffers()];
            for (int i = 0; i < buffers.length; i++) idle[idleCount++] = buffers[i] = AL10.alGenBuffers();
            pcm = MemoryUtil.memAllocShort(format.frames() * format.channels());
        }

        @Override
        public int free() {
            return idleCount + AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        }

        @Override
        public boolean write(float[] interleaved) {
            if (disconnect && ALC10.alcGetInteger(device, EXTDisconnect.ALC_CONNECTED) == 0) {
                lost = "the device disconnected";
                return false;
            }
            int buffer = idleCount > 0 ? idle[--idleCount] : AL10.alSourceUnqueueBuffers(source);
            pcm.clear();
            for (int i = 0, n = format.frames() * format.channels(); i < n; i++) pcm.put((short) (interleaved[i] * 32767f));
            pcm.flip();
            AL10.alBufferData(buffer, format16, pcm, format.sampleRate());
            AL10.alSourceQueueBuffers(source, buffer);
            if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                // A source that ran out of queued buffers stopped: an underrun.
                if (started) underruns++;
                started = true;
                AL10.alSourcePlay(source);
            }
            int error = AL10.alGetError();
            if (error == AL10.AL_NO_ERROR) return true;
            lost = "OpenAL error 0x" + Integer.toHexString(error) + " queueing buffer " + buffer;
            return false;
        }

        /** Why it can no longer play, once {@link #write} answered false. */
        @Override
        public String toString() {
            return lost;
        }

        @Override
        public int underruns() {
            return underruns;
        }

        @Override
        public double latency() {
            return AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED) * format.bufferSeconds();
        }

        @Override
        public void close() {
            AL10.alSourceStop(source);
            AL10.alSourcei(source, AL10.AL_BUFFER, 0);
            AL10.alDeleteSources(source);
            for (int b : buffers) AL10.alDeleteBuffers(b);
            AL10.alGetError();
            EXTThreadLocalContext.alcSetThreadContext(0);
            ALC10.alcDestroyContext(context);
            ALC10.alcCloseDevice(device);
            MemoryUtil.memFree(pcm);
        }
    }
}
