package com.crystalgraphics.render.post.bloom;

import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.CgPostEffect;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgGpuTrace;

/**
 * The post stack's bloom: what the scene emits ({@link CgFrameKeys#EMISSION}) blurred wide and added over the picture
 * by the composite. Its settings are global, as every engine's are; {@code CgPostStack.get().bloom()} is the instance.
 *
 * <pre>{@code
 * CgPostStack.get().bloom().intensity(1.5f);   // a brighter glow round every emissive thing
 * CgPostStack.get().bloom().intensity(0f);     // none: the world renderer's emission pass is then culled too
 * }</pre>
 *
 * <ul>
 *   <li>Off below {@code CgQuality.MEDIUM}.</li>
 *   <li>Nothing emitted this firing, nothing recorded.</li>
 * </ul>
 */
public final class CgBloom implements CgPostEffect {

    /** The deepest level blurred and summed (the composite reads 1 to 5), and each level's blur in its texels. */
    private static final int LEVELS = 5;
    private static final float SIGMA = 1.5f;
    private static final int GPU_CHAIN = CgGpuTrace.name("post.bloom.chain");

    private final CgPassConstants constants = new CgPassConstants();
    private final float[] block = new float[CgPassConstants.FLOATS];
    private float intensity = 1f;

    /** How strongly what emits blooms: 1 by default, 0 for none. */
    public CgBloom intensity(float intensity) {
        this.intensity = Math.max(0f, intensity);
        return this;
    }

    public float intensity() {
        return intensity;
    }

    @Override
    public CgPostPoint point() {
        return CgPostPoint.BEFORE_COMPOSITE;
    }

    @Override
    public boolean active(CgPostContext post) {
        return intensity > 0f && CgGraphicsSettings.QUALITY.get().atLeast(CgQuality.MEDIUM)
                && post.resources().has(CgFrameKeys.EMISSION);
    }

    @Override
    public void record(CgPostContext post) {
        CgGraphTexture emission = post.resources().get(CgFrameKeys.EMISSION);
        int last = Math.min(LEVELS, emission.getLevels() - 1);
        if (last < 1) return;
        post.constants().write(block, 0);
        constants.read(block, 0).resolution(emission.getWidth(), emission.getHeight());
        CgComputePass chain = post.recording().compute("post.bloom", constants).timed(GPU_CHAIN);
        for (int l = 1; l <= last; l++) {
            CgGpuOps.downsample(chain, emission, l - 1, l, CgGpuOps.Filter.AVERAGE);
            CgGpuOps.blur(chain, emission, l, emission, l, SIGMA);
        }
        chain.end();
        post.composite().bloom(emission, intensity);
    }
}
