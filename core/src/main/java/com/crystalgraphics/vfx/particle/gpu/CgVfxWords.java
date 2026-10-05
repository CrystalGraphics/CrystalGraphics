package com.crystalgraphics.vfx.particle.gpu;

/**
 * Where a module kind or an emitter writes its numbers for the GPU: whole {@code vec4}s of 32-bit words, in the order
 * its GLSL takes them. The pool hands one out positioned at a row and checks afterwards that exactly what was declared
 * was written.
 *
 * <pre>{@code
 * public void writeParams(CgVfxWords out) {
 *     out.vec4(strength, frequency, evolve, 0f);
 * }
 *
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

    CgVfxWords() {
    }

    /**
     * Words over {@code vectors} vec4s of {@code into} from word {@code from}, outside a pool: a test running one module
     * kind's GLSL against its Java side. {@code writer} names who writes, for the messages.
     *
     * <pre>{@code
     * int[] words = new int[8];
     * CgVfxWords out = CgVfxWords.into(words, 0, turbulence.paramVectors(), "turbulence");
     * turbulence.writeParams(out);
     * out.finish("its numbers");
     * }</pre>
     */
    public static CgVfxWords into(int[] into, int from, int vectors, String writer) {
        CgVfxWords words = new CgVfxWords();
        words.target(into, from, vectors, writer);
        return words;
    }

    /** Points it at {@code vectors} vec4s of {@code into} from word {@code from}; {@code writer} names who writes. */
    void target(int[] into, int from, int vectors, String writer) {
        this.words = into;
        this.start = from;
        this.at = from;
        this.end = from + vectors * 4;
        this.writer = writer;
    }

    /** Throws unless every word it was pointed at was written; {@code what} names the row. */
    public void finish(String what) {
        if (at != end) {
            throw new IllegalStateException(writer + " wrote " + (at - start) / 4 + " of the " + (end - start) / 4
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
            throw new IllegalStateException(writer + " wrote more vec4s than it declared");
        }
        words[at] = x;
        words[at + 1] = y;
        words[at + 2] = z;
        words[at + 3] = w;
        at += 4;
        return this;
    }
}
