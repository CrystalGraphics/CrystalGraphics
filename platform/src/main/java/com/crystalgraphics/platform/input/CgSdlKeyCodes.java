package com.crystalgraphics.platform.input;

/**
 * Translation between SDL3 scancodes and {@link CgKeyCodes}, and between SDL's mouse buttons and
 * {@link CgMouseCodes}.
 *
 * <p>Minecraft 26.3 runs on SDL3 and numbers its keys by scancode: {@code InputConstants.KEY_A} is 4,
 * {@code KEY_ESCAPE} 41. A host whose events carry those translates them here.</p>
 *
 * <pre>{@code
 * int cg = CgSdlKeyCodes.toCg(event.key());          // a key event from a 26.3 screen
 * int sdl = CgSdlKeyCodes.toSdl(CgKeyCodes.KEY_LSHIFT); // 225, to index SDL_GetKeyboardState
 * int button = CgSdlKeyCodes.mouseToCg(event.button()); // SDL_BUTTON_RIGHT (3) -> RIGHT_BUTTON (1)
 * }</pre>
 *
 * <p>Values are literals rather than {@code org.lwjgl.sdl} constants so this stays in {@code platform},
 * which a dedicated server loads without LWJGL. Scancodes are the USB HID usage IDs and do not move
 * between SDL versions. {@link #PAIRS} is the one source; both directions are derived from it.</p>
 */
public final class CgSdlKeyCodes {

    private CgSdlKeyCodes() {}

    /** {@code SDL_SCANCODE_UNKNOWN}. */
    public static final int SDL_SCANCODE_UNKNOWN = 0;

    /** {@code SDL_SCANCODE_COUNT}. */
    private static final int MAX_SDL = 512;
    private static final int MAX_CG = 256;

