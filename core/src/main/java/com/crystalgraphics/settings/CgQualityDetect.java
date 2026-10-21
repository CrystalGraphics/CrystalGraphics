package com.crystalgraphics.settings;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

/**
 * Chooses {@link CgGraphicsSettings#QUALITY} from the GPU's speed at a fresh install: Unreal's scalability auto-detect.
 * A fixed pass is timed once, its time made an index on Unreal's scale (100 a good GPU of 2014, a GTX 670), and the
 * index mapped to a tier by Unreal's thresholds: below 18 Low, below 42 Medium, below 115 High, else Ultra. The tier is
 * saved, so it runs once; a player's choice always wins after.
 *
 * <pre>{@code
 * // nothing to call: CgGraphicsLifecycle.tickFrame ticks it while the settings file has no quality
 * CgQuality tier = CgQualityDetect.tierOf(64f);   // MEDIUM
 * }</pre>
 *
 * <ul>
 *   <li>Runs only where the settings file holds no quality: a fresh install. Deleting the key detects again.</li>
 *   <li>Never waits on the GPU: the timer is polled on later frames, so the first frames draw at the default.</li>
 *   <li>{@code -Dcrystalgraphics.quality.detect=false} keeps the default.</li>
 * </ul>
 */
public final class CgQualityDetect {

    public static final CgVar.Bool ENABLED = CgVar.bool("crystalgraphics.quality.detect", true, CgVar.DEV,
            "Choose the effects quality from a timed pass at a fresh install, as Unreal's scalability auto-detect does;"
                    + " false keeps the default.");

    /**
     * What the pass takes on Unreal's index-100 GPU, in nanoseconds: an RTX 4070 SUPER measured 163 us, and is about
     * ten times a GTX 670 in games, so about 1000. llvmpipe (mac41) measured 10.1 ms: 16, Low.
     */
    static final long REFERENCE_NS = 1_630_000L;
    private static final float[] THRESHOLDS = {18f, 42f, 115f};
    private static final int SIZE = 512, DRAWS = 4, GIVE_UP_FRAMES = 600;
    private static final String SHADER = "crystalgraphics:shaders/settings/gpu_benchmark.shader";
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");

    private enum State { IDLE, WARMING, TIMING, DONE }

    private static State state = State.IDLE;
    @Nullable private static CgMaterial material;
    @Nullable private static CgFrameBuffer target;
    private static int query, waited;

    private CgQualityDetect() {}

    /** Unreal's thresholds over a GPU index: the tier it auto-detects. */
    public static CgQuality tierOf(float index) {
        CgQuality[] tiers = CgQuality.values();
        for (int i = 0; i < THRESHOLDS.length; i++) if (index < THRESHOLDS[i]) return tiers[i];
        return CgQuality.ULTRA;
    }

    /** A frame's step: starts, polls or finishes the measure. Render thread, inside the frame tick. */
    public static void tick() {
        switch (state) {
            case IDLE -> {
                CgGraphicsSettings.QUALITY.get();   // loads the file
                if (!ENABLED.get() || CgGraphicsSettings.QUALITY.var().valueAt(CgVar.Source.FILE) != null) {
                    state = State.DONE;
                    return;
                }
                material = CgMaterial.newInstance(SHADER);
                state = State.WARMING;
            }
            case WARMING -> {
                if (!material.prepare()) return;
                CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
                if (pipeline == null) {
                    finish("its shader did not compile: " + material.lastCompileError());
                    return;
                }
                target = CgFrameBuffer.create("cg_quality_detect", SIZE, SIZE,
                        CgFrameBufferFormat.builder("cg_quality_detect").color(0, CgTextureType.RGBA16F).build());
                draw(pipeline, 1);   // untimed: a device builds its pipeline at the first draw
                query = CgGL.glGenQuery();
                CgGL.glBeginTimeElapsedQuery(query);
                draw(pipeline, DRAWS);
                CgGL.glEndTimeElapsedQuery();
                state = State.TIMING;
            }
            case TIMING -> {
                if (!CgGL.glIsQueryResultAvailable(query)) {
                    if (++waited > GIVE_UP_FRAMES) finish("its timer never answered");
                    return;
                }
                long nanos = Math.max(1L, CgGL.glGetQueryResultNanos(query));
                float index = 100f * REFERENCE_NS / nanos;
                CgQuality tier = tierOf(index);
                CgGraphicsSettings.QUALITY.set(tier);
                LOG.info("[crystalgraphics] GPU index {} ({} us for the measure): effects quality {}",
                        Math.round(index), nanos / 1000L, tier);
                finish(null);
            }
            case DONE -> { }
        }
    }

    private static void draw(CgPipeline pipeline, int times) {
        try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.VIEWPORT)) {
            target.bind();
            CgGL.glViewport(0, 0, SIZE, SIZE);
            CgPassConstants constants = new CgPassConstants().resolution(SIZE, SIZE);
            for (int i = 0; i < times; i++) {
                try (CgImmediate immediate = CgImmediate.begin(constants)) {
                    CgChunkBuilder chunks = immediate.chunks();
                    chunks.draw(pipeline, material.captureBindings(immediate.bindings()), FULLSCREEN);
                    chunks.instance();
                }
            }
        }
    }

    private static void finish(@Nullable String failure) {
        if (failure != null) LOG.warn("[crystalgraphics] effects quality not detected: {}; kept {}", failure,
                CgGraphicsSettings.QUALITY.get());
        if (query != 0) CgGL.glDeleteQuery(query);
        if (material != null) material.delete();
        query = 0;
        material = null;
        state = State.DONE;
    }
}
