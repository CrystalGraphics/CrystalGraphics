package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgDynamicOps;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * A plain tree as TOML: a table's keys, then its tables as {@code [a.b]}, then its arrays of tables as {@code [[a]]};
 * anything inside an array inline. Comments go above their keys: a person's, read from the file being replaced, where
 * they wrote one, else the codec's.
 */
final class TomlWriter {

    private final StringBuilder out = new StringBuilder();
    /** Where each map's own comments are: the ops it was encoded with. */
    private final CgDynamicOps<Object> ours;
    @Nullable
    private final TomlReader.Comments theirs;

    private TomlWriter(CgDynamicOps<Object> ours, @Nullable TomlReader.Comments theirs) {
        this.ours = ours;
        this.theirs = theirs;
    }

    /**
     * {@code tree}, with the comments {@code ours} made its maps with.
     *
     * @throws CgCodecException where {@code tree} is not a table, or holds what TOML cannot: a null, a non-text key
     */
    static String write(Object tree, CgDynamicOps<Object> ours, @Nullable TomlReader.Comments theirs) {
        if (!(tree instanceof Map)) throw new CgCodecException("TOML holds a table at its top; this value is " + kind(tree));
        TomlWriter w = new TomlWriter(ours, theirs);
        w.comment(ours.commentsOf(tree).get(null), "");
        if (w.out.length() > 0) w.out.append('\n');
        w.table((Map<?, ?>) tree, "", "", null, null);
        if (theirs != null && !theirs.end.isEmpty()) {
            if (w.out.length() > 0) w.out.append('\n');
            for (String line : theirs.end) w.out.append('#').append(line).append('\n');
        }
        return w.out.toString();
    }

    /**
     * @param path     {@link TomlReader#path}: where a person's comments were read
     * @param header   {@code [a.b]} or {@code [[a]]}, null for the top
     * @param about    the codec's comment above that header
     */
    private void table(Map<?, ?> map, String path, String tomlKey, @Nullable String header, @Nullable String about) {
        if (header != null) {
            if (out.length() > 0 && !(out.length() > 1 && out.charAt(out.length() - 2) == '\n')) out.append('\n');
            comment(about, path);
            out.append(header);
            after(path);
            out.append('\n');
        }
        Map<String, String> notes = ours.commentsOf(map);
        List<Map.Entry<?, ?>> tables = new ArrayList<>();
        List<Map.Entry<?, ?>> arrays = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object value = e.getValue();
            if (value == null) continue;
            if (value instanceof Map) {
                tables.add(e);
            } else if (tableArray(value)) {
                arrays.add(e);
            } else {
                String key = key(e.getKey());
                String p = TomlReader.path(path, key);
                String note = notes.get(key);
                // A commented key gets a blank line above it, the first in its table excepted.
                if (!first && (note != null || theirsAbove(p) != null)) out.append('\n');
                first = false;
                comment(note, p);
                out.append(quoteKey(key)).append(" = ");
                inline(value);
                after(p);
                out.append('\n');
            }
        }
        for (Map.Entry<?, ?> e : tables) {
            String key = key(e.getKey());
            String sub = join(tomlKey, quoteKey(key));
            Map<?, ?> inner = (Map<?, ?>) e.getValue();
            table(inner, TomlReader.path(path, key), sub, "[" + sub + "]", aboutTable(notes.get(key), inner));
        }
        for (Map.Entry<?, ?> e : arrays) {
            String key = key(e.getKey());
            String sub = join(tomlKey, quoteKey(key));
            List<?> list = (List<?>) e.getValue();
            for (int i = 0; i < list.size(); i++) {
                Map<?, ?> element = (Map<?, ?>) list.get(i);
                // The key's comment goes above the first element only.
                table(element, TomlReader.path(TomlReader.path(path, key), "#" + i), sub, "[[" + sub + "]]",
                        aboutTable(i == 0 ? notes.get(key) : null, element));
            }
        }
    }

    /** Above a table's header: its key's comment, else the table's own. */
    @Nullable
    private String aboutTable(@Nullable String keyNote, Map<?, ?> table) {
        return keyNote != null ? keyNote : ours.commentsOf(table).get(null);
    }

    private void inline(Object value) {
        if (value instanceof String) {
            basic((String) value);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            out.append(decimal((Number) value));
        } else if (value instanceof Number) {
            out.append(value);
        } else if (value instanceof byte[]) {
            basic(Base64.getEncoder().encodeToString((byte[]) value));
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object element : (List<?>) value) {
                if (element == null) throw new CgCodecException("TOML has no null, and this array holds one");
                if (!first) out.append(", ");
                first = false;
                inline(element);
            }
            out.append(']');
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (e.getValue() == null) continue;
                out.append(first ? " " : ", ");
                first = false;
                out.append(quoteKey(key(e.getKey()))).append(" = ");
                inline(e.getValue());
            }
            out.append(first ? "}" : " }");
        } else {
            throw new CgCodecException("TOML cannot hold " + kind(value));
        }
    }

    private static String decimal(Number n) {
        double d = n.doubleValue();
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        String text = n instanceof Float ? Float.toString(n.floatValue())
                : n instanceof BigDecimal ? n.toString() : Double.toString(d);
        // A float without a point or exponent would read back as an integer.
        return text.indexOf('.') < 0 && text.indexOf('E') < 0 && text.indexOf('e') < 0 ? text + ".0" : text;
    }

    private void basic(String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\t': out.append("\\t"); break;
                case '\r': out.append("\\r"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x7F) out.append(String.format("\\u%04X", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }

    /** A person's comment at {@code path} where they wrote one, else {@code text}, the codec's. */
    private void comment(@Nullable String text, String path) {
        List<String> mine = theirsAbove(path);
        if (mine != null) {
            for (String line : mine) out.append('#').append(line).append('\n');
            return;
        }
        if (text == null) return;
        for (String line : text.split("\n", -1)) out.append(line.isEmpty() ? "#" : "# " + line).append('\n');
    }

    @Nullable
    private List<String> theirsAbove(String path) {
        List<String> lines = theirs == null ? null : theirs.above.get(path);
        return lines == null || lines.isEmpty() ? null : lines;
    }

    private void after(String path) {
        String text = theirs == null ? null : theirs.after.get(path);
        if (text != null) out.append("  #").append(text);
    }

    private static boolean tableArray(Object value) {
        if (!(value instanceof List) || ((List<?>) value).isEmpty()) return false;
        for (Object element : (List<?>) value) if (!(element instanceof Map)) return false;
        return true;
    }

    private static String key(Object key) {
        if (!(key instanceof String)) throw new CgCodecException("TOML's keys are text, and this table has key " + key);
        return (String) key;
    }

    private String quoteKey(String key) {
        boolean bare = !key.isEmpty();
        for (int i = 0; i < key.length() && bare; i++) {
            char c = key.charAt(i);
            bare = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
        }
        if (bare) return key;
        int mark = out.length();
        basic(key);
        String quoted = out.substring(mark);
        out.setLength(mark);
        return quoted;
    }

    private static String join(String tomlKey, String part) {
        return tomlKey.isEmpty() ? part : tomlKey + "." + part;
    }

    private static String kind(@Nullable Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
