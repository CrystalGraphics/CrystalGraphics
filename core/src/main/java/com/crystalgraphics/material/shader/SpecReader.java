package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.gpu.resource.CgEngineBufferRegistry;
import com.crystalgraphics.material.shader.syntax.CgCodeScan;
import com.crystalgraphics.material.shader.syntax.CgSettingsGrammar;
import com.crystalgraphics.material.shader.syntax.CgShaderError;
import com.crystalgraphics.material.shader.syntax.CgShaderSyntax;
import com.crystalgraphics.material.shader.syntax.CgShaderTokens;
import com.github.bsideup.jabel.Desugar;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a {@code .shader} into its {@link CgShaderSpec}: the file's tree, handed to its domain's reader, finished by
 * {@link SpecBuilder}. And what every domain reader shares: the file's code, which functions reach which, and how a
 * pass's code is laid out by stage.
 */
final class SpecReader {

    /** The material's {@code vertex()}, renamed so the pass's own {@code vertex} is the engine's. */
    static final String MATERIAL_VERTEX = "cg_MaterialVertex";

    /** The material's {@code surface()}, renamed. */
    static final String MATERIAL_SURFACE = "cg_MaterialSurface";

    /** Where a ui primitive's fragment runs the material. */
    static final String SURFACE_CALL = "CG_SURFACE";

    private SpecReader() {}

    static CgShaderSpec read(@Nullable String source, String path) {
        if (source == null) {
            throw CgShaderError.of(path, "a .shader opens with its #domain line (surface, post, ui or global):"
                    + " docs/MATERIAL_FORMAT.md");
        }
        CgShaderSyntax file = CgShaderSyntax.read(CgShaderTokens.lex(source, path), CgShaderSyntax.MATERIAL);
        CgShaderTokens t = file.tokens();
        String word = file.domainWord();
        CgMaterialDomain domain;
        try {
            domain = CgMaterialDomain.ofDirective(word);
        } catch (IllegalArgumentException e) {
            throw t.error(file.domainToken(), "'#domain " + word + "' is no domain: surface, post, ui, decal or global");
        }
        if (domain != CgMaterialDomain.POST) {
            for (CgShaderSyntax.Item i : file.items()) {
                if (i instanceof CgShaderSyntax.Step) throw t.error(i.first(), "'Step' is a #domain post material's");
            }
        }
        CgShaderSyntax.Block engine = file.block("EngineBuffers");
        if (domain != CgMaterialDomain.GLOBAL && engine != null) {
            throw t.error(engine.first(), "'EngineBuffers' is a #domain global's: a material reaches engine buffers through"
                    + " what draws it (VertexFactory, PerDrawValues On, a ui Primitive)");
        }
        return switch (domain) {
            case SURFACE -> SurfaceDomain.read(file);
            case GLOBAL -> GlobalDomain.read(file);
            case POST -> PostDomain.read(file);
            case UI -> UiDomain.read(file);
            case DECAL -> throw t.error(file.domainToken(), "'#domain decal' has no renderer until R11");
        };
    }

    // ── The file's code ───────────────────────────────────────────────────────────

    /** The code: every item but the blocks, the steps, the v2f and the structs a buffer holds, in order. */
    static List<CgShaderSyntax.Item> code(CgShaderSyntax file, List<CgShaderSyntax.Item> items) {
        Set<String> elements = bufferElements(file);
        List<CgShaderSyntax.Item> out = new ArrayList<>();
        for (CgShaderSyntax.Item i : items) {
            if (i instanceof CgShaderSyntax.Block || i instanceof CgShaderSyntax.Step) continue;
            if (i instanceof CgShaderSyntax.Struct s && (s.name().equals("v2f") || elements.contains(s.name()))) continue;
            if (i instanceof CgShaderSyntax.Function f) {
                if (f.name().equals(MATERIAL_VERTEX) || f.name().equals(MATERIAL_SURFACE)) {
                    throw file.tokens().error(f.nameToken(), "'" + f.name() + "' is the engine's name");
                }
                if (f.name().equals("main")) {
                    throw file.tokens().error(f.nameToken(), "main() is the engine's: it generates void main()."
                            + " Write the domain's function instead");
                }
            }
            out.add(i);
        }
        return out;
    }

