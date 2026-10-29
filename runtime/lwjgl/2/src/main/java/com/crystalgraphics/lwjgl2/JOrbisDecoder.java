package com.crystalgraphics.lwjgl2;

import java.nio.ByteBuffer;

import com.crystalgraphics.platform.audio.CgAudioDecoder;
import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

/**
 * {@link CgAudioDecoder} on JOrbis ({@code org.jcraft:jorbis}), which Minecraft 1.7.10 to 1.12.2 ship for their own
 * sounds: called, never bundled.
 *
 * <pre>{@code
 * CgPlatform.provide(CgAudioDecoder.SERVICE, new JOrbisDecoder());
 * }</pre>
 *
 * <ul>
 *   <li>A seek decodes again from the start up to the frame: long streams seek only to loop.</li>
 *   <li>Its length is the last page's granule position.</li>
 * </ul>
 */
public final class JOrbisDecoder implements CgAudioDecoder {

    @Override
    public Stream open(ByteBuffer ogg) {
        byte[] bytes = new byte[ogg.remaining()];
        ogg.duplicate().get(bytes);
        S s = new S(bytes);
        if (!s.start()) {
            s.close();
            return null;
        }
        return s;
    }

    private static final class S implements Stream {
        private final byte[] data;
        private final long frames;
        private SyncState sync;
        private StreamState stream;
        private final Page page = new Page();
        private final Packet packet = new Packet();
        private Info info;
        private Comment comment;
        private DspState dsp;
        private Block block;
        private final float[][][] pcm = new float[1][][];
        private int[] index;
        private int pos;
        private boolean lastPage, done;

        S(byte[] data) {
            this.data = data;
            frames = lastGranule(data);
        }

        /** The three header packets, then the decoder: false where the bytes are no Vorbis. */
        boolean start() {
            sync = new SyncState();
            stream = new StreamState();
            info = new Info();
            comment = new Comment();
            sync.init();
            info.init();
            comment.init();
            pos = 0;
            lastPage = done = false;
            if (!page()) return false;
            stream.init(page.serialno());
            stream.pagein(page);
            for (int headers = 0; headers < 3; ) {
                int r = stream.packetout(packet);
                if (r == 0) {
                    if (!page()) return false;
                    stream.pagein(page);
                    continue;
                }
                if (r < 0 || info.synthesis_headerin(comment, packet) < 0) return false;
                headers++;
            }
            dsp = new DspState();
            dsp.synthesis_init(info);
            block = new Block(dsp);
            index = new int[info.channels];
            return true;
        }

        /** The next page, feeding the sync layer from the bytes; false at their end. */
        private boolean page() {
            while (sync.pageout(page) != 1) {
                int n = Math.min(4096, data.length - pos);
                if (n <= 0) return false;
                int at = sync.buffer(n);
                System.arraycopy(data, pos, sync.data, at, n);
                pos += n;
                sync.wrote(n);
            }
            return true;
        }

        /** The next audio packet into the decoder; false at the stream's end. */
        private boolean packet() {
            while (true) {
                int r = stream.packetout(packet);
                if (r > 0) {
                    if (block.synthesis(packet) == 0) dsp.synthesis_blockin(block);
                    return true;
                }
                if (r < 0) continue;
                if (lastPage || !page()) return false;
                stream.pagein(page);
                if (page.eos() != 0) lastPage = true;
            }
        }

        @Override
        public int channels() {
            return info.channels;
        }

        @Override
        public int sampleRate() {
            return info.rate;
        }

        @Override
        public long frames() {
            return frames;
        }

        @Override
        public int read(float[] out, int offset, int count) {
            int got = 0, ch = info.channels;
            while (got < count && !done) {
                int ready = dsp.synthesis_pcmout(pcm, index);
                if (ready > 0) {
                    int take = Math.min(ready, count - got);
                    for (int c = 0; c < ch; c++) {
                        float[] samples = pcm[0][c];
                        int from = index[c];
                        for (int i = 0; i < take; i++) out[(offset + got + i) * ch + c] = samples[from + i];
                    }
                    dsp.synthesis_read(take);
                    got += take;
                } else if (!packet()) {
                    done = true;
                }
            }
            return got;
        }

        @Override
        public void seek(long frame) {
            clear();
            start();
            float[] skip = new float[1024 * info.channels];
            for (long left = frame; left > 0; ) {
                int n = read(skip, 0, (int) Math.min(1024, left));
                if (n <= 0) break;
                left -= n;
            }
        }

        private void clear() {
            if (block != null) block.clear();
            if (dsp != null) dsp.clear();
            if (stream != null) stream.clear();
            if (info != null) info.clear();
            if (sync != null) sync.clear();
        }

        @Override
        public void close() {
            clear();
        }

        /** The last Ogg page's granule position: the stream's length in frames. */
        private static long lastGranule(byte[] d) {
            for (int i = d.length - 27; i >= 0; i--) {
                if (d[i] == 'O' && d[i + 1] == 'g' && d[i + 2] == 'g' && d[i + 3] == 'S') {
                    long g = 0;
                    for (int b = 7; b >= 0; b--) g = g << 8 | (d[i + 6 + b] & 0xFF);
                    return Math.max(0, g);
                }
            }
            return 0;
        }
    }
}
