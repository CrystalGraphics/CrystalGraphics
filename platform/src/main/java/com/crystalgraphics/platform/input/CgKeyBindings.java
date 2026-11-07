package com.crystalgraphics.platform.input;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keys a player can rebind: declared once, in the engine's key codes, and listed in the game's controls on every
 * version. A press while no screen is up runs the binding's action on the client tick.
 *
 * <pre>{@code
 * // a client listener's onPreInit: before any loader's key registration
 * static final CgKeyBindings.Binding MAP = CgKeyBindings.declare("mymod:map", CgKeyCodes.KEY_M, "mymod:keys", Map::open);
 * }</pre>
 *
 * <p>Its name and category are translation keys made from the ids: {@code mymod:map} reads {@code key.mymod.map} and
 * the category {@code mymod:keys} reads {@code key.category.mymod.keys}, so one language entry serves every version.</p>
 *
 * <ul>
 *   <li><b>Declare early</b>, from a {@code client} listener's {@code onPreInit}: Forge and NeoForge take keys only in
 *       their registration event, and a binding declared after it never appears (logged).</li>
 *   <li>An id is declared once; a second declaration throws.</li>
 *   <li>The action runs on the client thread, only while no screen is up, as the game's own key mappings do.</li>
 * </ul>
 */
public final class CgKeyBindings {

    /** What a host answers: it registers each binding with its game as one is declared. One class per era. */
    public interface Host {
        /**
         * {@code binding} was declared: register it with the game, now or in the loader's registration window, and run
         * {@link Binding#press} for each press. Bindings declared before the host was provided arrive here too.
         */
        void declared(Binding binding);

        Host NONE = binding -> { };
    }

    /** One key a player can rebind. */
    public static final class Binding {
        private final String id;
        private final int defaultKey;
        private final String category;
        private final Runnable action;

        private Binding(String id, int defaultKey, String category, Runnable action) {
            this.id = id;
            this.defaultKey = defaultKey;
            this.category = category;
            this.action = action;
        }

        /** {@code namespace:path}. */
        public String id() {
            return id;
        }

        /** The key it starts on, a {@link CgKeyCodes} code. */
        public int defaultKey() {
            return defaultKey;
        }

        /** Its category's id, {@code namespace:path}. */
        public String category() {
            return category;
        }

        /** {@code key.<namespace>.<path>}: what the controls list shows it as. */
        public String nameKey() {
            return "key." + id.replace(':', '.');
        }

        /** {@code key.category.<namespace>.<path>}: its category's heading. */
        public String categoryKey() {
            return "key.category." + category.replace(':', '.');
        }

        /** Runs its action: a host, once per press. */
        public void press() {
            action.run();
        }
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:key_bindings", Host.NONE);

    private static final List<Binding> ALL = new CopyOnWriteArrayList<>();
    private static Host announcedTo;

    private CgKeyBindings() {
    }

    /**
     * Declares a binding.
     *
     * @param id         {@code namespace:path}, unique
     * @param defaultKey a {@link CgKeyCodes} code
     * @param category   {@code namespace:path} of its category
     * @param action     what a press does, on the client thread
     */
    public static synchronized Binding declare(String id, int defaultKey, String category, Runnable action) {
        for (Binding b : ALL) {
            if (b.id.equals(id)) throw new IllegalStateException("Key binding '" + id + "' is already declared");
        }
        Binding binding = new Binding(id, defaultKey, category, action);
        Host host = CgPlatform.get(HOST);
        catchUp(host);
        ALL.add(binding);
        if (host != Host.NONE) host.declared(binding);
        return binding;
    }

    /** Every binding declared so far, in declaration order. */
    public static List<Binding> all() {
        return Collections.unmodifiableList(ALL);
    }

    /**
     * Hands the host what was declared before it was provided. A host calls it once it is provided, from its install.
     */
    public static synchronized void hostProvided() {
        catchUp(CgPlatform.get(HOST));
    }

    // Each binding reaches a host exactly once: the ones before it all at once, the rest as they are declared
    private static void catchUp(Host host) {
        if (host == Host.NONE || host == announcedTo) return;
        announcedTo = host;
        for (Binding b : ALL) host.declared(b);
    }
}
