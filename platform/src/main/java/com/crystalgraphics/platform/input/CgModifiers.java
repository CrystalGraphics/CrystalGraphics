package com.crystalgraphics.platform.input;

/**
 * Platform-agnostic modifier bitmask constants.
 *
 * <p>No LWJGL/GLFW imports. Platform adapters convert native modifier
 * state into these flags before dispatching into core.</p>
 */
@SuppressWarnings("unused")
public final class CgModifiers {

    private CgModifiers() {}

    public static final int NONE  = 0;
    public static final int SHIFT = 1;
    public static final int CTRL  = 1 << 1;
    public static final int ALT   = 1 << 2;
    public static final int SUPER = 1 << 3;

    public static boolean hasShift(int modifiers) { return (modifiers & SHIFT) != 0; }
    public static boolean hasCtrl(int modifiers) { return (modifiers & CTRL) != 0; }
    public static boolean hasAlt(int modifiers) { return (modifiers & ALT) != 0; }
    public static boolean hasSuper(int modifiers) { return (modifiers & SUPER) != 0; }
}