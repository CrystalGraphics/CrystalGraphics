package io.github.somehussar.crystalgraphics.framebuffer;

import io.github.somehussar.crystalgraphics.framebuffer.capabilities.FramebufferCapabilities;
import io.github.somehussar.crystalgraphics.framebuffer.impl.ARBFramebufferHandler;
import io.github.somehussar.crystalgraphics.framebuffer.impl.CoreFramebufferHandler;
import io.github.somehussar.crystalgraphics.framebuffer.impl.EXTFramebufferHandler;

public final class FramebufferFactory {

    private FramebufferFactory() {}

    public static AbstractFramebuffer createFramebuffer(FramebufferCapabilities caps, int width, int height) {
        if (CoreFramebufferHandler.get().isSupported(caps)) {
            return CoreFramebufferHandler.get().create(caps, width, height);
        }

        if (ARBFramebufferHandler.get().isSupported(caps)) {
            return ARBFramebufferHandler.get().create(caps, width, height);
        }

        if (EXTFramebufferHandler.get().isSupported(caps)) {
            return EXTFramebufferHandler.get().create(caps, width, height);
        }

        throw new UnsupportedOperationException(
            "No frame buffer implementation supports requested capabilities: " + caps
        );
    }
}