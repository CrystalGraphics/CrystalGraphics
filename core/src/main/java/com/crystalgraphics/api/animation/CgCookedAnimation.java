package com.crystalgraphics.api.animation;

import com.crystalgraphics.util.io.CgCookedBytes;

import java.util.ArrayList;
import java.util.List;

/**
 * A cooked animation file ({@code .cganim}): sequences as columns of keys, read with no parsing beyond copying them.
 * What {@code CgMeshLoader} reads beside a cooked {@code .cgmesh}, and {@code sequences(path)} reads alone.
 *
 * <pre>{@code
 * byte[] file = CgCookedAnimation.write(kurama.sequences());
 * List<CgAnimSequence> back = CgCookedAnimation.read(file);   // equal, sequence by sequence
 * }</pre>
 *
 * <pre>
 * "CGAN"  u16 version (2)  u16 0  u32 sequences
 * per sequence: string name, f64 frame rate, u32 keys, u32 tracks, u32 curves
 *   per track: string bone, u8 which channels hold every key (1 translation, 2 rotation, 4 scale),
 *              then translations, rotations, scales: f32, 3, 4 and 3 a key, one key or every key
 *   per curve: string name, u8 what it drives (CgAnimSequence.DRIVES_*), u8 1 if it holds every key, then its f32 keys
 * little-endian; a string is u16 length and UTF-8
 * </pre>
 *
 * <ul>
 *   <li>The layout is the engine's own and versioned with it: a file of another version fails to read, naming both.</li>
 *   <li>Any thread.</li>
 * </ul>
 */
public final class CgCookedAnimation {

    private static final int VERSION = 2;

    private CgCookedAnimation() {
    }

    /** Whether {@code path} names a cooked animation file. */
    public static boolean is(String path) {
        return path != null && path.endsWith(".cganim");
    }

    public static byte[] write(List<CgAnimSequence> sequences) {
        CgCookedBytes.Writer out = new CgCookedBytes.Writer();
        out.magic("CGAN").u16(VERSION).u16(0).u32(sequences.size());
        for (CgAnimSequence s : sequences) {
            out.string(s.name()).f64(s.frameRate()).u32(s.frameCount()).u32(s.trackCount()).u32(s.curveCount());
            for (int t = 0; t < s.trackCount(); t++) {
                float[] tr = s.translations[t], ro = s.rotations[t], sc = s.scales[t];
                out.string(s.trackBone(t)).u8((tr.length > 3 ? 1 : 0) | (ro.length > 4 ? 2 : 0) | (sc.length > 3 ? 4 : 0));
                out.floats(tr).floats(ro).floats(sc);
            }
            for (int c = 0; c < s.curveCount(); c++) {
                out.string(s.curveName(c)).u8(s.curveDrives(c)).u8(s.curves[c].length > 1 ? 1 : 0).floats(s.curves[c]);
            }
        }
        return out.toBytes();
    }

    /** @throws IllegalArgumentException naming what is wrong with the file */
    public static List<CgAnimSequence> read(byte[] file) {
        CgCookedBytes.Reader in = new CgCookedBytes.Reader(file);
        in.magic("CGAN");
        int version = in.u16();
        if (version != VERSION) throw new IllegalArgumentException("a .cganim of version " + version + ", this engine reads " + VERSION);
        in.u16();
        int count = in.u32();
        List<CgAnimSequence> sequences = new ArrayList<>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) {
            String name = in.string();
            double rate = in.f64();
            int keys = in.u32(), tracks = in.u32(), curves = in.u32();
            String[] bones = new String[tracks], curveNames = new String[curves];
            float[][] translations = new float[tracks][], rotations = new float[tracks][], scales = new float[tracks][],
                    curveKeys = new float[curves][];
            int[] drives = new int[curves];
            for (int t = 0; t < tracks; t++) {
                bones[t] = in.string();
                int full = in.u8();
                translations[t] = in.floats(3 * ((full & 1) != 0 ? keys : 1));
                rotations[t] = in.floats(4 * ((full & 2) != 0 ? keys : 1));
                scales[t] = in.floats(3 * ((full & 4) != 0 ? keys : 1));
            }
            for (int c = 0; c < curves; c++) {
                curveNames[c] = in.string();
                drives[c] = in.u8();
                curveKeys[c] = in.floats(in.u8() != 0 ? keys : 1);
            }
            sequences.add(new CgAnimSequence(name, rate, keys, bones, translations, rotations, scales, curveNames, curveKeys, drives));
        }
        if (!in.atEnd()) throw new IllegalArgumentException("bytes past the last of " + count + " sequences");
        return sequences;
    }
}
