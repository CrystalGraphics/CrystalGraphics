package com.crystalgraphics.material.shader.syntax;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a piece of GLSL names, asked over its tokens: the identifiers in it, the fields a variable is read or written
 * through, the chains hanging off a variable. A preprocessor line inside it counts as code
 * ({@code #define GLOW s.emissive} names {@code s.emissive}).
 *
 * <pre>{@code
 * CgCodeScan body = CgCodeScan.of(tokens, f.open() + 1, f.close());
 * Set<String> outputs = body.members("s");                 // s.baseColor, s.opacity -> baseColor, opacity
 * boolean reads = body.reads("s", "color");                // named other than as a plain assignment's target
 * List<String> chains = body.chains("cg_Particle");        // "color.rgb", "" for cg_Particle passed whole
 * }</pre>
 *
 * <ul>
 *   <li>Generated code is scanned as text: {@link #of(String)}.</li>
 *   <li>Each question walks the tokens again: ask once and keep the answer.</li>
 * </ul>
 */
public final class CgCodeScan {

    private final CgShaderTokens t;
    private final int from, to;
    private List<CgCodeScan> directives;

    private CgCodeScan(CgShaderTokens t, int from, int to) {
        this.t = t;
        this.from = from;
        this.to = to;
    }

    /** Tokens {@code from} to {@code to}, exclusive. */
    public static CgCodeScan of(CgShaderTokens t, int from, int to) {
        return new CgCodeScan(t, from, to);
    }

    /** GLSL text, such as code a domain generated. */
    public static CgCodeScan of(String glsl) {
        CgShaderTokens t = CgShaderTokens.lexFragment(glsl, "<generated>");
        return new CgCodeScan(t, 0, t.count());
    }

    /** Every identifier named. */
    public Set<String> identifiers() {
        Set<String> out = new LinkedHashSet<>();
        identifiers(out);
        return out;
    }

    /** Adds every identifier named to {@code out}. */
    public void identifiers(Set<String> out) {
        for (int i = from; i < to; i++) if (t.kind(i) == CgShaderTokens.IDENT) out.add(t.text(i));
        for (CgCodeScan d : directives()) d.identifiers(out);
    }

    /** Whether it names {@code word} as a whole identifier. */
    public boolean names(String word) {
        for (int i = from; i < to; i++) if (t.isIdent(i, word)) return true;
        for (CgCodeScan d : directives()) if (d.names(word)) return true;
        return false;
    }

    /** Whether it names an identifier starting with {@code prefix}. */
    public boolean namesPrefix(String prefix) {
        for (int i = from; i < to; i++) if (t.identStartsWith(i, prefix)) return true;
        for (CgCodeScan d : directives()) if (d.namesPrefix(prefix)) return true;
        return false;
    }

    /** The fields {@code variable.<field>} names, in order. */
    public Set<String> members(String variable) {
        Set<String> out = new LinkedHashSet<>();
        members(variable, out);
        return out;
    }

    /** Adds the fields {@code variable.<field>} names to {@code out}. */
    public void members(String variable, Set<String> out) {
        for (int i = from; i < to; i++) {
            if (t.isIdent(i, variable) && !t.is(i - 1, '.') && t.is(i + 1, '.') && t.isIdent(i + 2)) out.add(t.text(i + 2));
        }
        for (CgCodeScan d : directives()) d.members(variable, out);
    }

    /** Whether {@code variable.field} is named anywhere but as the target of a plain {@code =}. */
    public boolean reads(String variable, String field) {
        for (int i = from; i < to; i++) {
            if (t.isIdent(i, variable) && !t.is(i - 1, '.') && t.is(i + 1, '.') && t.isIdent(i + 2, field)
                    && !(t.is(i + 3, '=') && !t.is(i + 4, '='))) {
                return true;
            }
        }
        for (CgCodeScan d : directives()) if (d.reads(variable, field)) return true;
        return false;
    }

    /** Whether {@code variable.field} is named at all. */
    public boolean namesMember(String variable, String field) {
        for (int i = from; i < to; i++) {
            if (t.isIdent(i, variable) && !t.is(i - 1, '.') && t.is(i + 1, '.') && t.isIdent(i + 2, field)) return true;
        }
        for (CgCodeScan d : directives()) if (d.namesMember(variable, field)) return true;
        return false;
    }

    /** Each use of {@code variable}: the dotted chain after it ({@code "color.rgb"}), {@code ""} where it is bare. */
    public List<String> chains(String variable) {
        List<String> out = new ArrayList<>();
        chains(variable, out);
        return out;
    }

    private void chains(String variable, List<String> out) {
        for (int i = from; i < to; i++) {
            if (!t.isIdent(i, variable) || t.is(i - 1, '.')) continue;
            StringBuilder chain = new StringBuilder();
            int j = i + 1;
            while (t.is(j, '.') && t.isIdent(j + 1)) {
                if (chain.length() > 0) chain.append('.');
                chain.append(t.text(j + 1));
                j += 2;
            }
            out.add(chain.toString());
        }
        for (CgCodeScan d : directives()) d.chains(variable, out);
    }

    /** The preprocessor lines in range, each scanned as code without its {@code #}. */
    private List<CgCodeScan> directives() {
        if (directives != null) return directives;
        List<CgCodeScan> out = null;
        for (int i = from; i < to; i++) {
            if (t.kind(i) != CgShaderTokens.DIRECTIVE) continue;
            if (out == null) out = new ArrayList<>();
            String line = t.text(i).substring(1).replace("\\\n", " ").replace("\\\r\n", " ");
            CgShaderTokens inner = CgShaderTokens.lexFragment(line, t.text().path());
            out.add(new CgCodeScan(inner, 0, inner.count()));
        }
        return directives = out == null ? List.of() : out;
    }
}
