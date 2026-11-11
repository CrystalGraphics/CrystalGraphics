package com.crystalgraphics.material.parse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.github.bsideup.jabel.Desugar;

/**
 * The keywords a renderer switches on a shader at runtime, Unreal's shader permutation domain
 * ({@code FShaderPermutationDomain}): declared beside the shader, so a precache names the shader once and every
 * variant a draw can bind is built. Each dimension is a bool (one keyword, off or on) or a one-of (none, or exactly one
 * of its keywords), as Unreal's {@code SHADER_PERMUTATION_BOOL} and {@code SHADER_PERMUTATION_ENUM}.
 *
 * <pre>{@code
 * #pragma cg_feature CHEAP
 * #pragma cg_feature KARIS
 * #pragma cg_feature FADE
 * #pragma cg_permutation CHEAP|KARIS      // none, CHEAP or KARIS: never both
 * #pragma cg_permutation FADE             // off or on
 * }</pre>
 *
 * <pre>{@code
 * domain.forEach(List.of(), keywords -> build(keywords));   // [] [CHEAP] [KARIS] [FADE] [CHEAP, FADE] [KARIS, FADE]
 * domain.forEach(List.of("TONEMAP"), keywords -> build(keywords));   // each with TONEMAP added
 * }</pre>
 *
 * <ul>
 *   <li>Only keywords a renderer switches belong here. A material's own keywords (set once on the material, a look's
 *       features) are its own: precaching the material covers them.</li>
 *   <li>Every keyword named is a {@code #pragma cg_feature} of the shader, in one dimension at most.</li>
 *   <li>At most {@link #MAX_VARIANTS} combinations: a domain past it is refused at parse, naming its size.</li>
 * </ul>
 *
 * @param dimensions each dimension's keywords: one for a bool, two or more for a one-of
 */
@Desugar
public record CgPermutationDomain(List<List<String>> dimensions) {

    /** No runtime keywords: the shader's variants are its materials'. */
    public static final CgPermutationDomain EMPTY = new CgPermutationDomain(Collections.emptyList());

    /** The most combinations a domain may span. */
    public static final int MAX_VARIANTS = 256;

    public CgPermutationDomain {
        dimensions = Collections.unmodifiableList(new ArrayList<>(dimensions));
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

    /**
     * Parses the preamble's {@code #pragma cg_permutation} lines, each one dimension: {@code A} a bool, {@code A|B} a
     * one-of. Same region as {@code cg_feature}; every keyword must be a declared feature.
     */
    static CgPermutationDomain parse(String source, List<String> features, String resourcePath) {
        List<List<String>> dimensions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean seenType = false;
        for (String rawLine : source.split("\n")) {
            String line = rawLine.trim();
            if (!seenType) {
                if (line.startsWith("#type")) seenType = true;
                continue;
            }
            if (line.startsWith("Properties {") || line.startsWith("Properties{")
                    || line.startsWith("struct v2f {") || line.startsWith("struct v2f{")
                    || line.startsWith("void vertex(")
                    || line.startsWith("Pass {") || line.startsWith("Pass{")) {
                break;
            }
            if (!line.startsWith(PRAGMA)) continue;
            String rest = line.substring(PRAGMA.length());
            int comment = rest.indexOf("//");
            if (comment >= 0) rest = rest.substring(0, comment);
            rest = rest.trim();
            if (rest.isEmpty()) throw fail(resourcePath, "missing keyword");
            for (String token : rest.split("\\s+")) {
                List<String> d = Arrays.asList(token.split("\\|", -1));
                for (String k : d) {
                    if (!features.contains(k)) {
                        throw fail(resourcePath, "'" + k + "' is not a #pragma cg_feature of this shader");
                    }
                    if (!seen.add(k)) throw fail(resourcePath, "'" + k + "' is in two dimensions");
                }
                dimensions.add(d);
            }
        }
        CgPermutationDomain domain = dimensions.isEmpty() ? EMPTY : new CgPermutationDomain(dimensions);
        if (domain.size() > MAX_VARIANTS) {
            throw fail(resourcePath, domain.size() + " combinations, past " + MAX_VARIANTS
                    + ": keep to the keywords a renderer switches");
        }
        return domain;
    }

    static final String PRAGMA = "#pragma cg_permutation";

    private static CgShaderParseException fail(String resourcePath, String why) {
        return new CgShaderParseException("[" + resourcePath + "] " + PRAGMA + ": " + why);
    }
}
