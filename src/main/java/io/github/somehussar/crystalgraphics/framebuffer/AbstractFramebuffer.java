package io.github.somehussar.crystalgraphics.framebuffer;

import io.github.somehussar.crystalgraphics.framebuffer.capabilities.FramebufferCapabilities;

public abstract class AbstractFramebuffer {

    protected final int width, height;
    protected final FramebufferCapabilities caps;

    protected void addToList() {
        FramebufferHandler.createdFramebuffers.add(this);
    }

    public AbstractFramebuffer(int width, int height, FramebufferCapabilities capabilities) {
        addToList();

        this.width = width;
        this.height = height;
        this.caps = capabilities.copy();
    }

    public abstract void drawBuffers(int... drawBuffers);
    public abstract void bind();
    public abstract void unbind();

    public void delete() {
        freeMemory();
        FramebufferHandler.createdFramebuffers.remove(this);
    }

    protected abstract void freeMemory();
}
