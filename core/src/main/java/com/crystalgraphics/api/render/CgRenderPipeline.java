package com.crystalgraphics.api.render;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.state.CgGlScope;
import com.crystalgraphics.gl.state.CgGlState;
import com.crystalgraphics.mc.compat.CgIrisCompat;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.pipeline.CgDepthPrepassRenderer;
import com.crystalgraphics.render.pipeline.CgForwardRenderer;
import com.crystalgraphics.render.pipeline.CgTransparentRenderer;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.crystalgraphics.api.state.CgGlSlot.*;

/**
 * Singleton orchestrator for the CrystalGraphics forward render pipeline.
 *
 * <p>Owns all frame-level GPU resources (per-frame UBO and per-object SSBO/TBO),
 * the command queue and pool, and the per-pass renderers. No {@code net.minecraft}
 * imports — MC-specific calls live in the mc/ package and feed this class via
 * {@link CgFrameData}.</p>
 *
 * <h3>Lifecycle</h3>
 * <pre>{@code
 * // On GL context creation:
 * CgRenderPipeline.init();           // or first getInstance() call
 *
 * // Per-frame — populate frame data, then execute:
 * CgRenderPipeline pipe = CgRenderPipeline.getInstance();
 * CgFrameData fd = pipe.getFrameData();
 * fd.viewMatrix.set(glViewBuf);
 * fd.timeSecs = elapsedSecs;
 * fd.viewportW = w; fd.viewportH = h;
 * fd.deriveFromViewMatrix();
 *
 * // Submit commands, then:
 * pipe.executeOpaquePass(partialTicks);   // after MC entity render
 * pipe.executeTransparentPass();          // after MC water/transparent render
 * pipe.endFrame();
 * // or: pipe.execute(partialTicks);      // convenience wrapper (all three)
 *
 * // On GL context destroy:
 * CgRenderPipeline.destroy();
 * }</pre>
 *
 * <h3>Pass sequence (Phase 1 MVP)</h3>
 * <ol>
 *   <li>Depth prepass — fills depth buffer; hardware early-Z eliminates overdraw in opaque pass without requiring GL_EQUAL override</li>
 *   <li>Opaque forward — GEOMETRY + ALPHA_TEST, front-to-back, auto-instanced</li>
 *   <li>Transparent — TRANSPARENT, back-to-front, per-object</li>
 * </ol>
 *
 * <h3>Anaglyph guard</h3>
 * <p>MC 1.7.10 anaglyph mode fires the hook twice per frame (once per eye).
 * The second call ("replay") reuses sorted commands without re-sorting or re-releasing.
 * Detected via {@code invocationId = worldTime * 2 + (callCount % 2)}.</p>
 */
public final class CgRenderPipeline {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    
    // ── Format constants (engine-canonical; must NOT be duplicated elsewhere) ──

    /** Default GLSL uniform block name used by {@code cg_env.glsl}. */
    public static final String FRAME_BLOCK_NAME = "CgFrameBlock";

    /**
     * Per-frame UBO format (std140, 38 floats = 152 bytes).
     *
     * <pre>
     *   mat4  cg_ViewMatrix   — floats  0–15
     *   mat4  cg_ProjMatrix   — floats 16–31
     *   vec4  cg_Time         — floats 32–35: t/20, t, t×2, t×3 (seconds)
     *   vec2  cg_Resolution   — floats 36–37: viewport width, height (pixels)
     * </pre>
     */
    public static final CgBufferFormat FRAME_FORMAT = CgBufferFormat
            .builder("CgFrameBlock", CgBufferFormat.MemoryLayout.STD140)
            .mat4("cg_ViewMatrix")
            .mat4("cg_ProjMatrix")
            .vec4("cg_Time")
            .vec2("cg_Resolution")
            .build();

    /** Default GLSL SSBO/TBO buffer name used by {@code cg_env.glsl}. */
    public static final String OBJECT_BLOCK_NAME = "CgObjectDataBuffer";

    /**
     * Per-object SSBO/TBO format (std430, 48 floats = 192 bytes).
     *
     * <pre>
     *   mat4  modelMatrix   — floats  0–15
     *   mat4  normalMatrix  — floats 16–31 (shader reads upper-left 3×3 as mat3)
     *   vec4  custom0       — floats 32–35
     *   vec4  custom1       — floats 36–39
     *   vec4  custom2       — floats 40–43
     *   vec4  custom3       — floats 44–47
     * </pre>
     *
     * <p>Use named writes; unwritten fields are auto-zeroed per record.</p>
     */
    public static final CgBufferFormat OBJECT_FORMAT = CgBufferFormat
            .builder("cg_object", CgBufferFormat.MemoryLayout.STD430)
            .mat4("modelMatrix")
            .mat4("normalMatrix")
            .vec4("custom0")
            .vec4("custom1")
            .vec4("custom2")
            .vec4("custom3")
            .build();

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static CgRenderPipeline INSTANCE;

