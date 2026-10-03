package com.crystalgraphics.api.shader;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text one stage compiles from expanded GLSL: the stage half of what {@link CgShaderPreprocessor} leaves to the
 * driver, for checks that read generated source with no driver, such as the stage-purity tests and the compile audit.
 * Only the stage macros resolve, in {@code #ifdef}, {@code #ifndef} and {@code #if} over {@code defined}, {@code !},
 * {@code &&}, {@code ||} and parentheses; every other conditional keeps both branches, so an inactive keyword cannot
 * hide a builtin the stage lacks.
 *
 * <pre>{@code
 * String expanded = new CgShaderPreprocessor().process(compiled.vertexSource(), path);
 * String vertex = CgShaderStages.reachable(expanded, CgShaderStages.Stage.VERTEX);
 * }</pre>
 */
public final class CgShaderStages {

    public enum Stage {
        VERTEX("CG_VERTEX_STAGE"), FRAGMENT("CG_FRAGMENT_STAGE"), COMPUTE("CG_COMPUTE_STAGE");

        final String macro;

        Stage(String macro) {
            this.macro = macro;
        }
    }

    private static final Pattern TOKEN = Pattern.compile("\\s*(defined|[A-Za-z_]\\w*|&&|\\|\\||!|\\(|\\))");

    private CgShaderStages() {}

    public static String reachable(String src, Stage stage) {
        StringBuilder out = new StringBuilder(src.length());
        // frame[0]: resolved; frame[1]: this branch emits
        Deque<boolean[]> stack = new ArrayDeque<>();
        for (String line : src.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#ifdef ") || t.startsWith("#ifndef ")) {
                boolean negated = t.startsWith("#ifndef ");
                Boolean defined = defined(t.substring(negated ? 8 : 7).trim(), stage);
                stack.push(defined == null ? new boolean[]{false, true} : new boolean[]{true, negated != defined});
                continue;
            }
            if (t.startsWith("#if")) {
                Boolean value = evaluate(t.substring(3), stage);
                stack.push(value == null ? new boolean[]{false, true} : new boolean[]{true, value});
                continue;
            }
            if (t.startsWith("#elif")) {
                if (!stack.isEmpty()) {
                    stack.peek()[0] = false;
                    stack.peek()[1] = true;
                }
                continue;
            }
            if (t.equals("#else")) {
                if (!stack.isEmpty()) {
                    boolean[] f = stack.peek();
                    f[1] = !f[0] || !f[1];
                }
                continue;
            }
            if (t.startsWith("#endif")) {
                if (!stack.isEmpty()) stack.pop();
                continue;
            }
            boolean emitting = true;
            for (boolean[] f : stack) {
                if (!f[1]) {
                    emitting = false;
                    break;
                }
            }
            if (emitting) out.append(line).append('\n');
        }
        return out.toString();
    }

    /** Whether a stage macro is defined in {@code stage}; null for any other name. */
    private static Boolean defined(String name, Stage stage) {
        for (Stage s : Stage.values()) if (s.macro.equals(name)) return s == stage;
        return null;
    }

    /** An {@code #if} over stage macros only, or null. */
    private static Boolean evaluate(String expression, Stage stage) {
        Deque<String> tokens = new ArrayDeque<>();
        Matcher m = TOKEN.matcher(expression);
        int at = 0;
        while (at < expression.length() && m.find(at) && m.start() == at) {
            tokens.add(m.group(1));
            at = m.end();
        }
        if (!expression.substring(at).isBlank()) return null;
        try {
            Boolean v = or(tokens, stage);
            return tokens.isEmpty() ? v : null;
        } catch (IllegalStateException unresolved) {
            return null;
        }
    }

    private static Boolean or(Deque<String> t, Stage stage) {
        boolean v = and(t, stage);
        while ("||".equals(t.peek())) {
            t.poll();
            v |= and(t, stage);
        }
        return v;
    }

    private static boolean and(Deque<String> t, Stage stage) {
        boolean v = unary(t, stage);
        while ("&&".equals(t.peek())) {
            t.poll();
            v &= unary(t, stage);
        }
        return v;
    }

    private static boolean unary(Deque<String> t, Stage stage) {
        String token = t.poll();
        if (token == null) throw new IllegalStateException();
        if (token.equals("!")) return !unary(t, stage);
        if (token.equals("(")) {
            boolean v = or(t, stage);
            if (!")".equals(t.poll())) throw new IllegalStateException();
            return v;
        }
        if (token.equals("defined")) {
            boolean paren = "(".equals(t.peek());
            if (paren) t.poll();
            Boolean v = defined(t.poll(), stage);
            if (paren && !")".equals(t.poll())) throw new IllegalStateException();
            if (v == null) throw new IllegalStateException();
            return v;
        }
        throw new IllegalStateException();
    }
}
