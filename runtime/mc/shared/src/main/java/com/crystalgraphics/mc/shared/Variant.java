package com.crystalgraphics.mc.shared;

import java.util.Collections;
import java.util.List;

/**
 * One row of {@code variants.json}: a loader, the Minecraft versions it covers, and the classes to
 * construct there.
 *
 * <pre>
 * Variant v = Variants.load("crystalgui").select("fabric", "1.20.4");
 * v.commonEntry();   // constructed on both sides
 * v.clientEntry();   // constructed on clients only, or null
 * </pre>
 *
 * <p>The entry names are the <b>relocated</b> ones — the build knows the relocation because it knows
 * the target — so they are what {@code Class.forName} wants and never what the source spells.</p>
 */
public final class Variant {

    private final String loader;
    private final String minecraft;
    private final VersionRange range;
    private final String era;
    private final String commonEntry;
    private final String clientEntry;
    private final List<String> mixinConfigs;

    Variant(String loader, String minecraft, String era,
            String commonEntry, String clientEntry, List<String> mixinConfigs) {
        this.loader = loader;
        this.minecraft = minecraft;
        this.range = VersionRange.parse(minecraft);
        this.era = era;
        this.commonEntry = commonEntry;
        this.clientEntry = clientEntry;
        this.mixinConfigs = Collections.unmodifiableList(mixinConfigs);
    }

    /** One of {@code fabric}, {@code forge}, {@code neoforge}, {@code fml1710}, {@code fml1122}. */
    public String loader() {
        return loader;
    }

    /** The range as written, for a message; {@link #covers} is what decides. */
    public String minecraft() {
        return minecraft;
    }

    public boolean covers(String minecraftVersion) {
        return range.contains(minecraftVersion);
    }

    /** Which source tree built it — documentation, and the word the boot log prints. */
    public String era() {
        return era;
    }

    /** The class constructed on both sides, or null where the loader has none. */
    public String commonEntry() {
        return commonEntry;
    }

    /** The class constructed on clients only, or null. */
    public String clientEntry() {
        return clientEntry;
    }

    /** Mixin configs this variant owns — what the config plugin gates on. */
    public List<String> mixinConfigs() {
        return mixinConfigs;
    }

    @Override
    public String toString() {
        return era + "/" + loader + " " + minecraft;
    }
}
