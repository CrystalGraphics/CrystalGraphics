package com.crystalgraphics.vfx.render;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.vfx.effect.beam.CgBeamBody;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.system.CgVfxSystemAssets;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** A system's slot renderers: built in Java to its file's entry, compiled from it, placed, and refused by name. */
public class CgVfxSlotRendererTest {

    private static final String LIGHT = "crystalgraphics:shaders/vfx/beam/body_light.shader";
    private static final String SHELL = "crystalgraphics:shaders/vfx/beam/body_shell.shader";

    private static CgMaterialDefinition material(String id, String shader) {
        CgAssetKinds.register();
        return CgMaterialDefinition.builder(id, shader).build();
    }

    private static CgAssetCompiler.Dependencies deps(CgMaterialDefinition... materials) {
        return (kind, id) -> {
            for (CgMaterialDefinition m : materials) if (kind == CgLookKinds.MATERIAL && m.id().equals(id)) return m;
            return null;
        };
    }

    @Test
    public void builtInJavaItIsItsFilesEntry() {
        CgMaterialDefinition shell = material("test:materials/shell", SHELL);
        CgVfxSlotRenderer built = CgVfxSlotRenderer.of("l1", CgBeamBody.BODY).material(shell)
                .bind("_Shell", "user.shell").size(1.15f).build();
        CgVfxSlotRenderer loaded = CgVfxSlotRenderer.compile("test:wave", built.tree(), deps(shell));
        assertEquals(built.tree(), loaded.tree());
        assertSame(CgBeamBody.BODY, loaded.slot());
        assertSame(shell, loaded.definition());
        assertEquals(1.15f, loaded.size(), 1e-6f);
        assertEquals(Map.of("_Shell", "user.shell"), loaded.bind());
    }

    @Test
    public void aSystemsSlotsRoundTripAndArePlaced() {
        CgMaterialDefinition shell = material("test:materials/shell", SHELL);
        Map<String, Object> tree = Map.of("slots", List.of(CgVfxSlotRenderer.of("l1", CgBeamBody.BODY).material(shell)
                .bind("_Shell", "user.shell").build().tree()));
        CgVfxSystemDefinition system = CgVfxSystemAssets.compile(
                CgAssetData.of("test:systems/wave", CgParticleKinds.SYSTEM, tree, null), deps(shell));
        List<CgVfxLook.Placed> slotted = system.look().slotted();
        assertEquals(1, slotted.size());
        assertSame(shell, slotted.get(0).definition());
        assertTrue(system.look().draws(CgBeamBody.BODY));
        assertEquals(tree.get("slots"), CgVfxSystemAssets.write(system).get("slots"));
    }

    @Test
    public void aTubeTaggedMaterialDrawsOnlyOnATube() {
        CgMaterialDefinition light = material("test:materials/light", LIGHT);
        assertEquals(CgParsedShader.Tube.VOLUME, light.tube());
        assertEquals(CgParsedShader.Tube.VOLUME,
                CgVfxSlotRenderer.of("l1", CgBeamBody.BODY).material(light).build().tube());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CgVfxSlotRenderer.of("l1", CgBeamBody.HEAD).material(light).build());
        assertTrue(e.getMessage(), e.getMessage().contains("needs a tube"));
    }

    @Test
    public void whatItCannotDrawFailsAtLoadNamingIt() {
        CgMaterialDefinition shell = material("test:materials/shell", SHELL);
        for (Map<String, Object> entry : List.<Map<String, Object>>of(
                Map.of("id", "l1", "slot", "test:no_such_slot", "material", shell.id()),
                Map.of("id", "l1", "slot", CgBeamBody.BODY.name(), "material", shell.id(), "mesh", "test:models/a.glb"),
                Map.of("id", "l1", "slot", CgBeamBody.BODY.name(), "material", shell.id(),
                        "bind", Map.of("_Shell", "particles.color")),
                Map.of("id", "l1", "slot", CgBeamBody.BODY.name()))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> CgVfxSlotRenderer.compile("test:wave", entry, deps(shell)));
            assertTrue(e.getMessage(), e.getMessage().startsWith("test:wave's slot l1"));
        }
    }
}
