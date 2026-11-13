package com.crystalgraphics.api.material;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * What a material is drawn by: Unreal's material usage flags ({@code bUsedWithStaticMesh}, {@code
 * bUsedWithParticleSprites}, ...), one program variant each. A material lists its usages in its file, static mesh when
 * it lists none; its archetype declares the keyword of each other usage it can be compiled for.
 *
 * <pre>{@code
 * // A material drawn on meshes and as sprites
 * usage: [static_mesh, sprites]
 *
 * // Its archetype: the first factory draws the base usage, each other's usage keyword picks it
 * Material { VertexFactory VfxMesh, Tube }
 * void vertex(inout CgVertex v, out v2f o) {
 * #ifdef FX_ON_TUBE
 *     ... cg_Tube ...
 * #endif
 * }
 * }</pre>
 *
 * <ul>
 *   <li>An archetype declaring no usage keyword is drawn by one kind alone: by particles (sprites, mesh particles,
 *       ribbons) when its factory reads them ({@code Sprites}, {@code MeshParticles}), else by meshes. Such a particle
 *       program takes {@code FX_ON_SPRITES} undeclared, as it takes the strips', so its sprites are placed as sprites
 *       whatever its factory.</li>
 *   <li>A usage its archetype cannot serve fails the material at load; a draw outside its usages fails the drawer at
 *       load ({@link CgMaterialDefinition#requireUsage}).</li>
 * </ul>
 */
public enum CgMaterialUsage {
    STATIC_MESH,
    SKELETAL_MESH,
    SPRITES,
    MESH_PARTICLES,
    RIBBONS;

    /** The keyword its archetype declares to be compiled for it: {@code FX_ON_SPRITES}; null for static mesh, the base. */
    @Nullable
    public String keyword() {
        return this == STATIC_MESH ? null : "FX_ON_" + name();
    }

    /** Whether particles draw it: their records, through a particle factory. */
    public boolean particles() {
        return this == SPRITES || this == MESH_PARTICLES || this == RIBBONS;
    }

    /** Its name in a file: {@code mesh_particles}. */
    public String fileName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The usage a file names. */
    public static CgMaterialUsage of(String fileName) {
        return valueOf(fileName.toUpperCase(Locale.ROOT));
    }
}
