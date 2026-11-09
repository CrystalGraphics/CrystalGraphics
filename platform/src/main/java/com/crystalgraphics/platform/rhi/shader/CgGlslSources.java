package com.crystalgraphics.platform.rhi.shader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The source edits every {@link CgGlslCompiler} makes before compiling: defines after {@code #version}, and the vertex
 * stage's GL-depth form.
 *
 * <pre>{@code
 * String vertex = CgGlslSources.withDefines(glsl, "stone", "#define CG_EXPLICIT 1\n");
 * String glDepth = CgGlslSources.wrapMain(vertex, "stone");   // clip z from [-w, w] to [0, w]
 * }</pre>
 *
 * <ul>
 *   <li>A source with no {@code #version}, or a vertex stage with no {@code main()}, is refused naming {@code label}.</li>
 *   <li>Errors keep the source's line numbers: {@code withDefines} adds a {@code #line} after what it inserts.</li>
 * </ul>
 */
public final class CgGlslSources {

    private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version\\b.*$");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");

    private CgGlslSources() {
    }

    /** {@code defines}, each a line ending in a newline, after {@code #version}; then a {@code #line}. */
    public static String withDefines(String source, String label, String defines) {
        Matcher m = VERSION.matcher(source);
        if (!m.find()) throw new CgShaderModule.CompileException(label + ": no #version");
        int line = 2 + lines(source, m.end());
        return source.substring(0, m.end()) + "\n" + defines + "#line " + line + source.substring(m.end());
    }

    /** {@code main} renamed, and a {@code main} after it that calls it and maps clip z from [-w, w] to [0, w]. */
    public static String wrapMain(String source, String label) {
        Matcher m = MAIN.matcher(withoutComments(source));
        if (!m.find()) throw new CgShaderModule.CompileException(label + ": the vertex stage has no main()");
        return source.substring(0, m.start()) + "void cg_main_user()" + source.substring(m.end())
                + "\nvoid main() {\n    cg_main_user();\n    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}\n";
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
