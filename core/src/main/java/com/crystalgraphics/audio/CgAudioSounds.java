package com.crystalgraphics.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.platform.audio.CgAudioDecoder;
import com.crystalgraphics.util.io.CgIO;

/**
 * Loads sounds by path and keeps them: each file is read and decoded once. A sound over {@link #STREAM_SECONDS}, or one
 * asked to stream, keeps its compressed bytes for a voice to decode as it plays; a shorter one is decoded whole to
 * 16-bit PCM. Unreal decompresses short waves when loaded and streams long ones.
 *
 * <pre>{@code
 * CgAudioSounds sounds = audio.sounds();
 * CgSound click = sounds.load("minecraft:sounds/random/click.ogg");
 * CgSound music = sounds.load("minecraft:sounds/music/game/calm1.ogg", true);
 * }</pre>
 *
 * <ul>
 *   <li>Loading reads and decodes on the calling thread: load ahead of play, off the frame where it can wait.</li>
 *   <li>A path that is not found, or not Ogg Vorbis, fails naming it.</li>
 *   <li>Any thread; a sound loaded twice at once is decoded twice and one kept.</li>
 * </ul>
 */
public final class CgAudioSounds {

    /** Longer than this, a sound streams. */
    public static final double STREAM_SECONDS = 10;

    private final CgAudioDecoder decoder;
    private final Map<String, CgSound> loaded = new ConcurrentHashMap<>();

    public CgAudioSounds(CgAudioDecoder decoder) {
        this.decoder = decoder;
    }

    public CgSound load(String path) {
        return load(path, false);
    }

    /** {@code path}'s sound, streamed where {@code stream} or long. */
    public CgSound load(String path, boolean stream) {
        CgSound s = loaded.get(path);
        if (s != null && (s.streamed() || !stream)) return s;
        s = decode(path, read(path), stream);
        loaded.put(path, s);
        return s;
    }

    /** The file at {@code file} as the sound {@code id} (a kit's decoded cue), read and decoded once. */
    public CgSound load(String id, Path file, boolean stream) {
        CgSound s = loaded.get(id);
        if (s != null && (s.streamed() || !stream)) return s;
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new CgAssetException(id + ": " + file + " could not be read: " + e.getMessage());
        }
        ByteBuffer ogg = ByteBuffer.allocateDirect(bytes.length);
        ogg.put(bytes).flip();
        s = decode(id, ogg, stream);
        loaded.put(id, s);
        return s;
    }

    /** {@code ogg} decoded as a sound named {@code id}: resident, or streamed where {@code stream} or long. */
    public CgSound decode(String id, ByteBuffer ogg, boolean stream) {
        CgAudioDecoder.Stream d = decoder.open(ogg);
        if (d == null) throw new CgAssetException(id + ": not Ogg Vorbis");
        try {
            int ch = d.channels();
            if (stream || d.frames() > STREAM_SECONDS * d.sampleRate()) {
                return new CgSound(id, ch, d.sampleRate(), d.frames(), null, ogg);
            }
            short[] pcm = new short[(int) d.frames() * ch];
            float[] chunk = new float[4096 * ch];
            int at = 0;
            for (int n; at < d.frames() && (n = d.read(chunk, 0, (int) Math.min(4096, d.frames() - at))) > 0; at += n) {
                for (int i = 0; i < n * ch; i++) {
                    float v = chunk[i];
                    pcm[at * ch + i] = (short) (v >= 1f ? 32767 : v <= -1f ? -32768 : v * 32767f);
                }
            }
            return new CgSound(id, ch, d.sampleRate(), at, pcm, null);
        } finally {
            d.close();
        }
    }

    private static ByteBuffer read(String path) {
        try (InputStream in = CgIO.openStream(path)) {
            if (in == null) throw new CgAssetException(path + ": no such sound");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            for (int n; (n = in.read(chunk)) > 0; ) bytes.write(chunk, 0, n);
            byte[] all = bytes.toByteArray();
            // Direct, as a native decoder reads it in place.
            ByteBuffer direct = ByteBuffer.allocateDirect(all.length);
            direct.put(all).flip();
            return direct;
        } catch (IOException e) {
            throw new CgAssetException(path + ": " + e);
        }
    }
}
