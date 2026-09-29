package com.crystalgraphics.platform.gl.tracked.glsl;

/** A GL GLSL program the rewrite cannot carry to Vulkan, naming what and where; the link fails with it. */
public final class GlslRewriteException extends RuntimeException {

    public GlslRewriteException(String message) {
        super(message);
    }
}