    // ── GPU resources ─────────────────────────────────────────────────────────

    private final CgUniformBuffer frameUbo;
    private final CgShaderBuffer  objectBuffer;

    private CgFrameBuffer depthSnapshotFbo;
    private boolean isDepthBlitDone = false;

    // ── Pipeline components ───────────────────────────────────────────────────

    private final CgRenderCommandPool     pool;
    @Getter
    private final CgFrameData             frameData;
    private final CgRenderCommandQueue    commandQueue;
    private final CgForwardRenderer       forwardRenderer;
    private final CgTransparentRenderer   transparentRenderer;
    // depth prepass is disabled (no engine depth shader yet); un-comment when ready
    @SuppressWarnings("unused")
    private final CgDepthPrepassRenderer  depthPrepass;

    // ── Per-frame execution state ─────────────────────────────────────────────

    private float currentPartialTicks     = 0f;
    private boolean replayOpaque          = false;
    private boolean deleted               = false;
    private boolean irisWarningLogged = false;
    private int lastSourceFboId = 0;
    private int depthBlitMask = CgGL.GL_DEPTH_BUFFER_BIT;
    private boolean depthBlitMaskResolved = false;

    // ── Anaglyph guard ────────────────────────────────────────────────────────

    private long lastInvocationId         = -1L;
    private int  invocationCountThisTick  = 0;
    private long lastWorldTimeTick        = -1L;

    // ── Constructor ───────────────────────────────────────────────────────────

    private CgRenderPipeline() {
        this.frameUbo      = new CgUniformBuffer(FRAME_BLOCK_NAME, FRAME_FORMAT, CgBindingPoints.FRAME_DATA_UBO);
        this.objectBuffer  = CgShaderBuffer.createInternal(OBJECT_BLOCK_NAME, OBJECT_FORMAT, CgBindingPoints.OBJECT_DATA);

        this.pool             = new CgRenderCommandPool();
        this.frameData        = new CgFrameData();
        this.commandQueue     = new CgRenderCommandQueue(pool);
        this.commandQueue.setFrameData(frameData);
        this.forwardRenderer     = new CgForwardRenderer();
        this.transparentRenderer = new CgTransparentRenderer();
        this.depthPrepass        = new CgDepthPrepassRenderer();

        this.depthSnapshotFbo = CgFrameBuffer.createScreenSized("cg_depth_snapshot", CgFrameBufferFormat.DEPTH);
        clearDepthSnapshot();
    }

    // ── Command submission API ────────────────────────────────────────────────

    /** Acquires a blank command slot. Fill all fields, then call {@link #submit(CgRenderCommand)}. */
    public CgRenderCommand acquireCommand() {
        checkNotDeleted();
        return commandQueue.acquireCommand();
    }

    /** Submits a filled command. Derives normalMatrix, cameraDepth, passFlags, sortKey. */
    public void submit(CgRenderCommand cmd) {
        checkNotDeleted();
        commandQueue.submit(cmd);
    }

    /** Returns {@code true} if any commands have been submitted since the last {@code endFrame()}. */
    public boolean hasPendingCommands() {
        return commandQueue.getCommandCount() > 0;
    }

    // ── GPU buffer accessors ──────────────────────────────────────────────────

    /**
     * Returns the per-frame UBO ({@code CgFrameBlock}) that backs all material programs.
     * Primary use case: wiring the block to a custom shader not created via the material pipeline.
     *
     * @return the engine-owned per-frame UBO; never {@code null}
     */
    public CgUniformBuffer frameBuffer() {
        checkNotDeleted();
        return frameUbo;
    }

    /**
     * Returns the shared per-object SSBO/TBO that backs every material draw.
     * Write all per-object records before calling {@code material.bind()}.
     *
     * @return the engine-owned object buffer; never {@code null}
     */
    public CgShaderBuffer objectBuffer() {
        checkNotDeleted();
        return objectBuffer;
    }

    // ── Execute API (split and convenience) ───────────────────────────────────

