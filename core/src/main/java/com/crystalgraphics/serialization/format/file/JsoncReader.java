package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.CgCodecException;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON with comments as a plain tree: an object a {@code LinkedHashMap}, an array an {@code ArrayList}, an integer an
 * {@code Integer} where it fits and a {@code Long} otherwise, any other number a {@code Double}. Keeps the comments
 * when asked ({@link FileComments}), each whole, for {@link JsoncWriter} to carry into the file that replaces this one.
 *
 * <p>The grammar is VS Code's, from {@code microsoft/node-jsonc-parser} (MIT): JSON (RFC 8259), with {@code //} and
 * {@code /* *}{@code /} comments wherever whitespace may go and a trailing comma before {@code ]} or {@code }}.</p>
 */
final class JsoncReader {

    private final String s;
    @Nullable
    private final FileComments comments;
    private final List<String> pending = new ArrayList<>();
    private int pos;

    private JsoncReader(String text, @Nullable FileComments comments) {
        this.s = text;
        this.comments = comments;
        if (text.startsWith("\uFEFF")) pos = 1;
    }

    /** {@code text} as a value. @throws CgCodecException where it is not JSON, naming the line */
    @Nullable
    static Object read(String text, @Nullable FileComments comments) {
        return new JsoncReader(text, comments).document();
    }

    @Nullable
    private Object document() {
        gap();
        // What a person wrote above the whole value is the file's header.
        if (comments != null && !pending.isEmpty()) comments.above.put("", new ArrayList<>(pending));
        pending.clear();
        Object value = value("");
        gap();
        if (pos < s.length()) fail("more after the value ends");
        if (comments != null) comments.end.addAll(pending);
        return value;
    }

    @Nullable
    private Object value(String path) {
        char c = peek();
        switch (c) {
            case '{':
                return object(path);
            case '[':
                return array(path);
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                return number();
        }
    }

    private Map<String, Object> object(String path) {
        pos++;
        Map<String, Object> out = new LinkedHashMap<>();
        while (true) {
            gap();
            if (peek() == '}') {
                pos++;
                pending.clear();
                return out;
            }
            if (peek() != '"') fail("expected a key in quotes");
            String key = string();
            String at = FileComments.path(path, key);
            above(at);
            gap();
            expect(':');
            gap();
            pending.clear();   // a comment between a key and its value has nowhere to go
            Object value = value(at);
            if (out.containsKey(key)) fail("key \"" + key + "\" is set twice");
            out.put(key, value);
            if (!separator(at, '}')) {
                pos++;
                return out;
            }
        }
    }

    private List<Object> array(String path) {
        pos++;
        List<Object> out = new ArrayList<>();
        while (true) {
            gap();
            if (peek() == ']') {
                pos++;
                pending.clear();
                return out;
            }
            String at = FileComments.path(path, "#" + out.size());
            above(at);
            out.add(value(at));
            if (!separator(at, ']')) {
                pos++;
                return out;
            }
        }
    }

    /**
     * After a value: a comma, then perhaps a comment on the same line, which is the value's; or the closing
     * {@code close}. Answers false at {@code close}, left unread.
     */
    private boolean separator(String at, char close) {
        spaces();
        boolean comma = pos < s.length() && s.charAt(pos) == ',';
        if (comma) pos++;
        spaces();
        if (s.startsWith("//", pos) || (s.startsWith("/*", pos) && !blockSpansLines())) {
            String text = comment();
            if (comments != null) comments.after.put(at, text);
        }
        if (comma) return true;
        gap();
        if (peek() != close) fail("expected , or " + close);
        return false;
    }

    private boolean blockSpansLines() {
        int end = s.indexOf("*/", pos + 2);
        return end < 0 || s.substring(pos, end).indexOf('\n') >= 0;
    }

    private String string() {
        pos++;
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = peek();
            pos++;
            if (c == '"') return out.toString();
            if (c == '\\') {
                char e = peek();
                pos++;
                switch (e) {
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case '/':
                        out.append('/');
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        out.append(hex());
                        break;
                    default:
                        fail("unknown escape \\" + e);
                }
            } else if (c < 0x20) {
                fail("a control character in a string must be escaped");
            } else {
                out.append(c);
            }
        }
    }

    private char hex() {
        if (pos + 4 > s.length()) fail("a \\u escape needs 4 hex digits");
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int d = Character.digit(s.charAt(pos + i), 16);
            if (d < 0) fail("a \\u escape needs 4 hex digits");
            value = value << 4 | d;
        }
        pos += 4;
        return (char) value;
    }

    @Nullable
    private Object literal(String word, @Nullable Object value) {
        if (!s.startsWith(word, pos)) fail("not a value");
        pos += word.length();
        return value;
    }

    private Object number() {
        int start = pos;
        if (pos < s.length() && s.charAt(pos) == '-') pos++;
        if (pos < s.length() && s.charAt(pos) == '0') {
            pos++;
        } else if (!digits()) {
            fail("not a value");
        }
        boolean whole = true;
        if (pos < s.length() && s.charAt(pos) == '.') {
            pos++;
            whole = false;
            if (!digits()) fail("a number needs digits after its point");
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            pos++;
            whole = false;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
            if (!digits()) fail("a number needs digits in its exponent");
        }
        String text = s.substring(start, pos);
        if (whole) {
            try {
                long value = Long.parseLong(text);
                return value == (int) value ? (Object) (int) value : (Object) value;
            } catch (NumberFormatException tooLong) {
                // past 64 bits: read as a double, as JSON readers do
            }
        }
        return Double.parseDouble(text);
    }

    private boolean digits() {
        int start = pos;
        while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
        return pos > start;
    }

    /** Whitespace and comments; the comments wait for the next key. */
    private void gap() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else if (s.startsWith("//", pos) || s.startsWith("/*", pos)) pending.add(comment());
            else return;
        }
    }

    private void spaces() {
        while (pos < s.length() && (s.charAt(pos) == ' ' || s.charAt(pos) == '\t')) pos++;
    }

    /** A comment, whole: {@code // text} to its line's end, or {@code /* text *}{@code /}. */
    private String comment() {
        int start = pos;
        if (s.startsWith("//", pos)) {
            while (pos < s.length() && s.charAt(pos) != '\n' && s.charAt(pos) != '\r') pos++;
        } else {
            int end = s.indexOf("*/", pos + 2);
            if (end < 0) fail("a /* comment is not closed");
            pos = end + 2;
        }
        return s.substring(start, pos);
    }

    private void above(@Nullable String at) {
        if (comments != null && at != null && !pending.isEmpty()) comments.above.put(at, new ArrayList<>(pending));
        pending.clear();
    }

    private void expect(char c) {
        if (peek() != c) fail("expected " + c);
        pos++;
    }

    private char peek() {
        if (pos >= s.length()) fail("the file ends in the middle of a value");
        return s.charAt(pos);
    }

    private void fail(String what) {
        int line = 1;
        for (int i = 0; i < Math.min(pos, s.length()); i++) if (s.charAt(i) == '\n') line++;
        throw new CgCodecException("Not JSON, line " + line + ": " + what);
    }
}
