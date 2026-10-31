package com.crystalgraphics.vfx.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.camera.CgCameraShakeDefinition;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgCameraKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.cue.CgCueDefinition.Effects;
import com.crystalgraphics.cue.CgCueDefinition.Shake;
import com.crystalgraphics.cue.CgCueDefinition.Where;
import com.crystalgraphics.cue.CgCueManager;
import com.crystalgraphics.cue.CgCueParameters;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.camera.CgShakePlay;
import com.crystalgraphics.world.CgAttachment;

/** A cue's shake: who feels it as it starts, as Unreal's {@code PlayCameraShake} decides. */
public class CgVfxCueShakeTest {

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgCameraShakeDefinition shake(String id) {
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("kind", "wave");
        pattern.put("location", Map.of("x", Map.of("amplitude", 1f, "frequency", 4f, "initial_offset", "zero")));
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("pattern", pattern);
        return CgCameraShakeDefinition.compile(CgAssetData.of(id, CgCameraKinds.SHAKE, tree, null));
    }

    private static Effects burst(CgCameraShakeDefinition d, boolean inWorld, float inner, float outer) {
        return new Effects(List.of(), List.of(), new Shake(d.id(), d, 1f, null, inWorld, inner, outer, 1f, Where.DEFAULT),
                null, null, null, List.of());
    }

    /** A manager whose viewer stands {@code distance} blocks from the origin, controlling {@code local}. */
    private static CgVfxCueEffects effects(double distance, boolean local) {
        CgCueManager cues = new CgCueManager(null).viewer(out -> {
            out[0] = distance;
            out[1] = 0;
            out[2] = 0;
            return true;
        }).localControl(a -> local);
        return new CgVfxCueEffects(new CgVfxSystem(), cues);
    }

    @Test
    public void inTheWorld_itShakesAViewerInsideItsOuterRadius_andNoOneBeyond() {
        CgCameraShakeDefinition near = shake("test:shake/near"), far = shake("test:shake/far");
        effects(6, false).burst(null, burst(near, true, 4f, 12f), new CgCueParameters().at(0, 0, 0), 0f);
        effects(13, false).burst(null, burst(far, true, 4f, 12f), new CgCueParameters().at(0, 0, 0), 0f);
        assertNotNull("within its reach", CgShakePlay.playing(near));
        assertNull("past its outer radius", CgShakePlay.playing(far));
    }

    @Test
    public void notInTheWorld_itShakesOnlyTheTargetsOwnMachine() {
        CgCameraShakeDefinition mine = shake("test:shake/mine"), theirs = shake("test:shake/theirs");
        CgAttachment target = (point, turn) -> true;
        effects(0, true).burst(null, burst(mine, false, 0f, 0f), new CgCueParameters().target(target), 0f);
        effects(0, false).burst(null, burst(theirs, false, 0f, 0f), new CgCueParameters().target(target), 0f);
        assertNotNull(CgShakePlay.playing(mine));
        assertNull("another player's target", CgShakePlay.playing(theirs));
    }

    @Test
    public void itsFalloffIsUnreals_theLinearShareToItsExponent() {
        assertEquals(0.25f, CgVfxCueEffects.falloff(8, 4f, 12f, 2f), 1e-6f);
        assertEquals(1f, CgVfxCueEffects.falloff(3, 4f, 12f, 2f), 0f);
    }

    @Test
    public void aSpaceItDoesNotPlayFailsByName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CgCameraShakeDefinition.worldSpace("user_defined", "space"));
        assertEquals("space user_defined is not played yet", e.getMessage());
        assertEquals(false, CgCameraShakeDefinition.worldSpace(null, "space"));
    }
}
