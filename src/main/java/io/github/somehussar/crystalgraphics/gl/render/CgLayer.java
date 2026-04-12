package io.github.somehussar.crystalgraphics.gl.render;

import com.github.bsideup.jabel.Desugar;
import org.joml.Matrix4f;

/**
 * Common interface for render layers managed by {@link CgBufferSource}.
 *
 * <p>Each layer owns a {@link CgBatchRenderer} and a {@link CgRenderState}. The
 * lifecycle is: {@link #begin} → vertex submission → {@link #flush} (repeatable) → {@link #end}.
 * Layers are flushed in registration (painter's) order by the owning buffer source.</p>
 *
 * <p>Implementations:</p>
 * <ul>
 *   <li>{@link CgRenderLayer} — fixed or no texture; state applied once per flush</li>
 *   <li>{@link CgDynamicTextureRenderLayer} — texture changes mid-frame trigger auto-flush</li>
 * </ul>
 */
public interface CgLayer {
    void begin(Matrix4f projection);
    void flush();
    void end();
    boolean isDirty();
    String getName();
    void delete();

    /**
     * Type-safe key for looking up layers in {@link CgBufferSource}.
     * Identity is by {@code name} equality, not object identity.
     *
     * @param <T> the concrete layer type this key addresses
     */
    @Desugar
    record Key<T extends CgLayer>(String name) {

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && name.equals(((Key<?>) o).name);
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
