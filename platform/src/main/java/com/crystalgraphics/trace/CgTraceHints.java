package com.crystalgraphics.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What a report <b>accuses</b> a slow frame of, and the seam a consumer registers its own accusations
 * through.
 *
 * <pre>{@code
 * CgTraceHints.register((frame, zones, counters, out) -> {
 *     long cleared = counters.getOrDefault("layer-clear-kpx", 0L);
 *     if (cleared > 8_000 && counters.getOrDefault("drawcalls", 0L) < 100) {
 *         out.add(new CgTraceHints.Hint("LAYER-BOUND",
 *                 cleared / 1000 + "Mpx cleared for " + counters.get("drawcalls") + " draw calls",
 *                 "docs/CGUI_WIDGETS.md §12f"));
 *     }
 * });
 * }</pre>
 *
 * <h3>Why the rules are not the engine's</h3>
 *
 * <p>A hint is a rule over counter NAMES, and counter names are the instrumentation's vocabulary rather
 * than the engine's. {@code retain-dynamic} and {@code layer-clear-kpx} mean something to whoever wrote
 * the painter and nothing here — the same reason a channel is a name a consumer registers and not an
 * enum this package ships. So the engine holds the shape and the list; CrystalGUI, a mod, or a test
 * supplies the rules.</p>
 *
 * <h3>A hint that cannot be acted on is noise</h3>
 *
 * <p>Which is what {@link Hint#link} is for. The difference between a finding and a complaint is
 * usually one reference — the zone it is about, the counter's own name, or the document that explains
 * the mechanism.</p>
 */
public final class CgTraceHints {

    private CgTraceHints() {
    }

    /**
     * One accusation.
     *
     * @param code  a short stable tag, upper-case and hyphenated — what a reader greps for and what a
     *              regression test names. Never a sentence
     * @param text  the finding, with the numbers that support it
     * @param link  where to look next: a source location, a counter name, a document section. May be null
     */
    public record Hint(String code, String text, String link) {

        public Hint(String code, String text) {
            this(code, text, null);
        }
    }

    /** Examines one frame and adds what it finds. */
    @FunctionalInterface
    public interface Rule {

        /**
         * @param frame    the frame being judged
         * @param zones    its zone tree, already built
         * @param counters its counters, summed per name
         * @param out      where findings go
         */
        void examine(CgFrameRecord frame, List<CgTraceAggregate.Node> zones,
                     Map<String, Long> counters, List<Hint> out);
    }

    private static final List<Rule> RULES = new CopyOnWriteArrayList<>();

    /** Adds a rule. Registering the same rule twice runs it twice — hold it in a constant. */
    public static void register(Rule rule) {
        if (rule != null) RULES.add(rule);
    }

    public static void clear() {
        RULES.clear();
    }

    public static int count() {
        return RULES.size();
    }

    /**
     * Every hint every rule finds for {@code frame}, in registration order.
     *
     * <p>A rule that throws is skipped rather than allowed to take the report with it: a diagnostic
     * that cannot be read because one accusation misfired is worse than an incomplete one.</p>
     */
    public static List<Hint> forFrame(CgFrameRecord frame, List<CgTraceAggregate.Node> zones,
                                      Map<String, Long> counters) {
        List<Hint> out = new ArrayList<>();
        for (Rule rule : RULES) {
            try {
                rule.examine(frame, zones, counters, out);
            } catch (RuntimeException ignored) {
                // As above — a broken rule costs its own finding and nothing else.
            }
        }
        return out;
    }
}
