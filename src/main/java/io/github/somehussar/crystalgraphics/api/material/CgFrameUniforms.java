package io.github.somehussar.crystalgraphics.api.material;

import lombok.Data;
import lombok.experimental.Accessors;
import org.joml.Matrix4f;

/**
 * Mutable per-frame uniform data holder passed to
 * {@link CgMaterialPipeline#beginFrame()}.
 *
 * <p>Allocate one instance at startup and mutate fields in-place each frame —
 * avoids per-frame allocation at 60+ FPS.</p>
 *
 * <p>Adding a new frame global: add a field here AND a corresponding field to
 * {@link CgMaterialPipeline#FRAME_BLOCK_FORMAT} AND one named-write line to
 * {@link CgMaterialPipeline#beginFrame()}. Zero callers break.</p>
 */
@Data
@Accessors(fluent = true) // This removes 'set'/'get' and enables chaining
public class CgFrameUniforms {

    /** View matrix. */
    private Matrix4f view;

    /** Projection matrix. */
    private Matrix4f proj;

    /** Elapsed time in seconds. */
    private float timeSecs;

    /** Viewport width in pixels. */
    private int viewportW;

    /** Viewport height in pixels. */
    private int viewportH;
}
