package com.crystalgraphics.vfx.particle.renderer;

import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a material's particle macro UVs are centred, and how far they reach: Cascade's {@code MacroUVPosition} and
 * {@code MacroUVRadius} on a system, and a required module's override on one renderer. A material reads the screen
 * placement they give through {@code CG_PARTICLE_MACRO_UV}: the centre's NDC and the scales taking the radius on
 * screen to one UV.
 *
 * <pre>{@code
 * # a system file
 * macro_uv: { position: [0, 0, 50], radius: 300 }
 * # one renderer's own
 * renderers:
 *   - { id: r1, kind: sprite, material: ..., macro_uv: { position: [0, 0, 0], radius: 120 } }
 * }</pre>
 *
 * <pre>{@code
 * CgVfxSystemDefinition d = definition.withMacroUv(new CgVfxMacroUv(0f, 0.5f, 0f, 3f));   // blocks
 * CgVfxRenderers.sprite("r1").material(AURA).macroUv(new CgVfxMacroUv(0f, 0f, 0f, 1.2f)).build();
 * }</pre>
 *
 * <ul>
 *   <li>A system without one is Cascade's default: its origin, 200 cm.</li>
 *   <li>A renderer's override keeps the system's centre and takes its own radius, and its position as a vector
 *       through the system's turn (Cascade's {@code TransformVector}), never a point.</li>
 *   <li>A radius of 0 or less gives no scale: the material's UVs collapse onto the centre.</li>
 * </ul>
 *
 * @param x      its position from the system's origin, before the system's turn, in blocks
 * @param radius blocks
 */
public record CgVfxMacroUv(float x, float y, float z, float radius) {

    /** Cascade's default ({@code ParticleComponents.cpp}): the origin and 200 cm. */
    public static final CgVfxMacroUv DEFAULT = new CgVfxMacroUv(0f, 0f, 0f, 2f);

    /**
     * {@code value}, a {@code macro_uv} map in {@code units}, as blocks; {@code fallback} for each field it leaves out,
     * and null for no map at all.
     */
    @Nullable
    public static CgVfxMacroUv read(String who, @Nullable Object value, CgUnits units, CgVfxMacroUv fallback) {
        if (value == null) return null;
        if (!(value instanceof Map<?, ?> m)) throw new IllegalArgumentException(who + "'s macro_uv is a map, not " + value);
        float[] p = {fallback.x, fallback.y, fallback.z};
        if (m.get("position") != null) {
            if (!(units.toHost(m.get("position"), CgQuantity.POSITION) instanceof List<?> v) || v.size() != 3
                    || !v.stream().allMatch(Number.class::isInstance)) {
                throw new IllegalArgumentException(who + "'s macro_uv position is three numbers, not " + m.get("position"));
            }
            for (int i = 0; i < 3; i++) p[i] = ((Number) v.get(i)).floatValue();
        }
        float radius = fallback.radius;
        if (m.get("radius") != null) {
            if (!(units.toHost(m.get("radius"), CgQuantity.LENGTH) instanceof Number r)) {
                throw new IllegalArgumentException(who + "'s macro_uv radius is a number, not " + m.get("radius"));
            }
            radius = r.floatValue();
        }
        return new CgVfxMacroUv(p[0], p[1], p[2], radius);
    }

    /** As a file writes it, in blocks. */
    public Map<String, Object> tree() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("position", List.of((double) x, (double) y, (double) z));
        out.put("radius", (double) radius);
        return out;
    }
}
