package com.crystalgraphics.mc.modern.platform;

//? if >=1.17 <1.19.3 {
/*import java.nio.FloatBuffer;

import org.joml.Matrix4fc;
import org.lwjgl.BufferUtils;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;

/^*
 * JOML matrices as Minecraft took them before 1.19.3, when it kept matrices of its own. Both store column-major, so a
 * matrix crosses through one buffer. Render thread.
 *
 * <pre>{@code
 * RenderSystem.setProjectionMatrix(MojangMatrices.of(projection));
 * MojangMatrices.pose(poseStack.last(), view);
 * }</pre>
 ^/
final class MojangMatrices {

    private static final FloatBuffer BUFFER = BufferUtils.createFloatBuffer(16);

    private MojangMatrices() {
    }

    /^* {@code m} as a matrix of Minecraft's own, made new. ^/
    static Matrix4f of(Matrix4fc m) {
        m.get(BUFFER);
        Matrix4f out = new Matrix4f();
        out.load(BUFFER);
        return out;
    }

    /^* {@code pose} set to {@code m}: its normal matrix the upper 3x3, so {@code m} carries no scale. ^/
    static void pose(PoseStack.Pose pose, Matrix4fc m) {
        Matrix4f mojang = of(m);
        pose.pose().load(mojang);
        pose.normal().load(new Matrix3f(mojang));
    }
}
*///?} else {
// Minecraft takes JOML's own matrices from 1.19.3, and has no 3D pose below 1.17
final class MojangMatrices {
    private MojangMatrices() {
    }
}
//?}
