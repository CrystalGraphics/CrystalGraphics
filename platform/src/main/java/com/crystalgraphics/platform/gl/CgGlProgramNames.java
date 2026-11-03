package com.crystalgraphics.platform.gl;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a program's names resolve to, read where it was linked so the render thread never asks the driver: under a
 * threaded driver a query waits for the whole command queue, 200 ms and more during a load. {@link CgGL}'s
 * {@code glGetUniformLocation}, {@code glGetUniformBlockIndex} and {@code glGetProgramResourceIndex} answer from it,
 * and ask the driver for what it does not hold.
 *
 * <pre>{@code
 * CgGlProgramNames.Names names = new CgGlProgramNames.Names();   // on the link worker, the program linked
 * names.uniform("_Color", CgGL.glGetUniformLocation(program, "_Color"));
 * names.uniformBlock("CgFrameBlock", CgGL.glGetUniformBlockIndex(program, "CgFrameBlock"));
 * CgGL.cgProgramNames(program, names.complete());               // every active uniform listed: an unlisted one is -1
 * }</pre>
 *
 * A link, a binary load or a deletion forgets the program, as {@link CgGlUniforms} does.
 */
public final class CgGlProgramNames {

    private static final Map<Integer, Names> PROGRAMS = new ConcurrentHashMap<>();

    private CgGlProgramNames() {}

    /** One program's names. Filled on one thread, then handed over with {@link CgGL#cgProgramNames}. */
    public static final class Names {
        private final Map<String, Integer> uniforms = new HashMap<>(), blocks = new HashMap<>(),
                storage = new HashMap<>();
        private boolean complete;

        /** A uniform's location; an array's first element is also its bare name. */
        public Names uniform(String name, int location) {
            uniforms.put(name, location);
            if (name.endsWith("[0]")) uniforms.put(name.substring(0, name.length() - 3), location);
            return this;
        }

        public Names uniformBlock(String name, int index) {
            blocks.put(name, index);
            return this;
        }

        public Names storageBlock(String name, int index) {
            storage.put(name, index);
            return this;
        }

        /** Every active uniform is listed: a uniform not listed is inactive, -1, with no driver call. */
        public Names complete() {
            complete = true;
            return this;
        }
    }

    static void put(int program, Names names) {
        if (program > 0) PROGRAMS.put(program, names);
    }

    static void forget(int program) {
        PROGRAMS.remove(program);
    }

    static void reset() {
        PROGRAMS.clear();
    }

    /** The location, or null to ask the driver. */
    static Integer uniform(int program, CharSequence name) {
        Names n = PROGRAMS.get(program);
        if (n == null) return null;
        Integer at = n.uniforms.get(name.toString());
        return at != null || !n.complete ? at : -1;
    }

    /** A block's index, or null to ask the driver: blocks are listed as the source declares them, never complete. */
    static Integer block(int program, CharSequence name, boolean storage) {
        Names n = PROGRAMS.get(program);
        return n == null ? null : (storage ? n.storage : n.blocks).get(name.toString());
    }
}
