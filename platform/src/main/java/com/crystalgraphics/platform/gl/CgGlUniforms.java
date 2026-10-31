package com.crystalgraphics.platform.gl;

import com.crystalgraphics.variable.CgVar;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * The last value each program's uniforms were set to through {@link CgGL}, so a set to what a uniform already holds
 * never reaches the driver: Unreal's {@code FOpenGLShaderParameterCache}. A driver validates a program's changed
 * uniforms at the next draw, so a repeated set costs that draw even when the call itself is cheap.
 *
 * <p>A uniform belongs to its program, and only our own calls set our programs' uniforms, so the shadow outlives
 * program switches and host sections. A link or a binary load resets a program's uniforms and a deletion frees its
 * name: {@link CgGL} forgets the program at each. Locations at or past {@link #MAX_LOCATION} and arrays longer than
 * {@link #MAX_ARRAY} are always issued.</p>
 */
final class CgGlUniforms {

    static final boolean ON = CgVar.bool("crystalgraphics.gl.uniformShadow", true, CgVar.DEV | CgVar.RESTART,
            "Skips a uniform set to what that program's uniform already holds.").get();

    static final int MAX_LOCATION = 128, MAX_ARRAY = 8;

    private static Entry[] programs = new Entry[256];

    private CgGlUniforms() {}

    /** Whether setting {@code bits} at {@code location} of {@code program} must reach the driver; records it if so. */
    static boolean scalar(int program, int location, int bits) {
        Entry e = entry(program, location);
        if (e == null) return true;
        if (e.known[location] && e.scalars[location] == bits) return false;
        e.known[location] = true;
        e.scalars[location] = bits;
        e.arrays[location] = null;
        return true;
    }

    /** {@link #scalar} for an array, from its position to its limit. */
    static boolean array(int program, int location, IntBuffer values) {
        int n = values.remaining();
        Entry e = n <= MAX_ARRAY ? entry(program, location) : null;
        if (e == null) return true;
        int[] held = e.arrays[location];
        int at = values.position();
        if (e.known[location] && held != null && held.length == n) {
            boolean same = true;
            for (int i = 0; i < n && same; i++) same = held[i] == values.get(at + i);
            if (same) return false;
        }
        if (held == null || held.length != n) held = e.arrays[location] = new int[n];
        for (int i = 0; i < n; i++) held[i] = values.get(at + i);
        e.known[location] = true;
        return true;
    }

    /** {@link #array(int, int, IntBuffer)} for floats, compared by their bits. */
    static boolean array(int program, int location, FloatBuffer values) {
        int n = values.remaining();
        Entry e = n <= MAX_ARRAY ? entry(program, location) : null;
        if (e == null) return true;
        int[] held = e.arrays[location];
        int at = values.position();
        if (e.known[location] && held != null && held.length == n) {
            boolean same = true;
            for (int i = 0; i < n && same; i++) same = held[i] == Float.floatToRawIntBits(values.get(at + i));
            if (same) return false;
        }
        if (held == null || held.length != n) held = e.arrays[location] = new int[n];
        for (int i = 0; i < n; i++) held[i] = Float.floatToRawIntBits(values.get(at + i));
        e.known[location] = true;
        return true;
    }

    /** A program linked, loaded from a binary or deleted: nothing it held is known. */
    static void forget(int program) {
        if (program > 0 && program < programs.length) programs[program] = null;
    }

    /** A new backend: its names are not the last one's. */
    static void reset() {
        programs = new Entry[256];
    }

    private static Entry entry(int program, int location) {
        if (program <= 0 || location < 0 || location >= MAX_LOCATION) return null;
        if (program >= programs.length) {
            Entry[] grown = new Entry[Math.max(programs.length * 2, program + 1)];
            System.arraycopy(programs, 0, grown, 0, programs.length);
            programs = grown;
        }
        Entry e = programs[program];
        return e != null ? e : (programs[program] = new Entry());
    }

    private static final class Entry {
        final boolean[] known = new boolean[MAX_LOCATION];
        final int[] scalars = new int[MAX_LOCATION];
        final int[][] arrays = new int[MAX_LOCATION][];
    }
}
