package com.crystalgraphics.tag;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.crystalgraphics.asset.registry.CgAssetEntry;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Every kit's scan declares the tags its assets own, and each tag matches every ancestor.
 *
 * <p>Runs over {@code -Dcrystalgraphics.kits=<namespace>=<dir>;...}, which {@code -Pkits=...} hands the build; skipped
 * without it, since the kits are private.</p>
 */
public class CgTagKitsGateTest {

    private static final Map<String, String> KITS = new LinkedHashMap<>();

    @BeforeClass
    public static void kits() {
        String kits = System.getProperty("crystalgraphics.kits");
        Assume.assumeTrue("no -Pkits: the tag gate did not run", kits != null && !kits.isEmpty());
        for (String kit : kits.split(";")) {
            String[] nd = kit.split("=", 2);
            KITS.put(nd[0], nd[1]);
        }
    }

    @Test
    public void everyKitsDeclaredTagsLoadAndMatchTheirAncestors() {
        CgAssetRegistry assets = new CgAssetRegistry();
        for (Map.Entry<String, String> kit : KITS.entrySet()) assets.scan(kit.getKey(), Paths.get(kit.getValue()), CgAssetSource.DEV);
        CgTagRegistry registry = CgTagRegistry.global();
        int declared = 0;
        for (String ns : KITS.keySet()) {
            for (CgAssetEntry e : assets.entries(ns)) {
                for (String t : e.tags()) {
                    declared++;
                    assertNotNull(e.id() + " declares " + t + ", which the scan did not register", registry.find(t));
                }
            }
        }
        List<String> bad = new ArrayList<>();
        int checked = 0;
        for (CgGameplayTag tag : registry.all()) {
            CgGameplayTag at = tag;
            while (at.parent() != null) {
                at = at.parent();
                checked++;
                if (!tag.matches(at) || at.matches(tag)) bad.add(tag + " vs " + at);
            }
            if (!tag.matches(tag)) bad.add(tag + " does not match itself");
            if (!tag.name().startsWith(tag.namespace() + ":")) bad.add(tag.name());
        }
        System.out.println("[tags gate] " + KITS.size() + " kits, " + declared + " declared, " + registry.size()
                + " tags, " + checked + " ancestor links");
        assertEquals(bad.toString(), 0, bad.size());
        CgTagContainer owned = new CgTagContainer();
        for (CgGameplayTag tag : registry.all()) if (tag.explicit()) owned.add(tag);
        for (CgGameplayTag tag : registry.all()) assertTrue(tag.name(), owned.has(tag));
    }
}