    /** {sdlScancode, cgKey} pairs. */
    private static final int[] PAIRS = {
        // Letters
        4,   CgKeyCodes.KEY_A,              5,   CgKeyCodes.KEY_B,
        6,   CgKeyCodes.KEY_C,              7,   CgKeyCodes.KEY_D,
        8,   CgKeyCodes.KEY_E,              9,   CgKeyCodes.KEY_F,
        10,  CgKeyCodes.KEY_G,              11,  CgKeyCodes.KEY_H,
        12,  CgKeyCodes.KEY_I,              13,  CgKeyCodes.KEY_J,
        14,  CgKeyCodes.KEY_K,              15,  CgKeyCodes.KEY_L,
        16,  CgKeyCodes.KEY_M,              17,  CgKeyCodes.KEY_N,
        18,  CgKeyCodes.KEY_O,              19,  CgKeyCodes.KEY_P,
        20,  CgKeyCodes.KEY_Q,              21,  CgKeyCodes.KEY_R,
        22,  CgKeyCodes.KEY_S,              23,  CgKeyCodes.KEY_T,
        24,  CgKeyCodes.KEY_U,              25,  CgKeyCodes.KEY_V,
        26,  CgKeyCodes.KEY_W,              27,  CgKeyCodes.KEY_X,
        28,  CgKeyCodes.KEY_Y,              29,  CgKeyCodes.KEY_Z,

        // Digits
        30,  CgKeyCodes.KEY_1,              31,  CgKeyCodes.KEY_2,
        32,  CgKeyCodes.KEY_3,              33,  CgKeyCodes.KEY_4,
        34,  CgKeyCodes.KEY_5,              35,  CgKeyCodes.KEY_6,
        36,  CgKeyCodes.KEY_7,              37,  CgKeyCodes.KEY_8,
        38,  CgKeyCodes.KEY_9,              39,  CgKeyCodes.KEY_0,

        // Editing and punctuation
        40,  CgKeyCodes.KEY_RETURN,         41,  CgKeyCodes.KEY_ESCAPE,
        42,  CgKeyCodes.KEY_BACK,           43,  CgKeyCodes.KEY_TAB,
        44,  CgKeyCodes.KEY_SPACE,          45,  CgKeyCodes.KEY_MINUS,
        46,  CgKeyCodes.KEY_EQUALS,         47,  CgKeyCodes.KEY_LBRACKET,
        48,  CgKeyCodes.KEY_RBRACKET,       49,  CgKeyCodes.KEY_BACKSLASH,
        51,  CgKeyCodes.KEY_SEMICOLON,      52,  CgKeyCodes.KEY_APOSTROPHE,
        53,  CgKeyCodes.KEY_GRAVE,          54,  CgKeyCodes.KEY_COMMA,
        55,  CgKeyCodes.KEY_PERIOD,         56,  CgKeyCodes.KEY_SLASH,
        57,  CgKeyCodes.KEY_CAPITAL,

        // Function keys. SDL goes to F24; CgKeyCodes stops at F19.
        58,  CgKeyCodes.KEY_F1,             59,  CgKeyCodes.KEY_F2,
        60,  CgKeyCodes.KEY_F3,             61,  CgKeyCodes.KEY_F4,
        62,  CgKeyCodes.KEY_F5,             63,  CgKeyCodes.KEY_F6,
        64,  CgKeyCodes.KEY_F7,             65,  CgKeyCodes.KEY_F8,
        66,  CgKeyCodes.KEY_F9,             67,  CgKeyCodes.KEY_F10,
        68,  CgKeyCodes.KEY_F11,            69,  CgKeyCodes.KEY_F12,
        104, CgKeyCodes.KEY_F13,            105, CgKeyCodes.KEY_F14,
        106, CgKeyCodes.KEY_F15,            107, CgKeyCodes.KEY_F16,
        108, CgKeyCodes.KEY_F17,            109, CgKeyCodes.KEY_F18,
        110, CgKeyCodes.KEY_F19,

        // System and navigation
        70,  CgKeyCodes.KEY_SYSRQ,          71,  CgKeyCodes.KEY_SCROLL,
        72,  CgKeyCodes.KEY_PAUSE,          73,  CgKeyCodes.KEY_INSERT,
        74,  CgKeyCodes.KEY_HOME,           75,  CgKeyCodes.KEY_PRIOR,
        76,  CgKeyCodes.KEY_DELETE,         77,  CgKeyCodes.KEY_END,
        78,  CgKeyCodes.KEY_NEXT,           79,  CgKeyCodes.KEY_RIGHT,
        80,  CgKeyCodes.KEY_LEFT,           81,  CgKeyCodes.KEY_DOWN,
        82,  CgKeyCodes.KEY_UP,             101, CgKeyCodes.KEY_APPS,
        102, CgKeyCodes.KEY_POWER,

        // Keypad
        83,  CgKeyCodes.KEY_NUMLOCK,        84,  CgKeyCodes.KEY_DIVIDE,
        85,  CgKeyCodes.KEY_MULTIPLY,       86,  CgKeyCodes.KEY_SUBTRACT,
        87,  CgKeyCodes.KEY_ADD,            88,  CgKeyCodes.KEY_NUMPADENTER,
        89,  CgKeyCodes.KEY_NUMPAD1,        90,  CgKeyCodes.KEY_NUMPAD2,
        91,  CgKeyCodes.KEY_NUMPAD3,        92,  CgKeyCodes.KEY_NUMPAD4,
        93,  CgKeyCodes.KEY_NUMPAD5,        94,  CgKeyCodes.KEY_NUMPAD6,
        95,  CgKeyCodes.KEY_NUMPAD7,        96,  CgKeyCodes.KEY_NUMPAD8,
        97,  CgKeyCodes.KEY_NUMPAD9,        98,  CgKeyCodes.KEY_NUMPAD0,
        99,  CgKeyCodes.KEY_DECIMAL,        103, CgKeyCodes.KEY_NUMPADEQUALS,
        133, CgKeyCodes.KEY_NUMPADCOMMA,

        // Japanese keyboards: HID International2..5
        136, CgKeyCodes.KEY_KANA,           137, CgKeyCodes.KEY_YEN,
        138, CgKeyCodes.KEY_CONVERT,        139, CgKeyCodes.KEY_NOCONVERT,

        // Modifiers
        224, CgKeyCodes.KEY_LCONTROL,       225, CgKeyCodes.KEY_LSHIFT,
        226, CgKeyCodes.KEY_LMENU,          227, CgKeyCodes.KEY_LMETA,
        228, CgKeyCodes.KEY_RCONTROL,       229, CgKeyCodes.KEY_RSHIFT,
        230, CgKeyCodes.KEY_RMENU,          231, CgKeyCodes.KEY_RMETA,
    };

