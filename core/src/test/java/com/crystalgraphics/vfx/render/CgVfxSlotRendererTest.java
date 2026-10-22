package com.crystalgraphics.vfx.render;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.render.world.CgWorldLights;
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

    private static final String VOLUME = "cgtest:shaders/tube_volume.shader";
    private static final String SHELL = "crystalgraphics:shaders/vfx/beam/body_shell.shader";

    private static CgMaterialDefinition material(String id, String shader) {
        CgAssetKinds.register();
        return CgMaterialDefinition.of(id, shader).build();
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
                .bind("_Shell", "user.shell").build().tree(),
                CgVfxSlotRenderer.light("l2", CgBeamBody.HEAD).intensity(4f).attenuationRadius(9f).build().tree()));
        CgVfxSystemDefinition system = CgVfxSystemAssets.compile(
                CgAssetData.of("test:systems/wave", CgParticleKinds.SYSTEM, tree, null), deps(shell));
        List<CgVfxLook.Placed> slotted = system.look().slotted();
        assertEquals(2, slotted.size());
        for (CgVfxLook.Placed p : slotted) {
            if (p.slotRenderer().id().equals("l1")) assertSame(shell, p.definition());
            else assertEquals(9f, p.slotRenderer().light().attenuationRadius(), 0f);
        }
        assertTrue(system.look().draws(CgBeamBody.BODY));
        assertEquals(tree.get("slots"), CgVfxSystemAssets.write(system).get("slots"));
    }

    @Test
    public void aTubeTaggedMaterialDrawsOnlyOnATube() {
        CgMaterialDefinition volume = material("test:materials/volume", VOLUME);
        assertEquals(CgParsedShader.Tube.VOLUME, volume.tube());
        assertEquals(CgParsedShader.Tube.VOLUME,
                CgVfxSlotRenderer.of("l1", CgBeamBody.BODY).material(volume).build().tube());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CgVfxSlotRenderer.of("l1", CgBeamBody.HEAD).material(volume).build());
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
                Map.of("id", "l1", "slot", CgBeamBody.BODY.name()),
                Map.of("id", "l1", "slot", CgBeamBody.HEAD.name(), "material", shell.id(), "light", Map.of()),
                Map.of("id", "l1", "slot", CgBeamBody.HEAD.name(), "light", Map.of(), "size", 2.0),
                Map.of("id", "l1", "slot", CgBeamBody.HEAD.name(), "light", Map.of("radius", 2.0)),
                Map.of("id", "l1", "slot", CgBeamBody.HEAD.name(), "light", Map.of(), "bind", Map.of("_Glow", "user.glow")),
                Map.of("id", "l1", "slot", CgBeamBody.ARCS.name(), "light", Map.of()))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> CgVfxSlotRenderer.compile("test:wave", entry, deps(shell)));
            assertTrue(e.getMessage(), e.getMessage().startsWith("test:wave's slot l1"));
        }
    }

    @Test
    public void aLightIsItsFilesEntryAndDecodesItsColour() {
        CgVfxSlotRenderer built = CgVfxSlotRenderer.light("l1", CgBeamBody.HEAD).lightColor(0x80FF8000, 2f).intensity(4f)
                .attenuationRadius(2.2f).bind("intensity", "user.strength").build();
        CgVfxSlotRenderer loaded = CgVfxSlotRenderer.compile("test:wave", built.tree(), null);
        assertEquals(built.tree(), loaded.tree());
        assertNull(loaded.material());
        CgVfxSlotRenderer.Light light = loaded.light();
        float a = 0x80 / 255f;
        assertEquals(2f * a, light.red(), 1e-6f);
        assertEquals(CgWorldLights.linear(0x80 / 255f) * 2f * a, light.green(), 1e-6f);
        assertEquals(0f, light.blue(), 0f);
        assertEquals(4f, light.intensity(), 0f);
        assertEquals(2.2f, light.attenuationRadius(), 1e-6f);
        assertEquals(new CgVfxSlotRenderer.Light(1f, 1f, 1f, 1f, 1f),
                CgVfxSlotRenderer.light("l2", CgBeamBody.BODY).build().light());
    }
}
