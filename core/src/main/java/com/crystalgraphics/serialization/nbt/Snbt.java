package com.crystalgraphics.serialization.nbt;

import com.crystalgraphics.serialization.CgCodecException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SNBT, Minecraft's text form of tags ({@code /data get}, a structure's text): compounds, lists, {@code [B;]},
 * {@code [I;]} and {@code [L;]} arrays, quoted and bare strings, numbers with {@code b s L f d} suffixes, and
 * {@code true}/{@code false} as bytes. Written from the grammar; Minecraft's {@code TagParser} is not ours to copy.
 */
final class Snbt {

    private static boolean isBareKey(String key) {
        if (key.isEmpty()) return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c == '_' || c == '-' || c == '.' || c == '+';
            if (!ok) return false;
        }
        return true;
    }

    private Snbt() {
    }

    // ── Write ───────────────────────────────────────────────────────────────

    static String write(CgNbtTag tag) {
        StringBuilder out = new StringBuilder();
        write(tag, out);
        return out.toString();
    }

    private static void write(CgNbtTag tag, StringBuilder out) {
        if (tag instanceof CgNbtCompound compound) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, CgNbtTag> e : compound.raw().entrySet()) {
                String key = e.getKey();
                if (!first) out.append(',');
                first = false;
                if (isBareKey(key)) out.append(key);
                else quote(key, out);
                out.append(':');
                write(e.getValue(), out);
            }
            out.append('}');
        } else if (tag instanceof CgNbtList list) {
            out.append('[');
            List<CgNbtTag> items = list.raw();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) out.append(',');
                write(items.get(i), out);
            }
            out.append(']');
        } else {
            writeValue((CgNbtValue) tag, out);
        }
    }

    private static void writeValue(CgNbtValue value, StringBuilder out) {
        Object raw = value.raw();
        switch (value.type()) {
            case STRING: quote((String) raw, out); break;
            case BYTE: out.append(raw).append('b'); break;
            case SHORT: out.append(raw).append('s'); break;
            case INT: out.append(raw); break;
            case LONG: out.append(raw).append('L'); break;
            case FLOAT: out.append(raw).append('f'); break;
            case DOUBLE: out.append(raw).append('d'); break;
            case BYTE_ARRAY: {
                byte[] a = (byte[]) raw;
                out.append("[B;");
                for (int i = 0; i < a.length; i++) out.append(i > 0 ? "," : "").append(a[i]).append('b');
                out.append(']');
                break;
            }
            case INT_ARRAY: {
                int[] a = (int[]) raw;
                out.append("[I;");
                for (int i = 0; i < a.length; i++) out.append(i > 0 ? "," : "").append(a[i]);
                out.append(']');
                break;
            }
            default: {
                long[] a = (long[]) raw;
                out.append("[L;");
                for (int i = 0; i < a.length; i++) out.append(i > 0 ? "," : "").append(a[i]).append('L');
                out.append(']');
            }
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') out.append('\\');
            out.append(c);
        }
        out.append('"');
    }

    // ── Read ────────────────────────────────────────────────────────────────

    static CgNbtTag parse(String snbt) {
        Reader reader = new Reader(snbt);
        CgNbtTag tag = reader.value();
        reader.skipSpace();
        if (reader.at < snbt.length()) throw reader.error("text after the tag");
        return tag;
    }

    private static final class Reader {
        private static final Pattern BYTE = Pattern.compile("[-+]?(?:0|[1-9][0-9]*)b", Pattern.CASE_INSENSITIVE);
        private static final Pattern SHORT = Pattern.compile("[-+]?(?:0|[1-9][0-9]*)s", Pattern.CASE_INSENSITIVE);
        private static final Pattern INT = Pattern.compile("[-+]?(?:0|[1-9][0-9]*)");
        private static final Pattern LONG = Pattern.compile("[-+]?(?:0|[1-9][0-9]*)l", Pattern.CASE_INSENSITIVE);
        private static final Pattern FLOAT = Pattern.compile(
                "[-+]?(?:[0-9]+[.]?|[0-9]*[.][0-9]+)(?:e[-+]?[0-9]+)?f", Pattern.CASE_INSENSITIVE);
        private static final Pattern DOUBLE = Pattern.compile(
                "[-+]?(?:[0-9]+[.]?|[0-9]*[.][0-9]+)(?:e[-+]?[0-9]+)?d", Pattern.CASE_INSENSITIVE);
        private static final Pattern DOUBLE_BARE = Pattern.compile(
                "[-+]?(?:[0-9]+[.]|[0-9]*[.][0-9]+)(?:e[-+]?[0-9]+)?", Pattern.CASE_INSENSITIVE);

        private final String text;
        int at;

        Reader(String text) {
            this.text = text;
        }

        CgNbtTag value() {
            skipSpace();
            if (at >= text.length()) throw error("a tag");
            char c = text.charAt(at);
            if (c == '{') return compound();
            if (c == '[') return list();
            if (c == '"' || c == '\'') return CgNbtValue.ofString(quoted());
            return scalar(bare());
        }

        private CgNbtCompound compound() {
            expect('{');
            CgNbtCompound out = new CgNbtCompound();
            skipSpace();
            if (peek('}')) {
                at++;
                return out;
            }
            while (true) {
                skipSpace();
                String key = peek('"') || peek('\'') ? quoted() : bare();
                if (key.isEmpty()) throw error("a key");
                skipSpace();
                expect(':');
                out.put(key, value());
                skipSpace();
                if (peek(',')) {
                    at++;
                    continue;
                }
                expect('}');
                return out;
            }
        }

        private CgNbtTag list() {
            expect('[');
            skipSpace();
            if (at + 1 < text.length() && text.charAt(at + 1) == ';' && "BIL".indexOf(text.charAt(at)) >= 0) {
                char type = text.charAt(at);
                at += 2;
                List<CgNbtTag> values = elements();
                if (type == 'B') {
                    byte[] a = new byte[values.size()];
                    for (int i = 0; i < a.length; i++) a[i] = number(values.get(i)).byteValue();
                    return CgNbtValue.ofBytes(a);
                }
                if (type == 'I') {
                    int[] a = new int[values.size()];
                    for (int i = 0; i < a.length; i++) a[i] = number(values.get(i)).intValue();
                    return CgNbtValue.ofInts(a);
                }
                long[] a = new long[values.size()];
                for (int i = 0; i < a.length; i++) a[i] = number(values.get(i)).longValue();
                return CgNbtValue.ofLongs(a);
            }
            CgNbtList out = new CgNbtList();
            for (CgNbtTag v : elements()) {
                try {
                    out.add(v);
                } catch (CgCodecException mixed) {
                    throw error("one kind of tag in a list");
                }
            }
            return out;
        }

        private List<CgNbtTag> elements() {
            List<CgNbtTag> out = new ArrayList<>();
            skipSpace();
            if (peek(']')) {
                at++;
                return out;
            }
            while (true) {
                out.add(value());
                skipSpace();
                if (peek(',')) {
                    at++;
                    continue;
                }
                expect(']');
                return out;
            }
        }

        private Number number(CgNbtTag v) {
            Number n = v instanceof CgNbtValue value ? value.number() : null;
            if (n == null) throw error("a number in a typed array");
            return n;
        }

        private String quoted() {
            char q = text.charAt(at++);
            StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '\\' && at < text.length()) out.append(text.charAt(at++));
                else if (c == q) return out.toString();
                else out.append(c);
            }
            throw error("the closing " + q);
        }

        private String bare() {
            int start = at;
            while (at < text.length() && isBare(text.charAt(at))) at++;
            return text.substring(start, at);
        }

        private CgNbtTag scalar(String word) {
            if (word.isEmpty()) throw error("a tag");
            if (word.equals("true")) return CgNbtValue.ofByte((byte) 1);
            if (word.equals("false")) return CgNbtValue.ofByte((byte) 0);
            char head = word.charAt(0);
            boolean numeric = head >= '0' && head <= '9' || head == '-' || head == '+' || head == '.';
            if (!numeric) return CgNbtValue.ofString(word);   // no number starts any other way
            try {
                if (BYTE.matcher(word).matches()) return CgNbtValue.ofByte(Byte.parseByte(trim(word)));
                if (SHORT.matcher(word).matches()) return CgNbtValue.ofShort(Short.parseShort(trim(word)));
                if (LONG.matcher(word).matches()) return CgNbtValue.ofLong(Long.parseLong(trim(word)));
                if (INT.matcher(word).matches()) return CgNbtValue.ofInt(Integer.parseInt(word.startsWith("+") ? word.substring(1) : word));
                if (FLOAT.matcher(word).matches()) return CgNbtValue.ofFloat(Float.parseFloat(trim(word)));
                if (DOUBLE.matcher(word).matches()) return CgNbtValue.ofDouble(Double.parseDouble(trim(word)));
                if (DOUBLE_BARE.matcher(word).matches()) return CgNbtValue.ofDouble(Double.parseDouble(word));
            } catch (NumberFormatException outOfRange) {
                // Minecraft reads an out-of-range number as a string; so does this.
            }
            return CgNbtValue.ofString(word);
        }

        private static String trim(String word) {
            String body = word.substring(0, word.length() - 1);
            return body.startsWith("+") ? body.substring(1) : body;
        }

        private static boolean isBare(char c) {
            return c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c == '_' || c == '-' || c == '.' || c == '+';
        }

        void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }

        private boolean peek(char c) {
            return at < text.length() && text.charAt(at) == c;
        }

        private void expect(char c) {
            if (!peek(c)) throw error("'" + c + "'");
            at++;
        }

        CgCodecException error(String expected) {
            return new CgCodecException("SNBT: expected " + expected + " at " + at + " in " + text);
        }
    }
}
