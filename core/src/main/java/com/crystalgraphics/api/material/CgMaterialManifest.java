package com.crystalgraphics.api.material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.github.bsideup.jabel.Desugar;

/**
 * The permutations a move (or any set of assets) draws, known before it plays: each material's
 * {@link CgMaterialPermutation} times the slot kinds its renderers draw it on. What warming reads, so a move's first
 * frame compiles nothing; the world renderer multiplies in its own variants for the settings in force
 * ({@code CgWorldRenderer.prepare}).
 *
 * <pre>{@code
 * CgMaterialManifest manifest = CgMaterialManifest.builder()
 *         .add(glow)                                   // drawn as plain objects
 *         .add(spark, "FX_ON_PARTICLES", false)        // on a particle slot: that keyword where the archetype has it
 *         .add(smoke, "FX_ON_FLUID", true)             // drawn at half size
 *         .build();
 * CgWorldRenderer.get().programs().warm(manifest);     // at READY; poll the answer's ready()
 * }</pre>
 *
 * <p>A permutation no manifest listed is a cook bug: drawn cold, it is skipped and counted where the world renderer
 * skips unready draws ({@code CgWorldRenderer.skipUnready}).</p>
 *
 * @param uses each permutation and how it is drawn, without repeats
 */
@Desugar
public record CgMaterialManifest(List<Use> uses) {

    /**
     * One permutation as a renderer draws it.
     *
     * @param permutation the material's program key
     * @param kind        the slot kind's keyword ({@code FX_ON_*}), enabled where the archetype declares it; null for none
     * @param half        drawn at half size: the world renderer's half-size passes come with it
     */
    @Desugar
    public record Use(CgMaterialPermutation permutation, @Nullable String kind, boolean half) {
    }

    /** An empty builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Collects uses; a repeat is kept once. */
    public static final class Builder {
        private final Set<Use> uses = new LinkedHashSet<>();

        private Builder() {
        }

        /** {@code definition} drawn as plain objects, at full size. */
        public Builder add(CgMaterialDefinition definition) {
            return add(definition.permutation(), null, false);
        }

        /** {@code definition} drawn on a slot of {@code kind} ({@code FX_ON_*}, or null), at half size or not. */
        public Builder add(CgMaterialDefinition definition, @Nullable String kind, boolean half) {
            return add(definition.permutation(), kind, half);
        }

        /** {@code permutation} drawn on a slot of {@code kind}, at half size or not. */
        public Builder add(CgMaterialPermutation permutation, @Nullable String kind, boolean half) {
            uses.add(new Use(permutation, kind, half));
            return this;
        }

        public CgMaterialManifest build() {
            return new CgMaterialManifest(Collections.unmodifiableList(new ArrayList<>(uses)));
        }
    }
}
