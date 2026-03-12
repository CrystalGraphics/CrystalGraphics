package com.crystalgraphics.harfbuzz;

/**
 * HarfBuzz text direction constants. Map to hb_direction_t.
 * Use HB_TAG macro pattern: each direction is a 4-char tag packed into int.
 */
public final class HBDirection {
    public static final int HB_DIRECTION_INVALID = 0;
    public static final int HB_DIRECTION_LTR = 4;
    public static final int HB_DIRECTION_RTL = 5;
    public static final int HB_DIRECTION_TTB = 6;
    public static final int HB_DIRECTION_BTT = 7;

    private HBDirection() {
    }

    public static boolean isHorizontal(int direction) {
        return direction == HB_DIRECTION_LTR || direction == HB_DIRECTION_RTL;
    }

    public static boolean isVertical(int direction) {
        return direction == HB_DIRECTION_TTB || direction == HB_DIRECTION_BTT;
    }

    public static boolean isForward(int direction) {
        return direction == HB_DIRECTION_LTR || direction == HB_DIRECTION_TTB;
    }

    public static boolean isBackward(int direction) {
        return direction == HB_DIRECTION_RTL || direction == HB_DIRECTION_BTT;
    }
}
