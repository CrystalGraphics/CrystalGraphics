package com.crystalgraphics.mc.shared;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A mixin config plugin that applies a config only on the variant whose package holds it.
 *
 * <p>A merged jar names every config in one manifest, and every Mixin that reads a manifest reads all
 * of them — 1.7.10's included. So each variant's config goes through this: it asks the variant table
 * which variant this loader and Minecraft version select, and contributes its mixins only when that
 * variant's entry class shares the config's package root.</p>
 *
 * <pre>
 * // mixins.crystalgraphics.forge.v1213.json -- the lists stay EMPTY
 * {"package": "com.crystalgraphics.mc.modern.forge.v1213.mixin",
 *  "plugin": "com.crystalgraphics.mc.shared.CrystalGraphicsForgeMixins", "mixins": [], "client": []}
 *
 * public final class CrystalGraphicsForgeMixins extends VariantMixins {
 *     public CrystalGraphicsForgeMixins() { super("crystalgraphics", "OpaquePassHook"); }
 * }
 * </pre>
 *
 * <ul>
 *   <li>Mixins come from {@link #getMixins()}, never the config's lists: Mixin parses every listed
 *       class before asking the plugin, and a Java 21 class read by 1.7.10's ASM is a crash.</li>
 *   <li>A subclass needs a public no-argument constructor; Mixin instantiates it by name.</li>
 *   <li>The client names apply on a client only. There is no server list yet.</li>
 * </ul>
 */
public abstract class VariantMixins implements IMixinConfigPlugin {

    private final String modId;
    private final String[] clientMixins;

    private boolean owned;

    /**
     * @param modId        whose {@code variants.json} to read
     * @param clientMixins mixin names relative to the config's package, applied on a client
     */
    protected VariantMixins(String modId, String... clientMixins) {
        this.modId = modId;
        this.clientMixins = clientMixins;
    }

    @Override
    public void onLoad(String mixinPackage) {
        String loader = LoaderProbe.current();
        String reason;
        try {
            String minecraft = minecraftVersion(loader);
            Variant running = Variants.load(modId, getClass().getClassLoader()).select(loader, minecraft);
            String entry = running.commonEntry() != null ? running.commonEntry() : running.clientEntry();
            owned = entry != null && mixinPackage.startsWith(packageOf(entry) + ".");
            reason = "variant " + running + " for Minecraft " + minecraft;
        } catch (RuntimeException notOurs) {
            owned = false;
            reason = notOurs.getMessage();
        }
        System.out.println("[" + modId + "] mixin config " + mixinPackage + ": "
                + (owned ? "applies" : "skipped") + " -- " + LoaderProbe.describe() + ", " + reason);
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return owned;
    }

    @Override
    public List<String> getMixins() {
        List<String> mixins = new ArrayList<String>();
        if (owned && isClient()) {
            Collections.addAll(mixins, clientMixins);
        }
        return mixins;
    }

    /** The running Minecraft version, asked of whichever loader this is. */
    private static String minecraftVersion(String loader) {
        if (LoaderProbe.FORGE.equals(loader)) {
            return FmlVersion.of(type("net.minecraftforge.fml.loading.FMLLoader"));
        }
        if (LoaderProbe.NEOFORGE.equals(loader)) {
            return FmlVersion.of(type("net.neoforged.fml.loading.FMLLoader"));
        }
        if (LoaderProbe.FABRIC.equals(loader)) {
            return fabricVersion();
        }
        if (LoaderProbe.FML1710.equals(loader)) {
            return "1.7.10";
        }
        throw new UnsupportedVariant("no Minecraft version for loader " + loader);
    }

    /** {@code FabricLoader.getInstance().getModContainer("minecraft")}'s version, by reflection. */
    private static String fabricVersion() {
        try {
            Object fabric = type("net.fabricmc.loader.api.FabricLoader").getMethod("getInstance").invoke(null);
            Optional<?> container = (Optional<?>) fabric.getClass()
                    .getMethod("getModContainer", String.class).invoke(fabric, "minecraft");
            Object metadata = call(container.get(), "getMetadata");
            return String.valueOf(call(call(metadata, "getVersion"), "getFriendlyString"));
        } catch (Exception e) {
            throw new UnsupportedVariant("could not read the Minecraft version from Fabric: " + e);
        }
    }

    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new UnsupportedVariant(name + " is not loadable here: " + e);
        }
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    private static boolean isClient() {
        try {
            return MixinEnvironment.getCurrentEnvironment().getSide() == MixinEnvironment.Side.CLIENT;
        } catch (Throwable noEnvironment) {
            // A render hook is client-only, so "not sure" means "no".
            return false;
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
    }
}
