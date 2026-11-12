package com.crystalgraphics.mc.modern.platform.world;

import static com.crystalgraphics.platform.world.CgCameraManager.ROTATION;
import static com.crystalgraphics.platform.world.CgCameraManager.ROLL;
import static com.crystalgraphics.platform.world.CgCameraManager.FOV;
import static com.crystalgraphics.platform.world.CgCameraManager.POSITION;
import static com.crystalgraphics.platform.world.CgCameraManager.LOCK;
import static com.crystalgraphics.platform.world.CgCameraManager.TRANSLATION;
import com.crystalgraphics.mc.modern.platform.CgMinecraftClient;
import com.crystalgraphics.platform.world.CgHeldCamera;
import com.crystalgraphics.platform.world.CgCameraManager;
import com.crystalgraphics.platform.world.CgWorldBegin;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
//? if >=1.16.5 {
import net.minecraft.client.CameraType;
//?}

import com.crystalgraphics.camera.CgCamera;
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
 * {@link CgCameraManager} for the modern hosts: holds the last offset core gave and adds it where each loader sets its
 * camera up — Forge's and NeoForge's camera-angle and field-of-view events, and node mixins on the camera and its field
 * of view where those events fall short or Fabric has none. A held pose is ours alone: an armour stand of ours, never in
 * the level, is the game's camera entity, so the frustum, the chunk order and the sound follow it, and the loaders' hooks
 * write its angles and field of view after every other mod's. Client only: constructed by
 * {@code PlatformServiceModern.gl()}.
 *
 * <pre>{@code
 * // a loader, at its camera hook (lowest priority)
 * event.setYaw(HostCameraModern.yaw(event.getYaw()));
 * event.setFOV(HostCameraModern.fov(event.getFOV()));
 * // and at the head of its client tick
 * HostCameraModern.tickStart();
 * }</pre>
 *
 * <ul>
 *   <li>An offset's translation and a held pose's position are written into the camera by the loaders' camera node
 *       mixins ({@link #moving}), at the end of its setup. Fabric rolls from 1.21.11, where Minecraft renders from the camera's quaternion; Forge 26.1.1 to 26.2
 *       turn the camera in a node mixin, their angle event coming after the view is taken.</li>
 *   <li>While held: every key mapping is released each tick, the player's look and hotbar slot are put back each frame,
 *       the view is first person, the HUD hidden where the play asks. All of it is restored when the hold ends: by core,
 *       by the watchdog ({@link CgHeldCamera}), or as the level goes.</li>
 *   <li>Render thread only, like the hooks that read it.</li>
 * </ul>
 */
public final class HostCameraModern implements CgCameraManager.Host {

    private static float yaw, pitch, roll, fovScale = 1f, eventRoll;
    /** The offset's translation, world blocks: added where the camera mixin writes the position. */
    private static float offX, offY, offZ;
    private static final Angle YAW = new Angle(), PITCH = new Angle(), ROLL_ANGLE = new Angle();
    private static volatile int capabilities = POSITION | LOCK | TRANSLATION;
    /** The parts whose hook has run: render thread only, like the hooks. */
    private static int applied;

    private static final CgHeldCamera HELD = new CgHeldCamera();
    private static ArmorStand camera;
    private static Entity replaced;
    private static float lookYaw, lookPitch;
    /** The camera's eye height as it eases toward its entity's, half the way a tick ({@code Camera.tick}): mirrored. */
    private static float eye, eyeOld;
    private static int slot;
    private static boolean savedHud, hudChanged;
    //? if >=1.16.5 {
    private static CameraType savedPerspective;
    //?} else {
    /*private static int savedPerspective;
    *///?}

    /** A loader, where it wires a hook: what it applies ({@code CgCameraManager.ROTATION} and the rest). */
    public static synchronized void declare(int parts) {
        capabilities |= parts;
    }

    @Override
    public int capabilities() {
        return capabilities;
    }

    @Override
    public int applied() {
        return applied;
    }

    @Override
    public void offset(float x, float y, float z, float yaw, float pitch, float roll, float fovScale) {
        offX = x;
        offY = y;
        offZ = z;
        HostCameraModern.yaw = yaw;
        HostCameraModern.pitch = pitch;
        HostCameraModern.roll = roll;
        HostCameraModern.fovScale = fovScale;
    }

    @Override
    public void hold(double x, double y, double z, float yaw, float pitch, float roll, float fov, int flags) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (HELD.hold(x, y, z, yaw, pitch, roll, fov, flags)) take(mc);
        pose(mc);
    }

    @Override
    public void release() {
        if (HELD.release()) give();
    }

    /**
     * {@code WORLD_BEGIN} if this frame has not had it, then whether a pose is held: for a hook that writes the held
     * pose into the camera itself and may be the frame's first.
     */
    public static boolean holding() {
        begin();
        return HELD.held();
    }

    /** Whether a pose is held now: a loader's hooks that lock or hide read it. */
    public static boolean held() {
        return HELD.held();
    }

    /** Whether {@code entity} is the local player and the held play asks it hidden: a loader's render hook skips it. */
    public static boolean hides(Object entity) {
        return HELD.held() && HELD.hidesPlayer() && entity == Minecraft.getInstance().player;
    }

    /** The head of the client tick: every key mapping released while held, so no key acts on the player. */
    public static void tickStart() {
        // held, not live: the watchdog is judged at the camera hook alone (CgHeldCamera).
        if (!HELD.held()) return;
        KeyMapping.releaseAll();
        //? if >=1.14.3 {
        if (camera != null) {
            eyeOld = eye;
            eye += (camera.getEyeHeight() - eye) * 0.5f;
        }
        //?}
    }

    /** The client's level is going (a disconnect, a world left): what a hold changed is given back now. */
    public static void levelGone() {
        if (HELD.release()) give();
        camera = null;
    }

    public static float yaw(float base) {
        begin();
        applied |= ROTATION;
        //? if >=1.14 {
        if (HELD.held()) return HELD.yaw + yaw;
        //?} else {
        /*// 1.13's camera-setup yaw is the entity's plus 180, as legacy Forge's: the sweep drew a held pose backwards.
        if (HELD.held()) return HELD.yaw + 180f + yaw;
        *///?}
        return YAW.add(base, yaw);
    }

    public static float pitch(float base) {
        begin();
        if (HELD.held()) return HELD.pitch + pitch;
        return PITCH.add(base, pitch);
    }

    public static float roll(float base) {
        begin();
        applied |= ROLL;
        if (HELD.held()) return eventRoll = HELD.roll + roll;
        return eventRoll = ROLL_ANGLE.add(base, roll);
    }

    /**
     * One angle's offset, added once: an angle arriving as the turned value we last returned passes unchanged. Forge 26.3
     * posts its camera event twice a frame, the second from the camera the first already turned.
     */
    private static final class Angle {
        private float in = Float.NaN, out = Float.NaN;

        float add(float base, float offset) {
            if (base == out && out != in) return base;
            in = base;
            return out = base + offset;
        }
    }

    /**
     * The roll the loader's camera event ended with, degrees, as our offset left it: 0 where no event rolls (Fabric).
     * Below 1.21.6 Minecraft keeps it only in the pose stack, so the host's view composes it from here.
     */
    public static float eventRoll() {
        return eventRoll;
    }

    public static double fov(double base) {
        begin();
        applied |= FOV;
        return (HELD.held() ? HELD.fov : base) * fovScale;
    }

    public static float fov(float base) {
        begin();
        applied |= FOV;
        return (HELD.held() ? HELD.fov : base) * fovScale;
    }

    /**
     * {@code WORLD_BEGIN} if this frame has not had it, then whether the camera's position moves this frame: a held pose,
     * or an offset's translation. A camera node mixin then writes {@link #x}, {@link #y}, {@link #z} of where it is.
     *
     * <pre>{@code
     * if (HostCameraModern.moving()) {
     *     Vec3 at = camera.getPosition();
     *     setPosition(HostCameraModern.x(at.x), HostCameraModern.y(at.y), HostCameraModern.z(at.z));
     * }
     * }</pre>
     */
    public static boolean moving() {
        boolean held = holding();
        applied |= TRANSLATION;
        return held || offX != 0f || offY != 0f || offZ != 0f;
    }

    /** Where the camera's eye goes from {@code x}, where setup put it: the held pose's if one is held, plus the offset. */
    public static double x(double x) {
        return (HELD.held() ? HELD.x : x) + offX;
    }

    public static double y(double y) {
        return (HELD.held() ? HELD.y : y) + offY;
    }

    public static double z(double z) {
        return (HELD.held() ? HELD.z : z) + offZ;
    }

    /** The held pose as the camera's own, for a node mixin writing the camera directly: x, y, z of the eye. */
    public static double heldX() {
        return HELD.x;
    }

    public static double heldY() {
        return HELD.y;
    }

    public static double heldZ() {
        return HELD.z;
    }

    /**
     * {@code WORLD_BEGIN}, at the first of a frame's camera hooks: the field of view is taken before the camera is set
     * up, so core's shakes are summed and its hold posed before either is used. Core runs once a frame however often it
     * is called. Then the watchdog: a hold core stopped refreshing is given back here.
     */
    private static void begin() {
        CgWorldBegin.fire(partialTick());
        if (!HELD.live() && HELD.expired()) give();
    }

    /** The first frame of a hold: what it replaces, saved to be given back. */
    private static void take(Minecraft mc) {
        replaced = mc.getCameraEntity();
        LocalPlayer player = mc.player;
        //? if >=1.17.1 {
        lookYaw = player.getYRot();
        lookPitch = player.getXRot();
        //?} else {
        /*lookYaw = player.yRot;
        lookPitch = player.xRot;
        *///?}
        slot = selectedSlot(player);
        // The camera's eye is the player's as the hold begins, and eases to our entity's from there.
        eye = eyeOld = player.getEyeHeight();
        //? if >=1.16.5 {
        savedPerspective = mc.options.getCameraType();
        //?} else {
        /*savedPerspective = mc.options.thirdPersonView;
        *///?}
        savedHud = CgMinecraftClient.hudHidden(mc);
        hudChanged = false;
    }

    /** Every frame of a hold, before the camera reads its entity: ours posed, the player's input put back. */
    private static void pose(Minecraft mc) {
        if (camera == null || level(camera) != mc.level) camera = new ArmorStand(mc.level, HELD.x, HELD.y, HELD.z);
        Entity current = mc.getCameraEntity();
        if (current != camera) {
            // Another mod's camera entity, set since the last frame: ours wins, and theirs comes back at the end.
            if (current != null) replaced = current;
            mc.setCameraEntity(camera);
        }
        // Nothing is targeted while held. On 1.13.2 the frame's block outline was chosen for the player before this
        // hook, and drawing it casts the camera entity to a player.
        mc.hitResult = null;
        //? if >=1.14.3 {
        double feet = HELD.y - (eyeOld + (eye - eyeOld) * partialTick());
        //?} else {
        /*double feet = HELD.y - camera.getEyeHeight();
        *///?}
        camera.setPos(HELD.x, feet, HELD.z);
        camera.xo = camera.xOld = HELD.x;
        camera.yo = camera.yOld = feet;
        camera.zo = camera.zOld = HELD.z;
        //? if >=1.17.1 {
        camera.setYRot(HELD.yaw);
        camera.setXRot(HELD.pitch);
        //?} else {
        /*camera.yRot = HELD.yaw;
        camera.xRot = HELD.pitch;
        *///?}
        camera.yRotO = HELD.yaw;
        camera.xRotO = HELD.pitch;
        // A living entity's view yaw is its head's: where no hook turns the camera (Fabric 1.14.4), setup reads it.
        camera.yHeadRot = camera.yHeadRotO = HELD.yaw;
        // The mouse turned the player earlier this frame: put it back, so neither its body nor the server sees it.
        LocalPlayer player = mc.player;
        //? if >=1.17.1 {
        player.setYRot(lookYaw);
        player.setXRot(lookPitch);
        //?} else {
        /*player.yRot = lookYaw;
        player.xRot = lookPitch;
        *///?}
        player.yRotO = lookYaw;
        player.xRotO = lookPitch;
        if (selectedSlot(player) != slot) selectSlot(player, slot);
        // A third-person view would put the camera behind our entity.
        //? if >=1.16.5 {
        if (!mc.options.getCameraType().isFirstPerson()) mc.options.setCameraType(CameraType.FIRST_PERSON);
        //?} else {
        /*mc.options.thirdPersonView = 0;
        *///?}
        boolean hide = HELD.hidesHud() || savedHud;
        if (CgMinecraftClient.hudHidden(mc) != hide) {
            CgMinecraftClient.setHudHidden(mc, hide);
            hudChanged = true;
        }
    }

    /** The hold is over: the camera entity, the perspective, the HUD and the keys still held come back. */
    private static void give() {
        Minecraft mc = Minecraft.getInstance();
        if (camera != null && mc.getCameraEntity() == camera) {
            Entity back = replaced != null && replaced.isAlive() && mc.level != null && level(replaced) == mc.level
                    ? replaced : mc.player;
            if (back != null) mc.setCameraEntity(back);
        }
        replaced = null;
        //? if >=1.16.5 {
        if (savedPerspective != null) mc.options.setCameraType(savedPerspective);
        //?} else {
        /*mc.options.thirdPersonView = savedPerspective;
        *///?}
        if (hudChanged && CgMinecraftClient.hudHidden(mc) != savedHud) CgMinecraftClient.setHudHidden(mc, savedHud);
        hudChanged = false;
        // The keys released while held, read again from the keyboard: one still held works without a fresh press.
        KeyMapping.setAll();
    }

    private static Object level(Entity entity) {
        //? if >=1.20.1 {
        return entity.level();
        //?} else {
        /*return entity.level;
        *///?}
    }

    private static int selectedSlot(LocalPlayer player) {
        //? if >=1.21.5 {
        /*return player.getInventory().getSelectedSlot();
        *///?} elif >=1.17.1 {
        return player.getInventory().selected;
        //?} else {
        /*return player.inventory.selected;
        *///?}
    }

    private static void selectSlot(LocalPlayer player, int slot) {
        //? if >=1.21.5 {
        /*player.getInventory().setSelectedSlot(slot);
        *///?} elif >=1.17.1 {
        player.getInventory().selected = slot;
        //?} else {
        /*player.inventory.selected = slot;
        *///?}
    }

    /** The frame's partial tick, pause-aware, as the render call has it. */
    private static float partialTick() {
        //? if >=1.21.3 {
        /*return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        *///?} elif >=1.21 {
        /*return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        *///?} else {
        return Minecraft.getInstance().getFrameTime();
        //?}
    }

    // ── Reading the camera Minecraft drew with ──────────────────────────

    /** {@code renderLevel}'s view and projection on 1.21.6–1.21.11, handed over by a node mixin each frame. */
    private static final Matrix4f LEVEL_VIEW = new Matrix4f();
    private static final Matrix4f LEVEL_PROJECTION = new Matrix4f();

    /** Where a matrix Minecraft keeps in no JOML form is built or read into. */
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f MODEL_BASE = new Matrix4f();

    //? if <1.19.3 {
    /*private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);
    *///?}

    public static void levelMatrices(Matrix4fc view, Matrix4fc projection) {
        LEVEL_VIEW.set(view);
        LEVEL_PROJECTION.set(projection);
    }

    /**
     * Minecraft's camera at the world stages: the position, view and projection it draws its own level with, read where
     * each version keeps them, into the stage's {@link CgCamera}. Render thread, during the level render; allocates
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
    public static void capture(Minecraft mc, float partialTick, CgCamera out) {
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
     * {@code CgModelSpace} for the modern hosts: the camera, and what the entity pose stack starts from. 1.15–1.20.4
     * hand {@code renderLevel} a stack turned by the camera; from 1.20.6 entities draw from a fresh one, unturned, the
     * rotation moved into the model-view matrix. 1.13–1.14 have no pose stack.
     */
    public static boolean modelSpace(double[] camera, float[] base) {
        Minecraft mc = Minecraft.getInstance();
        //? if >=26.2 {
        /*CameraRenderState state = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        camera[0] = state.pos.x;
        camera[1] = state.pos.y;
        camera[2] = state.pos.z;
        MODEL_BASE.identity().get(base);
        return true;
        *///?} elif >=26.1 {
        /*CameraRenderState state = mc.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
        camera[0] = state.pos.x;
        camera[1] = state.pos.y;
        camera[2] = state.pos.z;
        MODEL_BASE.identity().get(base);
        return true;
        *///?} elif >=1.21.6 {
        /*Vec3 at = mc.gameRenderer.getMainCamera().position();
        camera[0] = at.x;
        camera[1] = at.y;
        camera[2] = at.z;
        MODEL_BASE.identity().get(base);
        return true;
        *///?} elif >=1.20.6 {
        /*Vec3 at = mc.gameRenderer.getMainCamera().getPosition();
        camera[0] = at.x;
        camera[1] = at.y;
        camera[2] = at.z;
        MODEL_BASE.identity().get(base);
        return true;
        *///?} elif >=1.15 {
        Camera view = mc.gameRenderer.getMainCamera();
        Vec3 at = view.getPosition();
        camera[0] = at.x;
        camera[1] = at.y;
        camera[2] = at.z;
        MODEL_BASE.rotationZ((float) Math.toRadians(eventRoll()))
                .rotateX((float) Math.toRadians(view.getXRot())).rotateY((float) Math.toRadians(view.getYRot() + 180f))
                .get(base);
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * What {@code renderLevel} is handed from 1.15: the camera event's roll about Z (Forge and NeoForge keep it only in
     * the pose stack), pitch about X, then yaw plus a half turn about Y.
     */
    private static Matrix4f rotation(float xRot, float yRot) {
        return VIEW.rotationZ((float) Math.toRadians(eventRoll()))
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
