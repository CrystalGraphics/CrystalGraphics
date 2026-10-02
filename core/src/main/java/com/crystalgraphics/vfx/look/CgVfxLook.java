package com.crystalgraphics.vfx.look;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How an effect appears and behaves: a value for every parameter of its {@link CgVfxSchema}, and the
 * {@link CgVfxLayer}s it draws. Immutable and shared by every effect playing it; vary one from another with
 * {@link #toBuilder()}.
 *
 * <pre>{@code
 * CgVfxLook finalFlash = CgEnergyWave.kamehameha().toBuilder()
 *         .set(CgEnergyWave.CORE, 1f, 0.97f, 0.8f, 1f)
 *         .set(CgEnergyWave.RADIUS, 0.9f)
 *         .build();
 *
 * CgVfxLook mine = CgVfxLook.builder(CgEnergyWave.SCHEMA)
 *         .layer(CgVfxLayer.builder("mymod:shaders/my_beam.shader").colors(CgEnergyWave.CORE, CgEnergyWave.GLOW).build())
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>Layers draw in list order within their priority; give each additive layer a priority of its own so its draws
 *       batch.</li>
 *   <li>A layer's material is made once per layer object by the system that draws it, so share layer objects between
 *       looks that draw them alike.</li>
 * </ul>
 */
public final class CgVfxLook {

    private final CgVfxValues values;
    private final List<CgVfxLayer> layers;

    private CgVfxLook(CgVfxValues values, List<CgVfxLayer> layers) {
        this.values = values;
        this.layers = Collections.unmodifiableList(new ArrayList<>(layers));
    }

    public static Builder builder(CgVfxSchema schema) {
        return new Builder(new CgVfxValues(schema, schema.defaults(), schema.defaultCurves()), new ArrayList<>());
    }

    public Builder toBuilder() {
        return new Builder(values.copy(), new ArrayList<>(layers));
    }

    public CgVfxSchema schema() {
        return values.schema();
    }

    public List<CgVfxLayer> layers() {
        return layers;
    }

    public float get(CgVfxParam param) {
        return values.get(param);
    }

    /** A copy of every value, as a playing effect starts from. */
    public CgVfxValues copyValues() {
        return values.copy();
    }

    public static final class Builder {

        private final CgVfxValues values;
        private final List<CgVfxLayer> layers;

        private Builder(CgVfxValues values, List<CgVfxLayer> layers) {
            this.values = values;
            this.layers = layers;
        }

        public Builder set(CgVfxParam param, float value) {
            values.set(param, value);
            return this;
        }

        public Builder set(CgVfxParam param, float r, float g, float b, float a) {
            values.set(param, r, g, b, a);
            return this;
        }

        public Builder set(CgVfxParam param, CgVfxCurve curve) {
            values.set(param, curve);
            return this;
        }

        public Builder layer(CgVfxLayer layer) {
            layers.add(layer);
            return this;
        }

        /** Drops every layer, to build a look's layer list from nothing. */
        public Builder clearLayers() {
            layers.clear();
            return this;
        }

        public CgVfxLook build() {
            return new CgVfxLook(values.copy(), layers);
        }
    }
}
