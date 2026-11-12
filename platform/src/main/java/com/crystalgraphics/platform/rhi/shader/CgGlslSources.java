package com.crystalgraphics.platform.rhi.shader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The source edits every {@link CgGlslCompiler} makes before compiling: defines after {@code #version}.
 *
 * <pre>{@code
 * String vertex = CgGlslSources.withDefines(glsl, "stone", "#define CG_EXPLICIT 1\n");
 * }</pre>
 *
 * <ul>
 *   <li>A source with no {@code #version} is refused naming {@code label}.</li>
 *   <li>Errors keep the source's line numbers: {@code withDefines} adds a {@code #line} after what it inserts.</li>
 * </ul>
 */
public final class CgGlslSources {

    private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version\\b.*$");


    private CgGlslSources() {
    }

    /** {@code defines}, each a line ending in a newline, after {@code #version}; then a {@code #line}. */
    public static String withDefines(String source, String label, String defines) {
        Matcher m = VERSION.matcher(source);
        if (!m.find()) throw new CgShaderModule.CompileException(label + ": no #version");
        int line = 2 + lines(source, m.end());
        return source.substring(0, m.end()) + "\n" + defines + "#line " + line + source.substring(m.end());
    }


    /** Comments as spaces, newlines kept, so a match's offsets and lines are the source's. */
    public static String withoutComments(String s) {
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length; i++) {
            if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '/') {
                while (i < c.length && c[i] != '\n') c[i++] = ' ';
            } else if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '*') {
                while (i < c.length && !(c[i] == '*' && i + 1 < c.length && c[i + 1] == '/')) {
                    if (c[i] != '\n') c[i] = ' ';
                    i++;
                }
                if (i < c.length) c[i] = ' ';
                if (i + 1 < c.length) c[++i] = ' ';
            }
        }
        return new String(c);
    }

    /** {@code source} with every character above 127 a space: a GL driver may refuse one even in a comment. */
    public static String ascii(String source) {
        int n = source.length(), i = 0;
        while (i < n && source.charAt(i) < 128) i++;
        if (i == n) return source;
        char[] chars = source.toCharArray();
        for (int k = i; k < n; k++) if (chars[k] >= 128) chars[k] = ' ';
        return new String(chars);
    }

    /** The line {@code at} is on, from 1. */
    public static int lineAt(String s, int at) {
        return 1 + lines(s, at);
    }

    private static int lines(String s, int end) {
        int n = 0;
        for (int i = 0; i < end; i++) if (s.charAt(i) == '\n') n++;
        return n;
    }
}