    /** The struct names its {@code Buffers { }} entries hold: {@code SPARKS ("Sparks", Spark, readonly)}. */
    static Set<String> bufferElements(CgShaderSyntax file) {
        Set<String> out = new LinkedHashSet<>();
        CgShaderSyntax.Block buffers = file.block("Buffers");
        if (buffers == null) return out;
        CgShaderTokens t = file.tokens();
        for (int i = buffers.open() + 1; i < buffers.close(); i++) {
            if (t.is(i, '(') && t.kind(i + 1) == CgShaderTokens.STRING && t.is(i + 2, ',') && t.isIdent(i + 3)) {
                out.add(t.text(i + 3));
            }
        }
        return out;
    }

    static Map<String, List<CgShaderSyntax.Function>> functions(List<CgShaderSyntax.Item> code) {
        Map<String, List<CgShaderSyntax.Function>> out = new LinkedHashMap<>();
        for (CgShaderSyntax.Item i : code) {
            if (i instanceof CgShaderSyntax.Function f) out.computeIfAbsent(f.name(), k -> new ArrayList<>()).add(f);
        }
        return out;
    }

    /** The one function named {@code name}, or null; two fail. */
    @Nullable
    static CgShaderSyntax.Function only(CgShaderSyntax file, Map<String, List<CgShaderSyntax.Function>> functions,
                                        String name) {
        List<CgShaderSyntax.Function> found = functions.get(name);
        if (found == null) return null;
        if (found.size() > 1) throw file.tokens().error(found.get(1).nameToken(), "'" + name + "(' is written twice");
        return found.get(0);
    }

    /** The functions {@code root} calls, itself included, by name. */
    static Set<String> reach(CgShaderSyntax file, Map<String, List<CgShaderSyntax.Function>> functions,
                             @Nullable CgShaderSyntax.Function root) {
        Set<String> seen = new LinkedHashSet<>();
        if (root == null) return seen;
        Deque<String> todo = new ArrayDeque<>();
        todo.add(root.name());
        while (!todo.isEmpty()) {
            String name = todo.pop();
            if (!seen.add(name)) continue;
            for (CgShaderSyntax.Function f : functions.getOrDefault(name, List.of())) {
                for (String id : body(file, f).identifiers()) {
                    if (functions.containsKey(id) && !seen.contains(id)) todo.add(id);
                }
            }
        }
        return seen;
    }

    /** A function's body, scanned. */
    static CgCodeScan body(CgShaderSyntax file, CgShaderSyntax.Function f) {
        return CgCodeScan.of(file.tokens(), f.open() + 1, f.close());
    }

    /** An item, scanned whole. */
    static CgCodeScan scan(CgShaderSyntax file, CgShaderSyntax.Item i) {
        return CgCodeScan.of(file.tokens(), i.first(), i.last() + 1);
    }

    /**
     * The parameter names of {@code type} in {@code f}'s signature: {@code (in v2f i, inout CgSurface s)} gives
     * {@code s} for {@code CgSurface}.
     */
    static List<String> parameters(CgShaderSyntax file, CgShaderSyntax.Function f, String type) {
        List<String> out = new ArrayList<>();
        CgShaderTokens t = file.tokens();
        int close = t.close(f.paramOpen());
        for (int i = f.paramOpen() + 1; i + 1 < close; i++) {
            if (t.isIdent(i, type) && t.isIdent(i + 1) && (t.is(i + 2, ',') || i + 2 == close)) out.add(t.text(i + 1));
        }
        return out;
    }

    /**
     * Whether {@code f}'s signature is {@code words}, an {@code in} before a {@code v2f}, {@code CgScreen} or
     * {@code CgUiInput} optional; {@code *} stands for any name. Gives the names {@code *} stood for, or null.
     */
    @Nullable
    static List<String> signature(CgShaderSyntax file, CgShaderSyntax.Function f, String... words) {
        CgShaderTokens t = file.tokens();
        int close = t.close(f.paramOpen()), i = f.first();
        List<String> names = new ArrayList<>();
        for (String w : words) {
            if (w.equals("in?")) {
                if (t.isIdent(i, "in")) i++;
                continue;
            }
            if (i > close) return null;
            if (w.equals("*")) {
                if (!t.isIdent(i)) return null;
                names.add(t.text(i));
            } else if (w.length() == 1 && !Character.isLetterOrDigit(w.charAt(0))) {
                if (!t.is(i, w.charAt(0))) return null;
            } else if (!t.isIdent(i, w)) {
                return null;
            }
            i++;
        }
        return i == close + 1 ? names : null;
    }

