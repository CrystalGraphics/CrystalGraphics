package io.github.somehussar.crystalgraphics.gl.text;

import lombok.Value;

@Value
public final class CgTextConstraints {

    float maxWidth;
    float maxHeight;

    public static final CgTextConstraints UNBOUNDED = new CgTextConstraints(0.0f, 0.0f);

    public CgTextConstraints(float maxWidth, float maxHeight) {
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
    }

    public static CgTextConstraints maxWidth(float maxWidth) {
        return new CgTextConstraints(maxWidth, 0.0f);
    }

    public static CgTextConstraints maxHeight(float maxHeight) {
        return new CgTextConstraints(0.0f, maxHeight);
    }

    public static CgTextConstraints bounded(float maxWidth, float maxHeight) {
        return new CgTextConstraints(maxWidth, maxHeight);
    }
}
