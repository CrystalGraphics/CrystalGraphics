package com.crystalgraphics.freetype;

import java.util.Objects;

public final class FTVariationAxisInfo {

    private final String tag;
    private final String name;
    private final float minValue;
    private final float defaultValue;
    private final float maxValue;

    public FTVariationAxisInfo(String tag,
                               String name,
                               float minValue,
                               float defaultValue,
                               float maxValue) {
        if (tag == null) {
            throw new IllegalArgumentException("tag must not be null");
        }
        this.tag = tag;
        this.name = name;
        this.minValue = minValue;
        this.defaultValue = defaultValue;
        this.maxValue = maxValue;
    }

    public String getTag() {
        return tag;
    }

    public String getName() {
        return name;
    }

    public float getMinValue() {
        return minValue;
    }

    public float getDefaultValue() {
        return defaultValue;
    }

    public float getMaxValue() {
        return maxValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FTVariationAxisInfo)) {
            return false;
        }
        FTVariationAxisInfo that = (FTVariationAxisInfo) o;
        return Float.compare(that.minValue, minValue) == 0
                && Float.compare(that.defaultValue, defaultValue) == 0
                && Float.compare(that.maxValue, maxValue) == 0
                && tag.equals(that.tag)
                && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tag, name, minValue, defaultValue, maxValue);
    }

    @Override
    public String toString() {
        return "FTVariationAxisInfo{" +
                "tag='" + tag + '\'' +
                ", name='" + name + '\'' +
                ", minValue=" + minValue +
                ", defaultValue=" + defaultValue +
                ", maxValue=" + maxValue +
                '}';
    }
}
