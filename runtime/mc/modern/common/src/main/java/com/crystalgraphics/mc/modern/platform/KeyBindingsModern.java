package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.platform.input.CgKeyBindings;

import net.minecraft.client.KeyMapping;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@link CgKeyBindings} on 1.13-26.3: each binding a vanilla {@code KeyMapping}, registered in the loader's window and
 * pressed on the client tick.
 *
 * <pre>{@code
 * KeyBindingsModern.INSTANCE.register(event::register, false);                // Forge, NeoForge: their key event
 * KeyBindingsModern.INSTANCE.register(KeyBindingHelper::registerKeyBinding, true);   // Fabric: client init, stays open
 * }</pre>
 */
public final class KeyBindingsModern implements CgKeyBindings.Host {

    public static final KeyBindingsModern INSTANCE = new KeyBindingsModern();

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private final Map<CgKeyBindings.Binding, KeyMapping> mappings = new LinkedHashMap<>();
    //? if >=1.21.9 {
    /*private final Map<String, KeyMapping.Category> categories = new HashMap<>();
    *///?}
    private Consumer<KeyMapping> registrar;
    private boolean windowClosed;

    private KeyBindingsModern() {
    }

    @Override
    public synchronized void declared(CgKeyBindings.Binding binding) {
        KeyMapping mapping = new KeyMapping(binding.nameKey(), HostKeys.key(binding.defaultKey()), category(binding));
        mappings.put(binding, mapping);
        if (registrar != null) registrar.accept(mapping);
        else if (windowClosed) {
            LOGGER.warn("[cg] key binding '{}' was declared after the loader registered keys: it is not in the controls",
                    binding.id());
        }
    }

    /**
     * The loader's registration window: every mapping declared so far goes to {@code registrar}, and with
     * {@code staysOpen} each later one as it is declared.
     */
    public synchronized void register(Consumer<KeyMapping> registrar, boolean staysOpen) {
        mappings.values().forEach(registrar);
        this.registrar = staysOpen ? registrar : null;
        windowClosed = !staysOpen;
    }

    /** Runs each binding once per press. Client tick; presses count only with no screen up, as vanilla's do. */
    public void tick() {
        List<Map.Entry<CgKeyBindings.Binding, KeyMapping>> all;
        synchronized (this) {
            all = new ArrayList<>(mappings.entrySet());
        }
        for (Map.Entry<CgKeyBindings.Binding, KeyMapping> entry : all) {
            while (entry.getValue().consumeClick()) entry.getKey().press();
        }
    }

    // 1.21.9 made a category a registered id, registered once
    //? if >=1.21.9 {
    /*private KeyMapping.Category category(CgKeyBindings.Binding binding) {
        return categories.computeIfAbsent(binding.category(),
                id -> KeyMapping.Category.register(ResourceIds.parse(id)));
    }
    *///?} else {
    private String category(CgKeyBindings.Binding binding) {
        return binding.categoryKey();
    }
    //?}
}
