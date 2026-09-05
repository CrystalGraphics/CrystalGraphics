package com.crystalgraphics.platform.input;

/**
 * Translation between GLFW key codes and {@link CgKeyCodes}.
 *
 * <p>{@code CgKeyCodes} is LWJGL2/DirectInput scancode numbering, so on LWJGL2 hosts translation is the
 * identity and on a GLFW host it is this table. The two vocabularies agree on nothing: {@code GLFW_KEY_A}
 * is 65 and {@link CgKeyCodes#KEY_A} is 0x1E.</p>
 *
 * <p>GLFW values are written as literals rather than through {@code org.lwjgl.glfw.GLFW} so this stays in
 * {@code platform}, which ships to dedicated servers and must not pull LWJGL. They are fixed by the GLFW
 * header and do not move between versions.</p>
 *
 * <p>{@link #PAIRS} is the single source of truth; both directions are derived from it, so the inverse
 * cannot drift from the forward map.</p>
 */
public final class CgGlfwKeyCodes {

    private CgGlfwKeyCodes() {}

    /** GLFW's "no key". */
    public static final int GLFW_KEY_UNKNOWN = -1;

    private static final int MAX_GLFW = 349;
    private static final int MAX_CG = 256;

    /**
     * {glfwKey, cgKey} pairs. Keys with no counterpart on the other side are absent — see
     * {@link #UNMAPPED_CG} for the deliberate omissions.
     */
    private static final int[] PAIRS = {
        // Printable ASCII block
        32,  CgKeyCodes.KEY_SPACE,          39,  CgKeyCodes.KEY_APOSTROPHE,
        44,  CgKeyCodes.KEY_COMMA,          45,  CgKeyCodes.KEY_MINUS,
        46,  CgKeyCodes.KEY_PERIOD,         47,  CgKeyCodes.KEY_SLASH,
        48,  CgKeyCodes.KEY_0,              49,  CgKeyCodes.KEY_1,
        50,  CgKeyCodes.KEY_2,              51,  CgKeyCodes.KEY_3,
        52,  CgKeyCodes.KEY_4,              53,  CgKeyCodes.KEY_5,
        54,  CgKeyCodes.KEY_6,              55,  CgKeyCodes.KEY_7,
        56,  CgKeyCodes.KEY_8,              57,  CgKeyCodes.KEY_9,
        59,  CgKeyCodes.KEY_SEMICOLON,      61,  CgKeyCodes.KEY_EQUALS,
        65,  CgKeyCodes.KEY_A,              66,  CgKeyCodes.KEY_B,
        67,  CgKeyCodes.KEY_C,              68,  CgKeyCodes.KEY_D,
        69,  CgKeyCodes.KEY_E,              70,  CgKeyCodes.KEY_F,
        71,  CgKeyCodes.KEY_G,              72,  CgKeyCodes.KEY_H,
        73,  CgKeyCodes.KEY_I,              74,  CgKeyCodes.KEY_J,
        75,  CgKeyCodes.KEY_K,              76,  CgKeyCodes.KEY_L,
        77,  CgKeyCodes.KEY_M,              78,  CgKeyCodes.KEY_N,
        79,  CgKeyCodes.KEY_O,              80,  CgKeyCodes.KEY_P,
        81,  CgKeyCodes.KEY_Q,              82,  CgKeyCodes.KEY_R,
        83,  CgKeyCodes.KEY_S,              84,  CgKeyCodes.KEY_T,
        85,  CgKeyCodes.KEY_U,              86,  CgKeyCodes.KEY_V,
        87,  CgKeyCodes.KEY_W,              88,  CgKeyCodes.KEY_X,
        89,  CgKeyCodes.KEY_Y,              90,  CgKeyCodes.KEY_Z,
        91,  CgKeyCodes.KEY_LBRACKET,       92,  CgKeyCodes.KEY_BACKSLASH,
        93,  CgKeyCodes.KEY_RBRACKET,       96,  CgKeyCodes.KEY_GRAVE,

        // Editing and navigation
        256, CgKeyCodes.KEY_ESCAPE,         257, CgKeyCodes.KEY_RETURN,
        258, CgKeyCodes.KEY_TAB,            259, CgKeyCodes.KEY_BACK,
        260, CgKeyCodes.KEY_INSERT,         261, CgKeyCodes.KEY_DELETE,
        262, CgKeyCodes.KEY_RIGHT,          263, CgKeyCodes.KEY_LEFT,
        264, CgKeyCodes.KEY_DOWN,           265, CgKeyCodes.KEY_UP,
        266, CgKeyCodes.KEY_PRIOR,          267, CgKeyCodes.KEY_NEXT,
        268, CgKeyCodes.KEY_HOME,           269, CgKeyCodes.KEY_END,

        // Locks and system
        280, CgKeyCodes.KEY_CAPITAL,        281, CgKeyCodes.KEY_SCROLL,
        282, CgKeyCodes.KEY_NUMLOCK,        283, CgKeyCodes.KEY_SYSRQ,
        284, CgKeyCodes.KEY_PAUSE,

        // Function keys. GLFW goes to F25; CgKeyCodes stops at F19.
        290, CgKeyCodes.KEY_F1,             291, CgKeyCodes.KEY_F2,
        292, CgKeyCodes.KEY_F3,             293, CgKeyCodes.KEY_F4,
        294, CgKeyCodes.KEY_F5,             295, CgKeyCodes.KEY_F6,
        296, CgKeyCodes.KEY_F7,             297, CgKeyCodes.KEY_F8,
        298, CgKeyCodes.KEY_F9,             299, CgKeyCodes.KEY_F10,
        300, CgKeyCodes.KEY_F11,            301, CgKeyCodes.KEY_F12,
        302, CgKeyCodes.KEY_F13,            303, CgKeyCodes.KEY_F14,
        304, CgKeyCodes.KEY_F15,            305, CgKeyCodes.KEY_F16,
        306, CgKeyCodes.KEY_F17,            307, CgKeyCodes.KEY_F18,
        308, CgKeyCodes.KEY_F19,

        // Keypad
        320, CgKeyCodes.KEY_NUMPAD0,        321, CgKeyCodes.KEY_NUMPAD1,
        322, CgKeyCodes.KEY_NUMPAD2,        323, CgKeyCodes.KEY_NUMPAD3,
        324, CgKeyCodes.KEY_NUMPAD4,        325, CgKeyCodes.KEY_NUMPAD5,
        326, CgKeyCodes.KEY_NUMPAD6,        327, CgKeyCodes.KEY_NUMPAD7,
        328, CgKeyCodes.KEY_NUMPAD8,        329, CgKeyCodes.KEY_NUMPAD9,
        330, CgKeyCodes.KEY_DECIMAL,        331, CgKeyCodes.KEY_DIVIDE,
        332, CgKeyCodes.KEY_MULTIPLY,       333, CgKeyCodes.KEY_SUBTRACT,
        334, CgKeyCodes.KEY_ADD,            335, CgKeyCodes.KEY_NUMPADENTER,
        336, CgKeyCodes.KEY_NUMPADEQUALS,

        // Modifiers and the menu key
        340, CgKeyCodes.KEY_LSHIFT,         341, CgKeyCodes.KEY_LCONTROL,
        342, CgKeyCodes.KEY_LMENU,          343, CgKeyCodes.KEY_LMETA,
        344, CgKeyCodes.KEY_RSHIFT,         345, CgKeyCodes.KEY_RCONTROL,
        346, CgKeyCodes.KEY_RMENU,          347, CgKeyCodes.KEY_RMETA,
        348, CgKeyCodes.KEY_APPS,
    };

