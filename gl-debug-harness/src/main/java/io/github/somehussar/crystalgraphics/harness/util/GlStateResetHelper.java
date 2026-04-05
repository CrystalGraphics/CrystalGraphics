package io.github.somehussar.crystalgraphics.harness.util;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Shared GL state reset helper for the harness render pipeline.
 *
 * <p>After a scene's {@code renderFrame()} call, various GL objects may remain
 * bound (shader programs, VAOs, VBOs, textures) and state flags may be left in
 * non-default configurations (depth test off, blend on, cull face on). This
 * helper provides a single canonical reset sequence that restores GL state to
 * the baseline expected by subsequent pipeline passes (floor, pause overlay,
 * HUD).</p>
 *
 * <p>This replaces the duplicated reset logic that previously existed in both
 * {@code InteractiveSceneRunner.GL20ResetHelper} and
 * {@code InteractiveWorldTextScene.renderFrame()}.</p>
 */
public final class GlStateResetHelper {

    private GlStateResetHelper() { }

    /**
     * Resets GL state after a scene render pass to ensure subsequent overlay
     * passes (pause screen, HUD) render correctly.
     *
     * <p>Specifically:</p>
     * <ul>
     *   <li>Unbinds shader program (scenes may leave MSDF/bitmap shaders bound)</li>
     *   <li>Unbinds VAO (scene geometry VAOs must not leak into overlay draws)</li>
     *   <li>Unbinds VBO and IBO (CgGlyphVbo or cube VBOs may remain bound)</li>
     *   <li>Unbinds texture on unit 0 (atlas textures may remain bound)</li>
     *   <li>Enables depth test with LEQUAL func and depth writes on</li>
     *   <li>Disables blend (FloorRenderer expects blend OFF)</li>
     *   <li>Disables cull face (drawWorld() may leave GL_CULL_FACE enabled)</li>
     *   <li>Unbinds FBO to ensure we render to the default backbuffer</li>
     * </ul>
     */
    public static void resetAfterScene() {
        // Unbind shader program — scenes may leave MSDF/bitmap shaders bound
        GL20.glUseProgram(0);

        // Unbind VAO — scene geometry VAOs must not leak into overlay draws
        GL30.glBindVertexArray(0);

        // Unbind VBO and IBO — CgGlyphVbo or cube VBOs may remain bound
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        // Unbind texture on unit 0 — atlas textures may remain bound
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        // Restore depth state — FloorRenderer requires depth test ON + depth writes ON
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        // Disable blend — FloorRenderer expects blend OFF; HUD/pause manage their own
        GL11.glDisable(GL11.GL_BLEND);

        // Disable cull face — drawWorld() enables GL_CULL_FACE for single-sided text
        GL11.glDisable(GL11.GL_CULL_FACE);

        // Ensure we're rendering to the default framebuffer (backbuffer)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }
}
