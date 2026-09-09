package com.crystalgraphics.mixins.early;

import com.crystalgraphics.mc.shared.LoaderProbe;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The mixin config plugin for the 1.7.10 variant, and the gate that lets its config travel in a jar
 * that also carries other loaders' mixins.
 *
 * <p>A single jar's 1.7.10 config is named in the manifest, which is the only channel FML 1.7.10
 * reads — and ModLauncher reads it too, so Forge and NeoForge see a config that is not theirs. Mixin
 * asks {@link #shouldApplyMixin} before it looks a target class up, so a mixin belonging to another
 * variant is never resolved, never linked, and never fails.</p>
 *
 * <p>Replaces {@code CrystalGraphicsEarlyMixins}, whose side test lives here now beside the loader
 * test: the render hooks are contributed by {@link #getMixins()} rather than listed in the config,
 * which is where that class put them.</p>
 *
 * <h3>Why the ClassNode here is the SHADED one</h3>
 *
 * <p>{@code org.spongepowered.asm.lib.tree.ClassNode}, not {@code org.objectweb.asm.tree.ClassNode}.
 * A plugin compiled against vanilla Mixin's modern spelling <i>loads</i> under UniMixins — it is even
 * transformed to fit the environment — and then dies the first time a mixin is applied, with a
 * {@code CompanionPluginError} naming a copy constructor the shaded ASM does not have. So a 1.7.10
 * config's plugin is compiled against the Mixin this loader actually runs. What it shares with a
 * future 1.13+ plugin is {@link LoaderProbe}, which is where the loader question is answered once.</p>
 */
public final class CrystalGraphicsMixins implements IMixinConfigPlugin {

    /**
     * Which loader each mixin package belongs to.
     *
     * <p>Data rather than an {@code if}, so a variant is added by adding a row. J4 replaces this
     * table's <i>source</i> with the {@code variants.json} the merge writes; the lookup stays.</p>
     */
    private static final Map<String, String> OWNERS = new HashMap<String, String>();

    /** What 1.7.10 contributes on a client, by name under the config's own package. */
    private static final String[] CLIENT_MIXINS = {
            "client.MixinRenderGlobal", "client.MixinMinecraft", "client.CgRenderHook",
    };

    static {
        OWNERS.put("com.crystalgraphics.mixins.early.impl", LoaderProbe.FML1710);
    }

    /** The package this config declared, from {@link #onLoad}. */
    private String mixinPackage = "";

    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage == null ? "" : mixinPackage;
        System.out.println("[crystalgraphics] mixin config " + this.mixinPackage
                + ": loader is " + LoaderProbe.describe()
                + ", this config belongs to " + OWNERS.get(this.mixinPackage));
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return ownsThisConfig();
    }

    @Override
    public List<String> getMixins() {
        List<String> mixins = new ArrayList<String>();
        if (ownsThisConfig() && isClient()) {
            Collections.addAll(mixins, CLIENT_MIXINS);
        }
        return mixins;
    }

    private boolean ownsThisConfig() {
        String owner = OWNERS.get(mixinPackage);
        return owner != null && owner.equals(LoaderProbe.current());
    }

    private static boolean isClient() {
        try {
            return MixinEnvironment.getCurrentEnvironment().getSide() == MixinEnvironment.Side.CLIENT;
        } catch (Throwable noEnvironment) {
            // Asked before an environment exists, which is a dedicated server's shape on some hosts.
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
