package com.crystalgraphics.mc.shared;

/**
 * Everything a Forge {@code @Mod} bootstrapper does, for whichever Forge constructed it: modern Forge
 * (1.13+, ModLauncher) or legacy FML (1.8–1.12.2, LaunchWrapper). Both scan for the same annotation,
 * {@code net.minecraftforge.fml.common.Mod}, so one class per mod serves both, and this is its body.
 *
 * <pre>
 * &#64;Mod(value = "mymod", modid = "mymod")          // both eras' elements; see forge-stubs
 * public final class ForgeBootstrap {
 *     private final FmlEvents legacy = ForgeStart.start(ForgeBootstrap.class, "mymod");
 *
 *     &#64;Mod.EventHandler public void preInit(FMLPreInitializationEvent e) { ForgeStart.fire(legacy, e); }
 *     // ... one per legacy event a variant listens for
 * }
 * </pre>
 *
 * - Names no loader type: everything is read reflectively, so the class is compiled once and each era
 *   links only what it runs.
 * - On legacy FML the lifecycle events reach the {@code @Mod} instance alone; the bootstrapper forwards
 *   them to the {@link FmlEvents} this returns, which is its variant's context. On modern Forge it
 *   returns null and the variant gets {@code FMLJavaModLoadingContext}, as before.
 */
public final class ForgeStart {

    private ForgeStart() {
    }

    /** Selects and constructs {@code modId}'s variant; the legacy event sink, or null on modern Forge. */
    public static FmlEvents start(Class<?> owner, String modId) {
        ClassLoader loader = owner.getClassLoader();
        try {
            if (LoaderProbe.FML1122.equals(LoaderProbe.current())) {
                String minecraft = FmlVersion.of(Class.forName("net.minecraftforge.common.ForgeVersion", false, loader));
                FmlEvents events = new FmlEvents();
                VariantBootstrap.startCommon(owner, modId, LoaderProbe.FML1122, minecraft, events);
                if (legacyClient(loader)) VariantBootstrap.startClient(owner, modId, LoaderProbe.FML1122, minecraft, events);
                return events;
            }
            Class<?> fmlLoader = Class.forName("net.minecraftforge.fml.loading.FMLLoader", false, loader);
            String minecraft = FmlVersion.of(fmlLoader);
            Object context = Class.forName("net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext", true, loader)
                    .getMethod("get").invoke(null);
            VariantBootstrap.startCommon(owner, modId, LoaderProbe.FORGE, minecraft, context);
            if (FmlSide.isClient(fmlLoader)) VariantBootstrap.startClient(owner, modId, LoaderProbe.FORGE, minecraft, context);
            return null;
        } catch (ReflectiveOperationException e) {
            throw new UnsupportedVariant(modId + " could not ask this Forge for its version: " + e);
        }
    }

    /** Forwards a legacy lifecycle event; nothing on modern Forge, which never calls an {@code @EventHandler}. */
    public static void fire(FmlEvents events, Object event) {
        if (events != null) events.fire(event);
    }

    private static boolean legacyClient(ClassLoader loader) throws ReflectiveOperationException {
        Object handler = Class.forName("net.minecraftforge.fml.common.FMLCommonHandler", true, loader)
                .getMethod("instance").invoke(null);
        return "CLIENT".equals(String.valueOf(handler.getClass().getMethod("getSide").invoke(handler)));
    }
}
