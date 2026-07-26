package com.crystalgraphics.text.richtext;

import com.crystalgraphics.api.text.CgStyledText;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts markup text into a {@link CgStyledText} — plain text plus the sorted,
 * non-overlapping style spans the markup described.
 *
 * <p>Implementations are stateless and safe to share/reuse across calls — which is what lets
 * the built-in ones be exposed as the shared singletons {@link #HTML} and {@link #MINECRAFT}
 * instead of allocating a fresh parser per call site.</p>
 *
 * <h3>Registry</h3>
 * <p>Parsers are also addressable by name ({@link #require}), so a call site that gets its
 * markup flavor from data (a config file, a resource pack, a network packet) can resolve one
 * without a compile-time reference to the concrete class. The built-ins self-register as
 * {@code "html"} and {@code "minecraft"}; third parties add their own via
 * {@link #register(String, CgMarkupParser)}. Names are case-insensitive.</p>
 */
public interface CgMarkupParser {

    /**
     * Shared lenient {@code <b>}/{@code <i>}/{@code <u>}/{@code <s>}/{@code <overline>}/
     * {@code <color=#RRGGBB>} parser. Registered as {@code "html"}.
     */
    CgMarkupParser HTML = Registry.register("html", new CgTagMarkupParser());

    /**
     * Shared lenient Minecraft {@code §}-code parser ({@code §l} bold, {@code §o} italic,
     * {@code §n} underline, {@code §m} strikethrough, {@code §r} reset, color codes).
     * Registered as {@code "minecraft"}.
     */
    CgMarkupParser MINECRAFT = Registry.register("minecraft", new CgMinecraftColorCodeParser());

    /**
     * @param markup raw markup text
     * @return the parsed plain text + style spans
     * @throws CgMarkupParseException in strict-mode implementations, when {@code markup}
     *                                violates the parser's grammar (unmatched/unclosed tags,
     *                                unknown tag/code names, malformed attribute values)
     */
    CgStyledText parse(String markup);

    /**
     * Resolves a parser by its registered name (case-insensitive).
     *
     * @throws IllegalArgumentException if no parser is registered under {@code name}
     */
    static CgMarkupParser require(String name) {
        return Registry.require(name);
    }

    /** {@code byName} without the throw — {@code null} when unregistered. */
    static CgMarkupParser find(String name) {
        return Registry.find(name);
    }

    /**
     * Registers {@code parser} under {@code name} (case-insensitive), returning it so this can
     * be used as a field initializer — see how {@link #HTML} is declared.
     *
     * @throws IllegalArgumentException if {@code name} is already taken by a different parser
     */
    static CgMarkupParser register(String name, CgMarkupParser parser) {
        return Registry.register(name, parser);
    }

    /** Every registered name, lowercased. */
    static Set<String> registeredNames() {
        return Collections.unmodifiableSet(Registry.BY_NAME.keySet());
    }

    /**
     * Backing store for the by-name registry. A nested class rather than interface fields
     * because the map must be initialized before {@link #HTML}/{@link #MINECRAFT} run their
     * own initializers, and interface field initialization order can't express that.
     */
    final class Registry {

        private static final Map<String, CgMarkupParser> BY_NAME = new ConcurrentHashMap<>();

        private Registry() {}

        static CgMarkupParser register(String name, CgMarkupParser parser) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("name must not be null/empty");
            }
            if (parser == null) {
                throw new IllegalArgumentException("parser must not be null");
            }
            String key = name.toLowerCase(Locale.ROOT);
            CgMarkupParser existing = BY_NAME.putIfAbsent(key, parser);
            if (existing != null && existing != parser) {
                throw new IllegalArgumentException("Markup parser name already registered: " + key);
            }
            return parser;
        }

        static CgMarkupParser find(String name) {
            if (name == null) return null;
            ensureBuiltinsLoaded();
            return BY_NAME.get(name.toLowerCase(Locale.ROOT));
        }

        static CgMarkupParser require(String name) {
            CgMarkupParser parser = find(name);
            if (parser == null) {
                throw new IllegalArgumentException(
                        "No markup parser registered under name '" + name + "'. Registered: " + BY_NAME.keySet());
            }
            return parser;
        }

        /**
         * Touches {@link CgMarkupParser#HTML} so the interface's field initializers (which are
         * what self-register the built-ins) have definitely run before a by-name lookup. Without
         * this, resolving {@code "html"} purely by string — never having referenced the
         * constant — could miss it, since an interface isn't initialized until one of its
         * fields is actually read.
         */
        private static void ensureBuiltinsLoaded() {
            CgMarkupParser ignored = CgMarkupParser.HTML;
        }
    }
}
