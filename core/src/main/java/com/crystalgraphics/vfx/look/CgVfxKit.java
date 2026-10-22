package com.crystalgraphics.vfx.look;

import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.render.CgVfxSlotRenderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A named piece of a look: slot renderers, emitters (whose renderers draw their particles), the schemas their
 * parameters live on, and kits inside it. Looks are made of kits, and a look made from another adds, drops or swaps them
 * by name.
 *
 * <pre>{@code
 * public static final CgVfxKit BANDS = CgVfxKit.of("mymod:wave/bands")
 *         .params(BANDS_SCHEMA)                                 // the parameters its renderers bind
 *         .slot(CgVfxSlotRenderer.of("flat", CgBeamBody.BODY).material(FLAT).bind("_Color", TOP).build())
 *         .emitter(SPARKLES)                                    // drawn by its own renderers
 *         .build();
 *
 * CgVfxLook mine = CgEnergyWave.kamehameha().derive()
 *         .without(CgEnergyWave.REALISTIC_BODY)
 *         .with(BANDS)
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>A kit inside another can be dropped alone: {@code without(child)} removes the child's parts and leaves its
 *       parent's.</li>
 *   <li>Dropping a kit keeps its schemas in the look: the effect may still read their parameters.</li>
 *   <li>A kit whose emitters are authored at one size of its effect says so ({@link Builder#scalesWith}): its effect
 *       plays them scaled by that length, and a look's {@code scaled} leaves them, since the length scales.</li>
 * </ul>
 */
public final class CgVfxKit {

    private final String name;
    /** In order: each a {@link CgVfxSlotRenderer}, a {@link CgVfxEmitter} or a {@link CgVfxKit}. */
    final List<Object> parts;
    final List<CgVfxSchema> schemas;
    private final CgVfxParam.Scalar scalesWith;
    private final float authoredAt;

    private CgVfxKit(Builder b) {
        this.name = b.name;
        this.scalesWith = b.scalesWith;
        this.authoredAt = b.authoredAt;
        this.parts = Collections.unmodifiableList(new ArrayList<>(b.parts));
        this.schemas = Collections.unmodifiableList(new ArrayList<>(b.schemas));
    }

    public static Builder of(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    /** Its slot renderers, its children's included, in order. */
    public List<CgVfxSlotRenderer> slots() {
        List<CgVfxSlotRenderer> out = new ArrayList<>();
        collect(this, out, CgVfxSlotRenderer.class);
        return out;
    }

    /** Its emitters, its children's included. */
    public List<CgVfxEmitter> emitters() {
        List<CgVfxEmitter> out = new ArrayList<>();
        collect(this, out, CgVfxEmitter.class);
        return out;
    }

    /** The length its emitters are authored at ({@link Builder#scalesWith}), or null. */
    public CgVfxParam.Scalar scalesWith() {
        return scalesWith;
    }

    /**
     * What its emitters play scaled by where {@link #scalesWith} is {@code value}: 1 at the size they are authored at,
     * and 1 for a kit that scales with nothing.
     *
     * <pre>{@code
     * CgVfxEmitter played = look().emitter(e.name()).scaled(CgVfxBlast.KIT.emitterScale(get(CgVfxBlast.RADIUS)));
     * }</pre>
     */
    public float emitterScale(float value) {
        return scalesWith == null ? 1f : value / Math.max(authoredAt, 1.0e-6f);
    }

    /** Its schemas, its children's included. */
    public List<CgVfxSchema> schemas() {
        List<CgVfxSchema> out = new ArrayList<>(schemas);
        for (Object part : parts) {
            if (part instanceof CgVfxKit) {
                for (CgVfxSchema s : ((CgVfxKit) part).schemas()) if (!out.contains(s)) out.add(s);
            }
        }
        return out;
    }

    private static <T> void collect(CgVfxKit kit, List<T> out, Class<T> type) {
        for (Object part : kit.parts) {
            if (type.isInstance(part)) out.add(type.cast(part));
            else if (part instanceof CgVfxKit) collect((CgVfxKit) part, out, type);
        }
    }

    @Override
    public String toString() {
        return "kit " + name;
    }

    public static final class Builder {

        private final String name;
        private final List<Object> parts = new ArrayList<>();
        private final List<CgVfxSchema> schemas = new ArrayList<>();
        private CgVfxParam.Scalar scalesWith;
        private float authoredAt;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /** A slot renderer: what one of its behaviour's slots draws. */
        public Builder slot(CgVfxSlotRenderer renderer) {
            parts.add(Objects.requireNonNull(renderer, "renderer"));
            return this;
        }

        /** An emitter its effect plays, drawn by its own renderers. */
        public Builder emitter(CgVfxEmitter emitter) {
            parts.add(Objects.requireNonNull(emitter, "emitter"));
            return this;
        }

        /** A kit inside this one, which a look can drop or swap alone. */
        public Builder kit(CgVfxKit kit) {
            parts.add(Objects.requireNonNull(kit, "kit"));
            return this;
        }

        /** Schemas whose parameters its renderers bind and its effect reads. */
        public Builder params(CgVfxSchema... schemas) {
            for (CgVfxSchema s : schemas) if (!this.schemas.contains(s)) this.schemas.add(s);
            return this;
        }

        /**
         * Its emitters are authored where {@code length} is {@code authored}: its effect plays them scaled by
         * {@code length} over that ({@link CgVfxKit#emitterScale}), so a blast set wider bursts wider, and a look's
         * {@code scaled} leaves them to the length it scales.
         *
         * <pre>{@code
         * CgVfxKit.of("crystalgraphics:blast").scalesWith(CgVfxBlast.RADIUS, 9.6f)
         * }</pre>
         */
        public Builder scalesWith(CgVfxParam.Scalar length, float authored) {
            this.scalesWith = Objects.requireNonNull(length, "length");
            this.authoredAt = authored;
            return this;
        }

        public CgVfxKit build() {
            return new CgVfxKit(this);
        }
    }
}
