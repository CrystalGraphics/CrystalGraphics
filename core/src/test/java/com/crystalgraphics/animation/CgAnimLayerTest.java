package com.crystalgraphics.animation;

import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** A layered slot takes its branch's bones from what it plays and its material curves onto the instance's own materials. */
public class CgAnimLayerTest {

    private static final String SPRITE = "crystalgraphics:shaders/vfx/particle/sprite.shader";

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgSkeletalMesh face() {
        CgSkeleton s = CgSkeleton.builder().bone("root", null, new Vector3f()).bone("spine", "root", new Vector3f(0, 1, 0))
                .bone("facialbase", "spine", new Vector3f(0, 1, 0)).bone("jaw", "facialbase", new Vector3f(0, 0, 1)).build();
        CgMesh tri = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            int p = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).bone(3, 1f).end();
            int q = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).bone(3, 1f).end();
            int r = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).bone(3, 1f).end();
            m.triangle(p, q, r);
        });
        return CgSkeletalMesh.builder(tri, s).build();
    }

    private static float[] turn(float degrees) {
        Quaternionf q = new Quaternionf().rotateX((float) Math.toRadians(degrees));
        return new float[] {q.x, q.y, q.z, q.w};
    }

    @Test
    public void theLayerTakesItsBranchAndItsCurvesTheMaterials() {
        CgSkeletalMesh mesh = face();
        CgAnimSequence body = CgAnimSequence.builder("body", 30, 1)
                .track("spine", new float[] {0, 1, 0}, turn(30), new float[] {1, 1, 1})
                .track("jaw", new float[] {0, 0, 1}, turn(0), new float[] {1, 1, 1})   // the body's bind fill
                .build();
        CgAnimSequence open = CgAnimSequence.builder("open", 30, 31)
                .track("jaw", new float[] {0, 0, 1}, turn(40), new float[] {1, 1, 1})
                .curve("_SoftDistance", ramp(31, 0.5f, 2f), CgAnimSequence.DRIVES_MATERIAL)
                .build();
        CgAnimInstance goku = new CgAnimInstance(mesh);
        // A material declaring a scalar: the sprite program's, whose usage it must state
        CgMaterialDefinition eye = CgMaterialDefinition.of("test:eye", SPRITE).usage(CgMaterialUsage.SPRITES)
                .value("_SoftDistance", 0.25f).build();
        goku.material(0, eye);
        goku.play(CgAnimSection.of(body).until(Double.POSITIVE_INFINITY).build());
        CgAnimLayer face = goku.layer("FacialSlot", "facialbase", 1);
        face.play(CgAnimSection.of(open).until(Double.POSITIVE_INFINITY).build());

        goku.seek(0.5).evaluate().applyCurves();
        Quaternionf spine = rotation(goku, 1), jaw = rotation(goku, 3);
        assertEquals("the body keeps the spine", 1f, Math.abs(spine.dot(quat(turn(30)))), 1e-5f);
        assertEquals("the layer turns the jaw, under the body's spine", 1f, Math.abs(jaw.dot(quat(turn(70)))), 1e-5f);
        assertEquals("its curve on the instance's own material", 1.25f, scalar(goku.material(0), "_SoftDistance"), 1e-4f);

        face.stop(1f);   // FacialOff: the body shows through, the face's curves gone with it
        goku.evaluate().applyCurves();
        assertEquals("a stop shows the body's branch", 1f, Math.abs(rotation(goku, 3).dot(quat(turn(30)))), 1e-5f);
        assertEquals("and drops the layer's curves", 0.25f, scalar(goku.material(0), "_SoftDistance"), 1e-4f);
        goku.evaluate().applyCurves();
        assertEquals("for that frame only", 1f, Math.abs(rotation(goku, 3).dot(quat(turn(70)))), 1e-5f);

        face.clear();
        goku.evaluate().applyCurves();
        assertEquals("with nothing playing the branch is the body's", 1f, Math.abs(rotation(goku, 3).dot(quat(turn(30)))), 1e-5f);
        assertEquals("a curve no clip keys goes back to the default", 0.25f, scalar(goku.material(0), "_SoftDistance"), 1e-4f);
    }

    private static float[] ramp(int frames, float from, float to) {
        float[] k = new float[frames];
        for (int f = 0; f < frames; f++) k[f] = from + (to - from) * f / (frames - 1f);
        return k;
    }

    private static Quaternionf quat(float[] q) {
        return new Quaternionf(q[0], q[1], q[2], q[3]);
    }

    /** Bone {@code bone}'s rotation in the skeleton's space. */
    private static Quaternionf rotation(CgAnimInstance instance, int bone) {
        return instance.pose().component(bone, new Matrix4f()).getNormalizedRotation(new Quaternionf());
    }

    private static float scalar(CgMaterial m, String name) {
        for (CgMaterialProperty p : m.properties()) if (p.getName().equals(name)) return p.getFloatValue()[0];
        throw new AssertionError(name + " not declared");
    }
}
