package com.crystalgraphics.platform.gl.tracked.glsl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a GLSL source's {@code #if} family as a GL driver would, from the {@code #define}s in the text: the
 * inactive lines are blanked, so line numbers hold and only what the GL program declares is rewritten. Every
 * other directive, {@code #define} included, stays for the compiler.
 */
final class GlslConditionals {

    private static final String FUNCTION_LIKE = "\0fn";

    private final Map<String, String> defines = new HashMap<>();

    private GlslConditionals(int version) {
        defines.put("__VERSION__", Integer.toString(version));
        defines.put("GL_core_profile", "1");
    }

    /** {@code source} with its inactive branches blanked. */
    static String evaluate(String source, int version) {
        return new GlslConditionals(version).run(source);
    }

    private String run(String source) {
        String[] lines = source.split("\n", -1);
        StringBuilder out = new StringBuilder(source.length());
        Deque<boolean[]> stack = new ArrayDeque<>();   // {active, anyTaken, parentActive}
        boolean active = true;
        for (int i = 0; i < lines.length; i++) {
            int first = i;
            String line = lines[i];
            while (line.endsWith("\\") && i + 1 < lines.length) {
                line = line.substring(0, line.length() - 1) + lines[++i];
            }
            String t = line.trim();
            String directive = t.startsWith("#") ? t.substring(1).trim() : null;
            String word = directive == null ? "" : directive.split("[\\s(]", 2)[0];
            String rest = directive == null ? "" : directive.substring(word.length()).trim();
            switch (word) {
                case "ifdef":
                case "ifndef":
                case "if": {
                    boolean cond = active && ("ifdef".equals(word) ? defines.containsKey(ident(rest))
                            : "ifndef".equals(word) ? !defines.containsKey(ident(rest)) : eval(rest));
                    stack.push(new boolean[] {cond, cond, active});
                    active = cond;
                    line = "";
                    break;
                }
                case "elif": {
                    boolean[] top = top(stack, t);
                    boolean cond = top[2] && !top[1] && eval(rest);
                    top[0] = cond;
                    top[1] |= cond;
                    active = cond;
                    line = "";
                    break;
                }
                case "else": {
                    boolean[] top = top(stack, t);
                    top[0] = top[2] && !top[1];
                    top[1] = true;
                    active = top[0];
                    line = "";
                    break;
                }
                case "endif": {
                    boolean[] top = top(stack, t);
                    stack.pop();
                    active = top[2];
                    line = "";
                    break;
                }
                case "define":
                    if (active) define(rest);
                    break;
                case "undef":
                    if (active) defines.remove(ident(rest));
                    break;
                default:
                    break;
            }
            out.append(active ? line : "");
            for (int k = first; k < i; k++) out.append('\n');   // the lines a continuation joined
            if (i < lines.length - 1) out.append('\n');
        }
        if (!stack.isEmpty()) throw new GlslRewriteException("#if without #endif");
        return out.toString();
    }

    private static boolean[] top(Deque<boolean[]> stack, String line) {
        if (stack.isEmpty()) throw new GlslRewriteException("'" + line + "' without #if");
        return stack.peek();
    }

    private void define(String rest) {
        int i = 0;
        while (i < rest.length() && isIdent(rest.charAt(i))) i++;
        String name = rest.substring(0, i);
        if (i < rest.length() && rest.charAt(i) == '(') defines.put(name, FUNCTION_LIKE);
        else defines.put(name, rest.substring(i).trim());
    }

    private static String ident(String s) {
        int i = 0;
        while (i < s.length() && isIdent(s.charAt(i))) i++;
        return s.substring(0, i);
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ── #if expressions: C's integer subset ────────────────────────────────────

    private boolean eval(String expr) {
        return new Expr(tokens(expr, 0)).parse() != 0;
    }

    private List<String> tokens(String s, int depth) {
        if (depth > 32) throw new GlslRewriteException("Macro expansion too deep in #if");
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (s.startsWith("//", i)) break;
            if (Character.isDigit(c)) {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)))) j++;
                out.add(s.substring(i, j).replaceAll("[uUlL]+$", ""));
                i = j;
            } else if (isIdent(c)) {
                int j = i;
                while (j < s.length() && isIdent(s.charAt(j))) j++;
                String id = s.substring(i, j);
                i = j;
                if ("defined".equals(id)) {
                    while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
                    boolean paren = i < s.length() && s.charAt(i) == '(';
                    if (paren) i++;
                    while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
                    int k = i;
                    while (k < s.length() && isIdent(s.charAt(k))) k++;
                    out.add(defines.containsKey(s.substring(i, k)) ? "1" : "0");
                    i = k;
                    if (paren) {
                        int close = s.indexOf(')', i);
                        if (close < 0) throw new GlslRewriteException("defined( without ')' in #if");
                        i = close + 1;
                    }
                } else {
                    String value = defines.get(id);
                    if (value == null || FUNCTION_LIKE.equals(value)) {
                        out.add("0");
                    } else {
                        out.add("(");
                        out.addAll(value.isEmpty() ? List.of("0") : tokens(value, depth + 1));
                        out.add(")");
                    }
                }
            } else {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=")
                        || two.equals("<=") || two.equals(">=") || two.equals("<<") || two.equals(">>")) {
                    out.add(two);
                    i += 2;
                } else {
                    out.add(String.valueOf(c));
                    i++;
                }
            }
        }
        return out;
    }

    /** Recursive descent over C's precedence, for the operators an {@code #if} line uses. */
    private static final class Expr {
        private final List<String> t;
        private int p;

        Expr(List<String> tokens) { t = tokens; }

        long parse() {
            long v = or();
            if (p != t.size()) throw new GlslRewriteException("Unexpected '" + t.get(p) + "' in #if");
            return v;
        }

        private boolean at(String s) {
            if (p < t.size() && t.get(p).equals(s)) { p++; return true; }
            return false;
        }

        private long or() { long v = and(); while (at("||")) { long r = and(); v = (v != 0 || r != 0) ? 1 : 0; } return v; }

        private long and() { long v = eq(); while (at("&&")) { long r = eq(); v = (v != 0 && r != 0) ? 1 : 0; } return v; }

        private long eq() {
            long v = rel();
            while (true) {
                if (at("==")) v = v == rel() ? 1 : 0;
                else if (at("!=")) v = v != rel() ? 1 : 0;
                else return v;
            }
        }

        private long rel() {
            long v = add();
            while (true) {
                if (at("<=")) v = v <= add() ? 1 : 0;
                else if (at(">=")) v = v >= add() ? 1 : 0;
                else if (at("<")) v = v < add() ? 1 : 0;
                else if (at(">")) v = v > add() ? 1 : 0;
                else return v;
            }
        }

        private long add() {
            long v = mul();
            while (true) {
                if (at("+")) v += mul();
                else if (at("-")) v -= mul();
                else return v;
            }
        }

        private long mul() {
            long v = unary();
            while (true) {
                if (at("*")) v *= unary();
                else if (at("/")) { long d = unary(); v = d == 0 ? 0 : v / d; }
                else if (at("%")) { long d = unary(); v = d == 0 ? 0 : v % d; }
                else return v;
            }
        }

        private long unary() {
            if (at("!")) return unary() == 0 ? 1 : 0;
            if (at("-")) return -unary();
            if (at("+")) return unary();
            if (at("(")) {
                long v = or();
                if (!at(")")) throw new GlslRewriteException("Missing ')' in #if");
                return v;
            }
            if (p >= t.size()) throw new GlslRewriteException("#if ends early");
            String n = t.get(p++);
            try {
                return n.startsWith("0x") || n.startsWith("0X") ? Long.parseLong(n.substring(2), 16) : Long.parseLong(n);
            } catch (NumberFormatException e) {
                throw new GlslRewriteException("Not a number in #if: '" + n + "'");
            }
        }
    }
}
