package com.crystalgraphics.asset.load;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.asset.kind.CgCameraKinds;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;

/** Every loaded mod's assets mounted: from its jar or its folder, the dev roots first, only namespaces of the format. */
public class CgAssetMountsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SHAKE = "_v: 1\n";

    private Path write(Path root, String rel, String text) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private Path jar(String name, String... entries) throws IOException {
        Path jar = tmp.getRoot().toPath().resolve(name);
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String e : entries) {
                zip.putNextEntry(new ZipEntry(e));
                zip.write(SHAKE.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    @Test
    public void aModsJarIsOpenedAndItsNamespaceMounted() throws IOException {
        Path jar = jar("rpgcore.jar", "assets/rpgcore/cameras/shakes/ground_break.yaml", "META-INF/MANIFEST.MF");
        CgAssetRegistry r = new CgAssetRegistry();
        CgAssetMounts.Result mounted = CgAssetMounts.mount(r, List.of(), List.of(jar));
        assertEquals(List.of("rpgcore"), mounted.mounted());
        assertEquals(1, mounted.opened());
        assertEquals(CgAssetSource.JAR, r.entry(CgCameraKinds.SHAKE, "rpgcore:ground_break").source());
    }

    @Test
    public void aMinecraftNamespaceOfTexturesAndModelsIsNotMounted() throws IOException {
        Path mod = tmp.newFolder("blocks").toPath();
        write(mod, "assets/blocks/textures/block/stone.png", "png");
        write(mod, "assets/blocks/models/block/stone.json", "{}");
        write(mod, "assets/blocks/lang/en_us.json", "{}");
        CgAssetRegistry r = new CgAssetRegistry();
        assertEquals(List.of(), CgAssetMounts.mount(r, List.of(), List.of(mod)).mounted());
        assertTrue(r.entries("blocks").isEmpty());
    }

    @Test
    public void aDevRootWinsOverTheSameNamespacesJar() throws IOException {
        Path dev = tmp.newFolder("src").toPath();
        write(dev, "assets/dragonforge/cameras/shakes/slide.yaml", SHAKE);
        Path jar = jar("dragonforge.jar", "assets/dragonforge/cameras/shakes/slide.yaml");
        CgAssetRegistry r = new CgAssetRegistry();
        CgAssetMounts.Result mounted = CgAssetMounts.mount(r, List.of(dev), List.of(jar));
        assertEquals(List.of("dragonforge"), mounted.mounted());
        assertNotNull(r.entry(CgCameraKinds.SHAKE, "dragonforge:slide"));
        assertEquals("edits in the source folder reload", CgAssetSource.DEV,
                r.entry(CgCameraKinds.SHAKE, "dragonforge:slide").source());
    }
}
