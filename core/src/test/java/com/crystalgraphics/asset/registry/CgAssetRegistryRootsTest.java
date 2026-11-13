package com.crystalgraphics.asset.registry;

import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRenderers;
import com.crystalgraphics.vfx.system.CgVfxSystemAssets;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class CgAssetRegistryRootsTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aFolderReachedThroughALink_isScannedOnce() throws IOException {
        Path root = tmp.newFolder("kit").toPath();
        CgVfxEmitter sparks = CgVfxEmitter.builder("sparks").capacity(4).burst(0f, 2)
                .renderer(CgVfxRenderers.ribbon("trail").shader("crystalgraphics:shaders/vfx/trail/glow.shader")
                        .build("test:spark"))
                .build();
        CgVfxLook look = CgVfxLook.builder(CgVfxSchema.of("test:spark")).with(CgVfxKit.of("test:spark").emitter(sparks).build())
                .build();
        CgVfxSystemAssets.export(CgVfxSystemDefinition.of("test:spark", look), root, null);
        Path folder = root.resolve("assets/test");
        Path link = tmp.getRoot().toPath().resolve("linked");
        try {
            Files.createSymbolicLink(link, folder);
        } catch (IOException | UnsupportedOperationException e) {
            Assume.assumeNoException("this machine makes no symbolic links", e);
        }

        CgAssetRegistry registry = new CgAssetRegistry();
        registry.scan("test", folder, CgAssetSource.DEV);
        int once = registry.size();
        registry.scan("test", link, CgAssetSource.DEV);   // threw "registered twice" before
        assertEquals(once, registry.size());
    }
}
