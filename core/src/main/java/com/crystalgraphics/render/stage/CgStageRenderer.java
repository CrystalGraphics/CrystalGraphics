package com.crystalgraphics.render.stage;

/**
 * Records what it draws at a {@link CgRenderStage} into the stage's frame. Render thread.
 *
 * <pre>{@code
 * CgRenderStage.OPAQUE.register(0, frame -> {
 *     CgRasterPass pass = frame.recording().raster("mymod.pass").color(frame.target(), CgLoad.load()).camera(frame.camera());
 *     // ... chunks into pass ...
 *     pass.end();
 * });
 * }</pre>
 */
@FunctionalInterface
public interface CgStageRenderer {

    void render(CgStageFrame frame);
}
