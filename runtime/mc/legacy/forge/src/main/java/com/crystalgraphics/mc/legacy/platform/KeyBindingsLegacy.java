package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.platform.input.CgKeyBindings;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CgKeyBindings} on Forge 1.8-1.12.2: each binding a {@code KeyBinding} registered as it is declared (FML takes
 * one any time), pressed on the client tick. {@code CgKeyCodes} are LWJGL 2's, so a key needs no translation.
 */
final class KeyBindingsLegacy implements CgKeyBindings.Host {

    static final KeyBindingsLegacy INSTANCE = new KeyBindingsLegacy();

    private final Map<CgKeyBindings.Binding, KeyBinding> bindings = new LinkedHashMap<>();

    private KeyBindingsLegacy() {
    }

    @Override
    public synchronized void declared(CgKeyBindings.Binding binding) {
        KeyBinding key = new KeyBinding(binding.nameKey(), binding.defaultKey(), binding.categoryKey());
        bindings.put(binding, key);
        ClientRegistry.registerKeyBinding(key);
    }

    /** Runs each binding once per press. Client tick; presses count only with no screen up. */
    void tick() {
        List<Map.Entry<CgKeyBindings.Binding, KeyBinding>> all;
        synchronized (this) {
            all = new ArrayList<>(bindings.entrySet());
        }
        for (Map.Entry<CgKeyBindings.Binding, KeyBinding> entry : all) {
            while (entry.getValue().isPressed()) entry.getKey().press();
        }
    }
}
