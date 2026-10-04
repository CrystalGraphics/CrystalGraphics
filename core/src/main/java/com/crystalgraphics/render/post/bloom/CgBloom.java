package com.crystalgraphics.render.post.bloom;

import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.CgPostEffect;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.settings.CgGraphicsSettings;

/**
 * The post stack's bloom: what the scene emits ({@link CgFrameKeys#EMISSION}) spread wide by a chain of raster passes
 * ({@link CgBloomChain}) and added over the picture by the composite. Its settings are global, as every engine's are;
 * {@code CgPostStack.get().bloom()} is the instance.
 *
 * <pre>{@code
 * CgPostStack.get().bloom().intensity(1.5f);   // a brighter glow round every emissive thing
 * CgPostStack.get().bloom().intensity(0f);     // none: the world renderer's emission pass is then culled too
 *
 * // Bevy's energy-conserving mode: the picture dimmed by the share the glow takes, so nothing gets brighter overall
 * CgPostStack.get().bloom().mode(CgBloom.Mode.ENERGY_CONSERVING).intensity(0.15f);
 * CgPostStack.get().bloom().tint(1f, 0.85f, 0.7f);   // a warm glow
 * CgPostStack.get().bloom().linear(true);            // composite in linear light, at the cost of a copy of the target
 * }</pre>
 *
 * <ul>
 *   <li>Every tier blooms: Low a cheaper chain from a quarter-size emission, Ultra a full-size one
 *       ({@link CgBloomChain}).</li>
 *   <li>Nothing emitted this firing, nothing recorded.</li>
 *   <li>Settings are read on the render thread and may be written from any: a float, read at most a frame late.</li>
 * </ul>
 */
public final class CgBloom implements CgPostEffect {

    /** How the glow meets the picture. */
    public enum Mode {
        /** Added: {@code picture + glow * intensity}. */
        ADDITIVE,
        /** Mixed in: {@code picture * (1 - intensity) + glow * intensity}, intensity 0 to 1 (Bevy's 0.15 is subtle). */
        ENERGY_CONSERVING
    }

    private final CgBloomChain chain = new CgBloomChain();
    private float intensity = 1f, tintR = 1f, tintG = 1f, tintB = 1f;
    private Mode mode = Mode.ADDITIVE;
    /** {@code -Dcrystalgraphics.post.bloom.linear=true} starts it in linear light: the two forms side by side. */
    private boolean linear = Boolean.getBoolean("crystalgraphics.post.bloom.linear");

    /** How strongly what emits blooms: 1 by default, 0 for none. */
    public CgBloom intensity(float intensity) {
        this.intensity = Math.max(0f, intensity);
        return this;
    }

    public float intensity() {
        return intensity;
    }

    public CgBloom mode(Mode mode) {
        this.mode = mode;
        return this;
    }

    public Mode mode() {
        return mode;
    }

    /** Multiplies the glow's colour: white by default. */
    public CgBloom tint(float r, float g, float b) {
        tintR = r;
        tintG = g;
        tintB = b;
        return this;
    }

    /**
     * Whether it composites in linear light: decoding the target from sRGB, adding, and encoding again, which needs a
     * copy of the target. Off by default: the glow is added in the target's encoding, an even step in perceived
     * brightness, so over dark pixels it is fainter than linear light would make it and over bright ones stronger
     * (a glow of 0.1 over an encoded 0.2 shows 0.073 linear where linear light gives 0.133; over 0.8, 0.787 for 0.704).
     */
    public CgBloom linear(boolean linear) {
        this.linear = linear;
        return this;
    }

    @Override
    public CgPostPoint point() {
        return CgPostPoint.BEFORE_COMPOSITE;
    }

    @Override
    public boolean active(CgPostContext post) {
        return intensity > 0f && post.resources().has(CgFrameKeys.EMISSION);
    }

    @Override
    public void record(CgPostContext post) {
        CgGraphTexture emission = post.resources().get(CgFrameKeys.EMISSION);
        CgGraphTexture glow = chain.record(post.recording(), emission, post.constants(), CgGraphicsSettings.QUALITY.get());
        post.composite().bloom(glow, intensity, tintR, tintG, tintB, mode == Mode.ENERGY_CONSERVING);
        if (linear) post.composite().linear();
    }

    /** Forgets what it made on the GPU. At context teardown, through the stack. */
    public void release() {
        chain.release();
    }
}
