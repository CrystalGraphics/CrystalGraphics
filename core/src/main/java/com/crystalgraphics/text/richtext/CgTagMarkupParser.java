package com.crystalgraphics.text.richtext;

import com.crystalgraphics.api.text.CgStyleSpan;
import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.api.text.CgTextDecoration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses {@code <b>}/{@code <strong>}, {@code <i>}/{@code <em>}, {@code <u>},
 * {@code <s>}/{@code <strike>}, {@code <overline>}, and {@code <color=#RRGGBB>} tags into a
 * {@link CgStyledText}.
 *
 * <h3>Adding a tag</h3>
 * <p>Add one constant to {@link Tag}: its accepted spellings, how it mutates a {@link Style},
 * and (if it takes a {@code =value}) which {@link ValueSyntax} parses that value. Nothing else
 * in this class is tag-aware — no new field, no new switch arm, no precedence table — because
 * the active style is <em>folded</em> from the open-tag stack rather than tracked in
 * per-tag state.</p>
 *
 * <h3>Nesting produces merged spans, not overlapping ones</h3>
 * <p>{@link CgStyledText} requires non-overlapping spans, so {@code <b><i>x</i></b>} does not
 * produce two overlapping single-attribute spans. Open tags are kept on a stack; whenever that
 * stack changes, the text accumulated since the last change is emitted as one span carrying
 * the fold of every tag open over it. A span is only emitted when the folded style is actually
 * non-default (a plain, unstyled run of text needs no span at all).</p>
 *
 * <h3>Innermost wins for single-valued attributes</h3>
 * <p>The fold runs outermost → innermost, so for an attribute that can only hold one value at a
 * time — {@link CgStyleSpan#decoration()}, {@link CgStyleSpan#argbColor()} — the innermost
 * enclosing tag wins. {@code <s><u>x</u></s>} therefore renders underlined, not both;
 * {@link CgTextDecoration} is a single value, not a combinable set. Widening it to a bitmask is
 * the fix if simultaneous decorations are ever needed — {@link Tag}'s effects would then OR
 * instead of assign, with no other change here.</p>
 *
 * <h3>Lenient by default</h3>
 * <p>Unless constructed with {@code strict = true}:</p>
 * <ul>
 *   <li>An unmatched closing tag (no matching open tag active) is a no-op.</li>
 *   <li>A tag still open at the end of input implicitly closes there.</li>
 *   <li>Crossed tags ({@code <b><i></b></i>}) close the nearest matching open tag.</li>
 *   <li>An unrecognized tag name is stripped with no style effect.</li>
 *   <li>A malformed {@code color} value is treated as no color.</li>
 *   <li>An unterminated {@code <} (no matching {@code >}) is treated as literal text.</li>
 * </ul>
 * <p>In strict mode, all of the above throw {@link CgMarkupParseException} instead —
 * intended for a build-time lint over authored markup, not runtime user input.</p>
 *
 * <h3>Known limitation</h3>
 * <p>There is no escape syntax for a literal {@code <}/{@code >} in text — any
 * {@code <name>} / {@code <name=value>} shaped substring is parsed as a tag attempt.
 * Not addressed here; out of scope for this parser.</p>
 */
public final class CgTagMarkupParser implements CgMarkupParser {

    // ─────────────────────────────────────────────────────────────────────────────
    //  Tag table — the only place that knows what tags exist
    // ─────────────────────────────────────────────────────────────────────────────

    /** The mutable style accumulator a {@link Tag} writes into during the fold. */
    private static final class Style {
        boolean bold;
        boolean italic;
        CgTextDecoration decoration = CgTextDecoration.NONE;
        int argb;

        boolean isDefault() {
            return !bold && !italic && decoration == CgTextDecoration.NONE && argb == 0;
        }
    }

    /** What an open tag does to the style it encloses. {@code payload} is its parsed {@code =value}. */
    @FunctionalInterface
    private interface Effect {
        void applyTo(Style style, int payload);
    }

    /** How a tag's {@code =value} (if any) is turned into the {@code int} payload handed to {@link Effect}. */
    private enum ValueSyntax {
        /** Tag takes no value; any given is ignored. */
        NONE {
            @Override
            int parse(String value, boolean strict) {
                return 0;
            }
        },
        /** {@code #RRGGBB} or {@code RRGGBB} → opaque ARGB. */
        COLOR {
            @Override
            int parse(String value, boolean strict) {
                String hex = value == null ? null : value.startsWith("#") ? value.substring(1) : value;
                if (hex != null && hex.length() == 6) {
                    try {
                        return 0xFF000000 | Integer.parseInt(hex, 16);
                    } catch (NumberFormatException ignored) {
                        // fall through to shared malformed handling
                    }
                }
                if (strict) {
                    throw new CgMarkupParseException(
                            value == null ? "color tag requires a value" : "Invalid color value: " + value);
                }
                return 0;
            }
        };

        abstract int parse(String value, boolean strict);
    }

    private enum Tag {
        BOLD((style, payload) -> style.bold = true, "b", "strong"),
        ITALIC((style, payload) -> style.italic = true, "i", "em"),
        UNDERLINE((style, payload) -> style.decoration = CgTextDecoration.UNDERLINE, "u"),
        STRIKETHROUGH((style, payload) -> style.decoration = CgTextDecoration.STRIKETHROUGH, "s", "strike"),
        OVERLINE((style, payload) -> style.decoration = CgTextDecoration.OVERLINE, "overline"),
        COLOR((style, payload) -> style.argb = payload, ValueSyntax.COLOR, "color");

        private static final Map<String, Tag> BY_NAME = new HashMap<>();
        static {
            for (Tag tag : values()) {
                for (String name : tag.names) {
                    Tag clash = BY_NAME.put(name, tag);
                    if (clash != null) {
                        throw new IllegalStateException("Duplicate markup tag name '" + name
                                + "' on " + clash + " and " + tag);
                    }
                }
            }
        }

        final Effect effect;
        final ValueSyntax valueSyntax;
        final String[] names;

        Tag(Effect effect, String... names) {
            this(effect, ValueSyntax.NONE, names);
        }

        Tag(Effect effect, ValueSyntax valueSyntax, String... names) {
            this.effect = effect;
            this.valueSyntax = valueSyntax;
            this.names = names;
        }

        /** The canonical spelling, used in diagnostics. */
        String canonicalName() {
            return names[0];
        }

        static Tag lookup(String name) {
            return BY_NAME.get(name);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Parser
    // ─────────────────────────────────────────────────────────────────────────────

    private final boolean strict;

    public CgTagMarkupParser() {
        this(false);
    }

    public CgTagMarkupParser(boolean strict) {
        this.strict = strict;
    }

    @Override
    public CgStyledText parse(String markup) {
        if (markup == null) {
            throw new IllegalArgumentException("markup must not be null");
        }
        return new State(strict).parse(markup);
    }

    /** One open tag and its already-parsed {@code =value} payload. */
    private record Open(Tag tag, int payload) { }

    /** Per-call mutable parse state — keeps the parser instance itself stateless/reusable. */
    private static final class State {

        private final boolean strict;
        private final StringBuilder plainText = new StringBuilder();
        private final List<CgStyleSpan> spans = new ArrayList<>();
        /** Innermost-first (this is {@link ArrayDeque}'s own iteration order for a stack). */
        private final Deque<Open> openTags = new ArrayDeque<>();
        /** Start of the not-yet-emitted text run; see {@link #flush()}. */
        private int runStart;

        State(boolean strict) {
            this.strict = strict;
        }

        CgStyledText parse(String markup) {
            int i = 0;
            int n = markup.length();
            while (i < n) {
                if (markup.charAt(i) == '<') {
                    int close = markup.indexOf('>', i);
                    if (close < 0) {
                        if (strict) throw new CgMarkupParseException("Unterminated tag at index " + i);
                        plainText.append(markup, i, n);   // lenient: trailing '<...' is literal text
                        break;
                    }
                    tag(markup.substring(i + 1, close));
                    i = close + 1;
                } else {
                    int next = markup.indexOf('<', i);
                    if (next < 0) next = n;
                    plainText.append(markup, i, next);
                    i = next;
                }
            }

            flush();
            if (strict && !openTags.isEmpty()) {
                throw new CgMarkupParseException("Unclosed tag(s) at end of input: " + openTags);
            }
            return new CgStyledText(plainText.toString(), spans);
        }

        /** Parses and applies one tag body — the text between {@code <} and {@code >}. */
        private void tag(String body) {
            boolean closing = body.startsWith("/");
            if (closing) body = body.substring(1);

            int eq = body.indexOf('=');
            String name = (eq < 0 ? body : body.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
            String value = eq < 0 ? null : body.substring(eq + 1).trim();

            Tag tag = Tag.lookup(name);
            if (tag == null) {
                if (strict) throw new CgMarkupParseException("Unknown tag: " + name);
                return;   // lenient: unrecognized tag stripped, no style effect
            }
            if (closing) close(tag);
            else open(tag, value);
        }

        private void open(Tag tag, String value) {
            // Parse the payload BEFORE flushing so a strict-mode malformed value throws without
            // having already split the run at this point.
            int payload = tag.valueSyntax.parse(value, strict);
            flush();
            openTags.push(new Open(tag, payload));
        }

        private void close(Tag tag) {
            // Remove the nearest matching open tag rather than requiring it to be on top, so
            // crossed tags (<b><i></b></i>) degrade sensibly instead of dropping the close.
            Iterator<Open> innermostFirst = openTags.iterator();
            while (innermostFirst.hasNext()) {
                if (innermostFirst.next().tag() == tag) {
                    flush();
                    innermostFirst.remove();
                    return;
                }
            }
            if (strict) {
                throw new CgMarkupParseException("Unmatched closing tag: </" + tag.canonicalName() + ">");
            }
            // lenient: unmatched closing tag is a no-op
        }

        /**
         * Emits a span covering the text accumulated since the last stack change, then starts a
         * new run. Called immediately before every push/remove (and once at the end), which is
         * what keeps spans non-overlapping: each span describes exactly one interval over which
         * the open-tag stack — and therefore the style — did not change.
         */
        private void flush() {
            int end = plainText.length();
            if (end > runStart) {
                Style style = foldOpenTags();
                if (!style.isDefault()) {
                    spans.add(CgStyleSpan.builder()
                            .start(runStart).end(end)
                            .bold(style.bold).italic(style.italic)
                            .decoration(style.decoration)
                            .argbColor(style.argb)
                            .build());
                }
            }
            runStart = end;
        }

        /** Applies every open tag outermost → innermost, so the innermost wins on conflict. */
        private Style foldOpenTags() {
            Style style = new Style();
            for (Iterator<Open> outermostFirst = openTags.descendingIterator(); outermostFirst.hasNext(); ) {
                Open open = outermostFirst.next();
                open.tag().effect.applyTo(style, open.payload());
            }
            return style;
        }
    }
}
