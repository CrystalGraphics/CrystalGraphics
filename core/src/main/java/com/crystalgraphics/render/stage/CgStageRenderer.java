package com.crystalgraphics.render.stage;

/**
 * Records what it draws at a {@link CgRenderStage} into the stage's frame. Render thread.
 *
 * <pre>{@code
 * CgRenderStage.WORLD_OPAQUE.register(0, frame -> {
 *     CgRasterPass pass = frame.pass(myConstants, CgOrder.SORTED);
 *     // ... chunks into pass ...
 *     pass.end();
 * });
 * }</pre>
 */
@FunctionalInterface
public interface CgStageRenderer {

    void render(CgStageFrame frame);
}
