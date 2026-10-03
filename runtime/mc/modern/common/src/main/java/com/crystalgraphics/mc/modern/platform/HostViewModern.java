package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.mc.modern.platform.world.HostCameraModern;
import com.crystalgraphics.render.stage.CgHostView;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
//? if >=26.1 {
/*import net.minecraft.client.renderer.state.level.CameraRenderState;
*///?} elif >=1.19.3 {
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
//?} elif >=1.17 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;
*///?} elif >=1.14 {
/*import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import java.nio.FloatBuffer;
*///?} else {
/*import net.minecraft.world.entity.Entity;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import java.nio.FloatBuffer;
*///?}

/**
 * Minecraft's camera at the world stages: the position, view and projection it draws its own level with, read where
 * each version keeps them, into the stage's {@link CgHostView}. Render thread, during the level render; allocates
 * nothing.
 *
 * <ul>
 *   <li>26.1+: the frame's {@code CameraRenderState}.</li>
 *   <li>1.21.6–1.21.11: the camera's position, and the view and projection a node mixin took from
 *       {@code renderLevel}'s head — Minecraft keeps no CPU copy of the projection after (the global is a GPU
 *       buffer slice).</li>
 *   <li>1.17–1.21.5: the camera, and {@code RenderSystem}'s projection, which carries the view bobbing.</li>
 *   <li>1.15–1.16: the camera, and the fixed-function projection Minecraft loads its bobbed matrix into.</li>
 *   <li>1.13–1.14: both fixed-function matrices; 1.13 pairs them with the camera entity's feet, as its renderers
 *       subtract.</li>
 * </ul>
 */
final class HostViewModern {

    /** {@code renderLevel}'s view and projection on 1.21.6–1.21.11, handed over by a node mixin each frame. */
    private static final Matrix4f LEVEL_VIEW = new Matrix4f();
    private static final Matrix4f LEVEL_PROJECTION = new Matrix4f();

    /** Where a matrix Minecraft keeps in no JOML form is built or read into. */
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();

    //? if <1.19.3 {
    /*private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);
    *///?}

    private HostViewModern() {
    }

    static void levelMatrices(Matrix4fc view, Matrix4fc projection) {
        LEVEL_VIEW.set(view);
        LEVEL_PROJECTION.set(projection);
    }

    static void capture(Minecraft mc, float partialTick, CgHostView out) {
        double x, y, z;
        Matrix4fc view, projection;
        //? if >=26.2 {
        /*CameraRenderState camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        x = camera.pos.x;
        y = camera.pos.y;
        z = camera.pos.z;
        view = camera.viewRotationMatrix;
        projection = camera.projectionMatrix;
        *///?} elif >=26.1 {
        /*CameraRenderState camera = mc.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
        x = camera.pos.x;
        y = camera.pos.y;
        z = camera.pos.z;
        view = camera.viewRotationMatrix;
        projection = camera.projectionMatrix;
        *///?} elif >=1.21.6 {
        /*Vec3 at = mc.gameRenderer.getMainCamera().position();
        x = at.x;
        y = at.y;
        z = at.z;
        view = LEVEL_VIEW;
        projection = LEVEL_PROJECTION;
        *///?} elif >=1.19.3 {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 at = camera.getPosition();
        x = at.x;
        y = at.y;
        z = at.z;
        view = rotation(camera.getXRot(), camera.getYRot());
        projection = RenderSystem.getProjectionMatrix();
        //?} elif >=1.17 {
        /*Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 at = camera.getPosition();
        x = at.x;
        y = at.y;
        z = at.z;
        view = rotation(camera.getXRot(), camera.getYRot());
        MATRIX.clear();
        RenderSystem.getProjectionMatrix().store(MATRIX, false);
        projection = PROJECTION.set(MATRIX);
        *///?} elif >=1.15 {
        /*Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 at = camera.getPosition();
        x = at.x;
        y = at.y;
        z = at.z;
        view = rotation(camera.getXRot(), camera.getYRot());
        projection = glMatrix(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        *///?} elif >=1.14 {
        /*Vec3 at = mc.gameRenderer.getMainCamera().getPosition();
        x = at.x;
        y = at.y;
        z = at.z;
        view = glMatrix(GL11.GL_MODELVIEW_MATRIX, VIEW);
        projection = glMatrix(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        *///?} else {
        /*Entity entity = mc.getCameraEntity();
        x = entity.xOld + (entity.x - entity.xOld) * partialTick;
        y = entity.yOld + (entity.y - entity.yOld) * partialTick;
        z = entity.zOld + (entity.z - entity.zOld) * partialTick;
        view = glMatrix(GL11.GL_MODELVIEW_MATRIX, VIEW);
        projection = glMatrix(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        *///?}
        out.set(x, y, z, view, projection);
    }

    /**
     * What {@code renderLevel} is handed from 1.15: the camera event's roll about Z (Forge and NeoForge keep it only in
     * the pose stack), pitch about X, then yaw plus a half turn about Y.
     */
    private static Matrix4f rotation(float xRot, float yRot) {
        return VIEW.rotationZ((float) Math.toRadians(HostCameraModern.eventRoll()))
                .rotateX((float) Math.toRadians(xRot)).rotateY((float) Math.toRadians(yRot + 180f));
    }

    //? if <1.17 {
    /*private static Matrix4f glMatrix(int which, Matrix4f into) {
        MATRIX.clear();
        GL11.glGetFloatv(which, MATRIX);
        return into.set(MATRIX);
    }
    *///?}
}
