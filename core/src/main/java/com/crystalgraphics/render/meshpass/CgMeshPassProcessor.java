package com.crystalgraphics.render.meshpass;

/**
 * Decides, for one mesh batch, whether it draws in a mesh pass and with which material: Unreal's
 * {@code FMeshPassProcessor::AddMeshBatch}. A pass type has one ({@link CgMeshPassType#register}); the engine's passes
 * are processors too ({@code CgEnginePasses}).
 *
 * <pre>{@code
 * final class ThermalProcessor implements CgMeshPassProcessor {
 *     public void add(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out) {
 *         if (!primitive.has(HEAT_SOURCE)) return;            // a flag a component set
 *         out.draw(batch, THERMAL_MATERIAL);                  // this pass's own material
 *     }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Answer from the batch and the primitive alone: what it adds for a persistent primitive is cached and drawn again
 *       in later frames and other views until the batch's mesh, material, relevance or flags change.</li>
 *   <li>Keep no state across calls: one processor serves every view.</li>
 *   <li>Adding nothing leaves the batch out of the pass.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgMeshPassProcessor {

    void add(CgMeshBatch batch, CgPrimitiveInfo primitive, CgMeshPassCommands out);
}
