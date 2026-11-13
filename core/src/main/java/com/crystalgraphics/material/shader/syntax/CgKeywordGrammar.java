package com.crystalgraphics.material.shader.syntax;

import com.github.bsideup.jabel.Desugar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code Keywords { }}: compile-time switches, each a program of its own.
 *
 * <pre>{@code
 * Keywords {
 *     MOSS ("Mossy")                      // on or off: #ifdef MOSS
 *     DEPTH ("Writes depth", switched)    // the renderer's to flip at each draw: every combination built ahead
 *     SHAPE ("Shape") = BOX | CONE        // a one-of: SHAPE_BOX or SHAPE_CONE, never both
 * }
 * }</pre>
 *
 * <ul>
 *   <li>The label is optional; every name a keyword expands to is {@code [A-Z][A-Z0-9_]*}.</li>
 *   <li>A one-of of one value fails: it is an on-or-off keyword.</li>
 *   <li>A template's keywords (a factory's, a primitive's) are read from their text with {@link #read(String, String)}.</li>
 * </ul>
 */
public final class CgKeywordGrammar {

    /** One entry: {@code values} empty for an on-or-off keyword. */
    @Desugar
    public record Keyword(String name, List<String> values, boolean switched) {
        public Keyword {
            values = Collections.unmodifiableList(new ArrayList<>(values));
        }

        /** The names it defines: itself, or {@code NAME_VALUE} for each of a one-of's values. */
        public List<String> features() {
            if (values.isEmpty()) return List.of(name);
            List<String> out = new ArrayList<>(values.size());
            for (String v : values) out.add(name + "_" + v);
            return out;
        }

        /** Whether it is a dimension a precache builds every value of: a one-of, or a switched keyword. */
        public boolean dimension() {
            return !values.isEmpty() || switched;
        }
    }

    private CgKeywordGrammar() {}

    /** A template's {@code Keywords { }} entries, as written. */
    public static List<Keyword> read(String entries, String path) {
        CgShaderTokens t = CgShaderTokens.lex(entries, path);
        return read(t, 0, t.count());
    }

    /** Tokens {@code from} to {@code to} (exclusive) as entries. */
    public static List<Keyword> read(CgShaderTokens t, int from, int to) {
        List<Keyword> out = new ArrayList<>();
        int i = from;
        while (i < to) {
            int entry = i;
            if (!t.isIdent(i)) throw shape(t, entry);
            String name = t.text(i++);
            boolean switched = false;
            if (t.is(i, '(') && i < to) {
                int close = t.close(i);
                int j = i + 1;
                if (t.kind(j) != CgShaderTokens.STRING) throw shape(t, entry);
                j++;
                if (t.is(j, ',') && t.isIdent(j + 1, "switched")) {
                    switched = true;
                    j += 2;
                }
                if (j != close) throw shape(t, entry);
                i = close + 1;
            }
            List<String> values = new ArrayList<>();
            if (t.is(i, '=') && i < to) {
                i++;
                if (!t.isIdent(i)) throw shape(t, entry);
                values.add(t.text(i++));
                while (t.is(i, '|') && i < to) {
                    if (!t.isIdent(i + 1)) throw shape(t, entry);
                    values.add(t.text(i + 1));
                    i += 2;
                }
            }
            Keyword k = new Keyword(name, values, switched);
            for (String f : k.features()) {
                if (!keyword(f)) throw t.error(entry, "'" + f + "' is no keyword: [A-Z][A-Z0-9_]*");
            }
            if (values.size() == 1) throw t.error(entry, "'" + name + "' is a one-of of one value: make it on or off");
            out.add(k);
        }
        return out;
    }

    private static boolean keyword(String s) {
        if (s.isEmpty() || s.charAt(0) < 'A' || s.charAt(0) > 'Z') return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_')) return false;
        }
        return true;
    }

    private static CgShaderError shape(CgShaderTokens t, int at) {
        String s = t.text().source();
        int eol = s.indexOf('\n', t.start(at));
        return t.error(at, "'" + s.substring(t.start(at), eol < 0 ? s.length() : eol).trim() + "' is no keyword:"
                + " NAME (\"label\"), NAME (\"label\", switched) or NAME (\"label\") = A | B");
    }
}
