package com.crystalgraphics.vfx.sequence;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.prefab.CgVfxPrefab;
import com.crystalgraphics.vfx.sequence.CgVfxNodeClip.Channel;
import com.crystalgraphics.vfx.sequence.CgVfxNodeClip.Interp;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CgVfxNodeClipTest {

    private static final CgMaterialDefinition SKIN =
            CgMaterialDefinition.of("test:skin", "crystalgraphics:shaders/vfx/sequence/part.shader").build();
    private static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("sparks").build();

    private static CgVfxPrefab chain() {
        return CgVfxPrefab.builder("test:clip")
                .node("root").at(1f, 0f, 0f)
                .node("arm").parent("root").at(0f, 2f, 0f).opacity(0.5f).part(SKIN)
                .node("hand").parent("arm").at(1f, 0f, 0f).part(SKIN)
                .node("tip").parent("hand").at(0f, 1f, 0f)
                .node("loose").parent("root")
                .build();
    }

    @Test
    public void keysInterpolateStepAndHold() {
        CgVfxPrefab prefab = chain();
        CgVfxNodeClip clip = CgVfxNodeClip.of(prefab, 30)
                .channel("arm", Channel.LOCATION_X, Interp.LINEAR, 10, 0f, 20, 4f)
                .channel("arm", Channel.SCALE_Y, Interp.CONSTANT, 0, 1f, 15, 3f)
                .build();
        int arm = prefab.node("arm");
        assertEquals(0f, clip.keyed(arm, Channel.LOCATION_X.ordinal(), 2f), 0f);    // held before the first key
        assertEquals(2f, clip.keyed(arm, Channel.LOCATION_X.ordinal(), 15f), 1e-6f);
        assertEquals(4f, clip.keyed(arm, Channel.LOCATION_X.ordinal(), 28f), 0f);   // and after the last
        assertEquals(1f, clip.keyed(arm, Channel.SCALE_Y.ordinal(), 14.9f), 0f);
        assertEquals(3f, clip.keyed(arm, Channel.SCALE_Y.ordinal(), 15f), 0f);
        assertEquals(Float.NaN, clip.keyed(arm, Channel.LOCATION_Y.ordinal(), 5f), 0f);
    }

    @Test
    public void eulerDegreesTurnXThenYThenZIntrinsic() {
        CgVfxPrefab prefab = chain();
        CgVfxNodeClip clip = CgVfxNodeClip.of(prefab, 1)
                .channel("hand", Channel.ROTATION_X, Interp.LINEAR, 0, 90f)
                .channel("hand", Channel.ROTATION_Y, Interp.LINEAR, 0, 90f)
                .build();
        Matrix4f m = clip.local(prefab.node("hand"), 0f, new Matrix4f(), new float[10], new Vector3f(), new Quaternionf());
        Matrix4f want = new Matrix4f().translation(1f, 0f, 0f).rotateXYZ((float) Math.PI / 2, (float) Math.PI / 2, 0f);
        assertMatrix(want, m);
    }

    @Test
    public void theBakeComposesDownTheTreeAndBakesOnlyWhatDrawsOrSpawns() {
        CgVfxPrefab prefab = chain();
        CgVfxNodeClip clip = CgVfxNodeClip.of(prefab, 30)
                .channel("arm", Channel.ROTATION_Y, Interp.LINEAR, 0, 0f, 30, 90f)
                .channel("arm", Channel.SCALE_X, Interp.LINEAR, 0, 1f, 30, 2f)
                .channel("hand", Channel.OPACITY, Interp.LINEAR, 0, 1f, 30, 0.5f)
                .burst("tip", SPARKS, 20, 4)
                .build();
        CgVfxClipAtlas.Section s = CgVfxClipAtlas.of(clip);
        int root = prefab.node("root"), arm = prefab.node("arm"), hand = prefab.node("hand"), tip = prefab.node("tip");
        assertEquals(-1, s.base(root));
        assertEquals(-1, s.base(prefab.node("loose")));
        assertEquals(31, s.rowLength());

        // at frame 30 the arm, at (1, 2, 0), is turned a quarter about +y and doubled along its own x: hand's +x of 1
        // lands at -z 2, and tip's +y of 1 above it
        assertTranslation(s, hand, 30, 1f, 2f, -2f);
        assertTranslation(s, tip, 30, 1f, 3f, -2f);
        assertTranslation(s, hand, 0, 2f, 2f, 0f);
        float opacity = CgVfxClipAtlas.read(s.base(hand) + CgVfxClipAtlas.OPACITY_ROW * s.rowLength() + 30, 0);
        assertEquals(0.25f, opacity, 1e-6f);                       // its 0.5 times arm's rest 0.5
        assertEquals(0, s.stepMask(hand));
    }

    @Test
    public void aConstantChannelStepsItsRowsAndItsSubtreesMatrices() {
        CgVfxPrefab prefab = chain();
        CgVfxNodeClip clip = CgVfxNodeClip.of(prefab, 10)
                .channel("arm", Channel.LOCATION_Z, Interp.CONSTANT, 0, 0f, 5, 1f)
                .material("hand", "_U0_LocX", Interp.CONSTANT, 0, 0f, 5, 0.5f)
                .build();
        CgVfxClipAtlas.Section s = CgVfxClipAtlas.of(clip);
        assertEquals(0b111, s.stepMask(prefab.node("arm")));
        assertEquals(0b111 | 1 << CgVfxClipAtlas.SCALAR_ROW, s.stepMask(prefab.node("hand")));
    }

    @Test
    public void anUnkeyedScalarIsTheLayersOwn() {
        CgVfxPrefab prefab = chain();
        CgVfxNodeClip clip = CgVfxNodeClip.of(prefab, 10)
                .material("hand", "_V0_LocY", Interp.LINEAR, 0, 0f, 10, 1f)
                .rest("hand", "_Glare", 2f)
                .build();
        int hand = prefab.node("hand");
        int slotV = CgVfxNodeClip.MATERIAL_SCALARS.indexOf("_V0_LocY"), glare = CgVfxNodeClip.MATERIAL_SCALARS.indexOf("_Glare");
        assertEquals(1 << slotV | 1 << glare, clip.keys(hand));
        assertEquals(0, clip.keys(prefab.node("arm")));
        assertEquals(1f, clip.material(hand, CgVfxNodeClip.MATERIAL_SCALARS.indexOf("_U0_ScaleX"), 3f), 0f);
        assertEquals(1f, clip.material(hand, CgVfxNodeClip.MATERIAL_SCALARS.indexOf("_OutlineID"), 3f), 0f);
        assertEquals(0f, clip.material(hand, CgVfxNodeClip.MATERIAL_SCALARS.indexOf("_Clip"), 3f), 0f);
        assertEquals(2f, clip.material(hand, glare, 7f), 0f);

        CgVfxClipAtlas.Section s = CgVfxClipAtlas.of(clip);
        int row = s.base(hand) + CgVfxClipAtlas.OPACITY_ROW * s.rowLength() + 4;
        assertEquals(clip.keys(hand), (int) CgVfxClipAtlas.read(row, 1));
        assertEquals(0.5f, CgVfxClipAtlas.read(s.base(hand) + CgVfxClipAtlas.SCALAR_ROW * s.rowLength() + 5, slotV), 1e-6f);
    }

    @Test
    public void badKeysAreRefused() {
        CgVfxPrefab prefab = chain();
        assertThrows(IllegalArgumentException.class,
                () -> CgVfxNodeClip.of(prefab, 10).channel("arm", Channel.OPACITY, Interp.LINEAR, 5, 1f, 5, 0f));
        assertThrows(IllegalArgumentException.class,
                () -> CgVfxNodeClip.of(prefab, 10).material("arm", "_Alpha", Interp.LINEAR, 0, 1f));
        assertThrows(IllegalArgumentException.class, () -> CgVfxNodeClip.of(prefab, 10).burst("arm", SPARKS, 11, 1));
        assertThrows(IllegalArgumentException.class, () -> CgVfxNodeClip.of(prefab, 10).channel("nobody", Channel.OPACITY, Interp.LINEAR, 0, 1f));
    }

    private static void assertTranslation(CgVfxClipAtlas.Section s, int node, int frame, float x, float y, float z) {
        int at = s.base(node) + frame, len = s.rowLength();
        assertEquals(x, CgVfxClipAtlas.read(at, 3), 1e-5f);
        assertEquals(y, CgVfxClipAtlas.read(at + len, 3), 1e-5f);
        assertEquals(z, CgVfxClipAtlas.read(at + len * 2, 3), 1e-5f);
    }

    private static void assertMatrix(Matrix4f want, Matrix4f got) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) assertEquals("m" + c + r, want.get(c, r), got.get(c, r), 1e-5f);
        }
    }
}