    /** A fragment's {@code out vec4 <name>}; a fragment with no such parameter fails. */
    static String output(CgShaderSyntax file, CgShaderSyntax.Function fragment) {
        CgShaderTokens t = file.tokens();
        int close = t.close(fragment.paramOpen());
        for (int i = fragment.paramOpen() + 1; i + 2 < close + 1; i++) {
            if (t.isIdent(i, "out") && t.isIdent(i + 1, "vec4") && t.isIdent(i + 2)) return t.text(i + 2);
        }
        throw t.error(fragment.first(), "'" + fragment.name() + "(' writes one 'out vec4': a fragment has no other output");
    }

    /** {@code Material { }}'s pairs, each one of {@code allowed}. */
    static Map<String, CgSettingsGrammar.Setting> material(CgShaderSyntax file, Collection<String> allowed) {
        CgShaderSyntax.Block b = file.block("Material");
        return b == null ? new LinkedHashMap<>()
                : CgSettingsGrammar.read(file.tokens(), b.open() + 1, b.close(), allowed, "Material");
    }

    /** A setting's value, {@code fallback} where absent, one of {@code values}. */
    static String choice(CgShaderSyntax file, Map<String, CgSettingsGrammar.Setting> m, String key, String fallback,
                         String... values) {
        CgSettingsGrammar.Setting s = m.get(key);
        String v = s == null ? fallback : s.value();
        if (!List.of(values).contains(v)) {
            throw file.tokens().error(s == null ? 0 : s.valueToken(), "'" + key + " " + v + "' is one of " + List.of(values));
        }
        return v;
    }

    static boolean onOff(CgShaderSyntax file, Map<String, CgSettingsGrammar.Setting> m, String key, boolean fallback) {
        return choice(file, m, key, fallback ? "On" : "Off", "On", "Off").equals("On");
    }

    /** Whether the code names a global parameter, {@code CG_GLOBAL_<name>}. */
    static boolean namesGlobals(CgShaderSyntax file, List<CgShaderSyntax.Item> code) {
        for (CgShaderSyntax.Item i : code) if (scan(file, i).namesPrefix("CG_GLOBAL_")) return true;
        return false;
    }

    /**
     * A file reaches an engine buffer through what draws it (its factory, {@code PerDrawValues On}, a primitive), never
     * by naming it: one its code names and {@code reached} does not reach fails, naming what would.
     */
    static void refuseNamedBuffers(CgShaderSyntax file, List<CgShaderSyntax.Item> code, Collection<String> reached) {
        List<String> with = CgEngineBufferRegistry.withRequirements(new ArrayList<>(reached));
        for (String token : CgEngineBufferRegistry.tokens()) {
            if (with.contains(token)) continue;
            String macro = CgEngineBufferRegistry.get(token).macroName(), family = family(macro);
            for (CgShaderSyntax.Item i : code) {
                CgCodeScan s = scan(file, i);
                if (s.names(macro) || s.namesPrefix(family)) {
                    throw file.tokens().error(i.first(), "'" + family + "' is the engine's '" + token + "' buffer, which a"
                            + " #domain file reaches through what draws it (VertexFactory, PerDrawValues On, a ui Primitive),"
                            + " never by naming it");
                }
            }
        }
    }

    /** {@code CG_QUAD_} from {@code QUAD_DATA}: the convenience macros that expand to an engine buffer's raw name. */
    static String family(String macro) {
        return "CG_" + (macro.endsWith("_DATA") ? macro.substring(0, macro.length() - 5) : macro) + "_";
    }

    // ── A pass's code, by stage ───────────────────────────────────────────────────

    /**
     * What a pass compiles at file scope: preprocessor lines hoisted ahead of the engine's environment, and code in
     * order, each piece in the stages it compiles into. A conditional holding code stays with it, whole: hoisted, its
     * {@code #if} would leave the code unguarded. A material's {@code #include} stays in its place among its code, after
     * the environment and the factory's declarations, so a library function reads the engine as the material does
     * (Unreal's material function); a template's includes are hoisted, since the environment reads them.
     */
    static final class PassCode {
        final List<String> directives = new ArrayList<>();
        final List<CgShaderPass.Code> code = new ArrayList<>();

        void directive(String line) {
            directives.add(line);
        }

        void code(String text, int stages) {
            code.add(new CgShaderPass.Code(text, stages));
        }

