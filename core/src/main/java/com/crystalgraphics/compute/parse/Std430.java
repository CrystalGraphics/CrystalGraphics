package com.crystalgraphics.compute.parse;

import com.crystalgraphics.compute.source.CgElementField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * std430 sizes and alignments of GLSL types, structs included: a buffer element's stride, and what a {@code shared}
 * array holds. An array size is resolved through {@code constants}; an unresolvable one makes a type's layout null.
 */
final class Std430 {

    /** Bytes and alignment. */
    record Layout(int size, int align) {
        int stride() { return (size + align - 1) / align * align; }
    }

    private static final Pattern VECTOR = Pattern.compile("([biud]?)vec([234])");
    private static final Pattern MATRIX = Pattern.compile("(d?)mat([234])(?:x([234]))?");
    private static final Pattern FIELD = Pattern.compile(
            "(?:(?:precise|highp|mediump|lowp)\\s+)*([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*((?:\\[[^\\]]*\\]\\s*)*)");
    private static final Pattern DIMENSION = Pattern.compile("\\[([^\\]]*)\\]");

    private final Map<String, String> structBodies;
    private final Function<String, Integer> constants;

    /**
     * @param structBodies each struct the file declares, by name: what is inside its braces
     * @param constants    an array size expression's value, or null
     */
    Std430(Map<String, String> structBodies, Function<String, Integer> constants) {
        this.structBodies = structBodies;
        this.constants = constants;
    }

    /** The layout of {@code type}, or null for a type it does not know or a size it cannot read. */
    Layout of(String type) {
        switch (type) {
            case "float": case "int": case "uint": case "bool": return new Layout(4, 4);
            case "double": return new Layout(8, 8);
            default: break;
        }
        Matcher v = VECTOR.matcher(type);
        if (v.matches()) {
            int scalar = v.group(1).equals("d") ? 8 : 4;
            int n = Integer.parseInt(v.group(2));
            return new Layout(scalar * n, scalar * (n == 2 ? 2 : 4));
        }
        Matcher m = MATRIX.matcher(type);
        if (m.matches()) {
            int columns = Integer.parseInt(m.group(2));
            int rows = m.group(3) == null ? columns : Integer.parseInt(m.group(3));
            Layout column = of((m.group(1).equals("d") ? "dvec" : "vec") + rows);
            return new Layout(columns * column.stride(), column.align());
        }
        String body = structBodies.get(type);
        return body == null ? null : struct(body);
    }

    private Layout struct(String body) {
        int offset = 0;
        int align = 4;
        for (String statement : body.split(";")) {
            String field = GlslText.squash(statement);
            if (field.isEmpty()) continue;
            Matcher f = FIELD.matcher(field);
            if (!f.matches()) return null;
            Layout layout = array(f.group(1), f.group(3));
            if (layout == null) return null;
            offset = (offset + layout.align() - 1) / layout.align() * layout.align() + layout.size();
            align = Math.max(align, layout.align());
        }
        return new Layout((offset + align - 1) / align * align, align);
    }

    /**
     * Where each field of {@code type} sits: a struct's members in order, or one unnamed field for any other type.
     * Null where {@link #of} is.
     */
    List<CgElementField> fieldsOf(String type) {
        String body = structBodies.get(type);
        List<CgElementField> out = new ArrayList<>();
        if (body == null) {
            Layout whole = of(type);
            if (whole == null) return null;
            out.add(new CgElementField("", type, 0, whole.size()));
            return out;
        }
        int offset = 0;
        for (String statement : body.split(";")) {
            String field = GlslText.squash(statement);
            if (field.isEmpty()) continue;
            Matcher f = FIELD.matcher(field);
            if (!f.matches()) return null;
            Layout layout = array(f.group(1), f.group(3));
            if (layout == null) return null;
            offset = (offset + layout.align() - 1) / layout.align() * layout.align();
            out.add(new CgElementField(f.group(2), f.group(1), offset, layout.size()));
            offset += layout.size();
        }
        return out;
    }

    /** {@code type} under array dimensions such as {@code [4][CG_GROUP_SIZE]}: the element's layout without any. */
    Layout array(String type, String dimensions) {
        Layout element = of(type);
        if (element == null) return null;
        int count = 1;
        Matcher d = DIMENSION.matcher(dimensions == null ? "" : dimensions);
        boolean any = false;
        while (d.find()) {
            Integer n = constants.apply(d.group(1).trim());
            if (n == null || n <= 0) return null;
            count *= n;
            any = true;
        }
        return any ? new Layout(element.stride() * count, element.align()) : element;
    }

    /** The fields of a struct body as {@code type}, {@code dimensions} pairs, or null where one does not read. */
    static String[][] fields(String body) {
        String[] statements = body.split(";");
        String[][] out = new String[statements.length][];
        int n = 0;
        for (String statement : statements) {
            String field = GlslText.squash(statement);
            if (field.isEmpty()) continue;
            Matcher f = FIELD.matcher(field);
            if (!f.matches()) return null;
            out[n++] = new String[]{f.group(1), f.group(3).trim()};
        }
        String[][] trimmed = new String[n][];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }
}
