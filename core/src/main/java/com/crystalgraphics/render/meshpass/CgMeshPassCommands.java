package com.crystalgraphics.render.meshpass;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * What a processor adds for a batch: draws, each a pipeline with the material it binds, over the batch's mesh. The scene
 * then names the batch's objects and its sort key on each, and keeps them for a persistent primitive (Unreal's
 * {@code BuildMeshDrawCommands}, whose output {@code FCachedMeshDrawCommandInfo} keeps).
 *
 * <pre>{@code
 * out.draw(batch, batch.material());                                         // its Forward pass
 * out.draw(batch, THERMAL_MATERIAL);                                         // a material of the pass's own
 * out.draw(batch, m, CgMeshPassCommands.pipeline(m, CgRenderPassVariant.EMISSIVE));   // another of its passes
 * }</pre>
 *
 * <ul>
 *   <li>A skinned batch draws every pipeline in its skinned form, chosen here.</li>
 *   <li>A null pipeline (a material that does not parse, a form that does not apply) adds nothing.</li>
 * </ul>
 */
public final class CgMeshPassCommands {

    private int size;
    private CgPipeline[] pipelines = new CgPipeline[4];
    private CgMaterialInterface[] materials = new CgMaterialInterface[4];
    private CgMesh[] meshes = new CgMesh[4];
    private int[] submeshes = new int[4];

    /** {@code material}'s Forward pass over the batch's mesh. */
    public void draw(CgMeshBatch batch, CgMaterialInterface material) {
        draw(batch, material, pipeline(material, CgRenderPassVariant.FORWARD));
    }

    /** {@code pipeline}, binding {@code material}, over the batch's mesh; nothing for a null pipeline. */
    public void draw(CgMeshBatch batch, CgMaterialInterface material, @Nullable CgPipeline pipeline) {
        if (pipeline != null && batch.skinned()) pipeline = pipeline.as(CgPipelineForm.SKINNED);
        if (pipeline == null) return;
        if (size == pipelines.length) {
            int n = size * 2;
            pipelines = Arrays.copyOf(pipelines, n);
            materials = Arrays.copyOf(materials, n);
            meshes = Arrays.copyOf(meshes, n);
            submeshes = Arrays.copyOf(submeshes, n);
        }
        pipelines[size] = pipeline;
        materials[size] = material;
        meshes[size] = batch.mesh();
        submeshes[size++] = batch.submesh();
    }

    /** {@code material}'s pipeline for {@code pass}, drawing scene objects; null while its shader does not parse. */
    @Nullable
    public static CgPipeline pipeline(CgMaterialInterface material, CgRenderPassVariant pass) {
        return CgMaterialProxy.of(material).pipeline(pass, CgInstanceKind.OBJECT);
    }

    // -- What the scene reads back -------------------------------------------------------------------------------

    /** Draws added since {@link #clear}. */
    public int size() {
        return size;
    }

    public CgPipeline pipeline(int i) {
        return pipelines[i];
    }

    public CgMaterialInterface material(int i) {
        return materials[i];
    }

    public CgMesh mesh(int i) {
        return meshes[i];
    }

    public int submesh(int i) {
        return submeshes[i];
    }

    /** Forgets every draw: the scene's, between batches. */
    public void clear() {
        Arrays.fill(materials, 0, size, null);
        Arrays.fill(meshes, 0, size, null);
        Arrays.fill(pipelines, 0, size, null);
        size = 0;
    }
}
