package com.crystalgraphics.material.shader.syntax;

import com.crystalgraphics.material.CgMaterialProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * {@code Properties { }}: the values a material instance sets, a kernel exposes, or an engine buffer binds.
 *
 * <pre>{@code
 * Properties {
 *     _Tint   ("Tint", color, hdr) = (1, 1, 1, 1)       // hdr: an editor lets it past 1
 *     _Rough  ("Roughness", float, range(0, 1)) = 0.5   // range: an editor's slider
 *     _Albedo ("Albedo", sampler2D) = "white"
 *     _Bands  ("Bands", vec4[8]) = (0, 0, 0, 0)          // read _Bands(i)
 * }
 * }</pre>
 *
 * <pre>{@code
 * List<CgMaterialProperty> own = CgPropertyGrammar.read(tokens, block.open() + 1, block.close(), false);
 * List<CgMaterialProperty> engine = CgPropertyGrammar.read(provider.properties(), "cg_use quad", true);
 * }</pre>
 *
 * <ul>
 *   <li>Types: {@code float int boolean vec2 vec4 color Range(a, b) sampler2D usampler2D sampler2DArray sampler3D
 *       samplerCube}; {@code vec3} fails (std140 pads it), use {@code vec4}. An array is {@code color[n]} or
 *       {@code vec4[n]}, {@code n} from 1 to {@link #MAX_ARRAY}.</li>
 *   <li>A sampler's default is quoted ({@code "white"}); every other default is kept as written, which
 *       {@link CgMaterialProperty} reads.</li>
 *   <li>An authored name may not take an engine prefix ({@code cg_ CG_ _v2f_ _cg_}) or hold {@code __}; an engine
 *       buffer's ({@code engine}) must take {@code _cg_}.</li>
 *   <li>Each call returns new properties: they are mutable, a material's own.</li>
 * </ul>
 */
public final class CgPropertyGrammar {

    /** The longest array property: 4 KB of the material block, read a chunk at a time past 16. */
    public static final int MAX_ARRAY = 256;

    private static final Set<String> TYPES = Set.of("float", "vec2", "vec3", "vec4", "int", "boolean", "color", "Range",
            "sampler2D", "usampler2D", "sampler2DArray", "sampler3D", "samplerCube");

    private CgPropertyGrammar() {}

    /**
     * The top-level {@code Properties { }} block of a whole source ({@code .compute} included); empty where it has none.
     *
     * <pre>{@code
     * List<CgMaterialProperty> exposed = CgPropertyGrammar.readBlock(CgIO.loadSource(path), path);
     * }</pre>
     */
    public static List<CgMaterialProperty> readBlock(String source, String path) {
        CgShaderTokens t = CgShaderTokens.lex(source, path);
        for (int i = 0; i < t.count(); i++) {
            if (t.isIdent(i, "Properties") && t.is(i + 1, '{')) return read(t, i + 2, t.close(i + 1), false);
            if (t.is(i, '{') || t.is(i, '(') || t.is(i, '[')) i = t.close(i);
        }
        return List.of();
    }

    /** Entries written as text: an engine buffer's, a template's; {@code origin} names them in errors. */
    public static List<CgMaterialProperty> read(String entries, String origin, boolean engine) {
        CgShaderTokens t = CgShaderTokens.lex(entries, origin);
        return read(t, 0, t.count(), engine);
    }

    /** Tokens {@code from} to {@code to} (exclusive) as entries. */
    public static List<CgMaterialProperty> read(CgShaderTokens t, int from, int to, boolean engine) {
        List<CgMaterialProperty> out = new ArrayList<>();
        int i = from;
        while (i < to) {
            int entry = i;
            if (!t.isIdent(i) || !t.is(i + 1, '(')) {
                throw t.error(i, "'" + line(t, i) + "' is no property: _Name (\"Label\", type) = default");
            }
            String name = t.text(i);
            for (CgMaterialProperty p : out) {
                if (p.getName().equals(name)) throw t.error(i, "'" + name + "' is declared twice");
            }
            int open = i + 1, close = t.close(open);
            if (t.kind(open + 1) != CgShaderTokens.STRING || !t.is(open + 2, ',') || t.end(open + 1) - t.start(open + 1) <= 2) {
                throw t.error(entry, "'" + line(t, entry) + "' is missing its quoted label: _Name (\"Label\", type) = default");
            }
            String display = t.text(open + 1);
            display = display.substring(1, display.length() - 1);
            // The type and its marks, split at commas outside parentheses
            List<String> parts = new ArrayList<>();
            int at = open + 3;
            for (int j = at; j <= close; j++) {
                if (t.is(j, '(')) {
                    j = t.close(j);
                } else if (t.is(j, ',') || j == close) {
                    if (j == at) throw t.error(entry, "'" + line(t, entry) + "' has an empty type or mark");
                    parts.add(t.slice(at, j - 1));
                    at = j + 1;
                }
            }
            i = close + 1;
            String raw = null;
            if (t.is(i, '=') && i < to) {
                int first = ++i;
                while (i < to && !entryStart(t, i)) i = t.close(i) >= 0 && !t.is(i, ')') ? t.close(i) + 1 : i + 1;
                if (i > first) raw = t.slice(first, i - 1);
            } else if (i < to && !entryStart(t, i)) {
                throw t.error(i, "'" + line(t, entry) + "': a property is followed by '= default' or the next property");
            }
            out.add(property(t, entry, name, display, parts, raw, engine));
        }
        return Collections.unmodifiableList(out);
    }

    private static CgMaterialProperty property(CgShaderTokens t, int entry, String name, String display, List<String> parts,
                                               String raw, boolean engine) {
        String where = line(t, entry);
        if (engine) {
            if (!name.startsWith("_cg_")) {
                throw t.error(entry, "An engine buffer's property '" + name + "' must take the reserved prefix _cg_");
            }
        } else {
            CgShaderNames.refuseReserved(t, entry, name, "Properties block", false);
        }
        String type = parts.get(0).replaceAll("\\s+", "");
        boolean hdr = false;
        for (int m = 1; m < parts.size(); m++) {
            String mark = parts.get(m).replaceAll("\\s+", "");
            if (mark.equals("hdr") && (type.equals("color") || type.startsWith("color["))) {
                hdr = true;
            } else if (mark.startsWith("range(") && mark.endsWith(")") && type.equals("float") && range(mark) != null) {
                String[] r = range(mark);
                type = "Range(" + r[0] + ", " + r[1] + ")";
            } else {
                throw t.error(entry, "'" + parts.get(m) + "' is no mark of a " + type
                        + ": 'hdr' on a color, 'range(a, b)' on a float. In: '" + where + "'");
            }
        }
        int length = 0;
        int bracket = type.indexOf('[');
        if (bracket > 0 && type.endsWith("]")) {
            String element = type.substring(0, bracket);
            try {
                length = Integer.parseInt(type.substring(bracket + 1, type.length() - 1));
            } catch (NumberFormatException e) {
                length = -1;
            }
            if (!element.equals("color") && !element.equals("vec4") || length < 1 || length > MAX_ARRAY) {
                throw t.error(entry, "An array property is color[n] or vec4[n] with n from 1 to " + MAX_ARRAY
                        + ". In: '" + where + "'");
            }
            type = element;
        }
        String base = type.startsWith("Range(") ? "Range" : type;
        if (!TYPES.contains(base)) {
            throw t.error(entry, "Unknown property type '" + base + "'. Valid types: float, vec2, vec4, int, color, Range,"
                    + " sampler2D, usampler2D, sampler2DArray, sampler3D, samplerCube. (vec3 is not supported: use vec4.)"
                    + " In: '" + where + "'");
        }
        if (base.equals("vec3")) {
            throw t.error(entry, "vec3 is not supported as a material property type: std140 pads it, so use vec4. In: '"
                    + where + "'");
        }
        // usampler2D keeps its quotes, as the reader before kept them: CgMaterialProxy reads its default as written
        if (raw != null && base.startsWith("sampler")) {
            if (raw.length() < 2 || raw.charAt(0) != '"' || raw.charAt(raw.length() - 1) != '"') {
                throw t.error(entry, "Sampler default must be a quoted string (e.g. = \"white\"), got: '" + raw + "' in: '"
                        + where + "'");
            }
            raw = raw.substring(1, raw.length() - 1);
        }
        CgMaterialProperty p = length > 0 ? CgMaterialProperty.fromDecl(name, display, base, raw, length)
                : CgMaterialProperty.fromDecl(name, display, base, raw);
        String[] bounds = base.equals("Range") ? range(type) : null;
        if (bounds != null) {
            p.setRange(Float.parseFloat(bounds[0]), Float.parseFloat(bounds[1]));
            p.set(p.getFloatValue()[0]);
        }
        if (hdr) p.markHdr();
        return p;
    }

    /** The two numbers between {@code range(}/{@code Range(} and its last {@code )}, or null where they are not. */
    private static String[] range(String mark) {
        int open = mark.indexOf('('), close = mark.lastIndexOf(')');
        if (open < 0 || close < open) return null;
        String inner = mark.substring(open + 1, close);
        String[] ends = inner.split(",");
        if (ends.length != 2) return null;
        try {
            Float.parseFloat(ends[0].trim());
            Float.parseFloat(ends[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return new String[]{ends[0].trim(), ends[1].trim()};
    }

    /** Whether token {@code i} starts an entry: {@code _Name ( "Label"}. */
    private static boolean entryStart(CgShaderTokens t, int i) {
        return t.isIdent(i) && t.is(i + 1, '(') && t.kind(i + 2) == CgShaderTokens.STRING;
    }

    private static String line(CgShaderTokens t, int i) {
        String s = t.text().source();
        int eol = s.indexOf('\n', t.start(i));
        return s.substring(t.start(i), eol < 0 ? s.length() : eol).trim();
    }
}
