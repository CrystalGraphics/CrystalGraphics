package com.crystalgraphics.serialization.format.nbt;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.format.CgFormats;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Minecraft's tags in a unit test: installed into the {@code crystalgraphics:host_formats} slot, so
 * {@link CgFormats#toNbt} and {@link CgFormats#fromNbt} run with no game, under the same rules the game's tags do
 * (a boolean is a byte, a list holds one kind of tag, keys are text).
 *
 * <pre>{@code
 * private CgTestTags tags;
 * @Before public void tags() { tags = CgTestTags.install(); }
 * @After public void untag() { tags.close(); }
 *
 * @Test public void aCharacterSavesAsNbt() {
 *     Object tag = CgFormats.toNbt(Character.CODEC, character);
 *     assertEquals("{name:\"Goku\",level:9}", CgTestTags.toSnbt(tag));
 *     assertEquals(9, CgFormats.fromNbt(Character.CODEC, tag).level);
 * }
 * }</pre>
 *
 * A preset kept as text, the way {@code /data get} prints it:
 *
 * <pre>{@code
 * Object preset = CgTestTags.parse("{style:3b,dyed:1b,colors:[I;16711680,255]}");
 * Hair hair = CgFormats.fromNbt(Hair.CODEC, preset);   // dyed reads as true
 * }</pre>
 *
 * <ul>
 *   <li>Tags are plain Java values: a compound is a {@link Compound}, a list a {@link TagList}, a string a
 *       {@code String}, a number its own boxed width, a byte array a {@code byte[]}.</li>
 *   <li>{@link #close} puts back what the slot held, so tests in one JVM do not share tags.</li>
 *   <li>{@code [I;...]} and {@code [L;...]} parse to lists of ints and longs; codecs never write those arrays.</li>
 * </ul>
 */
public final class CgTestTags implements CgHostFormats, AutoCloseable {

    /** A compound tag: keys in the order they were put. */
    public static final class Compound extends LinkedHashMap<String, Object> {
    }

    /** A list tag: every element one kind, as Minecraft's are. */
    public static final class TagList extends ArrayList<Object> {
    }

    @Nullable
    private final CgHostFormats previous;

    private CgTestTags(@Nullable CgHostFormats previous) {
        this.previous = previous;
    }

    /** Installs test tags until {@link #close}. */
    public static CgTestTags install() {
        CgTestTags tags = new CgTestTags(CgPlatform.isProvided(SERVICE) ? CgPlatform.get(SERVICE) : null);
        CgPlatform.provide(SERVICE, tags);
        return tags;
    }

    @Override
    public void close() {
        CgPlatform.provide(SERVICE, previous);
    }

    @Override
    public boolean present() {
        return true;
    }

    @Override
    public Object compound() {
        return new Compound();
    }

    @Override
    public void put(Object compound, String key, Object value) {
        ((Compound) compound).put(key, value);
    }

    @Override
    @Nullable
    public Collection<String> keys(Object tag) {
        return tag instanceof Compound ? ((Compound) tag).keySet() : null;
    }

    @Override
    @Nullable
    public Object get(Object compound, String key) {
        return ((Compound) compound).get(key);
    }

    @Override
    public Object list() {
        return new TagList();
    }

    @Override
    public void add(Object list, Object value) {
        TagList tags = (TagList) list;
        if (!tags.isEmpty() && kind(tags.get(0)) != kind(value)) {
            throw new CgCodecException("a list tag holds one kind of tag: " + kind(tags.get(0)).getSimpleName()
                    + ", not " + kind(value).getSimpleName());
        }
        tags.add(value);
    }

    @Override
    @Nullable
    public List<?> elements(Object tag) {
        return tag instanceof TagList ? (TagList) tag : null;
    }

    @Override
    public Object string(String value) {
        return value;
    }

    @Override
    @Nullable
    public String stringOf(Object tag) {
        return tag instanceof String ? (String) tag : null;
    }

    @Override
    public Object number(Number value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float) {
            return value;
        }
        return value.doubleValue();
    }

    @Override
    @Nullable
    public Number numberOf(Object tag) {
        return tag instanceof Number ? (Number) tag : null;
    }

    @Override
    public Object bytes(byte[] value) {
        return value.clone();
    }

    @Override
    @Nullable
    public byte[] bytesOf(Object tag) {
        return tag instanceof byte[] ? (byte[]) tag : null;
    }

    private static Class<?> kind(Object tag) {
        return tag.getClass();
    }

    // ── SNBT ────────────────────────────────────────────────────────────────

    /**
     * A tag from SNBT, Minecraft's text form of tags ({@code /data get}, a structure file's text): compounds, lists,
     * {@code [B;...]} arrays, quoted and bare strings, numbers with {@code b s L f d} suffixes, {@code true} and
     * {@code false} as bytes.
     *
     * @throws CgCodecException where it is not SNBT, naming the position
     */
    public static Object parse(String snbt) {
        Reader reader = new Reader(snbt);
        Object tag = reader.value();
        reader.skipSpace();
        if (reader.at < snbt.length()) throw reader.error("text after the tag");
        return tag;
    }

    /** {@code tag} as SNBT: strings quoted, numbers with their suffix, a compound in its own order. */
    public static String toSnbt(Object tag) {
        StringBuilder out = new StringBuilder();
        write(tag, out);
        return out.toString();
    }

    private static final Pattern BARE = Pattern.compile("[A-Za-z0-9_\\-.+]+");

    private static void write(Object tag, StringBuilder out) {
        if (tag instanceof Compound) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Compound) tag).entrySet()) {
                if (!first) out.append(',');
                first = false;
                if (BARE.matcher(e.getKey()).matches()) out.append(e.getKey());
                else quote(e.getKey(), out);
                out.append(':');
                write(e.getValue(), out);
            }
            out.append('}');
        } else if (tag instanceof TagList) {
            out.append('[');
            List<Object> list = (TagList) tag;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                write(list.get(i), out);
            }
            out.append(']');
        } else if (tag instanceof byte[]) {
            out.append("[B;");
            byte[] bytes = (byte[]) tag;
            for (int i = 0; i < bytes.length; i++) out.append(i > 0 ? "," : "").append(bytes[i]).append('b');
            out.append(']');
        } else if (tag instanceof String) {
            quote((String) tag, out);
        } else if (tag instanceof Byte) {
            out.append(tag).append('b');
        } else if (tag instanceof Short) {
            out.append(tag).append('s');
        } else if (tag instanceof Integer) {
            out.append(tag);
        } else if (tag instanceof Long) {
            out.append(tag).append('L');
        } else if (tag instanceof Float) {
            out.append(tag).append('f');
        } else if (tag instanceof Double) {
            out.append(tag).append('d');
        } else {
            throw new CgCodecException("not a test tag: " + tag);
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

    /** Minecraft's {@code TagParser} grammar, written from the grammar: the code is not ours to copy. */
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

        Object value() {
            skipSpace();
            if (at >= text.length()) throw error("a tag");
            char c = text.charAt(at);
            if (c == '{') return compound();
            if (c == '[') return list();
            if (c == '"' || c == '\'') return quoted();
            return scalar(bare());
        }

        private Compound compound() {
            expect('{');
            Compound out = new Compound();
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

        private Object list() {
            expect('[');
            skipSpace();
            if (at + 1 < text.length() && text.charAt(at + 1) == ';' && "BIL".indexOf(text.charAt(at)) >= 0) {
                char type = text.charAt(at);
                at += 2;
                List<Object> values = elements();
                if (type == 'B') {
                    byte[] bytes = new byte[values.size()];
                    for (int i = 0; i < bytes.length; i++) bytes[i] = number(values.get(i)).byteValue();
                    return bytes;
                }
                TagList out = new TagList();
                for (Object v : values) out.add(type == 'I' ? (Object) number(v).intValue() : (Object) number(v).longValue());
                return out;
            }
            TagList out = new TagList();
            for (Object v : elements()) {
                if (!out.isEmpty() && out.get(0).getClass() != v.getClass()) throw error("one kind of tag in a list");
                out.add(v);
            }
            return out;
        }

        private List<Object> elements() {
            List<Object> out = new ArrayList<>();
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

        private Number number(Object v) {
            if (!(v instanceof Number)) throw error("a number in a typed array");
            return (Number) v;
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

        private Object scalar(String word) {
            if (word.isEmpty()) throw error("a tag");
            if (word.equals("true")) return (byte) 1;
            if (word.equals("false")) return (byte) 0;
            try {
                if (BYTE.matcher(word).matches()) return Byte.parseByte(trim(word));
                if (SHORT.matcher(word).matches()) return Short.parseShort(trim(word));
                if (LONG.matcher(word).matches()) return Long.parseLong(trim(word));
                if (INT.matcher(word).matches()) return Integer.parseInt(word);
                if (FLOAT.matcher(word).matches()) return Float.parseFloat(trim(word));
                if (DOUBLE.matcher(word).matches()) return Double.parseDouble(trim(word));
                if (DOUBLE_BARE.matcher(word).matches()) return Double.parseDouble(word);
            } catch (NumberFormatException outOfRange) {
                // Minecraft reads an out-of-range number as a string; so does this.
            }
            return word;
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
