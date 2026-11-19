package com.crystalgraphics.api.material.engine;

import java.util.Arrays;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialState;

/**
 * One of the engine's ready materials ({@link CgEngineMaterials}): a look by name, in any blend, on the screen or in the
 * world, made once and shared. Unreal's engine materials (its default, text and widget materials).
 *
 * <pre>{@code
 * canvas.quad(CgEngineMaterials.IMAGE.screen()).texture(0, icon).at(x, y).size(16, 16).submit();
 * canvas.quad(CgEngineMaterials.MASK.screen(CgBlendMode.ADDITIVE)).texture(0, glow).at(x, y).size(w, h).submit();
 * frame.canvas().quad(CgEngineMaterials.IMAGE.world()).world(x, y, z).pose(facing).size(1f, 1f).submit();
 * }</pre>
 *
 * <ul>
 *   <li>Each placement and blend is one shared material: never set a property on it. Per-draw values go on the draw
 *       (its texture, colour, opacity).</li>
 *   <li>A material with a blend of its own (a gradient, a grid, glass) refuses another; one that blends refuses
 *       {@code OPAQUE}, since a draw at full alpha under {@code ALPHA} already covers.</li>
 *   <li>Made at its first ask and precached then; the engine's common ones are built at load.</li>
 * </ul>
 */
public final class CgEngineMaterial {

    private static final CgBlendMode[] BLENDS = CgBlendMode.values();

    private final String name;
    private final String shader;
    private final String keyword;
    private final boolean blendable;
    private final CgBlendMode blend;
    private final CgMaterial[] made = new CgMaterial[CgPlacement.values().length * BLENDS.length];

    CgEngineMaterial(String name, String shader, String keyword, boolean blendable, CgBlendMode blend) {
        this.name = name;
        this.shader = shader;
        this.keyword = keyword;
        this.blendable = blendable;
        this.blend = blend;
    }

    /** On the screen, in its own blend. */
    public CgMaterial screen() {
        return material(CgPlacement.SCREEN, blend);
    }

    /** On the screen, under {@code blend}. */
    public CgMaterial screen(CgBlendMode blend) {
        return material(CgPlacement.SCREEN, blend);
    }

    /** In the world, in its own blend. */
    public CgMaterial world() {
        return material(CgPlacement.WORLD, blend);
    }

    /** In the world, under {@code blend}. */
    public CgMaterial world(CgBlendMode blend) {
        return material(CgPlacement.WORLD, blend);
    }

    /** At {@code placement}, in its own blend. */
    public CgMaterial material(CgPlacement placement) {
        return material(placement, blend);
    }

    /** At {@code placement} under {@code blend}: one shared material for each pair. */
    public synchronized CgMaterial material(CgPlacement placement, CgBlendMode blend) {
        if (!blendable && blend != this.blend) {
            throw new IllegalArgumentException(name + " draws under " + this.blend + " alone, not " + blend);
        }
        if (blendable && blend == CgBlendMode.OPAQUE) {
            throw new IllegalArgumentException(name + " blends: at full alpha ALPHA already covers");
        }
        int at = placement.ordinal() * BLENDS.length + blend.ordinal();
        CgMaterial m = made[at];
        if (m == null) {
            CgMaterialState.Builder state = CgMaterialState.builder().blend(blend).twoSided();
            if (placement == CgPlacement.SCREEN) state.disableDepthTest();
            m = CgMaterial.load(shader, state.build());
            if (keyword != null) m = m.keyword(keyword, true);
            m.precache();
            made[at] = m;
        }
        return m;
    }

    /** The blend it draws under unless asked otherwise. */
    public CgBlendMode blend() {
        return blend;
    }

    /** Whether it takes other blends. */
    public boolean blendable() {
        return blendable;
    }

    /** Its shader's resource path. */
    public String shader() {
        return shader;
    }

    public String name() {
        return name;
    }

    synchronized void forget() {
        Arrays.fill(made, null);
    }

    @Override
    public String toString() {
        return "CgEngineMaterial[" + name + "]";
    }
}
