package com.crystalgraphics.material.shader.syntax;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;

/**
 * A shader file as tokens: identifiers, numbers, strings, single punctuation characters, and each preprocessor line
 * whole. Comments and whitespace are skipped; every token keeps its span, and every bracket knows its match.
 *
 * <pre>{@code
 * CgShaderTokens t = CgShaderTokens.lex(new CgShaderText(source, path));
 * for (int i = 0; i < t.count(); i++) {
 *     if (t.isIdent(i, "Material") && t.is(i + 1, '{')) body = t.close(i + 1);   // the matching '}'
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A preprocessor line ({@code #} first on its line, continuations joined) is one {@link #DIRECTIVE}, its text
 *       without a trailing {@code //} comment; a {@code #} anywhere else is punctuation.</li>
 *   <li>An unclosed bracket, string or comment fails here, placed on it.</li>
 *   <li>One pass over the characters into int arrays: no regex, no string until {@link #text} asks.</li>
 * </ul>
 */
public final class CgShaderTokens {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    public static final byte IDENT = 1, NUMBER = 2, STRING = 3, PUNCT = 4, DIRECTIVE = 5;

    private final CgShaderText text;
    private byte[] kind;
    private int[] start, end, match;
    private int count;

    private CgShaderTokens(CgShaderText text, int capacity) {
        this.text = text;
        kind = new byte[capacity];
        start = new int[capacity];
        end = new int[capacity];
        match = new int[capacity];
    }

    /** {@code source} as tokens, its errors naming {@code path}. */
    public static CgShaderTokens lex(String source, String path) {
        return lex(new CgShaderText(source, path));
    }

    public static CgShaderTokens lex(CgShaderText text) {
        CgShaderTokens t = new CgShaderTokens(text, Math.max(16, text.length() / 4));
        t.scan(true);
        return t;
    }

    /** A fragment that may leave brackets open, a macro's body: unmatched ones keep {@link #close} at -1. */
    public static CgShaderTokens lexFragment(String source, String path) {
        CgShaderTokens t = new CgShaderTokens(new CgShaderText(source, path), Math.max(16, source.length() / 4));
        t.scan(false);
        return t;
    }

    public CgShaderText text() {
        return text;
    }

    public int count() {
        return count;
    }

    public byte kind(int i) {
        return i >= 0 && i < count ? kind[i] : 0;
    }

    public int start(int i) {
        return i < 0 ? 0 : i < count ? start[i] : text.length();
    }

    public int end(int i) {
        return i < 0 ? 0 : i < count ? end[i] : text.length();
    }

    /** The token's text: a directive's without its trailing comment, a string's with its quotes. */
    public String text(int i) {
        return text.slice(start(i), end(i));
    }

    /** The source from token {@code from}'s start to token {@code to}'s end, inclusive; empty where {@code to < from}. */
    public String slice(int from, int to) {
        return to < from ? "" : text.slice(start(from), end(to));
    }

    /**
     * Tokens {@code from} to {@code to} (inclusive) as code without its comments: a line break where the source broke
     * the line between two tokens, a space where anything else stood between them. What generated GLSL carries of a
     * file's code.
     */
    public String plain(int from, int to) {
        return plain(from, to, -1, null);
    }

    /** {@link #plain(int, int)} with token {@code swap} written as {@code with}: a function renamed where it is declared. */
    public String plain(int from, int to, int swap, String with) {
        return plain(from, to, swap, null, with);
    }

    /** {@link #plain(int, int)} with every identifier {@code word} written as {@code with}. */
    public String plain(int from, int to, String word, String with) {
        return plain(from, to, -1, word, with);
    }

    private String plain(int from, int to, int swap, String word, String with) {
        StringBuilder sb = new StringBuilder(Math.max(16, end(to) - start(from)));
        String s = text.source();
        for (int i = from; i <= to && i < count; i++) {
            if (i > from && start[i] > end[i - 1]) {
                boolean line = false;
                for (int g = end[i - 1]; g < start[i] && !line; g++) line = s.charAt(g) == '\n';
                sb.append(line ? '\n' : ' ');
            }
            if (i == swap || word != null && isIdent(i, word)) sb.append(with);
            else sb.append(s, start[i], end[i]);
        }
        return sb.toString();
    }

    /** For a bracket, the index of its match; -1 for any other token. */
    public int close(int i) {
        return i >= 0 && i < count ? match[i] : -1;
    }

    public boolean is(int i, char c) {
        return i >= 0 && i < count && kind[i] == PUNCT && text.charAt(start[i]) == c;
    }

    public boolean isIdent(int i) {
        return i >= 0 && i < count && kind[i] == IDENT;
    }

    public boolean isIdent(int i, String word) {
        return i >= 0 && i < count && kind[i] == IDENT && end[i] - start[i] == word.length()
                && text.source().regionMatches(start[i], word, 0, word.length());
    }

