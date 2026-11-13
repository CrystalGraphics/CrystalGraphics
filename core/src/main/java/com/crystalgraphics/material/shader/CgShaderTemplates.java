package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.material.CgVertexFactory;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.material.shader.syntax.CgSettingsGrammar;
import com.crystalgraphics.material.shader.syntax.CgRenderStateGrammar;
import com.crystalgraphics.material.shader.syntax.CgShaderSyntax;
import com.crystalgraphics.material.shader.syntax.CgShaderTokens;
import com.crystalgraphics.util.io.CgIO;
import com.github.bsideup.jabel.Desugar;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The templates a material is pasted into, read from files the first time a material names one, as an
 * {@code #include} resolves: a vertex factory ({@code VertexFactory Sprites}) and a ui primitive
 * ({@code Primitive Quad}). A bare name is the engine's, a namespaced one a mod's:
 *
 * <pre>{@code
 * VertexFactory Sprites            ->  crystalgraphics:shaders/factories/Sprites.factory
 * VertexFactory mymod:Ribbons      ->  mymod:shaders/factories/Ribbons.factory
 * Primitive mymod:Ring             ->  mymod:shaders/canvas/primitives/Ring.primitive
 * }</pre>
 *
 * <p>A factory file, every block but {@code Factory} and {@code fill} optional:</p>
 *
 * <pre>{@code
 * Factory { VertexType none  Parameters cg_Ribbon  Fade cg_Vfx.presence  Usage FX_ON_RIBBONS }
 * EngineBuffers { myribbons }
 * Tags { "Ribbon" = "Flat" }
 * Keywords { WIDE ("Wide strips") }
 * Properties { _Points ("Points", sampler2D) = "black" }
 * Declarations { #include "mymod:shaders/ribbon.glsl"  RibbonPoint cg_Ribbon; }
 * Parameters { float width;  flat int segment;  vec3 frame.normal; }
 * void fill(inout CgVertex v) { cg_Ribbon = ribbon_point(CG_VERTEX_ID); ... v.world = cg_Ribbon.world; }
 * void defaults(inout CgSurface s) { s.light = cg_Ribbon.light; }
 * }</pre>
 *
 * <p>A primitive file: {@code Primitive { Queue Transparent }}, {@code EngineBuffers}, {@code Keywords},
 * {@code Declarations}, {@code Properties}, a {@code RenderState}, {@code struct v2f}, {@code vertex(out v2f o)} and
 * {@code fragment(in v2f i, out vec4 fragColor)}, which runs the material at {@code CG_SURFACE(input, s)}.</p>
 *
 * <ul>
 *   <li>A factory registered in Java ({@link CgVertexFactory#register}) is found before a file.</li>
 *   <li>{@link #forget()} drops what was read, so the next read reads the files again: a shader reload.</li>
 *   <li>{@code Declarations} hold GLSL alone: a switch is a {@code Keywords { }} entry.</li>
 * </ul>
 */
public final class CgShaderTemplates {

    /** A ui primitive, read: what {@code #domain ui} wraps round a material's {@code surface()}. */
    @Desugar
    record Primitive(String name, String path, List<String> buffers, String keywords, String declarations,
                     String properties, String queue, CgRenderState state, CgShaderSyntax file,
                     @Nullable CgShaderSyntax.Struct v2f, String vertexBody, String fragmentBody, String output) {
    }

    private static final CgShaderSyntax.Vocabulary FACTORY = CgShaderSyntax.vocabulary(Set.of("Factory", "EngineBuffers",
            "Tags", "Keywords", "Properties", "Declarations", "Parameters"), false);
    private static final CgShaderSyntax.Vocabulary PRIMITIVE = CgShaderSyntax.vocabulary(Set.of("Primitive",
            "EngineBuffers", "Keywords", "Properties", "Declarations", "RenderState"), false);
    private static final Set<String> INTEGERS = Set.of("int", "uint", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4");

    private static final Map<String, CgVertexFactory.Factory> FACTORIES = new HashMap<>();
    private static final Map<String, Primitive> PRIMITIVES = new HashMap<>();

    private CgShaderTemplates() {}

    /** Drops every template read from a file, so the next read reads it again. */
    public static synchronized void forget() {
        FACTORIES.clear();
        PRIMITIVES.clear();
    }

    /** The factory {@code name} names, registered or from its file; null where neither exists. */
    @Nullable
    static synchronized CgVertexFactory.Factory factory(String name) {
        CgVertexFactory.Factory registered = CgVertexFactory.get(name);
        if (registered != null) return registered;
        if (FACTORIES.containsKey(name)) return FACTORIES.get(name);
        String file = location(name, "factories", "factory");
        String source = CgIO.loadSource(file);
        CgVertexFactory.Factory made = source == null ? null : readFactory(name, source, file);
        FACTORIES.put(name, made);
        return made;
    }

    /** Every factory registered or read so far. */
    static synchronized List<CgVertexFactory.Factory> factories() {
        List<CgVertexFactory.Factory> out = new ArrayList<>(CgVertexFactory.all());
        for (CgVertexFactory.Factory f : FACTORIES.values()) if (f != null) out.add(f);
        return out;
    }

    /** The primitive {@code name} names, from its file; null where there is none. */
    @Nullable
    static synchronized Primitive primitive(String name) {
        if (PRIMITIVES.containsKey(name)) return PRIMITIVES.get(name);
        String file = location(name, "canvas/primitives", "primitive");
        String source = CgIO.loadSource(file);
        Primitive made = source == null ? null : readPrimitive(name, source, file);
        PRIMITIVES.put(name, made);
        return made;
    }

    /** {@code name}'s file: {@code crystalgraphics:shaders/<dir>/<name>.<ext>}, or its own namespace's. */
    static String location(String name, String dir, String ext) {
        int colon = name.indexOf(':');
        String namespace = colon < 0 ? "crystalgraphics" : name.substring(0, colon);
        return namespace + ":shaders/" + dir + "/" + name.substring(colon + 1) + "." + ext;
    }

    // ── A factory file ────────────────────────────────────────────────────────────

    private static CgVertexFactory.Factory readFactory(String name, String source, String path) {
        CgShaderSyntax file = CgShaderSyntax.read(CgShaderTokens.lex(source, path), FACTORY);
        CgShaderTokens t = file.tokens();
        Map<String, CgSettingsGrammar.Setting> settings = settings(file, "Factory",
                List.of("VertexType", "PlacesNothing", "Parameters", "Fade", "Usage"));
        if (!settings.containsKey("VertexType")) {
            throw t.error(0, "'Factory { VertexType ... }' is missing: spatial, none, ...");
        }
        CgShaderSyntax.Function fill = null, defaults = null;
        for (CgShaderSyntax.Item i : file.items()) {
            if (i instanceof CgShaderSyntax.Function f && signature(t, f, "void", "fill", "(", "inout", "CgVertex", "v", ")")) {
                fill = f;
            } else if (i instanceof CgShaderSyntax.Function f
                    && signature(t, f, "void", "defaults", "(", "inout", "CgSurface", "s", ")")) {
                defaults = f;
            } else if (!(i instanceof CgShaderSyntax.Block)) {
                throw t.error(i.first(), "a factory file holds its blocks, 'void fill(inout CgVertex v)' and"
                        + " 'void defaults(inout CgSurface s)': '" + firstLine(file, i) + "'");
            }
        }
        if (fill == null) throw t.error(0, "'void fill(inout CgVertex v)' is missing");
        CgVertexFactory.Builder b = CgVertexFactory.builder(name).vertexType(settings.get("VertexType").value())
                .fill(t.plain(fill.open() + 1, fill.close() - 1))
                .buffers(words(file, "EngineBuffers").toArray(new String[0]))
                .keywords(body(file, "Keywords"))
                .declarations(declarations(file))
                .properties(body(file, "Properties"));
        if (settings.containsKey("PlacesNothing") && settings.get("PlacesNothing").value().equals("On")) b.placesNothing();
        if (defaults != null) b.defaults(t.plain(defaults.open() + 1, defaults.close() - 1));
        if (settings.containsKey("Fade")) b.fade(settings.get("Fade").value());
        if (settings.containsKey("Usage")) b.usage(settings.get("Usage").values().toArray(new String[0]));
        CgShaderSyntax.Block tags = file.block("Tags");
        for (int i = tags == null ? 0 : tags.open() + 1; tags != null && i < tags.close(); i += 3) {
            if (t.kind(i) != CgShaderTokens.STRING || !t.is(i + 1, '=') || t.kind(i + 2) != CgShaderTokens.STRING) {
                throw t.error(i, "'Tags' holds \"Name\" = \"Value\" pairs");
            }
            b.tag(unquote(t.text(i)), unquote(t.text(i + 2)));
        }
        if (settings.containsKey("Parameters")) b.parameters(settings.get("Parameters").value(), parameters(file));
        return b.build();
    }

    /** {@code Parameters { }}: {@code [flat] type name;}, the name dotted for a nested field, an array {@code vec4 d[4]}. */
    private static Map<String, String> parameters(CgShaderSyntax file) {
        Map<String, String> out = new LinkedHashMap<>();
        CgShaderSyntax.Block block = file.block("Parameters");
        if (block == null) return out;
        CgShaderTokens t = file.tokens();
        int i = block.open() + 1;
        while (i < block.close()) {
            int first = i;
            while (i < block.close() && !t.is(i, ';')) i++;
            String decl = t.plain(first, i - 1).replaceAll("\\s+", " ");
            int j = first;
            String qualifier = t.isIdent(j, "flat") || t.isIdent(j, "noperspective") ? t.text(j++) + " " : "";
            if (!t.isIdent(j) || !t.isIdent(j + 1)) throw t.error(first, "'" + decl + "' is no parameter: type name;");
            String type = t.text(j);
            StringBuilder name = new StringBuilder(t.text(j + 1));
            j += 2;
            while (t.is(j, '.') && t.isIdent(j + 1) && j < i) {
                name.append('.').append(t.text(j + 1));
                j += 2;
            }
            String length = null;
            if (t.is(j, '[') && t.kind(j + 1) == CgShaderTokens.NUMBER && t.is(j + 2, ']')) {
                length = t.text(j + 1);
                j += 3;
            }
            if (j != i) throw t.error(first, "'" + decl + "' is no parameter: type name;");
            if (qualifier.isEmpty() && INTEGERS.contains(type)) throw t.error(first, "'" + decl + "' is an integer: flat " + type);
            out.put(name.toString(), qualifier + type + (length == null ? "" : "[" + length + "]"));
            i++;
        }
        return out;
    }

    // ── A primitive file ──────────────────────────────────────────────────────────

    private static Primitive readPrimitive(String name, String source, String path) {
        CgShaderSyntax file = CgShaderSyntax.read(CgShaderTokens.lex(source, path), PRIMITIVE);
        CgShaderTokens t = file.tokens();
        Map<String, CgSettingsGrammar.Setting> settings = settings(file, "Primitive", List.of("Queue"));
        CgShaderSyntax.Struct v2f = null;
        CgShaderSyntax.Function vertex = null, fragment = null;
        for (CgShaderSyntax.Item i : file.items()) {
            if (i instanceof CgShaderSyntax.Struct s && s.name().equals("v2f")) v2f = s;
            else if (i instanceof CgShaderSyntax.Function f && f.name().equals("vertex")) vertex = f;
            else if (i instanceof CgShaderSyntax.Function f && f.name().equals("fragment")) fragment = f;
            else if (!(i instanceof CgShaderSyntax.Block)) {
                throw t.error(i.first(), "a primitive file holds its blocks, struct v2f, vertex() and fragment(): '"
                        + firstLine(file, i) + "'");
            }
        }
        if (v2f == null || vertex == null || fragment == null) {
            throw t.error(0, "a primitive file writes struct v2f, vertex(out v2f o) and fragment(in v2f i, out vec4 fragColor)");
        }
        int call = -1;
        for (int i = fragment.open() + 1; i < fragment.close() && call < 0; i++) {
            if (t.isIdent(i, SpecReader.SURFACE_CALL) && t.is(i + 1, '(')) call = i;
        }
        if (call < 0) throw t.error(fragment.first(), "fragment() runs the material at " + SpecReader.SURFACE_CALL + "(input, s)");
        String fragmentBody = t.plain(fragment.open() + 1, fragment.close() - 1, SpecReader.SURFACE_CALL,
                SpecReader.MATERIAL_SURFACE);
        CgShaderSyntax.Block state = file.block("RenderState");
        return new Primitive(name, path, words(file, "EngineBuffers"), body(file, "Keywords"), declarations(file),
                body(file, "Properties"), settings.containsKey("Queue") ? settings.get("Queue").value() : "Transparent",
                state == null ? CgRenderState.DEFAULT : CgRenderStateGrammar.read(t, state.open() + 1, state.close()),
                file, v2f, t.plain(vertex.open() + 1, vertex.close() - 1), fragmentBody,
                SpecReader.output(file, fragment));
    }

    // ── Shared ────────────────────────────────────────────────────────────────────

    /** A file's {@code Declarations}, GLSL alone. */
    private static String declarations(CgShaderSyntax file) {
        CgShaderSyntax.Block b = file.block("Declarations");
        if (b == null) return "";
        CgShaderTokens t = file.tokens();
        for (int i = b.open() + 1; i < b.close(); i++) {
            if (t.isDirective(i, "pragma")) {
                throw t.error(i, "'#pragma' in Declarations: a switch is a 'Keywords { }' entry, as in a material");
            }
        }
        return b.close() > b.open() + 1 ? t.plain(b.open() + 1, b.close() - 1) : "";
    }

    private static Map<String, CgSettingsGrammar.Setting> settings(CgShaderSyntax file, String block, List<String> allowed) {
        CgShaderSyntax.Block b = file.block(block);
        return b == null ? new LinkedHashMap<>() : CgSettingsGrammar.read(file.tokens(), b.open() + 1, b.close(), allowed, block);
    }

    /** A block's body as written, trimmed; empty where it is absent. */
    private static String body(CgShaderSyntax file, String block) {
        CgShaderSyntax.Block b = file.block(block);
        return b == null ? "" : file.body(b.open(), b.close()).strip();
    }

    /** A block's whitespace-separated words, each its tokens joined: {@code EngineBuffers { quad clip }}. */
    private static List<String> words(CgShaderSyntax file, String block) {
        List<String> out = new ArrayList<>();
        CgShaderSyntax.Block b = file.block(block);
        if (b == null) return out;
        CgShaderTokens t = file.tokens();
        for (int i = b.open() + 1; i < b.close(); i++) {
            int first = i;
            while (i + 1 < b.close() && t.touchesNext(i)) i++;
            out.add(t.slice(first, i));
        }
        return out;
    }

    /** Whether {@code f}'s signature is exactly {@code words}, token for token. */
    static boolean signature(CgShaderTokens t, CgShaderSyntax.Function f, String... words) {
        if (t.close(f.paramOpen()) - f.first() + 1 != words.length) return false;
        for (int k = 0; k < words.length; k++) {
            String w = words[k];
            int i = f.first() + k;
            boolean ok = w.length() == 1 && !Character.isLetterOrDigit(w.charAt(0)) ? t.is(i, w.charAt(0)) : t.isIdent(i, w);
            if (!ok) return false;
        }
        return true;
    }

    private static String firstLine(CgShaderSyntax file, CgShaderSyntax.Item i) {
        String text = file.text(i);
        int eol = text.indexOf('\n');
        return (eol < 0 ? text : text.substring(0, eol)).trim();
    }

    private static String unquote(String s) {
        return s.substring(1, s.length() - 1);
    }
}
