package com.crystalgraphics.mc.modern.platform;

//? if >=26.3 {
/*import com.crystalgraphics.platform.input.CgSdlKeyCodes;
*///?} else {
import com.crystalgraphics.platform.input.CgGlfwKeyCodes;
//?}

/**
 * CrystalGraphics' key and button numbers in this Minecraft's own: GLFW's before 26.3, SDL's from it.
 *
 * <pre>{@code
 * new KeyMapping("key.mymod.open", HostKeys.key(CgKeyCodes.KEY_LMENU), category);
 * }</pre>
 */
public final class HostKeys {

    private HostKeys() {}

    /** A key in the host's numbering, for a {@code KeyMapping} default or a synthetic press. */
    public static int key(int cgKey) {
        //? if >=26.3 {
        /*return CgSdlKeyCodes.toSdl(cgKey);
        *///?} else {
        return CgGlfwKeyCodes.toGlfw(cgKey);
        //?}
    }

    /** A mouse button in the host's numbering: SDL counts from 1 with middle second, GLFW as CrystalGraphics does. */
    public static int button(int cgButton) {
        //? if >=26.3 {
        /*return CgSdlKeyCodes.mouseToSdl(cgButton);
        *///?} else {
        return cgButton;
        //?}
    }
}
