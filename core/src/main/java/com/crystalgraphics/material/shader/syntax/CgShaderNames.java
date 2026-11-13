package com.crystalgraphics.material.shader.syntax;

/**
 * The names an authored shader may not give its own symbols: the engine's prefixes, and GLSL's reserved {@code __}.
 *
 * <pre>{@code
 * CgShaderNames.refuseReserved(tokens, at, "_cg_glow", "Properties block", false);   // fails: _cg_ is the engine's
 * CgShaderNames.refuseReserved(tokens, at, "_cg_in_param", "struct v2f", true);      // a template's passed field: allowed
 * }</pre>
 */
public final class CgShaderNames {

    /** The prefixes the engine's generated symbols take. */
    public static final String[] RESERVED = {"cg_", "CG_", "_v2f_", "_cg_"};

    /** The prefix of a v2f field carrying a template's own value ({@code _cg_in_param}). */
    public static final String PASSED = "_cg_in_";

    private CgShaderNames() {}

    /**
     * Refuses {@code name}, placed on token {@code at}, where it holds {@code __} or takes an engine prefix;
     * {@code passed} allows {@link #PASSED}, a template's own v2f field.
     */
    public static void refuseReserved(CgShaderTokens t, int at, String name, String context, boolean passed) {
        if (name.contains("__")) {
            // GLSL reserves it: shaderc warns, NVIDIA's GL refuses the program (C7528)
            throw t.error(at, "Name '" + name + "' in " + context + " contains '__', which GLSL reserves");
        }
        if (passed && name.startsWith(PASSED)) return;
        for (String prefix : RESERVED) {
            if (name.startsWith(prefix)) {
                throw t.error(at, "Name '" + name + "' in " + context + " uses engine-reserved prefix '" + prefix
                        + "'. Engine-reserved prefixes (cg_, CG_, _v2f_, _cg_) must not be used in authored symbols.");
            }
        }
    }
}
