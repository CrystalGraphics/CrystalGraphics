package com.crystalgraphics.material.shader.syntax;

import com.github.bsideup.jabel.Desugar;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A shader file's top-level items, each a span of its tokens: the directives, the named blocks, the {@code Step}s, the
 * structs, the functions and every other declaration, in order. What they mean is the domain's; this is their shape.
 *
 * <pre>{@code
 * CgShaderSyntax file = CgShaderSyntax.read(CgShaderTokens.lex(source, path), CgShaderSyntax.MATERIAL);
 * CgShaderSyntax.Block material = file.block("Material");          // null where absent
 * for (CgShaderSyntax.Function f : file.functions()) { ... }       // f.name(), its body's tokens
 * }</pre>
 *
 * <p>One reader serves every file of the family, each naming its blocks:</p>
 *
 * <pre>{@code
 * CgShaderSyntax.read(tokens, CgShaderSyntax.vocabulary(Set.of("Factory", "Declarations", ...), false));
 * }</pre>
 *
 * <ul>
 *   <li>A material file opens with {@code #domain}; {@link #domainWord()} is its word.</li>
 *   <li>The old format's constructs ({@code Pass}, {@code Tags}, {@code Queue}, {@code #pragma}, {@code #type},
 *       {@code #version}, a {@code main}) fail here, placed on their token, each naming where it went.</li>
 *   <li>A block's body is read by its grammar ({@link CgSettingsGrammar}, {@link CgPropertyGrammar},
 *       {@link CgKeywordGrammar}, {@link CgRenderStateGrammar}) over the tokens between its braces.</li>
 * </ul>
 */
public final class CgShaderSyntax {

    /** A top-level item: tokens {@link #first()} to {@link #last()}, inclusive. */
    public interface Item {
        int first();

        int last();
    }

    /** A preprocessor line. */
    @Desugar
    public record Directive(int first) implements Item {
        @Override
        public int last() {
            return first;
        }
    }

    /** {@code Name { ... }}: its body the tokens between {@link #open()} and {@link #close()}. */
    @Desugar
    public record Block(String name, int first, int open, int close, int last) implements Item {
    }

    /** {@code struct Name { ... };}. */
    @Desugar
    public record Struct(String name, int first, int open, int close, int last) implements Item {
    }

    /** {@code type name(params) { body }}: its parameters between {@link #paramOpen()} and its match. */
    @Desugar
    public record Function(String name, int first, int nameToken, int paramOpen, int open, int close) implements Item {
        @Override
        public int last() {
            return close;
        }
    }

    /** Any other declaration, up to its {@code ;} or its closing brace. */
    @Desugar
    public record Declaration(int first, int last) implements Item {
    }

    /** {@code Step name { Setting value ... code }}: a post material's step, its settings before its code. */
    @Desugar
    public record Step(String name, int first, int open, int close, int settingsEnd, List<Item> items) implements Item {
        @Override
        public int last() {
            return close;
        }
    }

    /** The blocks a file may hold, and whether it is a material ({@code #domain} first, {@code Step}s allowed). */
    @Desugar
    public record Vocabulary(Set<String> blocks, boolean material) {
    }

    /** A {@code .shader} material. */
    public static final Vocabulary MATERIAL = new Vocabulary(Set.of("Material", "Keywords", "Properties", "Buffers",
            "RenderState", "EngineBuffers", "Layers"), true);

    private static final Set<String> OLD = Set.of("Pass", "Tags", "Queue", "SubShader");

    private final CgShaderTokens tokens;
    private final int domain;
    private final List<Item> items;

    private CgShaderSyntax(CgShaderTokens tokens, int domain, List<Item> items) {
        this.tokens = tokens;
        this.domain = domain;
        this.items = items;
    }

    public static Vocabulary vocabulary(Set<String> blocks, boolean material) {
        return new Vocabulary(blocks, material);
    }

    /** {@code tokens} read as a file of {@code vocabulary}. */
    public static CgShaderSyntax read(CgShaderTokens tokens, Vocabulary vocabulary) {
        int at = 0, domain = -1;
        if (vocabulary.material()) {
            if (!tokens.isDirective(0, "domain")) {
                throw CgShaderError.of(tokens.text().path(), "a .shader opens with its #domain line (surface, post, ui"
                        + " or global): docs/MATERIAL_FORMAT.md");
            }
            domain = 0;
            at = 1;
        }
        return new CgShaderSyntax(tokens, domain, readItems(tokens, at, tokens.count(), vocabulary, true));
    }

    public CgShaderTokens tokens() {
        return tokens;
    }

    public CgShaderText text() {
        return tokens.text();
    }

    public List<Item> items() {
        return items;
    }

    /** The {@code #domain} line's word; refuses a line that is not {@code #domain <word>}. */
    public String domainWord() {
        String line = tokens.text(domain).substring(1).trim().substring("domain".length()).trim();
        boolean word = !line.isEmpty();
        for (int i = 0; i < line.length() && word; i++) word = Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_';
        if (!word) throw tokens.error(domain, "'#domain' takes one word: surface, post, ui, decal or global");
        return line;
    }

    /** The {@code #domain} line's token; -1 for a template. */
    public int domainToken() {
        return domain;
    }

    /** The block of that name, or null. */
    @Nullable
    public Block block(String name) {
        for (Item i : items) if (i instanceof Block b && b.name().equals(name)) return b;
        return null;
    }

    /** The struct of that name at the top level, or null. */
    @Nullable
    public Struct struct(String name) {
        for (Item i : items) if (i instanceof Struct s && s.name().equals(name)) return s;
        return null;
    }

    /** The source an item spans, as written. */
    public String text(Item item) {
        return tokens.slice(item.first(), item.last());
    }

    /** A block's or struct's body, as written, between its braces. */
    public String body(int open, int close) {
        return tokens.text().slice(tokens.end(open), tokens.start(close));
    }

    // ── The reader ────────────────────────────────────────────────────────────────

    private static List<Item> readItems(CgShaderTokens t, int from, int to, Vocabulary v, boolean top) {
        List<Item> out = new ArrayList<>();
        int i = from;
        while (i < to) {
            if (t.kind(i) == CgShaderTokens.DIRECTIVE) {
                refuseDirective(t, i);
                out.add(new Directive(i++));
                continue;
            }
            if (!t.isIdent(i)) throw t.error(i, "'" + t.text(i) + "' is no declaration");
            String word = t.text(i);
            if (top && v.blocks().contains(word) && t.is(i + 1, '{')) {
                for (Item seen : out) {
                    if (seen instanceof Block b && b.name().equals(word)) throw t.error(i, "'" + word + "' twice");
                }
                int close = t.close(i + 1);
                int last = t.is(close + 1, ';') ? close + 1 : close;
                out.add(new Block(word, i, i + 1, close, last));
                i = last + 1;
                continue;
            }
            if (top && v.material() && word.equals("Step")) {
                if (!t.isIdent(i + 1)) throw t.error(i, "'Step' takes a name: Step bright { ... }");
                if (!t.is(i + 2, '{')) throw t.error(i + 1, "'Step " + t.text(i + 1) + "' takes a { } block");
                int open = i + 2, close = t.close(open), settings = settingsEnd(t, open + 1, close);
                out.add(new Step(t.text(i + 1), i, open, close, settings,
                        Collections.unmodifiableList(readItems(t, settings, close, v, false))));
                i = close + 1;
                continue;
            }
            if (OLD.contains(word)) {
                throw t.error(i, "'" + word + "' is not written in a .shader: a #domain file has no passes, tags or"
                        + " queue; Material { } says how it draws");
            }
            int last = -1;
            for (int j = i; j < to && last < 0; j++) {
                if (t.is(j, '{')) {
                    int close = t.close(j);
                    last = t.is(close + 1, ';') ? close + 1 : close;
                } else if (t.is(j, ';')) {
                    last = j;
                } else if (t.kind(j) == CgShaderTokens.DIRECTIVE) {
                    break;
                }
            }
            if (last < 0) throw t.error(i, "'" + lineAt(t, i) + "' never ends");
            out.add(item(t, i, last));
            i = last + 1;
        }
        return out;
    }

    /** A struct, a function or a declaration, by its tokens. */
    private static Item item(CgShaderTokens t, int first, int last) {
        if (t.isIdent(first, "struct") && t.isIdent(first + 1) && t.is(first + 2, '{')) {
            return new Struct(t.text(first + 1), first, first + 2, t.close(first + 2), last);
        }
        // type... name ( params ) { body }, nothing after the body
        for (int j = first + 1; j < last; j++) {
            if (t.is(j, '{') || t.is(j, '=') || t.is(j, ';')) break;
            if (!t.is(j, '(')) continue;
            int paren = t.close(j);
            if (t.isIdent(j - 1) && j - 1 > first && t.is(paren + 1, '{') && t.close(paren + 1) == last) {
                return new Function(t.text(j - 1), first, j - 1, j, paren + 1, last);
            }
            break;
        }
        return new Declaration(first, last);
    }

    /**
     * Where a step's settings end: {@code Key value} pairs while the key is capitalised and the pair is not a
     * declaration's start ({@code Spark helper(}).
     */
    private static int settingsEnd(CgShaderTokens t, int from, int to) {
        int i = from;
        while (i + 1 < to && t.isIdent(i) && Character.isUpperCase(t.text(i).charAt(0))) {
            int value = i + 1, next = value;
            while (next + 1 < to && t.touchesNext(next) && !t.is(next + 1, '(')) next++;
            if (t.kind(value) != CgShaderTokens.IDENT && t.kind(value) != CgShaderTokens.NUMBER) break;
            if (t.is(next + 1, '(') || t.is(next + 1, ';') || t.is(next + 1, '=') || t.is(next + 1, '[')) break;
            i = next + 1;
        }
        return i;
    }

    private static void refuseDirective(CgShaderTokens t, int i) {
        if (t.isDirective(i, "pragma")) {
            throw t.error(i, "'" + t.text(i) + "' has no place in a #domain file: keywords are Keywords { },"
                    + " per-draw values PerDrawValues On, globals and the world's lights are found by name");
        }
        if (t.isDirective(i, "domain")) throw t.error(i, "'#domain' is the first line, once");
        if (t.isDirective(i, "type")) {
            throw t.error(i, "'#type' is not written in a .shader: a #domain file names its VertexFactory");
        }
        if (t.isDirective(i, "version")) throw t.error(i, "'#version' is the compiler's");
    }

    /** The source line token {@code i} is on, from it to its end, for a message. */
    private static String lineAt(CgShaderTokens t, int i) {
        String s = t.text().source();
        int eol = s.indexOf('\n', t.start(i));
        return s.substring(t.start(i), eol < 0 ? s.length() : eol).trim();
    }
}
