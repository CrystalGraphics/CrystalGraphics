package com.crystalgraphics.mc.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Legacy FML's lifecycle events (1.8–1.12.2), handed to a variant. That FML delivers them only to the
 * {@code @Mod} instance — the bootstrapper — so the bootstrapper forwards each one here, and this is the
 * context a legacy variant's {@link VariantEntry#start} receives.
 *
 * <pre>
 * public void start(Object context) {
 *     FmlEvents events = (FmlEvents) context;
 *     events.on("FMLPreInitializationEvent", event -&gt; preInit(event));
 *     events.on("FMLServerStartingEvent", event -&gt; registerCommands(event));
 * }
 * </pre>
 *
 * - Matched by the event class's SIMPLE name: this module is compiled once for every loader and can
 *   name no FML type.
 * - A handler registered after its event fired never runs; register from {@code start}, which runs
 *   while the {@code @Mod} is constructed, before any event.
 */
public final class FmlEvents {

    private final List<String> names = new ArrayList<String>();
    private final List<Consumer<Object>> handlers = new ArrayList<Consumer<Object>>();

    /** Runs {@code handler} for every event whose class's simple name is {@code eventName}. */
    public void on(String eventName, Consumer<Object> handler) {
        names.add(eventName);
        handlers.add(handler);
    }

    /** Called by the bootstrapper's {@code @Mod.EventHandler} methods. */
    public void fire(Object event) {
        String name = event.getClass().getSimpleName();
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(name)) {
                handlers.get(i).accept(event);
            }
        }
    }
}
