package com.crystalgraphics.vfx.system;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialDefinitions;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRenderers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** A system built in Java, exported as the files that load it, and loaded back. */
public class CgVfxSystemExportTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Map<?, ?> read(Path file) throws IOException {
        return (Map<?, ?>) CgAssetYaml.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    public void aSystemBuiltInJavaExportsItsFileAndItsMadeMaterialsWhichLoadBackToTheSameSystem() throws IOException {
        CgVfxEmitter sparks = CgVfxEmitter.builder("sparks").capacity(40).burst(0f, 20)
                .renderer(CgVfxRenderers.ribbon("trail").shader("crystalgraphics:shaders/vfx/trail/glow.shader")
                        .material(b -> b.value("_Fade", 0.5f)).build("test:spark"))
                .build();
        CgVfxLook look = CgVfxLook.builder(CgVfxSchema.of("test:spark")).with(CgVfxKit.of("test:spark").emitter(sparks).build())
                .build();
        CgVfxSystemDefinition spark = CgVfxSystemDefinition.of("test:spark", look);
        Path root = tmp.getRoot().toPath();

        List<Path> files = CgVfxSystemAssets.export(spark, root, null);
        assertEquals(root.resolve("assets/test/vfx/systems/spark.yaml"), files.get(0));
        String materialId = spark.emitters().get(0).renderers().get(0).common().definition().id();
        Path materialFile = CgAssetYaml.file(root, CgLookKinds.MATERIAL, materialId);
        assertEquals(List.of(files.get(0), materialFile), files);

        CgMaterialDefinition material = CgMaterialDefinitions.compile(
                CgAssetData.of(materialId, CgLookKinds.MATERIAL, read(materialFile), null));
        assertArrayEquals(new float[] {0.5f}, material.constants().get("_Fade"), 0f);
        CgVfxSystemDefinition back = CgVfxSystemAssets.compile(
                CgAssetData.of("test:spark", CgParticleKinds.SYSTEM, read(files.get(0)), null),
                (kind, id) -> id.equals(materialId) ? material : null);
        assertEquals("loaded back, it writes the tree it was exported from",
                CgVfxSystemAssets.write(spark), CgVfxSystemAssets.write(back));
        assertEquals("its renderer draws the exported material", material,
                back.emitters().get(0).renderers().get(0).common().definition());

        CgAssetRegistry known = new CgAssetRegistry();
        known.scanResources(root.toFile());
        assertEquals("a material with a file already is named, not written again",
                1, CgVfxSystemAssets.export(spark, tmp.newFolder("again").toPath(), known).size());
    }
}
