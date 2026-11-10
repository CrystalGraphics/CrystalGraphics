package com.crystalgraphics.platform.gl;

import com.crystalgraphics.variable.CgVar;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Extensions the engine treats as absent: for a driver whose implementation of one misbehaves, and for the harness's
 * downlevel contexts, where Mesa lists some that a real context of that version would not and cannot turn them off.
 *
 * <pre>{@code
 * -Dcrystalgraphics.gl.disableExtensions=GL_ARB_buffer_storage,GL_KHR_parallel_shader_compile
 *
 * // a backend probing an entry point itself:
 * if (caps.glClearBufferSubData != 0L && (caps.OpenGL43 || !CgDisabledExtensions.has("GL_ARB_clear_buffer_object"))) ...
 * }</pre>
 *
 * <ul>
 *   <li>Names only, separated by commas or spaces: a feature core in the context's version stays. Force a tier to turn
 *       one of those off ({@code -Dcrystalgraphics.compute.tier}, {@code -Dcrystalgraphics.shaderBuffer.tier}).</li>
 *   <li>The GL device and the GPU report leave them out; a driver that probes an entry point itself asks
 *       {@link #has}.</li>
 * </ul>
 */
public final class CgDisabledExtensions {

    public static final CgVar.Text DISABLED = CgVar.text("crystalgraphics.gl.disableExtensions", "", CgVar.DEV | CgVar.RESTART,
            "Extensions treated as absent, by name, separated by commas or spaces: a driver whose implementation misbehaves,"
                    + " and the harness's downlevel contexts. A feature core in the context's version stays; force a tier for that.");

    private static final Set<String> NAMES = parse(DISABLED.get());

    private CgDisabledExtensions() {}

    /** Whether {@code name} ({@code GL_ARB_compute_shader}) is to be treated as absent. */
    public static boolean has(String name) {
        return NAMES.contains(name);
    }

    /** Every disabled name, in the order given. */
    public static Set<String> all() {
        return NAMES;
    }

    private static Set<String> parse(String property) {
        if (property.isBlank()) return Set.of();
        Set<String> names = new LinkedHashSet<>();
        for (String name : property.split("[,\\s]+")) if (!name.isEmpty()) names.add(name);
        return Collections.unmodifiableSet(names);
    }

}
