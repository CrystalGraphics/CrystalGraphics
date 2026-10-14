package com.crystalgraphics.vfx.look;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.variable.CgVars;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CgVfxTuningTest {

    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("test:tuning");
    private static final CgVfxParam.Scalar RADIUS = SCHEMA.scalar("radius", 0.6f);
    private static final CgVfxParam.Color CORE = SCHEMA.color("core", 0xFFFF8040);
    private static final CgVfxSchema KIT_SCHEMA = CgVfxSchema.of("test:tuning/kit");
    private static final CgVfxParam.Scalar REACH = KIT_SCHEMA.length("reach", 4f);
    private static final CgVfxSlot BODY = CgVfxSlot.mesh("test:tuning/body");

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static CgVfxLook kitLook(CgVfxLayer layer) {
        return CgVfxLook.builder(SCHEMA).with(CgVfxKit.of("test:tuning/kit").params(KIT_SCHEMA).layer(layer).build())
                .build();
    }

    @Test
    public void aTunedValueReachesTheLookAndEveryPlayingCopy() {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).build();
        CgVfxValues playing = look.copyValues();
        playing.set(RADIUS, 2f);
        try (CgVfxTuning ignored = CgVfxTuning.open("test.tune1", look)) {
            assertTrue(CgVars.get("test.tune1.radius").setText(CgVar.Source.CONSOLE, "0.9"));
            assertTrue(CgVars.get("test.tune1.core.g").setText(CgVar.Source.CONSOLE, "0.8"));

            assertEquals(0.9f, look.get(RADIUS), 0f);
            assertEquals(2f, playing.get(RADIUS), 0f);     // until its next tick
            playing.takeTuning();
            assertEquals(0.9f, playing.get(RADIUS), 0f);
            assertEquals(0.8f, playing.get(CORE, 1), 0f);
            assertEquals(1f, playing.get(CORE, 0), 0f);    // untouched components stay
            assertEquals(0.9f, look.copyValues().get(RADIUS), 0f);
        }
    }

    @Test
    public void clearingPutsBackTheOpenedValueAndClosingUnregisters() {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).build();
        CgVfxTuning tuning = CgVfxTuning.open("test.tune2", look);
        CgVar<?> radius = CgVars.get("test.tune2.radius");
        radius.setText(CgVar.Source.CONSOLE, "3");
        radius.clear(CgVar.Source.CONSOLE);
        assertEquals(0.6f, look.get(RADIUS), 0f);

        radius.setText(CgVar.Source.CONSOLE, "3");
        tuning.close();
        assertNull(CgVars.get("test.tune2.radius"));
        assertEquals(3f, look.get(RADIUS), 0f);
        CgVfxTuning.open("test.tune2", look).close();      // the prefix is free again
    }

    @Test
    public void aCopyMadeWithCopyFollowsNothing() {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).build();
        CgVfxValues detached = look.copyValues().copy();
        try (CgVfxTuning ignored = CgVfxTuning.open("test.tune3", look)) {
            CgVars.get("test.tune3.radius").setText(CgVar.Source.CONSOLE, "5");
            detached.takeTuning();
            assertEquals(0.6f, detached.get(RADIUS), 0f);
        }
    }

    @Test
    public void aKitsParameterAndALayersConstantAreVariablesToo() {
        CgVfxLayer flat = CgVfxLayer.of("test:shaders/flat.shader").on(BODY).set("_Glow", 0.35f)
                .set("_Reach", 1f, 2f, 0f, 0f).build();
        CgVfxLayer again = CgVfxLayer.of("test:shaders/flat.shader").on(BODY).set("_Glow", 0.6f).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA)
                .with(CgVfxKit.of("test:tuning/kit").params(KIT_SCHEMA).layer(flat).layer(again).build()).build();
        try (CgVfxTuning ignored = CgVfxTuning.open("test.tune4", look)) {
            CgVars.get("test.tune4.test.tuning.kit.reach").setText(CgVar.Source.CONSOLE, "7");
            CgVars.get("test.tune4.layer.flat._Reach.y").setText(CgVar.Source.CONSOLE, "5");
            CgVar<?> glow = CgVars.get("test.tune4.layer.flat_2._Glow");
            glow.setText(CgVar.Source.CONSOLE, "0.9");

            assertEquals(7f, look.get(REACH), 0f);
            assertEquals(5f, flat.constant("_Reach")[1], 0f);
            assertEquals(1f, flat.constant("_Reach")[0], 0f);
            assertEquals(0.9f, again.constant("_Glow")[0], 0f);
            assertEquals(0.6f, again.constants().get("_Glow")[0], 0f);   // as built, still
            int tunings = again.tunings();
            glow.clear(CgVar.Source.CONSOLE);
            assertEquals(0.6f, again.constant("_Glow")[0], 0f);
            assertTrue(again.tunings() > tunings);                          // what a material re-applies on
        }
    }

    @Test
    public void overridesRoundTripThroughAFollowedFile() throws Exception {
        CgVfxLook look = kitLook(CgVfxLayer.of("test:shaders/flat.shader").on(BODY).set("_Glow", 0.35f).build());
        Path file = folder.getRoot().toPath().resolve("look.yaml");
        try (CgVfxTuning tuning = CgVfxTuning.open("test.tune5", look)) {
            CgVars.get("test.tune5.radius").setText(CgVar.Source.CONSOLE, "0.9");
            CgVars.get("test.tune5.layer.flat._Glow").setText(CgVar.Source.CONSOLE, "0.5");
            tuning.save(file);
        }
        String saved = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(saved, saved.contains("radius") && saved.contains("layer.flat._Glow") && !saved.contains("core"));

        CgVfxLayer layer = CgVfxLayer.of("test:shaders/flat.shader").on(BODY).set("_Glow", 0.35f).build();
        CgVfxLook fresh = kitLook(layer);
        try (CgVfxTuning tuning = CgVfxTuning.open("test.tune6", fresh).follow(file.toString())) {
            assertEquals(0.9f, fresh.get(RADIUS), 0f);
            assertEquals(0.5f, layer.constant("_Glow")[0], 0f);

            Files.write(file, "layer.flat._Glow: 0.25".getBytes(StandardCharsets.UTF_8));
            tuning.refresh();                                   // what a save does in a dev run
            assertEquals(0.25f, layer.constant("_Glow")[0], 0f);
            assertEquals(0.6f, fresh.get(RADIUS), 0f);          // no longer in the file: back to the look's
        }
    }
}
