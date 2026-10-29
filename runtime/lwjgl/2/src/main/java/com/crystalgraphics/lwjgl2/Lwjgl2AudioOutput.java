package com.crystalgraphics.lwjgl2;

import java.nio.ShortBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;

import com.crystalgraphics.platform.audio.CgAudioOutput;
import com.crystalgraphics.platform.audio.CgAudioStream;

/**
 * {@link CgAudioOutput} on LWJGL 2's OpenAL: one streaming source in the host's own context, fed on the audio thread.
 * LWJGL 2 has no {@code ALC_EXT_thread_local_context}, and its {@code alcMakeContextCurrent} is process-wide, so a
 * context of our own would move the host's sound thread onto it. OpenAL Soft locks a context per call, so the two
 * threads share it safely; the host's sources are never touched.
 *
 * <pre>{@code
 * CgPlatform.provide(CgAudioOutput.SERVICE, new Lwjgl2AudioOutput());
 * }</pre>
 *
 * <ul>
 *   <li>Opens nothing until the host's sound system has made its context ({@code AL.isCreated()}).</li>
 *   <li>The host restarting its sound (a resource reload, a device change) destroys the context: the stream answers
 *       false, and the engine opens it again in the new one.</li>
 * </ul>
 */
public final class Lwjgl2AudioOutput implements CgAudioOutput {

    @Override
    public CgAudioStream open(Format format) {
        if (!AL.isCreated()) return null;
        return new Stream(format);
    }

    private static final class Stream implements CgAudioStream {
        private final Format format;
        private final int source;
        private final int[] buffers, idle;
        private int idleCount;
        private final ShortBuffer pcm;
        private final int format16;
        private boolean started;
        private int underruns;
        private String lost = "playing";

        Stream(Format format) {
            this.format = format;
            format16 = format.channels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            source = AL10.alGenSources();
            buffers = new int[format.buffers()];
            idle = new int[format.buffers()];
            for (int i = 0; i < buffers.length; i++) idle[idleCount++] = buffers[i] = AL10.alGenBuffers();
            pcm = BufferUtils.createShortBuffer(format.frames() * format.channels());
        }

        private boolean alive() {
            return AL.isCreated() && AL10.alIsSource(source);
        }

        @Override
        public int free() {
            // Gone: one write, which answers false.
            if (!alive()) return 1;
            return idleCount + AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        }

        @Override
        public boolean write(float[] interleaved) {
            if (!alive()) {
                lost = "the host's sound system restarted";
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
            return alive() ? AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED) * format.bufferSeconds() : 0;
        }

        @Override
        public void close() {
            if (!alive()) return;
            AL10.alSourceStop(source);
            AL10.alSourcei(source, AL10.AL_BUFFER, 0);
            AL10.alDeleteSources(source);
            for (int b : buffers) AL10.alDeleteBuffers(b);
            AL10.alGetError();
        }
    }
}
