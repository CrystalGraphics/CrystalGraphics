package com.crystalgraphics.render.graph;

import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;

/**
 * Bytes into a graph texture, on the render thread, ordered before anything that reads the texture after it was
 * recorded. What a resource cache's atlas page and a requested texture's first contents go through.
 *
 * <pre>{@code
 * recording.upload(atlasPage, target -> page.write(target.getColorTexture(0)));   // the page's storage, resolved
 * }</pre>
 *
 * <p>Throwing fails the upload's {@link CgRequest} and nothing else.</p>
 */
@FunctionalInterface
public interface CgUpload {

    void upload(CgFrameBuffer target);
}
