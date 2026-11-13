package com.crystalgraphics.api.material;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.github.bsideup.jabel.Desugar;

/**
 * What of a material changes its programs: its archetype, its keywords, and its state's program-changing fields (blend
 * class, clip, depth test, shading). Everything else is a parameter. The pass and instance kind a draw adds complete a program's
 * key; the world renderer's settings complete a pipeline's.
 *
 * <p>Interned: two definitions with equal permutations hold the same instance, so an instance that overrides only
 * values shares its parent's programs, as Unreal's do: {@code UMaterialInstance::PrecachePSOs} defers to its parent
 * unless it has a static permutation of its own ({@code Engine/Private/Materials/MaterialInstance.cpp:2918-2942},
 * {@code bHasStaticPermutationResource}, {@code Public/Materials/MaterialInstance.h:681}).</p>
 *
 * <pre>{@code
 * CgMaterialPermutation p = CgMaterialPermutation.of("mymod:shaders/fx.shader", List.of("SOFT"), CgBlendMode.ADDITIVE, false, true, null);
 * p == CgMaterialPermutation.of("mymod:shaders/fx.shader", List.of("SOFT"), CgBlendMode.ADDITIVE, false, true, null);   // true
 * }</pre>
 *
 * @param shader   the archetype's path
 * @param keywords its keywords, sorted
 * @param blend    its blend, null for its archetype's own; {@code BlendMode Masked} is {@link CgBlendMode#OPAQUE} with
 *                 {@code clip}
 * @param clip     whether it clips ({@code BlendMode Masked})
 * @param depthTest whether it is tested against the scene's depth ({@code DepthTest}; false is Unreal's bDisableDepthTest)
 * @param shading  the id of the shading model it names, null for its archetype's own
 */
@Desugar
public record CgMaterialPermutation(String shader, List<String> keywords, @Nullable CgBlendMode blend, boolean clip,
                                    boolean depthTest, @Nullable String shading) {

    private static final Map<CgMaterialPermutation, CgMaterialPermutation> INTERNED = new ConcurrentHashMap<>();

    /** The interned permutation of these parts; keywords in any order. */
    public static CgMaterialPermutation of(String shader, Collection<String> keywords, @Nullable CgBlendMode blend,
                                           boolean clip, boolean depthTest, @Nullable String shading) {
        List<String> sorted = new ArrayList<>(keywords);
        Collections.sort(sorted);
        CgMaterialPermutation p = new CgMaterialPermutation(shader, Collections.unmodifiableList(sorted), blend, clip,
                depthTest, shading);
        CgMaterialPermutation had = INTERNED.putIfAbsent(p, p);
        return had != null ? had : p;
    }

    /**
     * The engine's state for it: blend, clip, depth write and depth test; a shading model is not state. An archetype
     * whose depth mode owns depth refuses the depth write: give it none. With no blend it is the archetype's own.
     */
    public CgMaterialState state() {
        CgMaterialState.Builder b = CgMaterialState.builder();
        if (!depthTest) b.disableDepthTest();
        if (blend == null) return b.build();
        b.blend(blend);
        // Unreal's rule: opaque writes depth, a blended draw never does; masked writes it by its clip.
        if (clip) b.clip();
        else b.depthWrite(blend == CgBlendMode.OPAQUE);
        return b.build();
    }
}