    /**
     * Uploads frame data into the UBO and binds both engine buffers to their GL slots.
     * Called once per non-replay frame at the start of the opaque pass.
     * partialTicks is applied to timeSecs here (not stored permanently in frameData).
     */
    private void uploadFrameData(CgFrameData fd) {
        float savedTime = fd.timeSecs;
        fd.timeSecs += currentPartialTicks * 0.05f;
        frameUbo.writer()
                .reset()
                .beginRecord()
                .mat4("cg_ViewMatrix", fd.viewMatrix)
                .mat4("cg_ProjMatrix", fd.projMatrix)
                .vec4("cg_Time",
                        fd.timeSecs / 20f, fd.timeSecs,
                        fd.timeSecs * 2f, fd.timeSecs * 3f)
                .vec2("cg_Resolution", (float) fd.viewportW, (float) fd.viewportH)
                .endRecord();
        fd.timeSecs = savedTime;
        frameUbo.upload();
    }

    private void bindFrameResources() {
        frameUbo.bind();
        objectBuffer.bind();
        CgTexture depthSnap = getDepthSnapshot();
        if (depthSnap != null) depthSnap.bind(CgBindingPoints.DEPTH_TEXTURE_UNIT);
    }

    /**
     * Uploads the current {@link #getFrameData()} into the frame UBO and binds both
     * engine buffers (frame UBO + object buffer) to their GL binding points.
     *
     * <p>Use this in manual-bind scenes (harness, non-MC consumers) that call
     * {@link CgMaterial#bind()} directly
     * rather than submitting commands through {@link #submit(CgRenderCommand)}.
     * It is a no-op equivalent of the frame-upload portion of {@link #executeOpaquePass(float, int)},
     * without sorting or dispatching any renderers.</p>
     *
     * <p>Callers are responsible for applying a {@link CgGlScope}
     * if they need GL state save/restore around their manual draw calls.</p>
     */
    public void prepareFrame() {
        checkNotDeleted();
        uploadFrameData(frameData);
        bindFrameResources();
    }

    /**
     * Called after MC entity render. Blits the scene depth snapshot, then runs
     * sort + frame UBO upload + depth prepass + opaque forward.
     * Sets up the anaglyph guard — must be called before {@link #executeTransparentPass()}.
     *
     * @param partialTicks MC render partial tick (0.0–1.0)
     * @param sourceFboId  GL framebuffer ID to read depth from (MC's main render target).
     *                     Pass {@code 0} when rendering to the default framebuffer (harness).
     * @return {@code false} if there are no pending commands (caller may skip transparent pass)
     */
    public boolean executeOpaquePass(float partialTicks, int sourceFboId) {
        checkNotDeleted();
        if (!hasPendingCommands()) return false;

        if (!irisWarningLogged && CgIrisCompat.isShaderPackActive()) {
            irisWarningLogged = true;
            LOGGER.warn(
                    "[CrystalGraphics] Iris/Oculus shader pack detected. CG geometry renders " + "into the main framebuffer outside Iris's deferred GBuffer chain — geometry " + "will appear unlit under shader packs with deferred pipelines. " + "cg_DepthBuffer remains valid.");
        }

        blitDepthSnapshot(sourceFboId);

        this.currentPartialTicks = partialTicks;

        // Anaglyph guard — detect replay invocation (second eye in anaglyph mode).
        // Only active when anaglyphModeEnabled is true; in harness/non-anaglyph mode,
        // replayOpaque is always false so every frame sorts and releases normally.
        if (frameData.anaglyphModeEnabled) {
            long worldTime = frameData.getCurrentWorldTime();
            if (worldTime != lastWorldTimeTick) {
                lastWorldTimeTick       = worldTime;
                invocationCountThisTick = 0;
            }
            long invId = worldTime * 2L + (invocationCountThisTick % 2L);
            replayOpaque = (invId == lastInvocationId);
            lastInvocationId = invId;
            invocationCountThisTick++;
        } else {
            replayOpaque = false;
        }

        try (CgGlScope scope = CgGlState.save(VERTEX_INPUT, PROGRAM, DEPTH, STENCIL, ALPHA_TEST, BLEND, CULL,
                COLOR_MASK, TEXTURES)) {

            if (!replayOpaque) {
                commandQueue.sort();
                uploadFrameData(frameData);
            }
            bindFrameResources();

            depthPrepass.execute(
                commandQueue.getSortedOpaque(), commandQueue.getOpaqueCount(), this);

            forwardRenderer.execute(
                commandQueue.getSortedOpaque(), commandQueue.getOpaqueCount(), this);
        }
        return true;
    }

