package com.crystalgraphics.tag;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Every kit's {@code tags.yaml} loads, and each tag matches every ancestor.
 *
 * <p>Runs over {@code -Dcrystalgraphics.kits=<namespace>=<dir>;...}, which {@code -Pkits=...} hands the build; skipped
 * without it, since the kits are private.</p>
 */
public class CgTagKitsGateTest {

    private static final Map<String, Path> KITS = new LinkedHashMap<>();

    @BeforeClass
    public static void kits() {
        String kits = System.getProperty("crystalgraphics.kits");
        Assume.assumeTrue("no -Pkits: the tag gate did not run", kits != null && !kits.isEmpty());
        for (String kit : kits.split(";")) {
            String[] nd = kit.split("=", 2);
            KITS.put(nd[0], Paths.get(nd[1]));
        }
    }

    @Test
    public void everyKitsTagsLoadAndMatchTheirAncestors() {
        CgTagRegistry registry = new CgTagRegistry();
        int files = 0;
        for (Map.Entry<String, Path> kit : KITS.entrySet()) {
            if (Files.isRegularFile(kit.getValue().resolve("tags.yaml"))) files++;
            registry.registerKit(kit.getKey(), kit.getValue());
        }
        assertTrue("some kit declares tags", registry.size() > 0);
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
        System.out.println("[tags gate] " + KITS.size() + " kits, " + files + " tags.yaml, " + registry.size()
                + " tags, " + checked + " ancestor links");
        assertEquals(bad.toString(), 0, bad.size());
        CgTagContainer owned = new CgTagContainer();
        for (CgGameplayTag tag : registry.all()) if (tag.explicit()) owned.add(tag);
        for (CgGameplayTag tag : registry.all()) assertTrue(tag.name(), owned.has(tag));
    }

    @Test
    public void aKitWithoutATagsFileDeclaresNothing() {
        CgTagRegistry registry = new CgTagRegistry();
        registry.registerKit("none", Paths.get("does", "not", "exist"));
        assertEquals(0, registry.size());
    }
}
