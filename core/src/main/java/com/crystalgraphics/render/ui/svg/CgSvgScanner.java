package com.crystalgraphics.render.ui.svg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns SVG text into a flat list of open/close/self-close tags.
 *
 * <h3>Why this replaced a regex</h3>
 *
 * <p>The first loader matched shape elements with one pattern and ignored everything around them. That is
 * enough for a flat icon — Feather's files are a {@code <svg>} and six self-closing children — and it
 * fails completely on anything an editor exports. Real artwork nests {@code <g>} with a
 * {@code transform}, hides templates in {@code <defs>}, and states colour on an ancestor. A pattern that
 * cannot see a closing tag cannot know when a group's transform stops applying, so there is no way to
 * bolt inheritance onto it: the structure <em>is</em> the missing information.</p>
 *
 * <h3>A scanner, not a DOM</h3>
 *
 * <p>Tokens in document order, with the consumer keeping its own stack. That is all a painter needs —
 * painter's order is document order — and it means no node objects, no parent pointers, and no tree to
 * walk twice. {@code <use>} is the one construct that genuinely needs random access, and it gets it from
 * an index of token positions rather than from a tree.</p>
 *
 * <h3>What it deliberately does not do</h3>
 *
 * <p>No namespace resolution ({@code xlink:href} is read as the literal name), no entity expansion beyond
 * the five predefined ones, no DTD, no validation. An icon or a logo uses none of it, and every one of
 * them is a way for a malformed file to become an exception instead of a missing shape.</p>
 */
public final class CgSvgScanner {

    /** One tag. Attributes are empty for a {@link Kind#CLOSE}. */
    public record Tag(Kind kind, String name, Map<String, String> attributes) {

        public String get(String attribute) {
            String value = attributes.get(attribute);
            return value == null ? "" : value;
        }

        public boolean has(String attribute) {
            return attributes.containsKey(attribute);
        }
    }

    public enum Kind {
        /** {@code <g …>} — a matching {@link #CLOSE} follows. */
        OPEN,
        /** {@code </g>}. */
        CLOSE,
        /** {@code <path …/>} — no children, so no close. */
        SELF_CLOSE
    }

    private CgSvgScanner() {
    }

    public static List<Tag> scan(String svg) {
        List<Tag> out = new ArrayList<>();
        if (svg == null) return out;

        int at = 0;
        int length = svg.length();
        while (at < length) {
            int open = svg.indexOf('<', at);
            if (open < 0) break;

            // Comments, declarations and CDATA all start with punctuation where a name would be, and all
            // of them can legally contain a bare '>' -- so each needs its own terminator.
            if (svg.startsWith("<!--", open)) {
                int end = svg.indexOf("-->", open);
                at = end < 0 ? length : end + 3;
                continue;
            }
            if (svg.startsWith("<![CDATA[", open)) {
                int end = svg.indexOf("]]>", open);
                at = end < 0 ? length : end + 3;
                continue;
            }
            if (svg.startsWith("<?", open)) {
                int end = svg.indexOf("?>", open);
                at = end < 0 ? length : end + 2;
                continue;
            }
            if (svg.startsWith("<!", open)) {
                int end = svg.indexOf('>', open);
                at = end < 0 ? length : end + 1;
                continue;
            }

            int close = findTagEnd(svg, open);
            if (close < 0) break;
            String body = svg.substring(open + 1, close);
            at = close + 1;
            if (body.isEmpty()) continue;

            if (body.charAt(0) == '/') {
                out.add(new Tag(Kind.CLOSE, localName(body.substring(1).trim()), Map.of()));
                continue;
            }
            boolean selfClosing = body.charAt(body.length() - 1) == '/';
            if (selfClosing) body = body.substring(0, body.length() - 1);

            int nameEnd = 0;
            while (nameEnd < body.length() && !Character.isWhitespace(body.charAt(nameEnd))) nameEnd++;
            String name = localName(body.substring(0, nameEnd));
            Map<String, String> attributes = attributes(body.substring(nameEnd));
            out.add(new Tag(selfClosing ? Kind.SELF_CLOSE : Kind.OPEN, name, attributes));
        }
        return out;
    }

    /**
     * Finds the {@code >} that ends a tag, skipping any inside a quoted attribute value.
     *
     * <p>A naive {@code indexOf('>')} is right for almost every file and wrong for the ones that matter:
     * a {@code d} attribute never contains {@code >}, but a {@code style} or a {@code font-family} can,
     * and an embedded {@code <style>} block certainly does. Cutting the tag short there loses every
     * attribute after the quote, which reads as a shape drawn in the wrong colour rather than as a parse
     * failure.</p>
     */
    private static int findTagEnd(String svg, int open) {
        char quote = 0;
        for (int i = open + 1; i < svg.length(); i++) {
            char c = svg.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Strips a namespace prefix — {@code svg:path} is a {@code path}.
     *
     * <p>Done on the element name and deliberately <b>not</b> on attributes: {@code xlink:href} and
     * {@code href} genuinely coexist in the wild and a consumer wants to try both, whereas two elements
     * differing only by prefix never mean different things in a file we can draw.</p>
     */
    private static String localName(String raw) {
        int colon = raw.indexOf(':');
        return colon < 0 ? raw : raw.substring(colon + 1);
    }

    private static Map<String, String> attributes(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        int at = 0;
        int length = raw.length();
        while (at < length) {
            while (at < length && (Character.isWhitespace(raw.charAt(at)) || raw.charAt(at) == '/')) at++;
            int nameStart = at;
            while (at < length && raw.charAt(at) != '=' && !Character.isWhitespace(raw.charAt(at))) at++;
            if (at <= nameStart) break;
            String name = raw.substring(nameStart, at);

            while (at < length && Character.isWhitespace(raw.charAt(at))) at++;
            if (at >= length || raw.charAt(at) != '=') {
                // A valueless attribute is not legal XML but does appear; record it as empty rather than
                // resynchronising, which would drop every attribute after it.
                out.put(name, "");
                continue;
            }
            at++;
            while (at < length && Character.isWhitespace(raw.charAt(at))) at++;
            if (at >= length) break;

            char quote = raw.charAt(at);
            String value;
            if (quote == '"' || quote == '\'') {
                int end = raw.indexOf(quote, at + 1);
                if (end < 0) end = length;
                value = raw.substring(at + 1, end);
                at = end + 1;
            } else {
                int end = at;
                while (end < length && !Character.isWhitespace(raw.charAt(end))) end++;
                value = raw.substring(at, end);
                at = end;
            }
            out.put(name, unescape(value));
        }
        return out;
    }

    /** The five predefined XML entities. Anything else is left alone rather than guessed at. */
    private static String unescape(String value) {
        if (value.indexOf('&') < 0) return value;
        return value.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
