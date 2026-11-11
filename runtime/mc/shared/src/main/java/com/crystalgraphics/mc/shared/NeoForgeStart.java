package com.crystalgraphics.mc.shared;

/**
 * Everything a NeoForge {@code @Mod} bootstrapper does, as {@link ForgeStart} is for Forge: selects the running
 * version's variant from the jar's table, which refuses one the jar does not support, and constructs its entries.
 * Everything is read reflectively, so the bootstrapper is compiled once, at Java 8, for every NeoForge.
 *
 * <pre>
 * &#64;Mod("mymod")
 * public final class MyModNeoForge {
 *     public MyModNeoForge(IEventBus modBus) {
 *         NeoForgeStart.start(MyModNeoForge.class, "mymod", modBus);
 *     }
 * }
 * </pre>
 *
 * - The entries receive {@code modBus} as their context.
 * - Pass the bootstrapper's own class: its loader is the one that sees the jar's variant table.
 */
public final class NeoForgeStart {

    private NeoForgeStart() {
    }

    /** Selects and constructs {@code modId}'s variant: its common entry, then on a client its client entry. */
    public static void start(Class<?> owner, String modId, Object modBus) {
        Class<?> fmlLoader;
        try {
            fmlLoader = Class.forName("net.neoforged.fml.loading.FMLLoader", false, owner.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new UnsupportedVariant(modId + " could not ask this NeoForge for its version: " + e);
        }
        String minecraft = FmlVersion.of(fmlLoader);
        VariantBootstrap.startCommon(owner, modId, LoaderProbe.NEOFORGE, minecraft, modBus);
        if (FmlSide.isClient(fmlLoader)) VariantBootstrap.startClient(owner, modId, LoaderProbe.NEOFORGE, minecraft, modBus);
    }
}
