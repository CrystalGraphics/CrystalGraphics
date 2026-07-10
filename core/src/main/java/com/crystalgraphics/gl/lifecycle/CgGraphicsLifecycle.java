package com.crystalgraphics.gl.lifecycle;

import com.crystalgraphics.demo.CgRenderDemo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.api.material.CgMaterialRegistry;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.gl.buffer.CgQuadIndexBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.debug.CgDebugBlit;
import com.crystalgraphics.gl.framebuffer.CgFrameBufferRegistry;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.mesh.CgMeshRegistry;
import com.crystalgraphics.gl.render.CgInstanceRenderer;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.gl.vertex.CgInstanceVertexArrayBinding;
import com.crystalgraphics.gl.vertex.CgVertexArray;
import com.crystalgraphics.gl.vertex.CgVertexArrayRegistry;
import com.crystalgraphics.gl.vertex.CgVertexBufferRegistry;

/**
 * Coordinates teardown of all CrystalGraphics GL resources in the correct order.
 *
 * <p>Call {@link #destroyContext()} exactly once when the OpenGL context is being
 * destroyed (e.g. game shutdown, render context reset). All VAOs must be deleted
 * before their referenced VBOs to avoid stale GPU state.</p>
 *
 * <h3>Canonical 4-step teardown order</h3>
 * <ol>
 *   <li>{@link CgVertexArrayRegistry#deleteAll()} — ALL VAOs (instanced first, then non-instanced inside {@code deleteAll})</li>
 *   <li>{@link CgMeshRegistry#deleteAll()} — static mesh VBOs + IBOs + per-mesh VAOs</li>
 *   <li>{@link CgVertexBufferRegistry#deleteAll()} — ALL stream VBOs (base + instance)</li>
 *   <li>{@link CgQuadIndexBuffer#freeAll()} — shared quad IBO</li>
 * </ol>
 *
 * <p>After all GL objects are freed, backend-capability caches are reset so that
 * context recreation will re-probe the new context's capabilities.</p>
 */
public final class CgGraphicsLifecycle {
    
    private static volatile boolean initialized = false;
    private static int currentWidth = -1, currentHeight = -1;
    
    private CgGraphicsLifecycle() {}

    /**
     * Initializes engine GL resources that require an active GL context.
     * Must be called once on the GL thread after context creation,
     * before any material or fallback-texture usage.
     */
    public static void initContext(int width, int height) {
        CgPlatform.gl().initContext();
        onResize(width, height);
        CgRenderPipeline.init();
        CgFallbackTextures.init();

        initialized = true;
    }

    /**
     * Notifies CrystalGraphics that the window has been resized.
     * Triggers recreation of all screen-sized framebuffers.
     *
     * @param width  new viewport width in pixels
     * @param height new viewport height in pixels
     */
    public static void onResize(int width, int height) {
        CgFrameBufferRegistry.get().onResize(width, height);
        CgRenderPipeline.onSceneResize();

        currentWidth = width;
        currentHeight = height;
    }

    /**
     * Called before MC's translucent terrain pass. Lazy-initialises the engine on the
     * first call. Performs the per-frame depth snapshot blit, then executes CG's opaque
     * passes (depth prepass + opaque forward).
     *
     * @param partialTick frame interpolation factor
     * @param w           current viewport width (pixels)
     * @param h           current viewport height (pixels)
     * @param sourceFboId GL framebuffer ID to read depth from (MC's main render target FBO)
     */
    public static void onOpaquePass(float partialTick, int w, int h, int sourceFboId) {
        if (!initialized) initContext(w, h);
        else if (w != currentWidth || h != currentHeight) onResize(w, h);
        CgRenderDemo.INSTANCE.renderOpaque(partialTick, w, h, sourceFboId);
    }

    /**
     * Called after MC's translucent terrain + particles pass. Executes CG's transparent
     * pass then ends the frame (releases the render command pool).
     *
     * <p>No-op if the engine context has not been initialised yet (e.g. GUI-only frames).</p>
     */
    public static void onTransparentPass() {
        if (!initialized) return;
        CgRenderDemo.INSTANCE.renderTransparent();
    }

    /**
     * Destroys all CrystalGraphics GL resources in canonical dependency order,
     * then resets all backend-capability caches.
     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     *
     * <p>After this call, all VAOs, VBOs, IBOs, and cached GL capability flags are
     * cleared. A new GL context can be initialised immediately afterwards.</p>
     */
    public static void destroyContext() {
        // Step 1: ALL VAOs — CgVertexArrayRegistry.deleteAll() deletes instanced VAOs first,
        //   then non-instanced VAOs, ensuring no VBO referenced by a VAO is deleted first.
        CgVertexArrayRegistry.get().deleteAll();

        // Step 2: Static mesh VBOs + IBOs + per-mesh VAOs.
        //   Mesh VAOs reference mesh VBOs, so meshes must be deleted after streaming VAOs
        //   (handled in step 1) but before streaming VBOs (step 3).
        CgMeshRegistry.get().deleteAll();

        // Step 3: ALL stream VBOs (base + instance streams).
        CgVertexBufferRegistry.get().deleteAll();

        // Step 4: Shared quad IBO.
        CgQuadIndexBuffer.freeAll();

        // Step 5: Free all cached textures.
        CgTextureManager.get().freeAll();

        // Step 5b: Free engine fallback textures.
        CgFallbackTextures.destroy();

        // Step 7a: Material instances (property UBOs) + their backing shader assets (GL programs).
        CgMaterialRegistry.get().deleteAll();
        CgMaterialShaderRegistry.get().deleteAll();

        // Step 7b: User-created SSBO/TBO/UBO resources managed by CgShaderBufferRegistry.
        //   Must be freed before the GL context is lost. Engine-owned pipeline buffers
        //   (frameUbo, objectBuffer in CgRenderPipeline) are NOT in this registry —
        //   they are freed in step 7c.
        CgShaderBufferRegistry.get().deleteAll();

        // Step 8: Pipeline-owned frame UBO + object SSBO + command queue.
        // Dispose the render demo first so its mesh/material handles are released before
        // the registries they reference are torn down.
        CgRenderDemo.INSTANCE.dispose();
        // CgRenderPipeline owns both the GPU pipeline buffers (formerly CgMaterialPipeline)
        // and the render command queue; one destroy call handles all of it.
        CgRenderPipeline.destroy();

        // Step 9: All owned framebuffers — must be first.
        CgFrameBufferRegistry.get().deleteAll();
        
        
        // Step 10: Debug utilities (lazy singleton — no-op if never used).
        CgDebugBlit.dispose();

        // Reset all backend-capability caches so context recreation re-probes correctly.
        CgCapabilities.clearCache();
        CgVertexArray.resetCoreCache();
        CgInstanceVertexArrayBinding.resetCoreCache();
        CgInstanceRenderer.resetCoreCache();

        initialized = false;
        currentWidth = -1;
        currentHeight = -1;
    }
}
