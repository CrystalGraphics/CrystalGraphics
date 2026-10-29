package com.crystalgraphics.lwjgl3;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryUtil;

import com.crystalgraphics.platform.audio.CgAudioDecoder;

/**
 * {@link CgAudioDecoder} on LWJGL 3's {@code stb_vorbis}, which every LWJGL 3 client ships for its own sounds.
 *
 * <pre>{@code
 * CgPlatform.provide(CgAudioDecoder.SERVICE, new StbVorbisDecoder());
 * }</pre>
 *
 * <ul>
 *   <li>A heap buffer is copied to native memory once, freed with its stream.</li>
 *   <li>Written against LWJGL 3.1.6's {@code STBVorbis}, the oldest a client ships.</li>
 * </ul>
 */
public final class StbVorbisDecoder implements CgAudioDecoder {

    /** The most frames one native read decodes. */
    private static final int CHUNK = 4096;

    @Override
    public Stream open(ByteBuffer ogg) {
        ByteBuffer data = ogg;
        boolean copied = !ogg.isDirect();
        if (copied) {
            data = MemoryUtil.memAlloc(ogg.remaining());
            data.put(ogg.duplicate()).flip();
        }
        long handle = STBVorbis.stb_vorbis_open_memory(data, new int[1], null);
        if (handle == 0) {
            if (copied) MemoryUtil.memFree(data);
            return null;
        }
        STBVorbisInfo info = STBVorbisInfo.malloc();
        STBVorbis.stb_vorbis_get_info(handle, info);
        int channels = info.channels(), rate = info.sample_rate();
        info.free();
        return new S(handle, copied ? data : null, channels, rate, STBVorbis.stb_vorbis_stream_length_in_samples(handle));
    }

    private static final class S implements Stream {
        private final long handle;
        private final ByteBuffer owned;
        private final int channels, rate;
        private final long frames;
        private final FloatBuffer scratch;

        S(long handle, ByteBuffer owned, int channels, int rate, long frames) {
            this.handle = handle;
            this.owned = owned;
            this.channels = channels;
            this.rate = rate;
            this.frames = frames;
            scratch = MemoryUtil.memAllocFloat(CHUNK * channels);
        }

        @Override
        public int channels() {
            return channels;
        }

        @Override
        public int sampleRate() {
            return rate;
        }

        @Override
        public long frames() {
            return frames;
        }

        @Override
        public int read(float[] out, int offset, int count) {
            int got = 0;
            while (got < count) {
                scratch.clear().limit(Math.min(CHUNK, count - got) * channels);
                int n = STBVorbis.stb_vorbis_get_samples_float_interleaved(handle, channels, scratch);
                if (n <= 0) break;
                scratch.get(out, (offset + got) * channels, n * channels);
                got += n;
            }
            return got;
        }

        @Override
        public void seek(long frame) {
            STBVorbis.stb_vorbis_seek(handle, (int) frame);
        }

        @Override
        public void close() {
            STBVorbis.stb_vorbis_close(handle);
            MemoryUtil.memFree(scratch);
            if (owned != null) MemoryUtil.memFree(owned);
        }
    }
}
