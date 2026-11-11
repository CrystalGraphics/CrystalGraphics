package com.crystalgraphics.platform.input;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * The host's input, as it stands now: which keys and buttons are held, whether the game holds the pointer, and the
 * window toolkit's codes in the engine's. Events themselves arrive at a stage ({@link CgInputStage}); this answers
 * what an event cannot carry.
 *
 * <pre>{@code
 * boolean copy = CgInput.isKeyDown(CgKeyCodes.KEY_LCONTROL) && key == CgKeyCodes.KEY_C;
 * if ((CgInput.modifiers() & CgModifiers.SHIFT) != 0) extendSelection();
 * if (CgInput.isMouseDown(CgMouseCodes.LEFT_BUTTON)) drag();
 * if (CgInput.pointerGrabbed()) return;              // the cursor is the camera: no event is over anything
 * }</pre>
 *
 * A host turning the toolkit's numbers into the engine's as it builds an event:
 *
 * <pre>{@code
 * int key = CgInput.key(glfwKey);                   // CgKeyCodes; KEY_NONE where the engine has no such key
 * int button = CgInput.mouseButton(glfwButton);
 * }</pre>
 *
 * Provided once, from the client install: the toolkit's host where it answers everything ({@code InputLwjgl2},
 * {@code InputGlfw}, {@code InputSdl}), or an era's host over it where the game knows better
 * ({@code InputModern}: Minecraft's own pointer grab).
 *
 * <pre>{@code
 * CgPlatform.provide(CgInput.HOST, InputLwjgl2.INSTANCE);
 * }</pre>
 *
 * <ul>
 *   <li>Every code here is the engine's ({@link CgKeyCodes}, {@link CgMouseCodes}, {@link CgModifiers}), never a
 *       toolkit's, except the argument to {@link #key} and {@link #mouseButton}.</li>
 *   <li>Without a host (a dedicated server, a test) nothing is held, the pointer is free and every code turns into
 *       none.</li>
 *   <li>Client thread, where the toolkit polls.</li>
 * </ul>
 */
public final class CgInput {

    /** What a host answers. One class per toolkit, or an era's over it. Client only. */
    public interface Host {
        /** Whether key {@code key}, a {@link CgKeyCodes} code, is held. */
        boolean isKeyDown(int key);

        /** Whether mouse button {@code button}, a {@link CgMouseCodes} code, is held. */
        boolean isMouseDown(int button);

        /** The {@link CgModifiers} mask of what is held now. */
        int modifiers();

        /** How many buttons the mouse has, as the toolkit reports them. */
        int mouseButtons();

        /** Whether the game holds the cursor (it is the camera), so a pointer event is over nothing. */
        boolean pointerGrabbed();

        /** The toolkit's key {@code toolkitKey} as a {@link CgKeyCodes} code, {@code KEY_NONE} for one it lacks. */
        int key(int toolkitKey);

        /** The toolkit's button {@code toolkitButton} as a {@link CgMouseCodes} code. */
        int mouseButton(int toolkitButton);

        /** Takes the pointer into the game, as clicking into the world does. */
        void grabPointer();

        /** Puts the pointer at {@code (x, y)}, the window's pixels from its top left. */
        void movePointer(int x, int y);

        Host NONE = new Host() {
            @Override public boolean isKeyDown(int key) { return false; }
            @Override public boolean isMouseDown(int button) { return false; }
            @Override public int modifiers() { return 0; }
            @Override public int mouseButtons() { return 0; }
            @Override public boolean pointerGrabbed() { return false; }
            @Override public int key(int toolkitKey) { return CgKeyCodes.KEY_NONE; }
            @Override public int mouseButton(int toolkitButton) { return CgMouseCodes.NONE; }
            @Override public void grabPointer() { }
            @Override public void movePointer(int x, int y) { }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:input", Host.NONE);

    private CgInput() {
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }

    /** Whether key {@code key} ({@link CgKeyCodes}) is held. */
    public static boolean isKeyDown(int key) {
        return host().isKeyDown(key);
    }

    /** Whether mouse button {@code button} ({@link CgMouseCodes}) is held. */
    public static boolean isMouseDown(int button) {
        return host().isMouseDown(button);
    }

    /** What is held now, as a {@link CgModifiers} mask. */
    public static int modifiers() {
        return host().modifiers();
    }

    /** How many buttons the mouse has. */
    public static int mouseButtons() {
        return host().mouseButtons();
    }

    /** Whether the game holds the cursor. */
    public static boolean pointerGrabbed() {
        return host().pointerGrabbed();
    }

    /** The toolkit's key as a {@link CgKeyCodes} code. */
    public static int key(int toolkitKey) {
        return host().key(toolkitKey);
    }

    /** The toolkit's mouse button as a {@link CgMouseCodes} code. */
    public static int mouseButton(int toolkitButton) {
        return host().mouseButton(toolkitButton);
    }

    /** Takes the pointer into the game, as clicking into the world does. */
    public static void grabPointer() {
        host().grabPointer();
    }

    /** Puts the pointer at the window's {@code (x, y)}, from its top left: an unattended run clicking somewhere. */
    public static void movePointer(int x, int y) {
        host().movePointer(x, y);
    }
}
