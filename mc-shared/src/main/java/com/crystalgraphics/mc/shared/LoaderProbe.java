package com.crystalgraphics.mc.shared;

import org.spongepowered.asm.service.MixinService;

/**
 * Which loader this process is.
 *
 * <p>Asked, never fingerprinted. Mixin's own service is definitional — LaunchWrapper on 1.7.10 and
 * 1.12, ModLauncher on Forge and NeoForge, Knot on Fabric — and the one split it does not answer,
 * Forge against NeoForge, is decided by a class that <i>defines</i> a loader rather than one that
 * happens to be on the classpath.</p>
 *
 * <pre>
 * if (LoaderProbe.current() == LoaderProbe.FML1710) { ... }
 * </pre>
 *
 * <p>Resolved once, in a class initializer, and logged with the path that decided it. Nothing here
 * loads a class: a probe calls {@code getResource}, so a variant for another loader is never linked
 * merely by being asked about.</p>
 *
 * <p>{@link #current()} is null when nothing recognised the host, which is a real answer and not a
 * failure — a caller decides whether that is fatal. It is what a bootstrapper turns into a message
 * naming the versions it does support.</p>
 */
public final class LoaderProbe {

    public static final String FABRIC = "fabric";
    public static final String NEOFORGE = "neoforge";
    public static final String FORGE = "forge";
    public static final String FML1710 = "fml1710";
    public static final String FML1122 = "fml1122";

    private static final String LOADER;
    private static final String HOW;

    static {
        String loader = null;
        String how = "mixin service";
        String service = serviceName();
        if (service != null) {
            if (service.contains("Knot")) {
                loader = FABRIC;
            } else if (service.contains("LaunchWrapper")) {
                loader = has("cpw/mods/fml/common/Mod.class") ? FML1710 : FML1122;
            } else if (service.contains("ModLauncher")) {
                loader = has("net/neoforged/fml/loading/FMLLoader.class") ? NEOFORGE : FORGE;
            }
        }
        if (loader == null) {
            // NeoForge is asked BEFORE Forge: 20.2-20.3 still carried net.minecraftforge compatibility
            // packages, so the Forge probe answers yes there too.
            how = "classpath probe";
            if (has("net/fabricmc/loader/api/FabricLoader.class")) loader = FABRIC;
            else if (has("net/neoforged/fml/common/Mod.class")) loader = NEOFORGE;
            else if (has("net/minecraftforge/fml/common/Mod.class")) loader = FORGE;
            else if (has("cpw/mods/fml/common/Mod.class")) loader = FML1710;
        }
        LOADER = loader;
        HOW = loader == null ? "nothing recognised" : how;
    }

    private LoaderProbe() {
    }

    /** The loader, or null when nothing recognised it. */
    public static String current() {
        return LOADER;
    }

    /** How it was decided — "mixin service", "classpath probe", or "nothing recognised". */
    public static String how() {
        return HOW;
    }

    /** One line, for a log: {@code fml1710 (by mixin service)}. */
    public static String describe() {
        return (LOADER == null ? "unknown" : LOADER) + " (by " + HOW + ")";
    }

    private static String serviceName() {
        try {
            return MixinService.getService().getName();
        } catch (Throwable noService) {
            // A host with no Mixin at all is a legitimate one -- a test JVM, the GL harness -- and the
            // probes below answer it. Never fatal here.
            return null;
        }
    }

    private static boolean has(String resource) {
        ClassLoader loader = LoaderProbe.class.getClassLoader();
        return loader != null && loader.getResource(resource) != null;
    }
}
