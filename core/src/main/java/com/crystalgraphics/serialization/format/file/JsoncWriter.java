package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgDynamicOps;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * A plain tree as JSON with comments, two spaces deep. Comments go above their keys as {@code //} lines: a person's,
 * read from the file being replaced and kept as they wrote them, where they wrote one, else the codec's.
 */
final class JsoncWriter {

    private final StringBuilder out = new StringBuilder();
    /** Where each map's own comments are: the ops it was encoded with. */
    private final CgDynamicOps<Object> ours;
    @Nullable
    private final FileComments theirs;

    private JsoncWriter(CgDynamicOps<Object> ours, @Nullable FileComments theirs) {
        this.ours = ours;
        this.theirs = theirs;
    }

    /** @throws CgCodecException where {@code tree} holds what JSON cannot: NaN, an infinity, a non-text key */
    static String write(@Nullable Object tree, CgDynamicOps<Object> ours, @Nullable FileComments theirs) {
        JsoncWriter w = new JsoncWriter(ours, theirs);
        int before = w.out.length();
        w.comment(tree == null ? null : ours.commentsOf(tree).get(null), "", "");
        if (w.out.length() > before) w.out.append('\n');
        w.value(tree, "", "");
        w.out.append('\n');
        if (theirs != null && !theirs.end.isEmpty()) {
            for (String line : theirs.end) w.out.append(line).append('\n');
        }
        return w.out.toString();
    }

    private void value(@Nullable Object value, String path, String indent) {
        if (value instanceof Map) {
            object((Map<?, ?>) value, path, indent);
        } else if (value instanceof List) {
            array((List<?>) value, path, indent);
        } else {
            scalar(value);
        }
    }

    private void object(Map<?, ?> map, String path, String indent) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        Map<String, String> notes = ours.commentsOf(map);
        String inner = indent + "  ";
        out.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String)) {
                throw new CgCodecException("JSON's keys are text, and this map has key " + e.getKey());
            }
            String key = (String) e.getKey();
            String at = FileComments.path(path, key);
            String note = notes.get(key);
            if (note == null && e.getValue() instanceof Map) note = ours.commentsOf(e.getValue()).get(null);
            // A commented key gets a blank line above it, the first excepted.
            if (i > 0 && (note != null || FileComments.above(theirs, at) != null)) out.append('\n');
            comment(note, at, inner);
            out.append(inner);
            quote(key);
            out.append(": ");
            value(e.getValue(), at, inner);
            if (++i < map.size()) out.append(',');
            after(at);
            out.append('\n');
        }
        out.append(indent).append('}');
    }

    private void array(List<?> list, String path, String indent) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        boolean flat = true;
        for (Object element : list) {
            if (element instanceof Map || element instanceof List) flat = false;
        }
        for (int i = 0; i < list.size() && flat; i++) {
            String at = FileComments.path(path, "#" + i);
            if (FileComments.above(theirs, at) != null || (theirs != null && theirs.after.containsKey(at))) flat = false;
        }
        if (flat) {
            // Scalars on one line, as a person writes a short list.
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(", ");
                scalar(list.get(i));
            }
            out.append(']');
            return;
        }
        String inner = indent + "  ";
        out.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            String at = FileComments.path(path, "#" + i);
            Object element = list.get(i);
            comment(element instanceof Map ? ours.commentsOf(element).get(null) : null, at, inner);
            out.append(inner);
            value(element, at, inner);
            if (i + 1 < list.size()) out.append(',');
            after(at);
            out.append('\n');
        }
        out.append(indent).append(']');
    }

    private void scalar(@Nullable Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            quote((String) value);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new CgCodecException("JSON has no " + value);
            out.append(value instanceof Float ? Float.toString((Float) value) : value.toString());
        } else if (value instanceof Number) {
            out.append(value);
        } else if (value instanceof byte[]) {
            quote(Base64.getEncoder().encodeToString((byte[]) value));
        } else {
            throw new CgCodecException("JSON cannot hold " + value.getClass().getSimpleName());
        }
    }

    private void quote(String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }

    /** A person's comment at {@code path} where they wrote one, else {@code text}, the codec's, as {@code //} lines. */
    private void comment(@Nullable String text, String path, String indent) {
        List<String> mine = FileComments.above(theirs, path);
        if (mine != null) {
            for (String line : mine) out.append(indent).append(line).append('\n');
            return;
        }
        if (text == null) return;
        for (String line : text.split("\n", -1)) out.append(indent).append(line.isEmpty() ? "//" : "// " + line).append('\n');
    }

    private void after(String path) {
        String text = theirs == null ? null : theirs.after.get(path);
        if (text != null) out.append(' ').append(text);
    }
}
