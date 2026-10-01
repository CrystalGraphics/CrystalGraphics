package com.crystalgraphics.gl.texture;

import com.crystalgraphics.api.texture.CgTexture;

import java.util.HashMap;
import java.util.Map;

/**
 * Mutable, non-owning {@link CgTexture} view over an externally-owned GL texture id.
 *
 * <p>Some call sites need to hand a real {@link CgTexture} to an API that only accepts that
 * interface (e.g. {@code CgMaterialProperties.sampler(name, unit, CgTexture)}), but the actual
 * texture is owned and mutated elsewhere as a raw GL id — e.g. {@code CgGlyphAtlasPage} exposes
 * only a raw {@code int}, not a {@link CgTexture}. This class wraps that raw id/target so it can
 * be repointed in place via {@link #setId(int)}/{@link #setTarget(int)} — typically once per
 * transition, on a single shared instance registered once with the consuming API — without ever
 * owning or deleting the underlying GL texture: {@link #delete()} is a no-op.</p>
 */
public final class CgTextureMutable extends CgTextureAbstract {

    private int target;

    public CgTextureMutable(int target) {
        super(0, 0, 0, null);
        this.target = target;
    }

    public CgTextureMutable(int id, int target) {
        super(id, 0, 0, null);
        this.target = target;
    }

    /** {@link #current} answers, one per id, so equal recorded snapshots stay equal. */
    private final Map<Integer, CgTextureMutable> fixed = new HashMap<>();

    public void setId(int id) {this.textureId = id;}

    /**
     * What this view points at now, as a view nothing repoints: what a recorded draw keeps, since it binds after this
     * one has moved on. One per id.
     */
    public CgTexture current() {
        CgTextureMutable view = fixed.get(textureId);
        if (view == null || view.target != target) {
            view = new CgTextureMutable(textureId, target);
            fixed.put(textureId, view);
        }
        return view;
    }

    public void setTarget(int target) {this.target = target;}

    @Override
    public int getTarget() {return target;}

    @Override
    public void delete() { /* view only — does not own the underlying GL texture */ }
}
