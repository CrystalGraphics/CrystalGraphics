package com.crystalgraphics.vfx.look;

import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.CgVfxScalability;
import com.crystalgraphics.vfx.camera.CgCameraShake;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRendererProperties;
import com.crystalgraphics.vfx.render.CgVfxSlotRenderer;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.settings.CgQuality;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * How an effect appears and behaves: its kits (slot renderers and emitters), and a value for every parameter of every schema it
 * includes. Immutable and shared by every effect playing it; a variant is {@link #derive()}d.
 *
 * <pre>{@code
 * // A variant: other colours, thinner, every length scaled down for a character
 * public static final CgVfxLook THIN = CgEnergyWave.kamehameha().derive()
 *         .set(CgEnergyWave.CORE, 0xFFFFF7CC)
 *         .scaled(0.375f)
 *         .build();
 *
 * // Its own drawing on the engine's behaviour: drop kits by name, add one, keep the blast
 * public static final CgVfxLook MINE = CgEnergyWave.kamehameha().derive()
 *         .without(CgEnergyWave.REALISTIC_BODY, CgEnergyWave.REALISTIC_HEAD)
 *         .with(MY_BANDS)
 *         .set(MyBands.TOP, 0xFFFFFFFF)
 *         .build();
 *
 * // From nothing: an effect's schema, a kit
 * CgVfxLook puff = CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).with(PUFF_KIT).build();
 * }</pre>
 *
 * <ul>
 *   <li>Its draws rank by hint ({@link Placed#hint()}: a renderer's sort order hint, a slot renderer's material
 *       order), then in the order they were added; the role bands are {@link #BAND} wide.</li>
 *   <li>{@code set}, {@code link} and {@code scaled} apply in the order written: {@code scaled(0.5f)} after
 *       {@code set(RADIUS, 1f)} leaves RADIUS 0.5.</li>
 *   <li>A material is made once per material definition, so looks built from the same kits share them.</li>
 * </ul>
 */
public final class CgVfxLook {

    /** An emitter's renderer or a slot renderer, as this look draws it, and where among its draws. */
    public static final class Placed {
        final CgVfxEmitter emitter;
        final CgVfxRendererProperties renderer;
        final CgVfxSlotRenderer slotRenderer;
        final int order;

        Placed(CgVfxEmitter emitter, CgVfxRendererProperties renderer, int order) {
            this(emitter, renderer, null, order);
        }

        Placed(CgVfxSlotRenderer slotRenderer, int order) {
            this(null, null, slotRenderer, order);
        }

        private Placed(CgVfxEmitter emitter, CgVfxRendererProperties renderer, CgVfxSlotRenderer slotRenderer, int order) {
            this.emitter = emitter;
            this.renderer = renderer;
            this.slotRenderer = slotRenderer;
            this.order = order;
        }

        /** The slot renderer it draws; null for an emitter's renderer. */
        public CgVfxSlotRenderer slotRenderer() {
            return slotRenderer;
        }

        /** Its material definition; null where it compiled with no dependencies to ask. */
        public CgMaterialDefinition definition() {
            return renderer != null ? renderer.common().definition() : slotRenderer != null ? slotRenderer.definition() : null;
        }

        /** The tier it draws from, its {@code quality}; null for every tier. */
        public CgQuality from() {
            return renderer != null ? renderer.common().quality() : slotRenderer.quality();
        }

        /** What a skipped part's name is matched against: its material's id. */
        public String partName() {
            return renderer != null ? String.valueOf(renderer.common().material()) : slotRenderer.material();
        }

        /** The mesh it draws on a mesh slot; null for the slot's own. */
        public CgMeshLods mesh() {
            return slotRenderer != null ? slotRenderer.mesh() : null;
        }

        /** Whether a tube slot draws it chunk by chunk on a sphere: its archetype's {@code "Tube" = "Volume"}. */
        public boolean volume() {
            return slotRenderer != null && slotRenderer.tube() == CgParsedShader.Tube.VOLUME;
        }

        /** Whether a tube slot draws it closed at both ends: its archetype's {@code "Tube" = "Closed"}. */
        public boolean closed() {
            return slotRenderer != null && slotRenderer.tube() == CgParsedShader.Tube.CLOSED;
        }

        /** The slot it draws on, a slot renderer's; none for an emitter's renderer. */
        public List<CgVfxSlot> slots() {
            return slotRenderer != null ? List.of(slotRenderer.slot()) : List.of();
        }

        /** The emitter whose renderer it is; null for a slot renderer. */
        public CgVfxEmitter emitter() {
            return emitter;
        }

        /** The renderer it draws; null for a slot renderer. */
        public CgVfxRendererProperties renderer() {
            return renderer;
        }

        /**
         * What it ranks by among the look's draws, 0 to 255, then in the look's order: a renderer's sort order hint, a
         * slot renderer's material {@code order}. The bands: smoke 0 to 63, light 64 to 127, distortion 128 to 191,
         * surface 192 to 255.
         */
        public int hint() {
            if (renderer != null) return renderer.common().sortOrderHint();
            CgMaterialDefinition d = slotRenderer.definition();
            return d == null ? 0 : d.order();
        }

        /** Whether it bends what is drawn before it: its material's Distortion pass. */
        public boolean distorts() {
            CgMaterialDefinition d = definition();
            return d != null && d.distortion();
        }

        /**
         * Whether it draws after its effect's distortion apply, so no haze of its own effect smears it: a surface-band
         * hint ({@link #SURFACE}) on a material that does not distort.
         */
        public boolean afterDistortion() {
            return !distorts() && hint() >= SURFACE;
        }

        /**
         * Its place among the look's draws, 0 to 255: its rank by {@link #hint()}, and the world renderer's {@code order}
         * within the effect.
         */
        public int order() {
            return order;
        }
    }

    private static final Placed[] NONE = new Placed[0];

    /** The width of a role band of {@link Placed#hint()}; the surface band starts at {@link #SURFACE}. */
    public static final int BAND = 64, SURFACE = 3 * BAND;

    private final CgVfxLayout layout;
    private final CgVfxValues values;
    private final List<Placed> placed;
    private final Placed[][] bySlot;
    private final List<CgVfxEmitter> emitters;
    /** Each emitter's renderers as placed. */
    private final Map<CgVfxEmitter, Placed[]> byEmitter = new IdentityHashMap<>();
    private final List<Placed> rendered;
    private final List<Placed> slotted;
    private final int draws;
    private final CgVfxScalability scalability;
    private final Builder recipe;

    private CgVfxLook(Builder b) {
        List<CgVfxSchema> schemas = new ArrayList<>();
        schemas.add(b.primary);
        for (CgVfxSchema s : b.schemas) if (!schemas.contains(s)) schemas.add(s);
        this.layout = CgVfxLayout.of(schemas);
        this.values = new CgVfxValues(layout);
        for (Consumer<CgVfxValues> op : b.ops) op.accept(values);
        // Every draw in the look's order (slot renderers, then each emitter's renderers), ranked by hint; ties keep
        // that order.
        List<Placed> candidates = new ArrayList<>();
        for (Entry e : b.entries) if (e.part instanceof CgVfxSlotRenderer) candidates.add(new Placed((CgVfxSlotRenderer) e.part, 0));
        List<CgVfxEmitter> emitted = new ArrayList<>();
        for (Entry e : b.entries) if (e.part instanceof CgVfxEmitter) emitted.add((CgVfxEmitter) e.part);
        for (CgVfxEmitter e : emitted) {
            for (CgVfxRendererProperties r : e.renderers()) if (r.common().enabled()) candidates.add(new Placed(e, r, 0));
        }
        candidates.sort(Comparator.comparingInt(Placed::hint));
        List<Placed> all = new ArrayList<>(), slotPlaced = new ArrayList<>(), renderers = new ArrayList<>();
        List<List<Placed>> slots = new ArrayList<>();
        Map<CgVfxEmitter, List<Placed>> mine = new IdentityHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            Placed c = candidates.get(i);
            Placed p = new Placed(c.emitter, c.renderer, c.slotRenderer, i);
            all.add(p);
            if (p.slotRenderer != null) {
                slotPlaced.add(p);
                CgVfxSlot slot = p.slotRenderer.slot();
                while (slots.size() <= slot.id) slots.add(null);
                if (slots.get(slot.id) == null) slots.set(slot.id, new ArrayList<>());
                slots.get(slot.id).add(p);
            } else {
                renderers.add(p);
                mine.computeIfAbsent(p.emitter, x -> new ArrayList<>()).add(p);
            }
        }
        this.slotted = Collections.unmodifiableList(slotPlaced);
        this.placed = Collections.unmodifiableList(all);
        this.bySlot = new Placed[slots.size()][];
        for (int i = 0; i < slots.size(); i++) bySlot[i] = slots.get(i) == null ? NONE : slots.get(i).toArray(NONE);
        this.emitters = Collections.unmodifiableList(emitted);
        for (Map.Entry<CgVfxEmitter, List<Placed>> e : mine.entrySet()) byEmitter.put(e.getKey(), e.getValue().toArray(NONE));
        this.rendered = Collections.unmodifiableList(renderers);
        this.draws = candidates.size();
        if (draws > 256) {
            throw new IllegalStateException("A look draws at most 256 slot renderers and renderers, not " + draws);
        }
        this.scalability = b.scalability;
        this.recipe = b.copy();
    }

    /** A look of an effect whose schema is {@code schema}: its parameters at their defaults, nothing drawn yet. */
    public static Builder builder(CgVfxSchema schema) {
        return new Builder(schema);
    }

    /** A new look starting from this one: its kits, its schemas and every value set on it. */
    public Builder derive() {
        return recipe.copy();
    }

    /** The effect's schema: the first of {@link #layout()}'s. */
    public CgVfxSchema schema() {
        return layout.primary();
    }

    public CgVfxLayout layout() {
        return layout;
    }

    /** Every draw it places, slot renderers and renderers, in draw order. */
    public List<Placed> placed() {
        return placed;
    }

    /** Its slot renderers on {@code slot}, in draw order; empty for none. Allocates nothing. */
    public Placed[] on(CgVfxSlot slot) {
        return slot.id < bySlot.length ? bySlot[slot.id] : NONE;
    }

    /** {@code emitter}'s renderers as this look draws them, by hint; empty for none. Allocates nothing. */
    public Placed[] renderers(CgVfxEmitter emitter) {
        Placed[] p = byEmitter.get(emitter);
        return p == null ? NONE : p;
    }

    /** Every emitter's renderers as placed, in draw order. */
    public List<Placed> rendered() {
        return rendered;
    }

    /** Its slot renderers as placed, in draw order. */
    public List<Placed> slotted() {
        return slotted;
    }

    /** How many draws it places: each one's {@link Placed#order()} is below it. */
    public int draws() {
        return draws;
    }

    /** Whether any slot renderer draws on {@code slot}. */
    public boolean draws(CgVfxSlot slot) {
        return on(slot).length > 0;
    }

    /** The emitters its effect plays. */
    public List<CgVfxEmitter> emitters() {
        return emitters;
    }

    /** When its effects are culled, lowered or put to sleep: {@link CgVfxScalability#DEFAULT} unless it names another. */
    public CgVfxScalability scalability() {
        return scalability;
    }

    /** The emitter named {@code name}, or null. */
    public CgVfxEmitter emitter(String name) {
        for (int i = 0; i < emitters.size(); i++) if (emitters.get(i).name().equals(name)) return emitters.get(i);
        return null;
    }

    /** Whether {@code kit} is in it, inside another kit or not. */
    public boolean has(CgVfxKit kit) {
        for (Entry e : recipe.entries) if (e.owners.contains(kit)) return true;
        return recipe.empty.contains(kit);
    }

    public float get(CgVfxParam.Scalar param) {
        return values.get(param);
    }

    public float get(CgVfxParam.Color param, int index) {
        return values.get(param, index);
    }

    /** Component {@code index} of a vector: 0 x, 1 y, 2 z. */
    public float get(CgVfxParam.Vector param, int index) {
        return values.get(param, index);
    }

    public CgKeyframes curve(CgVfxParam.Curve param) {
        return values.curve(param);
    }

    public CgCameraShake shake(CgVfxParam.Shake param) {
        return values.shake(param);
    }

    /** A copy of every value, as a playing effect starts from. */
    public CgVfxValues copyValues() {
        return values.copy();
    }

    CgVfxValues values() {
        return values;
    }

    /** Every draw with its slot or emitter, hint, material and binds, and every value: for a log or a tool. */
    public String describe() {
        StringBuilder sb = new StringBuilder("look of ").append(layout.schemas()).append('\n');
        for (Placed p : placed) {
            sb.append("  ").append(p.order).append(" hint ").append(p.hint()).append(' ');
            Map<String, Object> bind;
            if (p.slotRenderer != null) {
                sb.append(p.slotRenderer.id()).append(" on ").append(p.slotRenderer.slot()).append(" size ")
                        .append(p.slotRenderer.size()).append(' ').append(p.slotRenderer.material());
                bind = p.slotRenderer.bind();
            } else {
                sb.append(p.renderer.common().id()).append(" of ").append(p.emitter.name()).append(' ')
                        .append(p.renderer.common().material());
                bind = p.renderer.common().bind();
            }
            for (Map.Entry<String, Object> e : bind.entrySet()) {
                sb.append("\n      ").append(e.getKey()).append(" <- ").append(e.getValue());
            }
            sb.append('\n');
        }
        for (CgVfxParam param : layout.params()) {
            sb.append("  ").append(layout.qualifiedName(param));
            if (param.numeric()) {
                sb.append(" =");
                for (int i = 0; i < param.floats(); i++) sb.append(' ').append(values.raw(layout.offset(param) + i));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** A slot renderer or an emitter in a look, and the kits it came in. */
    private static final class Entry {
        final Object part;
        final List<CgVfxKit> owners;

        Entry(Object part, List<CgVfxKit> owners) {
            this.part = part;
            this.owners = owners;
        }
    }

    public static final class Builder {

        private final CgVfxSchema primary;
        private final List<CgVfxSchema> schemas = new ArrayList<>();
        private final List<Entry> entries = new ArrayList<>();
        private final List<Consumer<CgVfxValues>> ops = new ArrayList<>();
        /** Kits added that hold no slot renderer or emitter: still {@link #has} them. */
        private final List<CgVfxKit> empty = new ArrayList<>();
        private CgVfxScalability scalability = CgVfxScalability.DEFAULT;

        private Builder(CgVfxSchema primary) {
            this.primary = Objects.requireNonNull(primary, "schema");
        }

        private Builder copy() {
            Builder b = new Builder(primary);
            b.schemas.addAll(schemas);
            b.entries.addAll(entries);
            b.ops.addAll(ops);
            b.empty.addAll(empty);
            b.scalability = scalability;
            return b;
        }

        /** Adds kits: their slot renderers and emitters after those already here, and their schemas. */
        public Builder with(CgVfxKit... kits) {
            for (CgVfxKit kit : kits) {
                if (contains(kit)) throw new IllegalArgumentException(kit + " is in this look already");
                List<Entry> added = new ArrayList<>();
                flatten(kit, new ArrayList<>(), added);
                entries.addAll(added);
                if (added.isEmpty()) empty.add(kit);
                for (CgVfxSchema s : kit.schemas()) if (!schemas.contains(s)) schemas.add(s);
            }
            return this;
        }

        /** Drops kits, inside others or not: their slot renderers and emitters go, their schemas stay. Absent throws. */
        public Builder without(CgVfxKit... kits) {
            for (CgVfxKit kit : kits) {
                if (!contains(kit)) throw new IllegalArgumentException(kit + " is not in this look");
                entries.removeIf(e -> e.owners.contains(kit));
                empty.remove(kit);
            }
            return this;
        }

        /** Puts {@code with} where {@code kit} was, in the draw order, and drops {@code kit}. */
        public Builder replace(CgVfxKit kit, CgVfxKit with) {
            int at = -1;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).owners.contains(kit)) {
                    at = i;
                    break;
                }
            }
            without(kit);
            List<Entry> added = new ArrayList<>();
            flatten(with, new ArrayList<>(), added);
            entries.addAll(at < 0 ? entries.size() : Math.min(at, entries.size()), added);
            for (CgVfxSchema s : with.schemas()) if (!schemas.contains(s)) schemas.add(s);
            return this;
        }

        /** A slot renderer of the look's own, after those here. */
        public Builder slot(CgVfxSlotRenderer renderer) {
            entries.add(new Entry(Objects.requireNonNull(renderer, "renderer"), Collections.emptyList()));
            return this;
        }

        /** Adds an emitter, or replaces the one of its name where it is: how a look changes one element's physics. */
        public Builder emitter(CgVfxEmitter emitter) {
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if (e.part instanceof CgVfxEmitter && ((CgVfxEmitter) e.part).name().equals(emitter.name())) {
                    entries.set(i, new Entry(emitter, e.owners));
                    return this;
                }
            }
            entries.add(new Entry(emitter, Collections.emptyList()));
            return this;
        }

        /**
         * When its effects are culled, drawn lower or put to sleep, shared with every look naming the same object
         * ({@link CgVfxScalability}'s caps count them together).
         *
         * <pre>{@code
         * CgVfxLook.builder(SCHEMA).with(SPARKS).scalability(CgVfxScalability.IMPACT).build();
         * }</pre>
         */
        public Builder scalability(CgVfxScalability scalability) {
            this.scalability = Objects.requireNonNull(scalability, "scalability");
            return this;
        }

        /** Schemas of the look's own: parameters its renderers bind and no kit brought. */
        public Builder params(CgVfxSchema... more) {
            for (CgVfxSchema s : more) if (s != primary && !schemas.contains(s)) schemas.add(s);
            return this;
        }

        public Builder set(CgVfxParam.Scalar param, float value) {
            ops.add(v -> v.set(param, value));
            return this;
        }

        public Builder set(CgVfxParam.Vector param, float x, float y, float z) {
            ops.add(v -> v.set(param, x, y, z));
            return this;
        }

        /** {@code 0xAARRGGBB}. */
        public Builder set(CgVfxParam.Color param, int argb) {
            return set(param, argb, 1f);
        }

        /** {@code 0xAARRGGBB}, its rgb times {@code intensity}: past white where above 1. */
        public Builder set(CgVfxParam.Color param, int argb, float intensity) {
            ops.add(v -> v.set(param, argb, intensity));
            return this;
        }

        /** Colour {@code entry} of a palette, {@code 0xAARRGGBB}. */
        public Builder set(CgVfxParam.Palette param, int entry, int argb) {
            return set(param, entry, argb, 1f);
        }

        /** Colour {@code entry} of a palette, {@code 0xAARRGGBB}, its rgb times {@code intensity}. */
        public Builder set(CgVfxParam.Palette param, int entry, int argb, float intensity) {
            ops.add(v -> v.set(param, entry, argb, intensity));
            return this;
        }

        public Builder set(CgVfxParam.Curve param, CgKeyframes curve) {
            ops.add(v -> v.set(param, curve));
            return this;
        }

        public Builder set(CgVfxParam.Shake param, CgCameraShake shake) {
            ops.add(v -> v.set(param, shake));
            return this;
        }

        /** {@code param} follows {@code source × factor}. */
        public Builder link(CgVfxParam.Scalar param, CgVfxParam.Scalar source, float factor) {
            ops.add(v -> v.link(param, source, factor));
            return this;
        }

        /**
         * Every length and speed times {@code s} as they stand here, and every emitter here scaled by it: a look
         * authored at one size played at another. A kit's emitters that scale with one of its lengths
         * ({@link CgVfxKit.Builder#scalesWith}) are left to it.
         */
        public Builder scaled(float s) {
            if (!(s > 0f)) throw new IllegalArgumentException("scale " + s);
            ops.add(v -> v.scale(s));
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if (e.part instanceof CgVfxEmitter && !scalesItself(e)) {
                    entries.set(i, new Entry(((CgVfxEmitter) e.part).scaled(s), e.owners));
                }
            }
            return this;
        }

        private static boolean scalesItself(Entry e) {
            for (CgVfxKit kit : e.owners) if (kit.scalesWith() != null) return true;
            return false;
        }

        public CgVfxLook build() {
            return new CgVfxLook(this);
        }

        private boolean contains(CgVfxKit kit) {
            if (empty.contains(kit)) return true;
            for (Entry e : entries) if (e.owners.contains(kit)) return true;
            return false;
        }

        private static void flatten(CgVfxKit kit, List<CgVfxKit> chain, List<Entry> out) {
            List<CgVfxKit> owners = new ArrayList<>(chain);
            owners.add(kit);
            List<CgVfxKit> fixed = Collections.unmodifiableList(owners);
            for (Object part : kit.parts) {
                if (part instanceof CgVfxKit) flatten((CgVfxKit) part, owners, out);
                else out.add(new Entry(part, fixed));
            }
        }
    }
}
