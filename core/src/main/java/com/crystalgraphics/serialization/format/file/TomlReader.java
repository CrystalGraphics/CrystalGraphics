package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.CgCodecException;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * TOML 1.0 text as a plain tree: a table a {@code LinkedHashMap}, an array an {@code ArrayList}, an integer an
 * {@code Integer} where it fits and a {@code Long} otherwise, a float a {@code Double}, a date or time its text. Keeps
 * the comments by key path when asked, for {@link TomlWriter} to carry into the file that replaces this one.
 *
 * <p>Ported from Jackson's TOML module (jackson-dataformats-text 2.19, Apache-2.0, © FasterXML): the table rules of
 * {@code Parser.java}, and the tokens of {@code toml.jflex} as one hand-written scanner.</p>
 */
final class TomlReader {

    /**
     * A file's comments by {@link #path}: the lines above a key or table header, the one after it, the file's last.
     * Its header, a block above the first key with a blank line after it, is above {@code ""}.
     */
    static final class Comments {
        final Map<String, List<String>> above = new HashMap<>();
        final Map<String, String> after = new HashMap<>();
        final List<String> end = new ArrayList<>();
    }

    private static final String DEC_INT = "[+-]?(?:[0-9]|[1-9](?:_?[0-9])+)";
    private static final String DIGITS = "[0-9](?:_?[0-9])*";
    private static final String EXP = "[eE][+-]?" + DIGITS;
    private static final Pattern INTEGER = Pattern.compile(DEC_INT + "|0x[0-9A-Fa-f](?:_?[0-9A-Fa-f])*"
            + "|0o[0-7](?:_?[0-7])*|0b[01](?:_?[01])*");
    private static final Pattern FLOAT = Pattern.compile(DEC_INT + "(?:" + EXP + "|\\." + DIGITS + "(?:" + EXP + ")?)"
            + "|[+-]?(?:inf|nan)");
    private static final String TIME = "[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?";
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern DATE_TIME = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}"
            + "(?:[Tt ]" + TIME + "(?:[Zz]|[+-][0-9]{2}:[0-9]{2})?)?|" + TIME);
    private static final Pattern LEADING_ZERO = Pattern.compile("[+-]?[0-9]+");

    private final String s;
    @Nullable
    private final Comments comments;
    private final List<String> pending = new ArrayList<>();
    /** Tables a header or a dotted key has defined: a second header for one is refused. */
    private final Set<Object> defined = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Inline tables and static arrays: nothing may be added to them afterwards. */
    private final Set<Object> closed = Collections.newSetFromMap(new IdentityHashMap<>());
    private int pos;
    private boolean started;

    private TomlReader(String text, @Nullable Comments comments) {
        this.s = text;
        this.comments = comments;
        if (text.startsWith("\uFEFF")) pos = 1;
    }

    /** {@code text} as a table. @throws CgCodecException where it is not TOML, naming the line */
    static Map<String, Object> read(String text, @Nullable Comments comments) {
        return new TomlReader(text, comments).document();
    }

    /** A key path: {@code segment} under {@code parent}, an array-of-tables element as {@code #index}. */
    @Nullable
    static String path(@Nullable String parent, String segment) {
        if (parent == null) return null;
        return parent.isEmpty() ? segment : parent + '\u001f' + segment;
    }

    private Map<String, Object> document() {
        Map<String, Object> root = new LinkedHashMap<>();
        defined.add(root);
        Map<String, Object> table = root;
        String tablePath = "";
        while (true) {
            gap(true);
            if (pos >= s.length()) break;
            started = true;
            String at;
            if (s.charAt(pos) == '[') {
                boolean array = s.startsWith("[[", pos);
                pos += array ? 2 : 1;
                ws();
                Ref ref = enter(root, "", true);
                ws();
                expect(array ? "]]" : "]");
                if (array) {
                    List<Object> list = arrayAt(ref.map, ref.key);
                    if (closed.contains(list)) fail("[[" + ref.key + "]] adds to an array that was written inline");
                    table = new LinkedHashMap<>();
                    list.add(table);
                    tablePath = path(path(ref.path, ref.key), "#" + (list.size() - 1));
                } else {
                    table = tableAt(ref.map, ref.key);
                    if (!defined.add(table)) fail("table [" + ref.key + "] is defined twice");
                    tablePath = path(ref.path, ref.key);
                }
                at = tablePath;
                above(at);
            } else {
                at = keyVal(table, tablePath);
            }
            endOfLine(at);
        }
        if (comments != null) comments.end.addAll(pending);
        return root;
    }

    /** {@code key = value} into {@code table}; answers the key's path. */
    @Nullable
    private String keyVal(Map<String, Object> table, @Nullable String tablePath) {
        Ref ref = enter(table, tablePath, false);
        String at = path(ref.path, ref.key);
        if (at != null) above(at);
        expect("=");
        ws();
        Object value = value();
        if (ref.map.containsKey(ref.key)) fail("key " + ref.key + " is set twice");
        ref.map.put(ref.key, value);
        return at;
    }

    /** Walks a dotted key from {@code outer}, making its tables; answers the last part and the table it goes in. */
    @SuppressWarnings("unchecked")
    private Ref enter(Map<String, Object> outer, @Nullable String outerPath, boolean forTable) {
        Map<String, Object> node = outer;
        String at = outerPath;
        while (true) {
            if (closed.contains(node)) fail("an inline table cannot be added to");
            // A dotted key defines each table on its way, provided it was not already.
            if (!forTable) defined.add(node);
            String part = simpleKey();
            ws();
            if (pos >= s.length() || s.charAt(pos) != '.') return new Ref(node, part, at);
            pos++;
            ws();
            Object existing = node.get(part);
            if (existing == null) {
                Map<String, Object> made = new LinkedHashMap<>();
                node.put(part, made);
                node = made;
                at = path(at, part);
            } else if (existing instanceof Map) {
                node = (Map<String, Object>) existing;
                at = path(at, part);
            } else if (existing instanceof List && !closed.contains(existing)) {
                // An array of tables: a reference goes to its last element.
                List<Object> list = (List<Object>) existing;
                node = (Map<String, Object>) list.get(list.size() - 1);
                at = path(path(at, part), "#" + (list.size() - 1));
            } else {
                fail("key " + part + " already holds a value, not a table");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tableAt(Map<String, Object> in, String key) {
        Object existing = in.get(key);
        if (existing == null) {
            Map<String, Object> made = new LinkedHashMap<>();
            in.put(key, made);
            return made;
        }
        if (!(existing instanceof Map)) fail("key " + key + " already holds a value, not a table");
        return (Map<String, Object>) existing;
    }

    @SuppressWarnings("unchecked")
    private List<Object> arrayAt(Map<String, Object> in, String key) {
        Object existing = in.get(key);
        if (existing == null) {
            List<Object> made = new ArrayList<>();
            in.put(key, made);
            return made;
        }
        if (!(existing instanceof List)) fail("key " + key + " already holds a value, not an array of tables");
        return (List<Object>) existing;
    }

    private String simpleKey() {
        char c = peek();
        if (c == '"') {
            if (s.startsWith("\"\"\"", pos)) fail("a key cannot be a multi-line string");
            return basicString();
        }
        if (c == '\'') {
            if (s.startsWith("'''", pos)) fail("a key cannot be a multi-line string");
            return literalString();
        }
        int start = pos;
        while (pos < s.length() && bare(s.charAt(pos))) pos++;
        if (pos == start) fail("expected a key");
        return s.substring(start, pos);
    }

    private Object value() {
        char c = peek();
        switch (c) {
            case '"':
                return s.startsWith("\"\"\"", pos) ? multiLineBasic() : basicString();
            case '\'':
                return s.startsWith("'''", pos) ? multiLineLiteral() : literalString();
            case '[':
                return array();
            case '{':
                return inlineTable();
            case 't':
                if (s.startsWith("true", pos)) {
                    pos += 4;
                    return Boolean.TRUE;
                }
                break;
            case 'f':
                if (s.startsWith("false", pos)) {
                    pos += 5;
                    return Boolean.FALSE;
                }
                break;
            default:
                break;
        }
        return scalar();
    }

    /** A number, or a date or time as its text. */
    private Object scalar() {
        int start = pos;
        token();
        // A date and its time may be split by one space instead of a T.
        if (DATE.matcher(s.substring(start, pos)).matches() && pos + 1 < s.length() && s.charAt(pos) == ' '
                && isDigit(s.charAt(pos + 1))) {
            pos++;
            token();
        }
        String text = s.substring(start, pos);
        if (text.isEmpty()) fail("expected a value");
        if (DATE_TIME.matcher(text).matches()) return text;
        if (FLOAT.matcher(text).matches()) {
            if (text.endsWith("nan")) return Double.NaN;
            if (text.endsWith("inf")) return text.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            return Double.parseDouble(text.replace("_", ""));
        }
        if (INTEGER.matcher(text).matches()) return integer(text);
        if (LEADING_ZERO.matcher(text).matches()) fail("an integer cannot start with 0; an octal one starts 0o");
        fail("not a value: " + text);
        return null;
    }

    private void token() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (!bare(c) && c != '+' && c != '.' && c != ':') break;
            pos++;
        }
    }

    private Object integer(String text) {
        String digits = text.replace("_", "");
        long value = 0;
        try {
            if (digits.startsWith("0x")) value = Long.parseLong(digits.substring(2), 16);
            else if (digits.startsWith("0o")) value = Long.parseLong(digits.substring(2), 8);
            else if (digits.startsWith("0b")) value = Long.parseLong(digits.substring(2), 2);
            else value = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            fail("integer out of the 64-bit range: " + text);
        }
        return value == (int) value ? (Object) (int) value : (Object) value;
    }

    private String basicString() {
        pos++;
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = peek();
            if (c == '"') {
                pos++;
                return out.toString();
            }
            if (c == '\\') escape(out);
            else if (c == '\n' || c == '\r') fail("a \"string\" ends on its line; \"\"\" starts one that does not");
            else {
                plain(c);
                out.append(c);
                pos++;
            }
        }
    }

    private String multiLineBasic() {
        pos += 3;
        newlineRightAfterDelimiter();
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = peek();
            if (c == '"') {
                if (closes('"', out)) return out.toString();
            } else if (c == '\\') {
                int j = pos + 1;
                while (j < s.length() && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) j++;
                if (j < s.length() && (s.charAt(j) == '\n' || s.startsWith("\r\n", j))) {
                    // A backslash ending a line trims it and the whitespace that follows.
                    pos = j;
                    while (pos < s.length() && (s.charAt(pos) == ' ' || s.charAt(pos) == '\t' || s.charAt(pos) == '\n'
                            || s.startsWith("\r\n", pos))) pos++;
                } else {
                    escape(out);
                }
            } else if (!newline(out)) {
                plain(c);
                out.append(c);
                pos++;
            }
        }
    }

    private String literalString() {
        int start = ++pos;
        while (true) {
            char c = peek();
            if (c == '\'') return s.substring(start, pos++);
            if (c == '\n' || c == '\r') fail("a 'string' ends on its line; ''' starts one that does not");
            plain(c);
            pos++;
        }
    }

    private String multiLineLiteral() {
        pos += 3;
        newlineRightAfterDelimiter();
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = peek();
            if (c == '\'') {
                if (closes('\'', out)) return out.toString();
            } else if (!newline(out)) {
                plain(c);
                out.append(c);
                pos++;
            }
        }
    }

    private void newlineRightAfterDelimiter() {
        if (s.startsWith("\n", pos)) pos++;
        else if (s.startsWith("\r\n", pos)) pos += 2;
    }

    /** A run of {@code quote}s inside a multi-line string: three or more close it, the extra one or two are its text. */
    private boolean closes(char quote, StringBuilder out) {
        int run = 0;
        while (pos + run < s.length() && s.charAt(pos + run) == quote) run++;
        if (run > 5) fail("too many quotes in a row");
        int text = run >= 3 ? run - 3 : run;
        for (int i = 0; i < text; i++) out.append(quote);
        pos += run;
        return run >= 3;
    }

    private boolean newline(StringBuilder out) {
        if (s.charAt(pos) == '\n') pos++;
        else if (s.startsWith("\r\n", pos)) pos += 2;
        else return false;
        out.append('\n');
        return true;
    }

    private void escape(StringBuilder out) {
        pos++;
        char e = peek();
        pos++;
        switch (e) {
            case '"':
                out.append('"');
                return;
            case '\\':
                out.append('\\');
                return;
            case 'b':
                out.append('\b');
                return;
            case 'f':
                out.append('\f');
                return;
            case 'n':
                out.append('\n');
                return;
            case 'r':
                out.append('\r');
                return;
            case 't':
                out.append('\t');
                return;
            case 'u':
                out.appendCodePoint(hex(4));
                return;
            case 'U':
                out.appendCodePoint(hex(8));
                return;
            default:
                fail("unknown escape \\" + e);
        }
    }

    private int hex(int digits) {
        if (pos + digits > s.length()) fail("a \\u escape needs " + digits + " hex digits");
        int value = 0;
        for (int i = 0; i < digits; i++) {
            int d = Character.digit(s.charAt(pos + i), 16);
            if (d < 0) fail("a \\u escape needs " + digits + " hex digits");
            value = value << 4 | d;
        }
        pos += digits;
        if (!Character.isValidCodePoint(value) || (value >= 0xD800 && value <= 0xDFFF)) {
            fail("\\u" + Integer.toHexString(value) + " is not a character");
        }
        return value;
    }

    private List<Object> array() {
        pos++;
        List<Object> list = new ArrayList<>();
        while (true) {
            gap(false);
            if (peek() == ']') {
                pos++;
                break;
            }
            list.add(value());
            gap(false);
            char c = peek();
            pos++;
            if (c == ']') break;
            if (c != ',') fail("expected , or ] in an array");
        }
        closed.add(list);
        return list;
    }

    private Map<String, Object> inlineTable() {
        pos++;
        ws();
        Map<String, Object> table = new LinkedHashMap<>();
        if (peek() == '}') {
            pos++;
        } else {
            while (true) {
                keyVal(table, null);
                ws();
                char c = peek();
                pos++;
                if (c == '}') break;
                if (c != ',') fail("expected , or } in an inline table, which ends on its line");
                ws();
                if (peek() == '}') fail("an inline table cannot end with a comma");
            }
        }
        closed.add(table);
        defined.add(table);
        return table;
    }

    /** Whitespace, comments and line breaks; {@code keep} files the comments for the next key. */
    private void gap(boolean keep) {
        boolean emptyLine = true;
        while (true) {
            ws();
            if (pos >= s.length()) return;
            char c = s.charAt(pos);
            if (c == '#') {
                String text = comment();
                if (keep) pending.add(text);
                emptyLine = false;
            } else if (c == '\n' || s.startsWith("\r\n", pos)) {
                pos += c == '\n' ? 1 : 2;
                // Comments above the first key with a blank line after them are the file's header.
                if (keep && emptyLine && !started && !pending.isEmpty() && comments != null
                        && !comments.above.containsKey("")) {
                    comments.above.put("", new ArrayList<>(pending));
                    pending.clear();
                }
                emptyLine = true;
            } else {
                return;
            }
        }
    }

    private void endOfLine(@Nullable String at) {
        ws();
        if (pos < s.length() && s.charAt(pos) == '#') {
            String text = comment();
            if (comments != null && at != null) comments.after.put(at, text);
        }
        if (pos >= s.length()) return;
        if (s.charAt(pos) == '\n') pos++;
        else if (s.startsWith("\r\n", pos)) pos += 2;
        else fail("expected the end of the line after a value");
    }

    /** From {@code #} to the end of its line; answers the text after the {@code #}. */
    private String comment() {
        int start = ++pos;
        while (pos < s.length() && s.charAt(pos) != '\n' && !s.startsWith("\r\n", pos)) {
            plain(s.charAt(pos));
            pos++;
        }
        return s.substring(start, pos);
    }

    private void above(String at) {
        if (comments != null && !pending.isEmpty()) comments.above.put(at, new ArrayList<>(pending));
        pending.clear();
    }

    private void ws() {
        while (pos < s.length() && (s.charAt(pos) == ' ' || s.charAt(pos) == '\t')) pos++;
    }

    private void expect(String token) {
        if (!s.startsWith(token, pos)) fail("expected " + token);
        pos += token.length();
    }

    private char peek() {
        if (pos >= s.length()) fail("the file ends in the middle of a value");
        return s.charAt(pos);
    }

    /** Refuses a control character: tab is the only one text may hold unescaped. */
    private void plain(char c) {
        if ((c < 0x20 && c != '\t') || c == 0x7F) fail("control character U+" + String.format("%04X", (int) c));
    }

    private static boolean bare(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || isDigit(c) || c == '-' || c == '_';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void fail(String what) {
        int line = 1;
        for (int i = 0; i < Math.min(pos, s.length()); i++) if (s.charAt(i) == '\n') line++;
        throw new CgCodecException("Not TOML, line " + line + ": " + what);
    }

    private record Ref(Map<String, Object> map, String key, @Nullable String path) {
    }
}
