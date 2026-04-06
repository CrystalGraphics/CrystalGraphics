package io.github.somehussar.crystalgraphics.api.font;

import lombok.Value;

@Value
public class CgFontAxisInfo {

    String tag;
    String name;
    float minValue;
    float defaultValue;
    float maxValue;

    public CgFontAxisInfo(String tag,
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
}
