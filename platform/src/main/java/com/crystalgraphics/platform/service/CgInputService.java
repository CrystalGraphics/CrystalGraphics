package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;

/**
 * Platform abstraction for reading input state and translating native codes into the engine's own.
 *
 * <p>{@link CgKeyCodes} and {@link CgMouseCodes} are LWJGL2-shaped, so on those platforms every
 * translation method is the identity. Everywhere else this is a lookup table.</p>
 *
 * <h3>The clipboard lives here too</h3>
 * <p>{@link #getClipboard()} / {@link #setClipboard(String)} sit on the input service rather than in a
 * service of their own. A clipboard is not conceptually input, but it is reached the same way — one
 * loader-owned handle, needed by exactly the code that handles keys — and every implementation that
 * provides one already provides the rest of this interface. Two methods do not earn a registration slot.</p>
 *
 * <p><b>Nothing here has a default implementation</b>, including the clipboard pair and
 * {@link #translateMouseCodes(int)} — whose identity mapping is right on every platform seen so far and is
 * exactly why it should not be inherited silently. See {@link com.crystalgraphics.platform.CgPlatformService}
 * for the reasoning: a default is an answer chosen for someone who never saw the question, and a new
 * platform that quietly inherits a broken clipboard reports nothing. Returning {@code ""} and discarding
 * writes is a fine answer where there is no clipboard; it just has to be written down.</p>
 *
 * <p>Suggested implementation for a non-LWJGL2 platform:</p>
 * <pre>
 *     {@code
 * public class InputService implements CgInputService {
 *
 *     private static final Int2IntMap PLATFORM_TO_LOCAL = new Int2IntOpenHashMap();
 *     private static final Int2IntMap LOCAL_TO_PLATFORM = new Int2IntOpenHashMap();
 *
 *     static {
 *         register(Keyboard.KEY_A, CgKeyCodes.KEY_A);
 *         register(Keyboard.KEY_LSHIFT, CgKeyCodes.KEY_LSHIFT);
 *         register(Keyboard.KEY_RSHIFT, CgKeyCodes.KEY_RSHIFT);
 *         // ... full table
 *     }
 *
 *     private static void register(int platformCode, int localCode) {
 *         PLATFORM_TO_LOCAL.put(platformCode, localCode);
 *         LOCAL_TO_PLATFORM.put(localCode, platformCode);
 *     }
 *
 *     @Override
 *     public int translateKeyboardCodes(int platformCode) {
 *         return PLATFORM_TO_LOCAL.getOrDefault(platformCode, CgKeyCodes.KEY_NONE);
 *     }
 *
 *     @Override
 *     public boolean isKeyDown(int localCode) {
 *         final int platformCode = LOCAL_TO_PLATFORM.getOrDefault(localCode, -1);
 *         if (platformCode == -1) return false;
 *         return Keyboard.isKeyDown(platformCode);
 *     }
 *
 *     @Override
 *     public int getCurrentModifiers() {
 *         int mods = 0;
 *         if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT))
 *             mods |= CgModifiers.SHIFT;
 *         if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL))
 *             mods |= CgModifiers.CTRL;
 *         if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU))
 *             mods |= CgModifiers.ALT;
 *         return mods;
 *     }
 * }
 * }
 * </pre>
 */
@SuppressWarnings("unused")
public interface CgInputService {

    /**
     * The modifier keys currently held.
     *
     * @return a {@link CgModifiers}-coded mask
     */
    int getCurrentModifiers();

    /**
     * Translates a native keycode into the engine's vocabulary.
     *
     * @param platformCode a keycode in whatever encoding the running platform uses
     * @return the {@link CgKeyCodes} equivalent
     */
    int translateKeyboardCodes(int platformCode);

    boolean isKeyDown(int localKeyCode);

    /**
     * Translates a native mouse button id. Usually 1:1 — an implementation returning {@code platformCode}
     * unchanged is normal and expected.
     *
     * @param platformCode a button id in whatever encoding the running platform uses
     * @return the {@link CgMouseCodes} equivalent
     */
    int translateMouseCodes(int platformCode);

    boolean isMouseDown(int localMouseCode);

    /** @return how many mouse buttons this platform reports */
    int howManyMouseButtons();

    /**
     * Current clipboard contents, or an empty string when the clipboard is empty or unavailable.
     * Never {@code null} — a platform with no clipboard returns {@code ""}.
     */
    String getClipboard();

    /** Writes to the system clipboard. A platform with no clipboard leaves the body empty. */
    void setClipboard(String text);
}
