package com.crystalgraphics.animation;

import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * A pose skinned on the CPU lands where the GPU's skinning puts it (placement × skinning matrix × bind position), with
 * normals turned by the same matrices and unit length; a quad written as two triangles leaves as one quad, a lone
 * triangle as a quad repeating its last corner, and a vertex on two bones takes their blend.
 */
public class CgCpuSkinTest {

    /** The elbow turned 45 degrees about z, raised to 3: key 2 of a bend over five keys. */
    private static CgSkeletalMesh arm() {
        CgSkeleton skeleton = CgSkeleton.builder().bone("root", null, new Vector3f()).bone("elbow", "root", new Vector3f(0, 1, 0)).build();
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
        CgMesh mesh = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            int a = m.vertex().position(0, 1, 0).normal(1, 0, 0).uv(0, 0).color(0xFF336699).bone(1, 1f).end();
            int b = m.vertex().position(1, 1, 0).normal(1, 0, 0).uv(1, 0).color(0xFF336699).bone(1, 1f).end();
            int c = m.vertex().position(1, 2, 0).normal(1, 0, 0).uv(1, 1).color(0xFF336699).bone(1, 1f).end();
            int d = m.vertex().position(0, 2, 0).normal(1, 0, 0).uv(0, 1).color(0xFF336699).bone(1, 1f).end();
            m.quad(a, b, c, d);
            int e = m.vertex().position(0, 0.5f, 0).normal(0, 1, 0).uv(0, 0).color(0xFFFFFFFF).bone(0, 0.5f).bone(1, 0.5f).end();
            int f = m.vertex().position(1, 0, 0).normal(0, 1, 0).uv(1, 0).color(0xFFFFFFFF).bone(0, 1f).end();
            int g = m.vertex().position(0, 0, 1).normal(0, 1, 0).uv(0, 1).color(0xFFFFFFFF).bone(0, 1f).end();
            m.triangle(e, f, g);
        });
        return CgSkeletalMesh.builder(mesh, skeleton).sequence(bend).build();
    }

    @Test
    public void aCpuSkinnedPoseIsTheGpusAndItsTrianglesLeaveAsQuads() {
        CgSkeletalMesh mesh = arm();
        Matrix4f placement = new Matrix4f().rotateY(0.7f).scale(2f);
        CgAnimInstance arm = new CgAnimInstance(mesh).place(10, 64, -3, placement)
                .play(CgAnimSection.of(mesh.sequence("bend")).until(Double.POSITIVE_INFINITY).build()).seek(0.5).evaluate();
        List<float[]> out = new ArrayList<>();
        arm.cpuSkin(0, false, (x, y, z, u, v, argb, nx, ny, nz) -> out.add(new float[]{x, y, z, u, v, argb, nx, ny, nz}));
        assertEquals("one quad, then the triangle as a quad", 8, out.size());

        Matrix4f elbow = new Matrix4f(placement).mul(arm.skinning()[1]);
        float[][] corners = {{0, 1, 0}, {1, 1, 0}, {1, 2, 0}, {0, 2, 0}};
        Vector3f expected = new Vector3f(), normal = new Vector3f();
        elbow.transformDirection(normal.set(1, 0, 0)).normalize();
        for (int i = 0; i < 4; i++) {
            elbow.transformPosition(expected.set(corners[i][0], corners[i][1], corners[i][2]));
            float[] got = out.get(i);
            assertEquals("corner " + i + " x", expected.x, got[0], 1e-4f);
            assertEquals("corner " + i + " y", expected.y, got[1], 1e-4f);
            assertEquals("corner " + i + " z", expected.z, got[2], 1e-4f);
            assertEquals(normal.x, got[6], 1e-3f);
            assertEquals(normal.y, got[7], 1e-3f);
            assertEquals(normal.z, got[8], 1e-3f);
            assertEquals(0xFF336699, (int) got[5]);
        }
        assertEquals(1f, out.get(2)[3], 1e-3f);
        assertEquals(1f, out.get(2)[4], 1e-3f);

        // The triangle: its last corner twice; its first on two bones at the weights the format stores (127 and 128 of 255)
        assertEquals(out.get(6)[0], out.get(7)[0], 0f);
        Matrix4f root = new Matrix4f(placement).mul(arm.skinning()[0]);
        Vector3f fromRoot = root.transformPosition(new Vector3f(0, 0.5f, 0));
        Vector3f fromElbow = elbow.transformPosition(new Vector3f(0, 0.5f, 0));
        float[] blended = out.get(4);
        assertEquals(fromRoot.y + (fromElbow.y - fromRoot.y) * 0.5f, blended[1], 0.02f);
        assertEquals(fromRoot.x + (fromElbow.x - fromRoot.x) * 0.5f, blended[0], 0.02f);
    }
}
