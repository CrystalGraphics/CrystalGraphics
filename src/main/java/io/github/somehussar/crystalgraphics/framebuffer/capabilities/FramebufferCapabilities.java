package io.github.somehussar.crystalgraphics.framebuffer.capabilities;


import java.util.EnumSet;
import java.util.Set;

import static io.github.somehussar.crystalgraphics.framebuffer.capabilities.FramebufferFeature.*;

public final class FramebufferCapabilities {
    public static final FramebufferCapabilities DEFAULT =
            new FramebufferCapabilities().with(FEATURE_DEPTH_BUFFER);


    private final EnumSet<FramebufferFeature> capabilities;

    public FramebufferCapabilities() {
        this.capabilities = EnumSet.noneOf(FramebufferFeature.class);
    }
    public FramebufferCapabilities(Set<FramebufferFeature> capabilities) {
        this.capabilities = EnumSet.copyOf(capabilities);
    }

    public FramebufferCapabilities with(FramebufferFeature cap) {
        EnumSet<FramebufferFeature> newCaps = EnumSet.copyOf(this.capabilities);
        newCaps.add(cap);
        return new FramebufferCapabilities(newCaps);
    }

    public Set<FramebufferFeature> getAll() {
        return EnumSet.copyOf(capabilities);
    }

    public boolean has(FramebufferFeature cap) {
        return capabilities.contains(cap);
    }

}
