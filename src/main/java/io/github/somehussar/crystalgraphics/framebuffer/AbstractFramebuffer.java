package io.github.somehussar.crystalgraphics.framebuffer;

import io.github.somehussar.crystalgraphics.framebuffer.capabilities.FramebufferCapabilities;

public abstract class AbstractFramebuffer {
    static final AbstractFramebuffer DUMMY_BUFFER = new AbstractFramebuffer(0, 0, 0, new FramebufferCapabilities(), true) {
        @Override
        public void drawBuffers(int... drawBuffers) {

        }

        @Override
        public void bind() {

        }

        @Override
        public void unbind() {

        }

        @Override
        protected void freeMemory() {

        }
    };

    protected final int framebufferPointer;
    protected final int width, height;
    protected final FramebufferCapabilities caps;

    protected final boolean doWeOwnThisBuffer;

    public AbstractFramebuffer(int framebuffer, int width, int height, FramebufferCapabilities capabilities, boolean doWeOwnThisBuffer) {
        this.framebufferPointer = framebuffer;
        this.width = width;
        this.height = height;
        this.caps = capabilities.copy();

        this.doWeOwnThisBuffer = doWeOwnThisBuffer;

        if (doWeOwnThisBuffer) {
            FramebufferHandler.createdFramebuffers.add(this);
        }
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