    /**
     * Called after MC water/transparent render. Runs the transparent back-to-front pass.
     * Re-establishes depth test state for the transparent pass.
     *
     * @return {@code false} if there are no transparent commands
     */
    public boolean executeTransparentPass() {
        checkNotDeleted();
        if (commandQueue.getTransparentCount() == 0) return false;

        blitDepthSnapshot(lastSourceFboId);

        try (CgGlScope scope = CgGlState.save(VERTEX_INPUT, PROGRAM, DEPTH, BLEND, CULL, TEXTURES)) {
            bindFrameResources();
            transparentRenderer.execute(
                    commandQueue.getSortedTransparent(), commandQueue.getTransparentCount(), this);
        }
        
        return true;
    }

    /**
     * Releases the command pool and resets the per-frame depth snapshot guard.
     * Call after {@link #executeTransparentPass()}.
     * Anaglyph replay: pool is NOT released on replay — commands are reused for the second eye.
     */
    public void endFrame() {
        checkNotDeleted();
        if (!replayOpaque) {
            commandQueue.releaseAll();
            isDepthBlitDone = false;
            lastSourceFboId = 0;
        }
    }

    /**
     * Convenience wrapper: {@link #executeOpaquePass} → {@link #executeTransparentPass} →
     * {@link #endFrame()}. Use from the harness or a single-hook render path.
     *
     * @param partialTicks MC render partial tick (0.0–1.0)
     */
    public void execute(float partialTicks) {
        executeOpaquePass(partialTicks, 0);
        executeTransparentPass();
        endFrame();
    }

    // ── Depth snapshot handling ─────────────────────────────────────────────────────────────

    /**
     * Blits scene depth from {@code sourceFboId} into the depth snapshot FBO, then marks the
     * blit as done so subsequent calls within the same frame are no-ops.
     *
     * <p>On the first call ever, probes the source FBO's stencil attachment once and caches the
     * optimal blit mask for the context lifetime — see {@link CgFrameBuffer#optimalDepthBlitMask}.
     *
     * @param sourceFboId GL framebuffer ID to read depth from; {@code 0} for the default FB (harness)
     */
    private void blitDepthSnapshot(int sourceFboId) {
        if (depthSnapshotFbo == null || isDepthBlitDone) return;
        if (!depthBlitMaskResolved) {
            depthBlitMask = CgFrameBuffer.optimalDepthBlitMask(sourceFboId);
            depthBlitMaskResolved = true;
        }
        depthSnapshotFbo.blitFrom(sourceFboId, depthBlitMask);
        isDepthBlitDone = true;
        lastSourceFboId = sourceFboId;
    }

    private void clearDepthSnapshot() {
        if (depthSnapshotFbo != null) depthSnapshotFbo.clearDepthStencil();
    }

    public CgTexture getDepthSnapshot() {
        return depthSnapshotFbo != null ? depthSnapshotFbo.getDepthTexture() : null;
    }
    
    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Creates and installs the singleton. Must be called on the GL thread after context
     * creation, before any {@link #getInstance()} calls (getInstance() auto-inits).
     */
    public static void init() {
        if (INSTANCE != null) return;
        CgBindingPoints.init(CgCapabilities.detect());
        INSTANCE = new CgRenderPipeline();
    }

    /** Returns the singleton, auto-initialising on first call. */
    public static CgRenderPipeline getInstance() {
        if (INSTANCE == null) init();
        return INSTANCE;
    }

    /**
     * Destroys all owned GPU resources and nulls the singleton.
     * Called by {@code CgGraphicsLifecycle.destroyContext()}. A no-op if never initialized.
     */
    public static void destroy() {
        if (INSTANCE != null) {
            INSTANCE.delete();
            INSTANCE = null;
        }
    }

    public static void onSceneResize() {
        if (INSTANCE != null) INSTANCE.clearDepthSnapshot();
    }

    // ── Private helpers ───────────────────────────────────────────────────────
    
    private void delete() {
        if (!deleted) {
            deleted = true;
            commandQueue.releaseAll();
            frameUbo.unbind();
            frameUbo.delete();
            objectBuffer.delete();
            depthSnapshotFbo = null;  // GL object deleted by CgFrameBufferRegistry.deleteAll()
        }
    }

    private void checkNotDeleted() {
        if (deleted) throw new IllegalStateException("CgRenderPipeline has been deleted");
    }
}