        /** GLSL written as text (a template's declarations): its preprocessor lines hoisted, its code for both stages. */
        void glsl(String text, String path) {
            if (text.isBlank()) return;
            CgShaderSyntax decls = CgShaderSyntax.read(CgShaderTokens.lex(text, path),
                    CgShaderSyntax.vocabulary(Set.of(), false));
            List<Piece> pieces = new ArrayList<>();
            for (CgShaderSyntax.Item i : decls.items()) pieces.add(new Piece(decls, i, decls.tokens().plain(i.first(), i.last()), CgShaderPass.BOTH));
            lay(pieces, false);
        }

        /**
         * A material's code: each function compiled into the stages that reach it, {@code first} and {@code second}
         * the two entry points. A material's own ({@code rename}) are renamed to the engine's names and kept in order;
         * a global's are left out, written as the pass's bodies.
         */
        void material(CgShaderSyntax file, List<CgShaderSyntax.Item> items, @Nullable CgShaderSyntax.Function first,
                      CgShaderSyntax.Function second, boolean rename) {
            Map<String, List<CgShaderSyntax.Function>> functions = functions(items);
            Set<String> vertexReach = reach(file, functions, first), fragmentReach = reach(file, functions, second);
            CgShaderTokens t = file.tokens();
            List<Piece> pieces = new ArrayList<>();
            for (CgShaderSyntax.Item i : items) {
                if (!rename && (i == first || i == second)) continue;
                String text;
                if (rename && i == first) {
                    text = t.plain(i.first(), i.last(), first.nameToken(), MATERIAL_VERTEX);
                } else if (rename && i == second) {
                    text = t.plain(i.first(), i.last(), second.nameToken(), MATERIAL_SURFACE);
                } else {
                    text = t.plain(i.first(), i.last());
                }
                int stages = CgShaderPass.BOTH;
                if (i instanceof CgShaderSyntax.Function f) {
                    boolean v = vertexReach.contains(f.name()), fr = fragmentReach.contains(f.name());
                    stages = v && fr ? CgShaderPass.BOTH : v ? CgShaderPass.VERTEX : CgShaderPass.FRAGMENT;
                }
                pieces.add(new Piece(file, i, text, stages));
            }
            lay(pieces, true);
        }

        /**
         * Hoists each directive outside a conditional holding code; the rest is code, in order. With {@code includesInPlace},
         * an {@code #include}, and a conditional holding one, stays in order among the code.
         */
        private void lay(List<Piece> pieces, boolean includesInPlace) {
            for (int k = 0; k < pieces.size(); k++) {
                Piece p = pieces.get(k);
                if (!(p.item() instanceof CgShaderSyntax.Directive d)) {
                    code(p.text(), p.stages());
                    continue;
                }
                CgShaderTokens t = p.file().tokens();
                if (!t.isDirective(d.first(), "if") && !t.isDirective(d.first(), "ifdef") && !t.isDirective(d.first(), "ifndef")) {
                    if (includesInPlace && t.isDirective(d.first(), "include")) code(p.text(), CgShaderPass.BOTH);
                    else directive(p.text());
                    continue;
                }
                int end = k, depth = 0;
                boolean holdsCode = false;
                for (int j = k; j < pieces.size(); j++) {
                    Piece q = pieces.get(j);
                    end = j;
                    if (q.item() instanceof CgShaderSyntax.Directive e) {
                        CgShaderTokens u = q.file().tokens();
                        if (u.isDirective(e.first(), "if") || u.isDirective(e.first(), "ifdef") || u.isDirective(e.first(), "ifndef")) {
                            depth++;
                        } else if (u.isDirective(e.first(), "endif") && --depth == 0) {
                            break;
                        } else if (includesInPlace && u.isDirective(e.first(), "include")) {
                            holdsCode = true;
                        }
                    } else {
                        holdsCode = true;
                    }
                }
                for (int j = k; j <= end; j++) {
                    Piece q = pieces.get(j);
                    if (holdsCode) code(q.text(), q.item() instanceof CgShaderSyntax.Directive ? CgShaderPass.BOTH : q.stages());
                    else directive(q.text());
                }
                k = end;
            }
        }

        @Desugar
        private record Piece(CgShaderSyntax file, CgShaderSyntax.Item item, String text, int stages) {
        }
    }

    /** All the GLSL a pass's code and bodies hold, as one text: what the preprocessor's {@code mentions} reads. */
    static String text(PassCode code, String vertexBody, String fragmentBody) {
        StringBuilder sb = new StringBuilder();
        for (String d : code.directives) sb.append(d).append('\n');
        for (CgShaderPass.Code c : code.code) sb.append(c.text()).append('\n');
        return sb.append(vertexBody).append('\n').append(fragmentBody).append('\n').toString();
    }
}