    /** Whether the token is an identifier starting with {@code prefix}. */
    public boolean identStartsWith(int i, String prefix) {
        return i >= 0 && i < count && kind[i] == IDENT && end[i] - start[i] >= prefix.length()
                && text.source().regionMatches(start[i], prefix, 0, prefix.length());
    }

    /** Whether token {@code i} is a directive whose word is {@code word}: {@code #include}, {@code #if}. */
    public boolean isDirective(int i, String word) {
        if (i < 0 || i >= count || kind[i] != DIRECTIVE) return false;
        int at = start[i] + 1, n = end[i];
        while (at < n && (text.charAt(at) == ' ' || text.charAt(at) == '\t')) at++;
        int w = at;
        while (w < n && Character.isLetter(text.charAt(w))) w++;
        return w - at == word.length() && text.source().regionMatches(at, word, 0, word.length());
    }

    /** Whether no whitespace or comment stands between token {@code i} and the next. */
    public boolean touchesNext(int i) {
        return i >= 0 && i + 1 < count && end[i] == start[i + 1];
    }

    public CgShaderError error(int i, String why) {
        return CgShaderError.at(text, start(i), why);
    }

    // ── The scan ──────────────────────────────────────────────────────────────────

    private void scan(boolean strict) {
        String s = text.source();
        int n = s.length(), i = 0;
        boolean lineStart = true;
        int[] open = new int[64];
        int depth = 0;
        StringBuilder wide = null;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') {
                lineStart = true;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == '\f') {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int close = s.indexOf("*/", i + 2);
                if (close < 0) throw CgShaderError.at(text, i, "'/*' is never closed");
                i = close + 2;
                continue;
            }
            if (c > 127) {
                if (wide == null) wide = new StringBuilder();
                else wide.append(", ");
                wide.append('\'').append(c).append("' (U+").append(String.format("%04X", (int) c)).append(") at ")
                        .append(text.line(i)).append(':').append(text.column(i));
            }
            int from = i;
            if (c == '#' && lineStart) {
                int last = i;
                while (i < n) {
                    char d = s.charAt(i);
                    if (d == '\n') {
                        int back = i - 1;
                        if (back >= from && s.charAt(back) == '\r') back--;
                        if (back >= from && s.charAt(back) == '\\') {
                            i++;
                            continue;
                        }
                        break;
                    }
                    if (d == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                        while (i < n && s.charAt(i) != '\n') i++;
                        break;
                    }
                    if (d != ' ' && d != '\t' && d != '\r') last = i;
                    i++;
                }
                add(DIRECTIVE, from, last + 1);
                continue;
            }
            lineStart = false;
            if (Character.isLetter(c) || c == '_') {
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                add(IDENT, from, i);
            } else if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1))) {
                boolean hex = c == '0' && i + 1 < n && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X');
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    boolean sign = (d == '+' || d == '-') && !hex && (s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E');
                    if (!Character.isLetterOrDigit(d) && d != '.' && d != '_' && !sign) break;
                    i++;
                }
                add(NUMBER, from, i);
            } else if (c == '"') {
                i++;
                while (i < n && s.charAt(i) != '"' && s.charAt(i) != '\n') i++;
                if (i >= n || s.charAt(i) != '"') throw CgShaderError.at(text, from, "a string is never closed");
                add(STRING, from, ++i);
            } else {
                int at = count;
                add(PUNCT, from, ++i);
                if (c == '(' || c == '[' || c == '{') {
                    if (depth == open.length) open = Arrays.copyOf(open, depth * 2);
                    open[depth++] = at;
                } else if (c == ')' || c == ']' || c == '}') {
                    char want = c == ')' ? '(' : c == ']' ? '[' : '{';
                    if (depth == 0 || s.charAt(start[open[depth - 1]]) != want) {
                        if (!strict) continue;
                        throw CgShaderError.at(text, from, "'" + c + "' closes nothing");
                    }
                    int o = open[--depth];
                    match[o] = at;
                    match[at] = o;
                }
            }
        }
        if (depth > 0 && strict) {
            int o = open[depth - 1];
            throw CgShaderError.at(text, start[o], "'" + s.charAt(start[o]) + "' is never closed");
        }
        if (wide != null) {
            LOGGER.warn("[{}] Non-ASCII character(s) in a shader file: invisible unicode silently breaks GLSL: {}",
                    text.path(), wide);
        }
    }

    private void add(byte k, int from, int to) {
        if (count == kind.length) {
            int grown = count * 2;
            kind = Arrays.copyOf(kind, grown);
            start = Arrays.copyOf(start, grown);
            end = Arrays.copyOf(end, grown);
            match = Arrays.copyOf(match, grown);
        }
        kind[count] = k;
        start[count] = from;
        end[count] = to;
        match[count] = -1;
        count++;
    }
}
