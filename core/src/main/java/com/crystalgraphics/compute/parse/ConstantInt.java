package com.crystalgraphics.compute.parse;

import java.util.Map;

/**
 * An integer constant expression, as an array size is written: literals (with a {@code u} suffix or in hex),
 * {@code + - * / %}, parentheses, and names with a known value. Anything else reads as null.
 *
 * <pre>{@code
 * ConstantInt.eval("CG_GROUP_SIZE * 2", Map.of("CG_GROUP_SIZE", "64"))   // 128
 * }</pre>
 */
final class ConstantInt {

    private final String text;
    private final Map<String, String> names;
    private final int depth;
    private int at;

    private ConstantInt(String text, Map<String, String> names, int depth) {
        this.text = text;
        this.names = names;
        this.depth = depth;
    }

    /**
     * @param names a name's own expression: the compiler's {@code CG_GROUP_SIZE}, the file's object-like
     *              {@code #define}s
     */
    static Integer eval(String expression, Map<String, String> names) {
        return eval(expression, names, 0);
    }

    private static Integer eval(String expression, Map<String, String> names, int depth) {
        if (depth > 16) return null;
        ConstantInt p = new ConstantInt(expression, names, depth);
        try {
            long v = p.sum();
            p.skipSpace();
            return p.at == p.text.length() && v == (int) v ? (int) v : null;
        } catch (IllegalStateException | ArithmeticException | NumberFormatException unreadable) {
            return null;
        }
    }

    private long sum() {
        long v = product();
        for (skipSpace(); at < text.length(); skipSpace()) {
            char c = text.charAt(at);
            if (c == '+') { at++; v += product(); }
            else if (c == '-') { at++; v -= product(); }
            else break;
        }
        return v;
    }

    private long product() {
        long v = unary();
        for (skipSpace(); at < text.length(); skipSpace()) {
            char c = text.charAt(at);
            if (c == '*') { at++; v *= unary(); }
            else if (c == '/') { at++; v /= unary(); }
            else if (c == '%') { at++; v %= unary(); }
            else break;
        }
        return v;
    }

    private long unary() {
        skipSpace();
        if (at >= text.length()) throw new IllegalStateException();
        char c = text.charAt(at);
        if (c == '-') { at++; return -unary(); }
        if (c == '+') { at++; return unary(); }
        if (c == '(') {
            at++;
            long v = sum();
            skipSpace();
            if (at >= text.length() || text.charAt(at) != ')') throw new IllegalStateException();
            at++;
            return v;
        }
        int start = at;
        if (Character.isDigit(c)) {
            while (at < text.length() && Character.isLetterOrDigit(text.charAt(at))) at++;
            String literal = text.substring(start, at).toLowerCase();
            if (literal.endsWith("u")) literal = literal.substring(0, literal.length() - 1);
            return literal.startsWith("0x") ? Long.parseLong(literal.substring(2), 16) : Long.parseLong(literal);
        }
        if (Character.isLetter(c) || c == '_') {
            while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) at++;
            String value = names.get(text.substring(start, at));
            Integer v = value == null ? null : eval(value, names, depth + 1);
            if (v == null) throw new IllegalStateException();
            return v;
        }
        throw new IllegalStateException();
    }

    private void skipSpace() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    }
}
