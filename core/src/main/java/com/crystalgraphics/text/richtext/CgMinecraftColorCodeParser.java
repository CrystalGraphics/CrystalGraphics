package com.crystalgraphics.text.richtext;

import com.crystalgraphics.api.text.CgStyleSpan;
import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.api.text.CgTextDecoration;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses vanilla Minecraft {@code §}-prefixed formatting codes into a {@link CgStyledText} —
 * colors ({@code §0}-{@code §9}, {@code §a}-{@code §f}), styles ({@code §k} obfuscated,
 * {@code §l} bold, {@code §m} strikethrough, {@code §n} underline, {@code §o} italic), and
 * {@code §r} reset. Palette and code table extracted from vanilla 1.7.10's
 * {@code FontRenderer} ({@code mc1710/build/rfg/minecraft-src/.../FontRenderer.java}).
 *
 * <h3>Adding a code</h3>
 * <p>Add one constant to {@link Code}: its char and how it mutates a {@link Style}. Nothing
 * else in this class is code-aware — see {@link CgTagMarkupParser}'s javadoc for the same
 * table-driven shape this mirrors (open/close tags there vs. flat toggles here).</p>
 *
 * <h3>Flat toggle semantics, not nesting</h3>
 * <p>Formatting codes have no open/close pairing — {@code §l} turns bold on and it stays on
 * (for everything that follows) until something turns it back off. There is nothing to
 * "unclose" at end of input, so strict mode has no end-of-input check the way the tag parser
 * does.</p>
 *
 * <h3>A color code resets styles — this is vanilla's actual behavior, not a bug</h3>
 * <p>{@code §lBold§cRed} renders "Red" in red and <strong>not bold</strong> — {@code FontRenderer}
 * clears every active style flag whenever a color code is applied (see
 * {@code renderStringAtPos}, the {@code j < 16} branch), before setting the new color. Each
 * {@link Code#COLOR} constant reproduces this by clearing styles as well as setting
 * {@link Style#argb}. {@code §r} does the same, plus resetting the color to "inherit".</p>
 *
 * <h3>{@code §k} obfuscated — parsed and discarded</h3>
 * <p>{@code CgStyleSpan} has no field for a randomized-glyph-cycling effect (that's a draw-time
 * animation concern, not a static style attribute), so {@code §k} is recognized syntactically
 * (advances past it, doesn't get treated as unknown) but has no effect on the emitted spans.
 * Wire up real obfuscation support — a new {@code CgStyleSpan} field threaded through
 * {@code CgShapedRun}/baking/the renderer — as a separate, larger change if it's ever needed.</p>
 *
 * <h3>Only one decoration at a time</h3>
 * <p>{@link CgStyleSpan#decoration()} is a single {@link CgTextDecoration} value, not a
 * combinable set — {@code §n§m} (underline then strikethrough) shows only strikethrough, the
 * last one applied. Same limitation as {@link CgTagMarkupParser}; widening
 * {@code CgTextDecoration} to a bitmask is the fix for both if simultaneous decorations are
 * ever needed.</p>
 *
 * <h3>Lenient by default</h3>
 * <p>Unless constructed with {@code strict = true}, an unrecognized code (e.g. {@code §z}) is
 * silently stripped, and a trailing {@code §} with nothing after it is kept as literal text.
 * In strict mode, both throw {@link CgMarkupParseException}.</p>
 */
public final class CgMinecraftColorCodeParser implements CgMarkupParser {

    // ─────────────────────────────────────────────────────────────────────────────
    //  Code table — the only place that knows what codes exist
    // ─────────────────────────────────────────────────────────────────────────────

    /** The mutable style accumulator a {@link Code} writes into. */
    private static final class Style {
        boolean bold;
        boolean italic;
        CgTextDecoration decoration = CgTextDecoration.NONE;
        int argb;
        // §k obfuscated is intentionally not tracked here — see class javadoc.

        boolean isDefault() {
            return !bold && !italic && decoration == CgTextDecoration.NONE && argb == 0;
        }

        void resetStyles() {
            bold = false;
            italic = false;
            decoration = CgTextDecoration.NONE;
        }
    }

    @FunctionalInterface
    private interface Effect {
        void applyTo(Style style);
    }

    /** One color code's effect: reset styles (vanilla behavior), then apply its ARGB. */
    private static Effect color(int argb) {
        return style -> {
            style.resetStyles();
            style.argb = argb;
        };
    }

    private enum Code {
        // ── Colors — palette extracted from FontRenderer's colorCode[] bit math ──
        BLACK('0', color(0xFF000000)),
        DARK_BLUE('1', color(0xFF0000AA)),
        DARK_GREEN('2', color(0xFF00AA00)),
        DARK_AQUA('3', color(0xFF00AAAA)),
        DARK_RED('4', color(0xFFAA0000)),
        DARK_PURPLE('5', color(0xFFAA00AA)),
        GOLD('6', color(0xFFFFAA00)),
        GRAY('7', color(0xFFAAAAAA)),
        DARK_GRAY('8', color(0xFF555555)),
        BLUE('9', color(0xFF5555FF)),
        GREEN('a', color(0xFF55FF55)),
        AQUA('b', color(0xFF55FFFF)),
        RED('c', color(0xFFFF5555)),
        LIGHT_PURPLE('d', color(0xFFFF55FF)),
        YELLOW('e', color(0xFFFFFF55)),
        WHITE('f', color(0xFFFFFFFF)),

        // ── Styles ──
        OBFUSCATED('k', style -> { /* parsed and discarded — see class javadoc */ }),
        BOLD('l', style -> style.bold = true),
        STRIKETHROUGH('m', style -> style.decoration = CgTextDecoration.STRIKETHROUGH),
        UNDERLINE('n', style -> style.decoration = CgTextDecoration.UNDERLINE),
        ITALIC('o', style -> style.italic = true),

        // ── Reset ──
        RESET('r', style -> {
            style.resetStyles();
            style.argb = 0;
        });

        /** Indexed by {@code char - '0'}/{@code char - 'a'} would need two ranges; a flat array over the full char range is simpler and just as fast. */
        private static final Code[] BY_CHAR = new Code[128];
        static {
            for (Code code : values()) {
                Code clash = BY_CHAR[code.ch];
                if (clash != null) {
                    throw new IllegalStateException("Duplicate formatting code '" + code.ch
                            + "' on " + clash + " and " + code);
                }
                BY_CHAR[code.ch] = code;
            }
        }

        final char ch;
        final Effect effect;

        Code(char ch, Effect effect) {
            this.ch = ch;
            this.effect = effect;
        }

        static Code lookup(char ch) {
            return ch < BY_CHAR.length ? BY_CHAR[ch] : null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Parser
    // ─────────────────────────────────────────────────────────────────────────────

    private static final char SECTION_SIGN = '§';

    private final boolean strict;

    public CgMinecraftColorCodeParser() {
        this(false);
    }

    public CgMinecraftColorCodeParser(boolean strict) {
        this.strict = strict;
    }

    @Override
    public CgStyledText parse(String markup) {
        if (markup == null) {
            throw new IllegalArgumentException("markup must not be null");
        }
        return new State(strict).parse(markup);
    }

    /** Per-call mutable parse state — keeps the parser instance itself stateless/reusable. */
    private static final class State {

        private final boolean strict;
        private final StringBuilder plainText = new StringBuilder();
        private final List<CgStyleSpan> spans = new ArrayList<>();
        private final Style style = new Style();
        /** Start of the not-yet-emitted text run; see {@link #flush()}. */
        private int runStart;

        State(boolean strict) {
            this.strict = strict;
        }

        CgStyledText parse(String markup) {
            int i = 0;
            int n = markup.length();
            while (i < n) {
                char c = markup.charAt(i);
                if (c == SECTION_SIGN && i + 1 < n) {
                    apply(Character.toLowerCase(markup.charAt(i + 1)));
                    i += 2;
                } else if (c == SECTION_SIGN) {
                    if (strict) throw new CgMarkupParseException("Dangling section sign at end of input");
                    plainText.append(c);   // lenient: trailing '§' with nothing after it is literal text
                    i++;
                } else {
                    plainText.append(c);
                    i++;
                }
            }

            flush();
            return new CgStyledText(plainText.toString(), spans);
        }

        private void apply(char codeChar) {
            Code code = Code.lookup(codeChar);
            if (code == null) {
                if (strict) throw new CgMarkupParseException("Unknown formatting code: " + SECTION_SIGN + codeChar);
                return;   // lenient: unrecognized code stripped, no style effect
            }
            flush();
            code.effect.applyTo(style);
        }

        /**
         * Emits a span covering the text accumulated since the last style change, then starts a
         * new run. Called immediately before every code's effect is applied (and once at the
         * end), which is what keeps spans non-overlapping.
         */
        private void flush() {
            int end = plainText.length();
            if (end > runStart && !style.isDefault()) {
                spans.add(CgStyleSpan.builder()
                        .start(runStart).end(end)
                        .bold(style.bold).italic(style.italic)
                        .decoration(style.decoration)
                        .argbColor(style.argb)
                        .build());
            }
            runStart = end;
        }
    }
}
