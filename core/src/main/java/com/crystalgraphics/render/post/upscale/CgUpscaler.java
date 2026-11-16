package com.crystalgraphics.render.post.upscale;

import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.CgPostContext;

import javax.annotation.Nullable;

/**
 * The post chain's temporal step (Unreal's {@code ITemporalUpscaler}): a picture drawn through a jittered projection
 * at the view's render size, resolved through a history of the view's into the chain's picture, at the view's output
 * size where it rebuilds a smaller picture. Engine side: the chain picks one per view
 * ({@code view.antiAliasing()}) and runs it after separate translucency, before {@code SCENE}.
 *
 * <pre>{@code
 * CgUpscaler upscaler = post.upscaler(view.antiAliasing());
 * float f = view.resolutionFraction();
 * boolean rebuilds = upscaler.rebuilds(f);
 * view.jittering(true, upscaler.jitterSamples(rebuilds ? f : 1f));        // at prepare
 * view.materialMipBias(rebuilds ? upscaler.materialMipBias(f) : 0f);
 * CgScreenTexture resolved = upscaler.apply(ctx, picture);                // in the chain
 * }</pre>
 *
 * <ul>
 *   <li>Its history is the view's ({@code view.state().history}), keyed by the upscaler: a switch never hands one
 *       another's.</li>
 *   <li>One that never rebuilds ({@link #minFraction()} 1) answers its input's size; a view drawn smaller is then
 *       upscaled spatially after the chain.</li>
 * </ul>
 */
public interface CgUpscaler {

    /** The least resolution fraction it rebuilds to the output size: 1 for one that resolves at the size drawn. */
    float minFraction();

    /** Whether it rebuilds a picture drawn at {@code fraction} of the output's width to the output size. */
    default boolean rebuilds(float fraction) {
        return fraction < 1f && fraction >= minFraction();
    }

    /** Its jitter's sequence length while drawing at {@code fraction}. */
    int jitterSamples(float fraction);

    /** The bias material textures sample by while it rebuilds a picture drawn at {@code fraction}. */
    float materialMipBias(float fraction);

    /** Resolves {@code input}, this frame's jittered picture, and answers the chain's picture after it. */
    CgScreenTexture apply(CgPostContext ctx, CgScreenTexture input);

    /**
     * Bloom's light as this frame's resolve keeps it: the excess past {@code threshold}, averaged through its history,
     * which bloom then takes with the threshold not taken again. Null where bloom takes its light from the resolved
     * picture. After {@link #apply}, given its input and answer.
     */
    @Nullable
    default CgGraphTexture bloomLight(CgPostContext ctx, CgScreenTexture input, CgScreenTexture resolved, float threshold) {
        return null;
    }
}
