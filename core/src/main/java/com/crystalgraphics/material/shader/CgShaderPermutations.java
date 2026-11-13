package com.crystalgraphics.material.shader;

import com.github.bsideup.jabel.Desugar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * The keywords a renderer switches on a shader at draw time, Unreal's shader permutation domain
 * ({@code FShaderPermutationDomain}): a precache names the shader once and every variant a draw can bind is built.
 * Each dimension is a bool (one keyword, off or on) or a one-of (none, or exactly one of its keywords), as Unreal's
 * {@code SHADER_PERMUTATION_BOOL} and {@code SHADER_PERMUTATION_ENUM}. A material's {@code Keywords { }} makes them:
 * a one-of, and a keyword marked {@code switched}.
 *
 * <pre>{@code
 * Keywords { CHEAP ("Cheap", switched)  SHAPE ("Shape") = BOX | CONE }
 * // dimensions [[CHEAP], [SHAPE_BOX, SHAPE_CONE]]: [] [CHEAP] [SHAPE_BOX] [SHAPE_CONE] [CHEAP, SHAPE_BOX] ...
 * spec.permutations().forEach(List.of(), keywords -> build(keywords));
 * spec.permutations().forEach(List.of("TONEMAP"), keywords -> build(keywords));   // each with TONEMAP added
 * }</pre>
 *
 * <ul>
 *   <li>Only keywords a renderer switches belong here: a material's own keywords are set once on it, and precaching
 *       the material covers them.</li>
 *   <li>At most {@link #MAX_VARIANTS} combinations: a shader past it fails when read, naming its size.</li>
 * </ul>
 *
 * @param dimensions each dimension's keywords: one for a bool, two or more for a one-of
 */
@Desugar
public record CgShaderPermutations(List<List<String>> dimensions) {

    /** No runtime keywords: the shader's variants are its materials'. */
    public static final CgShaderPermutations NONE = new CgShaderPermutations(Collections.emptyList());

    /** The most combinations a shader may span. */
    public static final int MAX_VARIANTS = 256;

    public CgShaderPermutations {
        List<List<String>> copy = new ArrayList<>(dimensions.size());
        for (List<String> d : dimensions) copy.add(Collections.unmodifiableList(new ArrayList<>(d)));
        dimensions = Collections.unmodifiableList(copy);
    }

    /** How many combinations it spans: a bool doubles it, a one-of of n multiplies it by n + 1. */
    public int size() {
        int n = 1;
        for (List<String> d : dimensions) n *= d.size() + 1;
        return n;
    }

    /** Whether {@code keyword} is one of its dimensions'. */
    public boolean contains(String keyword) {
        for (List<String> d : dimensions) if (d.contains(keyword)) return true;
        return false;
    }

    /**
     * Hands {@code consumer} every combination with {@code fixed} added, each keyword once. A dimension holding one of
     * {@code fixed}'s keywords is fixed to it, so asking for a combination already chosen yields it alone.
     */
    public void forEach(List<String> fixed, Consumer<List<String>> consumer) {
        walk(0, new ArrayList<>(fixed), fixed, consumer);
    }

    private void walk(int at, List<String> chosen, List<String> fixed, Consumer<List<String>> consumer) {
        if (at == dimensions.size()) {
            consumer.accept(Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(chosen))));
            return;
        }
        List<String> d = dimensions.get(at);
        for (String k : d) {
            if (fixed.contains(k)) {
                walk(at + 1, chosen, fixed, consumer);
                return;
            }
        }
        walk(at + 1, chosen, fixed, consumer);
        for (String k : d) {
            chosen.add(k);
            walk(at + 1, chosen, fixed, consumer);
            chosen.remove(chosen.size() - 1);
        }
    }
}
