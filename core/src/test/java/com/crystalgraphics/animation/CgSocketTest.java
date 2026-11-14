package com.crystalgraphics.animation;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgSocket;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A socket in the mesh's space is where Unreal puts one (bone × relative), found as Unreal finds one: a socket, then a
 * bone, ignoring case, then the mesh's origin. Where it stands in the world is its component's
 * ({@code CgSkeletalMeshTest}).
 */
public class CgSocketTest {

    /** A root and its elbow; the elbow's rotation keyed from 0 to 90 degrees about z over a second, rising a block a key. */
    public static CgSkeletalMesh arm() {
        CgSkeleton skeleton = CgSkeleton.builder().bone("root", null, new Vector3f()).bone("Elbow", "root", new Vector3f(0, 1, 0)).build();
        float[] rotations = new float[5 * 4], translations = new float[5 * 3];
        for (int k = 0; k < 5; k++) {
            Quaternionf q = new Quaternionf().rotateZ((float) Math.toRadians(22.5 * k));
            rotations[k * 4] = q.x;
            rotations[k * 4 + 1] = q.y;
            rotations[k * 4 + 2] = q.z;
            rotations[k * 4 + 3] = q.w;
            translations[k * 3 + 1] = 1f + k;
        }
        CgAnimSequence bend = CgAnimSequence.builder("bend", 4, 5).track("elbow", translations, rotations, new float[]{1, 1, 1}).build();
        CgMesh triangle = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            int a = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).bone(1, 1f).end();
            int b = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).bone(1, 1f).end();
            int c = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).bone(1, 1f).end();
            m.triangle(a, b, c);
        });
        return CgSkeletalMesh.builder(triangle, skeleton).sequence(bend)
                .socket(CgSocket.of("Palm", "elbow", new Vector3f(0, 0.5f, 0))).build();
    }

    private static CgAnimInstance playing(CgSkeletalMesh mesh) {
        return new CgAnimInstance(mesh).play(mesh.sequence("bend"));
    }

    @Test
    public void aSocketIsBoneTimesRelative() {
        CgAnimInstance arm = playing(arm()).position(0.5f).tick(0f);       // key 2: up 3, turned 45 degrees
        Matrix4f palm = arm.socket(arm.socket("palm"), new Matrix4f());
        Matrix4f expected = new Matrix4f().translate(0, 3, 0).rotateZ((float) Math.toRadians(45)).translate(0, 0.5f, 0);
        assertTrue(palm + " against " + expected, palm.equals(expected, 1e-5f));
    }

    @Test
    public void aSocketIsFoundAsUnrealFindsOne() {
        CgAnimInstance arm = playing(arm()).position(0.5f).tick(0f);
        assertTrue("the socket, ignoring case", (arm.socket("PALM") & (1 << 30)) != 0);
        assertEquals("else a bone, ignoring case", 1, arm.socket("elbow"));
        assertEquals(CgAnimInstance.NO_SOCKET, arm.socket("tail"));
        assertTrue("neither: the mesh's origin", arm.socket(CgAnimInstance.NO_SOCKET, new Matrix4f()).equals(new Matrix4f(), 0f));
    }

    @Test
    public void socketsOnBonesTheSkeletonLacksAreRefused() {
        CgSkeletalMesh mesh = arm();
        try {
            mesh.withSockets(List.of(CgSocket.of("x", "tail", new Vector3f())));
            throw new AssertionError("a socket on a missing bone was taken");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("tail"));
        }
    }
}
