package com.crystalgraphics.mc.shared;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The half of a bootstrapper that names no loader: choose the variant, say which one, construct it.
 *
 * <p>Each loader family has a tiny class of its own that knows how to ask that loader for its
 * Minecraft version and its side — that is all a family-specific bootstrapper does — and then calls
 * one of these.</p>
 *
 * <pre>
 * // Fabric, whose two entry points fire separately
 * public void onInitialize()       { VariantBootstrap.startCommon(FabricBootstrap.class, MOD_ID, "fabric", version(), null); }
 * public void onInitializeClient() { VariantBootstrap.startClient(FabricBootstrap.class, MOD_ID, "fabric", version(), null); }
 *
 * // Forge, whose one constructor fires once and knows the side
 * VariantBootstrap.startCommon(ForgeBootstrap.class, MOD_ID, "forge", version, context);
 * if (client) VariantBootstrap.startClient(ForgeBootstrap.class, MOD_ID, "forge", version, context);
 * </pre>
 *
 * <p>The first argument is the <b>calling bootstrapper's own class</b>, and it is not decoration:
 * its class loader is the mod's, which is the only one that can see both the variant table and the
 * entry classes. This module is a library on a parent layer and its own loader sees neither.</p>
 *
 * <p>The variant is resolved once per (mod, loader) and the choice is logged once. Everything here
 * throws {@link UnsupportedVariant} rather than returning null or logging a warning: a mod that
 * quietly registers nothing is the exact failure this mechanism exists to prevent, and it is
 * invisible until someone opens a screen that is not there.</p>
 */
public final class VariantBootstrap {

    private static final Logger LOGGER = LogManager.getLogger("VariantBootstrap");

    /** Resolved once per (mod, loader); the log line is printed with the first resolution. */
    private static final Map<String, Variant> CHOSEN = new ConcurrentHashMap<String, Variant>();

    private VariantBootstrap() {
    }

    /** The variant this loader and version select, resolving and logging it on first ask. */
    public static Variant variant(Class<?> owner, String modId, String loader, String minecraftVersion) {
        String key = modId + "/" + loader;
        Variant known = CHOSEN.get(key);
        if (known != null) {
            return known;
        }
        Variant chosen = Variants.load(modId, owner.getClassLoader()).select(loader, minecraftVersion);
        Variant raced = CHOSEN.putIfAbsent(key, chosen);
        if (raced != null) {
            return raced;
        }
        LOGGER.info("[{}] variant {} for Minecraft {}", modId, chosen, minecraftVersion);
        return chosen;
    }

    /** Constructs the entry this variant names for both sides. No-ops where the loader has none. */
    public static void startCommon(Class<?> owner, String modId, String loader,
                                   String minecraftVersion, Object context) {
        construct(owner, modId, variant(owner, modId, loader, minecraftVersion).commonEntry(), context);
    }

    /** Constructs the client-only entry. Call on a client; never on a dedicated server. */
    public static void startClient(Class<?> owner, String modId, String loader,
                                   String minecraftVersion, Object context) {
        construct(owner, modId, variant(owner, modId, loader, minecraftVersion).clientEntry(), context);
    }

    private static void construct(Class<?> owner, String modId, String className, Object context) {
        if (className == null) {
            return;
        }
        Object entry;
        try {
            // THE OWNER'S LOADER, never this class's: on ModLauncher this module is a library on the
            // parent layer and the entry classes are the mod's, on the child layer.
            Class<?> type = Class.forName(className, true, owner.getClassLoader());
            entry = type.newInstance();
        } catch (ClassNotFoundException e) {
            throw new UnsupportedVariant(modId + "'s variant table names " + className
                    + ", which is not in the jar — the descriptors and the classes disagree");
        } catch (Exception e) {
            throw new UnsupportedVariant(modId + " could not construct " + className
                    + "; a variant entry needs a public no-argument constructor (" + e + ")");
        }
        if (!(entry instanceof VariantEntry)) {
            throw new UnsupportedVariant(className + " is named by " + modId
                    + "'s variant table but does not implement VariantEntry");
        }
        ((VariantEntry) entry).start(context);
    }
}
