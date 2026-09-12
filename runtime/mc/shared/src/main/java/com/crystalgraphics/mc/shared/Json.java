package com.crystalgraphics.mc.shared;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON to read {@code variants.json}, which this build writes itself.
 *
 * <p>A parser rather than a dependency: every loader ships <i>a</i> Gson, but which one is the
 * loader's choice and this module may name neither a loader nor Minecraft. The file is ours and its
 * shape is fixed, so a small reader that fails with a position beats a version negotiation across
 * four loaders.</p>
 *
 * <p>Objects become {@link LinkedHashMap} in file order, arrays {@link ArrayList}, numbers
 * {@link Double}. Package-private: it exists for {@link Variants} and is not a general utility.</p>
 */
final class Json {

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json json = new Json(text);
        json.skipWhitespace();
        Object value = json.value();
        json.skipWhitespace();
        if (json.at < json.text.length()) {
            throw json.error("trailing content after the top-level value");
        }
        return value;
    }

    private Object value() {
        if (at >= text.length()) {
            throw error("a value was expected");
        }
        char c = text.charAt(at);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            at++;
            return out;
        }
        while (true) {
            skipWhitespace();
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            out.put(key, value());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw error("expected , or } in an object");
            }
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<Object>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            at++;
            return out;
        }
        while (true) {
            skipWhitespace();
            out.add(value());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw error("expected , or ] in an array");
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escape = next();
            switch (escape) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (at + 4 > text.length()) {
                        throw error("a truncated \\u escape");
                    }
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                    break;
                default: throw error("an unknown escape \\" + escape);
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-.eE0123456789".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        if (start == at) {
            throw error("a value was expected");
        }
        try {
            return Double.valueOf(text.substring(start, at));
        } catch (NumberFormatException e) {
            throw error("not a number: " + text.substring(start, at));
        }
    }

    private Object literal(String word, Object result) {
        if (!text.startsWith(word, at)) {
            throw error("expected " + word);
        }
        at += word.length();
        return result;
    }

    private void skipWhitespace() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private char peek() {
        if (at >= text.length()) {
            throw error("the file ends early");
        }
        return text.charAt(at);
    }

    private char next() {
        char c = peek();
        at++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            at--;
            throw error("expected " + c);
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("variants.json is malformed at character " + at + ": " + what);
    }
}
