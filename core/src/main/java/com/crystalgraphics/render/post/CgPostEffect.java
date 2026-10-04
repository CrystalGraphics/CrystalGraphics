package com.crystalgraphics.render.post;

/**
 * One effect of the post stack, recorded at its {@link CgPostPoint} every firing it is {@link #active}. The engine's
 * own effects (bloom) and a mod's implement the same interface.
 *
 * <pre>{@code
 * public final class Outline implements CgPostEffect {
 *     public CgPostPoint point() { return CgPostPoint.AFTER_COMPOSITE; }
 *     public boolean active(CgPostContext post) { return enabled; }
 *     public void record(CgPostContext post) {
 *         CgRasterPass pass = post.recording().raster(post.target(), CgLoad.load(), post.constants(), null, CgOrder.SORTED)
 *                 .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT);
 *         // ... a full-screen triangle reading cg_DepthBuffer ...
 *         pass.end();
 *     }
 * }
 * CgRenderStage.Registration outline = CgPostStack.get().add(new Outline());
 * outline.close();   // removes it
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, inside the stage firing. Record only; nothing executes until the firing does.</li>
 *   <li>An effect that feeds the composite does so through {@link CgPostContext#composite()}, at
 *       {@link CgPostPoint#BEFORE_COMPOSITE}.</li>
 *   <li>{@link #active} is asked every firing: an inactive effect records nothing and costs nothing.</li>
 * </ul>
 */
public interface CgPostEffect {

    CgPostPoint point();

    /** Its place among the effects at its point, ascending; ties in the order they were added. */
    default int order() {
        return 0;
    }

    /** Whether it records this firing. */
    boolean active(CgPostContext post);

    void record(CgPostContext post);
}
