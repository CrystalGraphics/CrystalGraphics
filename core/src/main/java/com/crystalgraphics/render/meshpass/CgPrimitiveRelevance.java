package com.crystalgraphics.render.meshpass;

import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.material.CgMaterialProxy;

/**
 * Which of the engine's passes a material draws in: Unreal's {@code FPrimitiveViewRelevance}, read from the material's
 * blend mode, outputs and domain. A batch's {@link CgMeshBatch#relevance()}; processors read it, so no tag in a shader
 * names a pass.
 *
 * <pre>{@code
 * int r = CgPrimitiveRelevance.of(material);
 * if ((r & CgPrimitiveRelevance.TRANSLUCENT) != 0) ...      // drawn in the translucent pass
 * }</pre>
 */
public final class CgPrimitiveRelevance {

    /** Solid (opaque or masked): the base pass. */
    public static final int MAIN = 1;
    /** Writes depth: the depth pass. A solid does; a translucent one with a depth prepass or depth write does too. */
    public static final int DEPTH = 2;
    /** Blended: the translucent pass. */
    public static final int TRANSLUCENT = 4;
    /** Names {@code s.emissive}: the emissive pass. */
    public static final int EMISSIVE = 8;
    /** Names {@code s.refraction}: the distortion pass. */
    public static final int DISTORTION = 16;
    /** Writes custom data: the custom data pass. */
    public static final int CUSTOM_DATA = 32;
    /** A {@code #domain decal} material: the decal pass alone. */
    public static final int DECAL = 64;

    private CgPrimitiveRelevance() {}

    /**
     * {@code material}'s relevance; 0 while its shader does not parse, and for a domain no mesh pass draws, which is said
     * once a material (a scene's rows draw {@code #domain surface} and {@code decal}).
     */
    public static int of(CgMaterialInterface material) {
        CgMaterialProxy proxy = CgMaterialProxy.of(material);
        CgMaterialDomain domain = proxy.domain();
        if (domain == CgMaterialDomain.DECAL) return DECAL;
        if (domain != CgMaterialDomain.SURFACE) {
            if (domain != null) proxy.warnNoMeshPass(domain);
            return 0;
        }
        // Solid: queued before the translucent, or blending nothing and writing depth (a lit solid queued after the lights)
        boolean solid = proxy.getRenderQueue() < CgRenderQueue.TRANSPARENT_THRESHOLD || proxy.solid();
        int r = solid ? MAIN | DEPTH : TRANSLUCENT;
        if ((r & TRANSLUCENT) != 0 && (proxy.hasDepthPrepass() || proxy.writesDepth())) r |= DEPTH;
        if (proxy.hasEmissivePass()) r |= EMISSIVE;
        if (proxy.hasDistortionPass()) r |= DISTORTION;
        if (proxy.writesCustomData()) r |= CUSTOM_DATA;
        return r;
    }
}