    /** CgKeyCodes values SDL has no scancode for, so {@link #PAIRS} can be checked for completeness. */
    public static final int[] UNMAPPED_CG = {
        CgKeyCodes.KEY_NONE,
        CgKeyCodes.KEY_CIRCUMFLEX,  CgKeyCodes.KEY_AT,          CgKeyCodes.KEY_COLON,
        CgKeyCodes.KEY_UNDERLINE,   CgKeyCodes.KEY_KANJI,       CgKeyCodes.KEY_STOP,
        CgKeyCodes.KEY_AX,          CgKeyCodes.KEY_UNLABELED,   CgKeyCodes.KEY_SECTION,
        CgKeyCodes.KEY_FUNCTION,    CgKeyCodes.KEY_CLEAR,       CgKeyCodes.KEY_SLEEP,
    };

    private static final int[] TO_CG = new int[MAX_SDL];
    private static final int[] TO_SDL = new int[MAX_CG];

    static {
        for (int i = 0; i < PAIRS.length; i += 2) {
            TO_CG[PAIRS[i]] = PAIRS[i + 1];
            TO_SDL[PAIRS[i + 1]] = PAIRS[i];
        }
    }

    /** @return the {@link CgKeyCodes} value, or {@link CgKeyCodes#KEY_NONE} when SDL's has none */
    public static int toCg(int sdlScancode) {
        return sdlScancode < 0 || sdlScancode >= MAX_SDL ? CgKeyCodes.KEY_NONE : TO_CG[sdlScancode];
    }

    /** @return the SDL scancode, or {@link #SDL_SCANCODE_UNKNOWN} when there is none */
    public static int toSdl(int cgKey) {
        return cgKey < 0 || cgKey >= MAX_CG ? SDL_SCANCODE_UNKNOWN : TO_SDL[cgKey];
    }

    /**
     * An SDL button to {@link CgMouseCodes}. SDL counts from 1 with middle before right; the engine
     * counts from 0 with right before middle. X1 and X2 land where GLFW's buttons 4 and 5 do, so both
     * toolkits report the same side button as the same code.
     */
    public static int mouseToCg(int sdlButton) {
        switch (sdlButton) {
            case 1: return CgMouseCodes.LEFT_BUTTON;
            case 2: return CgMouseCodes.MIDDLE_BUTTON;
            case 3: return CgMouseCodes.RIGHT_BUTTON;
            case 4: return CgMouseCodes.PAGE_FORWARD;
            case 5: return CgMouseCodes.PAGE_BACKWARD;
            default: return CgMouseCodes.NONE;
        }
    }

    /** {@link #mouseToCg}'s inverse; 0 for a code SDL has no button for. */
    public static int mouseToSdl(int cgButton) {
        switch (cgButton) {
            case CgMouseCodes.LEFT_BUTTON: return 1;
            case CgMouseCodes.MIDDLE_BUTTON: return 2;
            case CgMouseCodes.RIGHT_BUTTON: return 3;
            case CgMouseCodes.PAGE_FORWARD: return 4;
            case CgMouseCodes.PAGE_BACKWARD: return 5;
            default: return 0;
        }
    }
}
