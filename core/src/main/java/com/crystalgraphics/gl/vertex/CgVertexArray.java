package com.crystalgraphics.gl.vertex;


import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.gl.CgGL;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;
import lombok.Getter;

/**
 * VAO wrapper that owns vertex array creation, attribute pointer setup, bind/unbind, and deletion.
 *
 * <p>Call {@link #onContextDestroyed()} when the GL context goes, so the duplicate-name check in
 * {@code gen()} starts empty for the next one. {@link CgGraphicsLifecycle#destroyContext()} does.</p>
 */
public final class CgVertexArray {

    @Getter
    private final int vaoId;

    private CgVertexArray(int vaoId) {
        this.vaoId = vaoId;
    }

    /**
     * Creates a new {@link CgVertexArray}.
     *
     * @return a new VAO wrapper
     */
    public static CgVertexArray create() {
        return new CgVertexArray(gen());
    }

    public void bind() {
        bind(vaoId);
    }

    public void unbind() {
        bind(0);
    }

    public void delete() {
        delete(vaoId);
    }

    public void configure(CgVertexFormat format) {
        bind();
        for (int i = 0; i < format.getAttributeCount(); i++) {
            pointer(i, format.getAttribute(i), format.getStride(), format.getAttribute(i).getOffset());
            CgGL.glEnableVertexAttribArray(i);
        }
    }

    /** Points attribute {@code index} at the bound array buffer: an integer attribute through {@code glVertexAttribIPointer}. */
    public static void pointer(int index, CgVertexAttribute attr, int stride, long offset) {
        int type = attr.getType().getGlConstant();
        if (attr.isInteger()) CgGL.glVertexAttribIPointer(index, attr.getComponents(), type, stride, offset);
        else CgGL.glVertexAttribPointer(index, attr.getComponents(), type, attr.isNormalized(), stride, offset);
    }

    /**
     * Re-issues glVertexAttribPointer with a new base offset. Does NOT re-enable
     * attribute arrays (they're already enabled and stored in VAO state from configure()).
     * This is the fast path after each stream buffer commit in the sync ring-buffer.
     */
    public void reconfigureWithOffset(CgVertexFormat format, int dataOffset) {
        bind();
        for (int i = 0; i < format.getAttributeCount(); i++) {
            pointer(i, format.getAttribute(i), format.getStride(), dataOffset + format.getAttribute(i).getOffset());
        }
    }

    /**
     * Generates a raw VAO id
     */
    private static int gen() {
        int id = CgGL.glGenVertexArrays();
        if (!LIVE.add(id)) {
            // Being handed a name we still hold means the two calls ran against DIFFERENT contexts:
            // buffers and textures are shared between GL contexts, container objects are not. The
            // second owner then configures the first's VAO, and a mesh drawing another mesh's
            // attributes rasterises nothing and raises no GL error. On 1.7.10 the second context is
            // FML's splash screen, which is why no GL work may happen during mod loading.
            LOGGER.warn("[cg-vao] glGenVertexArrays returned {}, which this process already owns. "
                    + "Two contexts are in play and one VAO now has two owners.", id);
        }
        return id;
    }

    /** @see #gen() */
    private static final Set<Integer> LIVE = new HashSet<>();

    private static final Logger LOGGER = LogManager.getLogger("CgVertexArray");

    /**
     * Generates and returns a raw VAO id without wrapping it in a {@link CgVertexArray} object.
     *
     * <p>For a class that manages its own VAO's lifecycle ({@code CgMesh}) and wants a raw id, not a wrapper.</p>
     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     *
     * @return the generated VAO id
     */
    public static int createRawVaoId() {
        return gen();
    }

    /**
     * Deletes a raw VAO id. Counterpart to {@link #createRawVaoId()}.

     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     *
     * @param vaoId the VAO id to delete (from a prior {@link #createRawVaoId()} call)
     */
    public static void deleteRaw(int vaoId) {
        delete(vaoId);
    }

    /**
     * Binds a VAO id.
     *
     * <p>Pass {@code 0} to unbind (restore default VAO state).</p>
     *
     * @param vao VAO id to bind, or {@code 0} to unbind
     */
    public static void bind(int vao) {
        CgGL.glBindVertexArray(vao);
    }

    /**
     * Deletes a VAO id.
     *
     * @param vao VAO id to delete
     */
    public static void delete(int vao) {
        LIVE.remove(vao);
        CgGL.glDeleteVertexArrays(vao);
    }

    /** Forgets every VAO name this process holds; the context that owned them is gone. */
    public static void onContextDestroyed() {
        LIVE.clear();
    }
}
