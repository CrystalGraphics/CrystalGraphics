package com.crystalgraphics.render.post.composite;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.trace.CgGpuTrace;

import java.util.function.Consumer;

/**
 * The post stack's one composite pass: every screen-wide look of the firing laid over the target in a single
 * full-screen triangle, as production engines compose bloom inside their tonemap or uber pass. Effects at
 * {@code BEFORE_COMPOSITE} set its inputs; the stack records it once, between that point and {@code AFTER_COMPOSITE}.
 *
 * <pre>{@code
 * // an effect, at BEFORE_COMPOSITE
 * post.composite().bloom(chain, 1.2f);
 * }</pre>
 *
 * <ul>
 *   <li>Each input switches on a {@link CgCompositeFeature}; a firing with none records nothing.</li>
 *   <li>Inputs last one firing: the stack clears them before its effects record.</li>
 * </ul>
 */
public final class CgPostComposite {

    private static final String SHADER = "crystalgraphics:shaders/post/composite.shader";
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU = CgGpuTrace.name("post.composite");
    private static final CgCompositeFeature[] FEATURES = CgCompositeFeature.values();

    private CgMaterial material;
    /** The features asked for this firing, and those the material's keywords are set to, as bits by ordinal. */
    private int active, keyed = -1;
    private CgGraphTexture bloom;
    private float bloomIntensity;
    /** What the material's properties were last set to. */
    private CgGraphTexture setBloom;
    private float setIntensity = Float.NaN;
    private final Consumer<CgShaderBindings> properties =
            b -> b.sampler("_Bloom", 0, bloom).set1f("_Intensity", bloomIntensity);

    /** Clears this firing's inputs. The stack's, before its effects record. */
    public void begin() {
        active = 0;
        bloom = null;
    }

    /** Adds {@code chain}'s level 0, the whole glow, over the target, times {@code intensity}. */
    public CgPostComposite bloom(CgGraphTexture chain, float intensity) {
        bloom = chain;
        bloomIntensity = intensity;
        active |= 1 << CgCompositeFeature.BLOOM.ordinal();
        return this;
    }

    /** Whether an effect has switched {@code feature} on this firing. */
    public boolean active(CgCompositeFeature feature) {
        return (active & 1 << feature.ordinal()) != 0;
    }

    /** Records the pass onto {@code target} if any feature is on. The stack's. */
    public void record(CgRecording recording, CgGraphTexture target, CgPassConstants constants) {
        if (active == 0) return;
        if (material == null) material = CgMaterial.newInstance(SHADER);
        if (active != keyed) {
            for (CgCompositeFeature feature : FEATURES) material.toggleKeyword(feature.keyword, active(feature));
            keyed = active;
        }
        if (bloom != setBloom || bloomIntensity != setIntensity) {
            material.applyProperties(properties);
            setBloom = bloom;
            setIntensity = bloomIntensity;
        }
        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        CgRasterPass pass = recording.raster(target, CgLoad.load(), constants, null, CgOrder.SORTED).timed(GPU);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, material.captureBindings(recording.bindings()), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
    }

    /** Forgets its material, which the material registry frees with the context. */
    public void release() {
        material = null;
        keyed = -1;
        setBloom = null;
        setIntensity = Float.NaN;
    }
}
