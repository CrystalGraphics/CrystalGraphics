package com.crystalgraphics.compute.parse;

import com.crystalgraphics.gl.material.parse.CgShaderParseException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code .compute}'s file scope, item by item: directives, functions, {@code shared} variables, structs, the
 * engine's blocks and every other statement, each with its span. Scans comment-blanked text, so a span cuts the
 * original text at the same offsets.
 */
final class TopLevel {

    enum Kind { DIRECTIVE, FUNCTION, SHARED, STRUCT, BLOCK, STATEMENT }

    /**
     * @param name   a function's, struct's, shared variable's or block's name; null otherwise
     * @param header a function's signature or a block's name, squashed; a statement's text
     * @param body   inside a function's or a block's braces, or a struct's
     */
    record Item(Kind kind, int start, int end, String name, String header, String body) {}

    static final Pattern FUNCTION = Pattern.compile(
            "(?:(?:precise|highp|mediump|lowp)\\s+)*([A-Za-z_]\\w*(?:\\s*\\[[^\\]]*\\])?)\\s+([A-Za-z_]\\w*)\\s*\\(([^()]*)\\)");
    private static final Pattern STRUCT = Pattern.compile("struct\\s+([A-Za-z_]\\w*)\\s*\\{.*", Pattern.DOTALL);
    private static final Pattern SHARED = Pattern.compile(
            "(?:layout\\s*\\([^)]*\\)\\s*)?shared\\s+(?:(?:precise|highp|mediump|lowp|coherent|volatile)\\s+)*"
            + "([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*((?:\\[[^\\]]*\\]\\s*)*)(.*);", Pattern.DOTALL);
    private static final Pattern INTERFACE = Pattern.compile("\\b(uniform|buffer)\\b");
    private static final Pattern LOCAL_SIZE = Pattern.compile("\\blocal_size_[xyz]\\b");

    private TopLevel() {}

    static List<Item> scan(String blanked, String path) {
        List<Item> items = new ArrayList<>();
        int n = blanked.length();
        int start = -1;
        boolean lineStart = true;
        for (int i = 0; i < n; ) {
            char c = blanked.charAt(i);
            if (c == '\n') {
                lineStart = true;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (lineStart && c == '#') {
                int end = GlslText.lineEnd(blanked, i);
                // A directive inside a pending statement stays part of it.
                if (start < 0) items.add(new Item(Kind.DIRECTIVE, i, end, null, blanked.substring(i, end).trim(), null));
                i = end;
                continue;
            }
            lineStart = false;
            if (start < 0) start = i;
            if (c == '{') {
                int close = GlslText.matchBrace(blanked, i, path);
                String header = GlslText.squash(blanked.substring(start, i));
                String body = blanked.substring(i + 1, close);
                if (header.equals("Properties") || header.equals("Buffers") || header.equals("Images")) {
                    items.add(new Item(Kind.BLOCK, start, close + 1, header, header, body));
                    start = -1;
                } else if (FUNCTION.matcher(header).matches()) {
                    Matcher m = FUNCTION.matcher(header);
                    m.matches();
                    items.add(new Item(Kind.FUNCTION, start, close + 1, m.group(2), header, body));
                    start = -1;
                } else if (INTERFACE.matcher(header).find()) {
                    throw fail(path, blanked, start, "declares a '" + header + "' block: storage buffers go in Buffers { }, "
                            + "values in Properties { }");
                }
                // Anything else (a struct, an initializer) ends at its ';'.
                i = close + 1;
                continue;
            }
            if (c == ';') {
                String text = blanked.substring(start, i + 1);
                String squashed = GlslText.squash(text);
                Matcher struct = STRUCT.matcher(squashed);
                Matcher shared = SHARED.matcher(squashed);
                if (struct.matches()) {
                    int open = blanked.indexOf('{', start);
                    int close = GlslText.matchBrace(blanked, open, path);
                    items.add(new Item(Kind.STRUCT, start, i + 1, struct.group(1), squashed, blanked.substring(open + 1, close)));
                } else if (shared.matches()) {
                    if (shared.group(4).contains(",")) {
                        throw fail(path, blanked, start, "declares several shared variables in one statement; declare one each");
                    }
                    items.add(new Item(Kind.SHARED, start, i + 1, shared.group(2), squashed, null));
                } else if (INTERFACE.matcher(squashed).find()) {
                    throw fail(path, blanked, start, "declares '" + squashed + "': values and samplers go in Properties { }, "
                            + "storage images in Images { }, storage buffers in Buffers { }");
                } else if (LOCAL_SIZE.matcher(squashed).find()) {
                    throw fail(path, blanked, start, "declares a local size: a kernel's is its #pragma kernel's");
                } else {
                    items.add(new Item(Kind.STATEMENT, start, i + 1, null, squashed, null));
                }
                start = -1;
                i++;
                continue;
            }
            i++;
        }
        if (start >= 0) items.add(new Item(Kind.STATEMENT, start, n, null, GlslText.squash(blanked.substring(start)), null));
        return items;
    }

    /** {@code shared T name[dims]}: the type, and the dimension expressions. */
    static String[] sharedType(Item item) {
        Matcher m = SHARED.matcher(item.header());
        m.matches();
        return new String[]{m.group(1), m.group(3)};
    }

    static CgShaderParseException fail(String path, String text, int at, String message) {
        return CgShaderParseException.at("[" + path + "] " + message, text, at);
    }
}
