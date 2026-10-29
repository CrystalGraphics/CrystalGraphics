package com.crystalgraphics.platform.audio;

import java.nio.ByteBuffer;

import com.crystalgraphics.platform.CgService;

/**
 * Decodes Ogg Vorbis held in memory, a host's own decoder: what the engine's sounds are read with. Unreal's
 * {@code ICompressedAudioInfo}. Client only.
 *
 * <pre>{@code
 * CgAudioDecoder.Stream s = CgPlatform.get(CgAudioDecoder.SERVICE).open(oggBytes);   // a direct buffer
 * float[] chunk = new float[4096 * s.channels()];
 * for (int n; (n = s.read(chunk, 0, 4096)) > 0; ) consume(chunk, n);
 * s.close();
 * }</pre>
 *
 * <pre>{@code
 * CgPlatform.provide(CgAudioDecoder.SERVICE, new StbVorbisDecoder());   // a host, on a client
 * }</pre>
 *
 * <ul>
 *   <li>{@link #open} answers null for bytes that are not Ogg Vorbis.</li>
 *   <li>The buffer must stay unchanged while a stream reads it.</li>
 *   <li>One thread at a time uses a stream.</li>
 *   <li>{@link #NONE} throws: a host with no decoder cannot load a sound, and says so.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgAudioDecoder {

    /** An open Ogg Vorbis stream, decoding as it is read. */
    interface Stream extends AutoCloseable {
        int channels();

        int sampleRate();

        /** Its length in frames. */
        long frames();

        /** Decodes up to {@code frames} interleaved frames into {@code out} from frame {@code offset}; 0 at its end. */
        int read(float[] out, int offset, int frames);

        /** Reads from frame {@code frame} next. */
        void seek(long frame);

        @Override
        void close();
    }

    /** A stream over {@code ogg}, or null where it is not Ogg Vorbis. */
    Stream open(ByteBuffer ogg);

    CgAudioDecoder NONE = ogg -> {
        throw new IllegalStateException("This host has no Ogg Vorbis decoder");
    };

    CgService<CgAudioDecoder> SERVICE = CgService.of("crystalgraphics:audio_decoder", NONE);
}