    /**
     * CgKeyCodes values GLFW has no key for, listed so {@link #PAIRS} can be checked for completeness
     * rather than merely believed.
     *
     * <p>All are DirectInput-era: Japanese IME keys, OEM keys, and a few GLFW reports through
     * {@code WORLD_1}/{@code WORLD_2} instead, which are layout-dependent and not worth guessing at.</p>
     */
    public static final int[] UNMAPPED_CG = {
        CgKeyCodes.KEY_NONE,
        CgKeyCodes.KEY_KANA,        CgKeyCodes.KEY_CONVERT,     CgKeyCodes.KEY_NOCONVERT,
        CgKeyCodes.KEY_YEN,         CgKeyCodes.KEY_CIRCUMFLEX,  CgKeyCodes.KEY_AT,
        CgKeyCodes.KEY_COLON,       CgKeyCodes.KEY_UNDERLINE,   CgKeyCodes.KEY_KANJI,
        CgKeyCodes.KEY_STOP,        CgKeyCodes.KEY_AX,          CgKeyCodes.KEY_UNLABELED,
        CgKeyCodes.KEY_SECTION,     CgKeyCodes.KEY_NUMPADCOMMA, CgKeyCodes.KEY_FUNCTION,
        CgKeyCodes.KEY_CLEAR,       CgKeyCodes.KEY_POWER,       CgKeyCodes.KEY_SLEEP,
    };

    private static final int[] TO_CG = new int[MAX_GLFW];
    private static final int[] TO_GLFW = new int[MAX_CG];

    static {
        java.util.Arrays.fill(TO_GLFW, GLFW_KEY_UNKNOWN);
        for (int i = 0; i < PAIRS.length; i += 2) {
            TO_CG[PAIRS[i]] = PAIRS[i + 1];
            TO_GLFW[PAIRS[i + 1]] = PAIRS[i];
        }
    }

    /** @return the {@link CgKeyCodes} value, or {@link CgKeyCodes#KEY_NONE} when GLFW has no mapping */
    public static int toCg(int glfwKey) {
        return glfwKey < 0 || glfwKey >= MAX_GLFW ? CgKeyCodes.KEY_NONE : TO_CG[glfwKey];
    }

    /** @return the GLFW key, or {@link #GLFW_KEY_UNKNOWN} when there is none */
    public static int toGlfw(int cgKey) {
        return cgKey < 0 || cgKey >= MAX_CG ? GLFW_KEY_UNKNOWN : TO_GLFW[cgKey];
    }
}
