package com.crystalgraphics.vfx.particle.gpu;

/**
 * Where a module kind writes its lanes, or an emitter its spawn numbers, for the GPU: whole {@code vec4}s of 32-bit
 * words, in the order its GLSL takes them. The pool hands one out positioned at a row and checks afterwards that exactly
 * what was declared was written. A kind's own numbers are its declaration's ({@code CgVfxModuleTypes}), never written
 * here.
 *
 * <pre>{@code
 * public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
 *     double x = instance.originX() * frequency;
 *     out.ivec4((int) Math.floor(x), ..., 0).vec4((float) (x - Math.floor(x)), ..., 0f);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Write every lane declared, in order: a kind declaring two {@code vec4}s that writes one throws when the call
 *       returns, naming the kind.</li>
 *   <li>Never keep it: it moves to the next row after the call.</li>
 * </ul>
 */
public final class CgVfxWords {

    private int[] words;
    private int start, at, end;
    private String writer;
    /** A module kind {@link #writer} writes for, named in the messages only: no label is built a step. */
    private String kind;

    /** A writer pointed nowhere, re-pointed row by row with {@link #target}: a pool's, which allocates nothing a step. */
    public CgVfxWords() {
    }

    /**
     * Words over {@code vectors} vec4s of {@code into} from word {@code from}, outside a pool: a test running one module
     * kind's GLSL against its Java side. {@code writer} names who writes, for the messages.
     *
     * <pre>{@code
     * int[] words = new int[8];
     * CgVfxWords out = CgVfxWords.into(words, 0, 2, "curl_noise");
     * turbulence.writeInstance(instance, out);
     * out.finish("its lanes");
     * }</pre>
     */
    public static CgVfxWords into(int[] into, int from, int vectors, String writer) {
        CgVfxWords words = new CgVfxWords();
        words.target(into, from, vectors, writer);
        return words;
    }

    /** As {@link #target(int[], int, int, String)}, for {@code writer}'s module of {@code kind}: the pool's, each step. */
    public void target(int[] into, int from, int vectors, String writer, String kind) {
        target(into, from, vectors, writer);
        this.kind = kind;
    }

    private String writer() {
        return kind == null ? writer : writer + "'s fx_" + kind;
    }

    /** Points it at {@code vectors} vec4s of {@code into} from word {@code from}; {@code writer} names who writes. */
    public void target(int[] into, int from, int vectors, String writer) {
        this.words = into;
        this.start = from;
        this.at = from;
        this.end = from + vectors * 4;
        this.writer = writer;
        this.kind = null;
    }

    /** Throws unless every word it was pointed at was written; {@code what} names the row. */
    public void finish(String what) {
        if (at != end) {
            throw new IllegalStateException(writer() + " wrote " + (at - start) / 4 + " of the " + (end - start) / 4
                    + " vec4s it declared for " + what);
        }
        words = null;
    }

    public CgVfxWords vec4(float x, float y, float z, float w) {
        return put(Float.floatToRawIntBits(x), Float.floatToRawIntBits(y), Float.floatToRawIntBits(z),
                Float.floatToRawIntBits(w));
    }

    public CgVfxWords ivec4(int x, int y, int z, int w) {
        return put(x, y, z, w);
    }

    /** Four words read as unsigned: the bits of each {@code int}. */
    public CgVfxWords uvec4(int x, int y, int z, int w) {
        return put(x, y, z, w);
    }

    private CgVfxWords put(int x, int y, int z, int w) {
        if (words == null || at + 4 > end) {
            throw new IllegalStateException(writer() + " wrote more vec4s than it declared");
        }
        words[at] = x;
        words[at + 1] = y;
        words[at + 2] = z;
        words[at + 3] = w;
        at += 4;
        return this;
    }
}
