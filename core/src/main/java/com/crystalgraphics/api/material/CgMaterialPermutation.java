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
 * class, clip, shading). Everything else is a parameter. The pass and instance kind a draw adds complete a program's
 * key; the world renderer's settings complete a pipeline's.
 *
 * <p>Interned: two definitions with equal permutations hold the same instance, so an instance that overrides only
 * values shares its parent's programs, as Unreal's do: {@code UMaterialInstance::PrecachePSOs} defers to its parent
 * unless it has a static permutation of its own ({@code Engine/Private/Materials/MaterialInstance.cpp:2918-2942},
 * {@code bHasStaticPermutationResource}, {@code Public/Materials/MaterialInstance.h:681}).</p>
 *
 * <pre>{@code
 * CgMaterialPermutation p = CgMaterialPermutation.of("mymod:shaders/fx.shader", List.of("SOFT"), "additive", false, null);
 * p == CgMaterialPermutation.of("mymod:shaders/fx.shader", List.of("SOFT"), "additive", false, null);   // true
 * }</pre>
 *
 * @param shader   the archetype's path
 * @param keywords its keywords, sorted
 * @param blend    the format's blend class: {@code opaque}, {@code masked}, {@code translucent}, {@code additive},
 *                 {@code modulate}, {@code premultiplied}, {@code subtract}
 * @param clip     whether it clips ({@code masked})
 * @param shading  the shading model it names, null for its archetype's own
 */
@Desugar
public record CgMaterialPermutation(String shader, List<String> keywords, String blend, boolean clip,
                                    @Nullable String shading) {

    private static final Map<CgMaterialPermutation, CgMaterialPermutation> INTERNED = new ConcurrentHashMap<>();

    /** The interned permutation of these parts; keywords in any order. */
    public static CgMaterialPermutation of(String shader, Collection<String> keywords, String blend, boolean clip,
                                           @Nullable String shading) {
        List<String> sorted = new ArrayList<>(keywords);
        Collections.sort(sorted);
        CgMaterialPermutation p = new CgMaterialPermutation(shader, Collections.unmodifiableList(sorted), blend, clip,
                shading);
        CgMaterialPermutation had = INTERNED.putIfAbsent(p, p);
        return had != null ? had : p;
    }

    /**
     * The engine's state for it: blend, clip and depth write; a shading model is not state. An archetype with a
     * {@code "Depth"} tag refuses the depth write: give it none.
     *
     * @throws UnsupportedOperationException for {@code modulate}, which no {@link CgBlendMode} draws yet
     */
    public CgMaterialState state() {
        CgMaterialState.Builder b = CgMaterialState.builder();
        switch (blend) {
            case "opaque":
            case "masked":
                b.blend(CgBlendMode.OPAQUE);
                break;
            case "translucent":
                b.blend(CgBlendMode.ALPHA);
                break;
            case "additive":
                b.blend(CgBlendMode.ADDITIVE);
                break;
            case "premultiplied":
                b.blend(CgBlendMode.PREMULTIPLIED);
                break;
            case "subtract":
                b.blend(CgBlendMode.SUBTRACT);
                break;
            default:
                throw new UnsupportedOperationException("blend '" + blend + "' has no CgBlendMode");
        }
        // Unreal's rule: opaque writes depth, a blended draw never does; masked writes it by its clip.
        if (clip) b.clip();
        else b.depthWrite(blend.equals("opaque"));
        return b.build();
    }
}
