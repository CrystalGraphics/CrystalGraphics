package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.world.CgHeldCamera;
import com.crystalgraphics.platform.world.CgHostCamera;
import com.crystalgraphics.platform.world.CgWorldBegin;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
//? if >=1.16.5 {
import net.minecraft.client.CameraType;
//?}

/**
 * {@link CgHostCamera} for the modern hosts: holds the last offset core gave and adds it where each loader sets its
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
 *   <li>An offset's translation is dropped: no modern hook offers the camera's position. A held pose's is the camera
 *       entity's. Fabric rolls from 1.21.11, where Minecraft renders from the camera's quaternion; Forge 26.1.1 to 26.2
 *       turn the camera in a node mixin, their angle event coming after the view is taken.</li>
 *   <li>While held: every key mapping is released each tick, the player's look and hotbar slot are put back each frame,
 *       the view is first person, the HUD hidden where the play asks. All of it is restored when the hold ends: by core,
 *       by the watchdog ({@link CgHeldCamera}), or as the level goes.</li>
 *   <li>Render thread only, like the hooks that read it.</li>
 * </ul>
 */
public final class HostCameraModern implements CgHostCamera {

    private static float yaw, pitch, roll, fovScale = 1f, eventRoll;
    private static final Angle YAW = new Angle(), PITCH = new Angle(), ROLL_ANGLE = new Angle();
    private static volatile int capabilities = POSITION | LOCK;
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

    /** A loader, where it wires a hook: what it applies ({@code CgHostCamera.ROTATION} and the rest). */
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
        savedHud = hudHidden(mc);
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
        if (hudHidden(mc) != hide) {
            hideHud(mc, hide);
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
        if (hudChanged && hudHidden(mc) != savedHud) hideHud(mc, savedHud);
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

    private static boolean hudHidden(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gui.hud.isHidden();
        *///?} else {
        return mc.options.hideGui;
        //?}
    }

    private static void hideHud(Minecraft mc, boolean hidden) {
        //? if >=26.2 {
        /*if (mc.gui.hud.isHidden() != hidden) mc.gui.hud.toggle();
        *///?} else {
        mc.options.hideGui = hidden;
        //?}
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
}
