package com.crystalgraphics.easing;

/** A straight ramp from {@code startY} to {@code endY}, clamped: {@code CgEasings.linear(start, end)}. */
public final class CgLinearEasing implements CgEasing {

    private final double startY, deltaY;

    CgLinearEasing(double startY, double endY) {
        this.startY = startY;
        this.deltaY = endY - startY;
    }

    @Override
    public double ease(double time) {
        if (time <= 0) return startY;
        if (time >= 1) return startY + deltaY;
        return startY + time * deltaY;
    }
}
