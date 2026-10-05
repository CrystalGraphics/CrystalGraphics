package com.crystalgraphics.render.post.distortion;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.stage.CgDistortionField;
import com.crystalgraphics.trace.CgGpuTrace;

import java.util.Arrays;

/**
 * Bends a side input of the scene by the firing's distortion ({@link CgDistortionField}), so a post effect reading
 * something besides the target (bloom's emission, a mask) sees it bent as the scene beneath was. Reached through
 * {@code CgPostContext.distorted(texture)}; one instance, the post stack's.
 *
 * <ul>
 *   <li>A texture asked for twice in a firing is bent once.</li>
 *   <li>A texture with no format of its own (an imported one) comes back unbent.</li>
 *   <li>A field of more than one target is summed into one offset texture once a firing ({@code distortion_sum.shader},
 *       GPU zone {@code post.distortionSum}), so a bend binds two units: a host with eight leaves materials five.</li>
 * </ul>
 */
public final class CgPostDistortion {

    /** The most textures bent in one firing; past it, an input comes back unbent. */
    private static final int BENDS = 4;
    private static final String SHADER = "crystalgraphics:shaders/post/distortion_bend.shader";
    private static final String SUM_SHADER = "crystalgraphics:shaders/post/distortion_sum.shader";
    private static final String[] OFFSETS = {"_Offsets0", "_Offsets1", "_Offsets2", "_Offsets3", "_Offsets4"};
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU_BEND = CgGpuTrace.name("post.distortionBend");
    private static final int GPU_SUM = CgGpuTrace.name("post.distortionSum");

    private int bent;
    private final CgGraphTexture[] sources = new CgGraphTexture[BENDS], results = new CgGraphTexture[BENDS];
    private final CgMaterial[] materials = new CgMaterial[BENDS];
    /** What each material reads, the source then the offsets, as last bound. */
    private final CgTexture[][] bound = new CgTexture[BENDS][2];

    /** This firing's summed field, or null until a bend asks for it. */
    private CgGraphTexture summed;
    private CgGraphTexture sum;
    private CgMaterial sumMaterial;
    private final CgTexture[] sumBound = new CgTexture[CgDistortionField.MAX], sumWanted = new CgTexture[CgDistortionField.MAX];

    private final CgPassConstants constants = new CgPassConstants();
    private final float[] block = new float[CgPassConstants.FLOATS];

    /** Forgets the last firing's bends. The post stack's, as each firing begins. */
    public void begin() {
        bent = 0;
        summed = null;
    }

    /**
     * {@code source} bent by {@code field}, into a target of its size and format recorded into {@code recording}; with
     * no field, {@code source} itself. {@code camera} gives the pass's constants, at the source's size.
     */
    public CgGraphTexture bend(CgRecording recording, CgDistortionField field, CgPassConstants camera, CgGraphTexture source) {
        if (field == null || field.count() == 0 || source == null || source.desc() == null) return source;
        for (int k = 0; k < bent; k++) if (sources[k] == source) return results[k];
        if (bent == BENDS) return source;
        CgGraphTexture offsets = offsets(recording, field, camera);
        if (offsets == null) return source;
        int k = bent++;
        CgTextureDesc desc = source.desc();
        CgGraphTexture result = results[k];
        if (result == null || !desc.equals(result.desc())) {
            results[k] = result = CgGraphTexture.transientTexture("cg_post_bent" + k, desc);
        }
        sources[k] = source;
        CgMaterial material = materials[k];
        if (material == null) materials[k] = material = CgMaterial.newInstance(SHADER);
        CgTexture[] was = bound[k];
        if (was[0] != source || was[1] != offsets) {
            material.applyProperties(b -> {
                b.sampler("_Source", 0, source);
                b.sampler("_Offsets", 1, offsets);
            });
            was[0] = source;
            was[1] = offsets;
        }
        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return source;
        fullscreen(recording, result, desc, camera, material, pipeline, GPU_BEND);
        return result;
    }

    /** The field as one offset texture: its only target, or this firing's sum; null if the sum cannot draw. */
    private CgGraphTexture offsets(CgRecording recording, CgDistortionField field, CgPassConstants camera) {
        if (field.count() == 1) return field.offsets(0);
        if (summed != null) return summed;
        CgTextureDesc desc = field.offsets(0).desc();
        if (desc == null) return null;
        if (sum == null || !desc.equals(sum.desc())) sum = CgGraphTexture.transientTexture("cg_post_distortion_sum", desc);
        if (sumMaterial == null) sumMaterial = CgMaterial.newInstance(SUM_SHADER);
        boolean changed = false;
        for (int i = 0; i < CgDistortionField.MAX; i++) {
            sumWanted[i] = i < field.count() ? field.offsets(i) : CgFallbackTextures.BLACK_1x1;
            changed |= sumBound[i] != sumWanted[i];
        }
        if (changed) {
            sumMaterial.applyProperties(b -> {
                for (int i = 0; i < OFFSETS.length; i++) b.sampler(OFFSETS[i], i, sumWanted[i]);
            });
            System.arraycopy(sumWanted, 0, sumBound, 0, sumWanted.length);
        }
        CgPipeline pipeline = sumMaterial.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return null;
        fullscreen(recording, sum, desc, camera, sumMaterial, pipeline, GPU_SUM);
        return summed = sum;
    }

    private void fullscreen(CgRecording recording, CgGraphTexture target, CgTextureDesc desc, CgPassConstants camera,
                            CgMaterial material, CgPipeline pipeline, int gpuZone) {
        camera.write(block, 0);
        constants.read(block, 0).resolution(desc.width(), desc.height());
        CgRasterPass pass = recording.raster(target, CgLoad.clear(0f, 0f, 0f, 0f), constants, null, CgOrder.SORTED)
                .timed(gpuZone);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, material.captureBindings(recording.bindings()), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
    }

    /** Forgets its materials, which the material registry frees with the context. */
    public void release() {
        for (int k = 0; k < BENDS; k++) {
            materials[k] = null;
            Arrays.fill(bound[k], null);
        }
        sumMaterial = null;
        Arrays.fill(sumBound, null);
    }
}
