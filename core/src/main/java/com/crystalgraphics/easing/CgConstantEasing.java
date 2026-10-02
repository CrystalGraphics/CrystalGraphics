package com.crystalgraphics.easing;

/** The same value at every moment: {@code CgEasings.linear(value)}. */
public final class CgConstantEasing implements CgEasing {

    private final double value;

    CgConstantEasing(double value) {
        this.value = value;
    }

    @Override
    public double ease(double time) {
        return value;
    }
}
