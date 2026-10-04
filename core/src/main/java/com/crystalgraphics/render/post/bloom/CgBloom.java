package com.crystalgraphics.render.post.bloom;

import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.CgPostEffect;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;

/**
 * The post stack's bloom: what the scene emits ({@link CgFrameKeys#EMISSION}) spread wide by a chain of raster passes
 * ({@link CgBloomChain}) and added over the picture by the composite. Its settings are global, as every engine's are;
 * {@code CgPostStack.get().bloom()} is the instance.
 *
 * <pre>{@code
 * CgPostStack.get().bloom().intensity(1.5f);   // a brighter glow round every emissive thing
 * CgPostStack.get().bloom().intensity(0f);     // none: the world renderer's emission pass is then culled too
 * }</pre>
 *
 * <ul>
 *   <li>Off below {@code CgQuality.MEDIUM}.</li>
 *   <li>Nothing emitted this firing, nothing recorded.</li>
 *   <li>Settings are read on the render thread and may be written from any: a float, read at most a frame late.</li>
 * </ul>
 */
public final class CgBloom implements CgPostEffect {

    private final CgBloomChain chain = new CgBloomChain();
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
        CgGraphTexture glow = chain.record(post.recording(), emission, post.constants());
        post.composite().bloom(glow, intensity);
    }

    /** Forgets what it made on the GPU. At context teardown, through the stack. */
    public void release() {
        chain.release();
    }
}
