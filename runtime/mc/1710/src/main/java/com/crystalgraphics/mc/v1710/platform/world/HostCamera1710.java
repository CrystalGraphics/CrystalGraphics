package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.world.CgHeldCamera;
import com.crystalgraphics.platform.world.CgHostCamera;
import com.crystalgraphics.platform.world.CgWorldBegin;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.client.event.RenderPlayerEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * {@link CgHostCamera} on Minecraft 1.7.10: holds the last offset core gave, and {@code CameraHook} adds it where the
 * entity renderer orients its camera and computes its field of view. 1.7.10's Forge has no camera-angle event, and its
 * FOV event is the player's smoothed modifier, so both are mixins here. A held pose is ours alone: a living entity of
 * ours, never in the world, is the render view entity, so the camera, the frustum and the chunk order follow it. Client
 * only: provided by {@code PlatformService1710.onPreInit}, which registers {@link Ticks} and {@link Renders}.
 *
 * <ul>
 *   <li>The rotation is applied in view space ahead of the camera's own, so yaw turns about the view's vertical rather
 *       than the world's: the same at a level gaze, and indistinguishable in a shake.</li>
 *   <li>An offset has no translation: the hook has no position to move. A held pose's is the view entity's.</li>
 *   <li>While held: every key binding released each tick, the player's look and hotbar slot put back each frame, the
 *       view first person, the hands not drawn, the HUD and the player hidden where the play asks; all of it restored
 *       when the hold ends, by core or by the watchdog ({@link CgHeldCamera}).</li>
 *   <li>Vanilla's particles face the player's look here, not the camera's: 1.7.10 orients them from {@code thePlayer}.
 *       Render thread only.</li>
 * </ul>
 */
public final class HostCamera1710 implements CgHostCamera {

    private static float yaw, pitch, roll, fovScale = 1f;
    private static int applied;

    private static final CgHeldCamera HELD = new CgHeldCamera();
    /** orientCamera's eye: the view entity's position less its {@code yOffset}, plus 1.62. */
    private static final double EYE = 1.62;
    private static CameraEntity1710 camera;
    private static EntityLivingBase replaced;
    private static float lookYaw, lookPitch;
    private static int slot, savedPerspective;
    private static boolean savedHud, hudChanged;

    @Override
    public void offset(float x, float y, float z, float yaw, float pitch, float roll, float fovScale) {
        HostCamera1710.yaw = yaw;
        HostCamera1710.pitch = pitch;
        HostCamera1710.roll = roll;
        HostCamera1710.fovScale = fovScale;
    }

    @Override
    public void hold(double x, double y, double z, float yaw, float pitch, float roll, float fov, int flags) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (HELD.hold(x, y, z, yaw, pitch, roll, fov, flags)) take(mc);
        pose(mc);
    }

    @Override
    public void release() {
        if (HELD.release()) give();
    }

    @Override
    public int capabilities() {
        return ROTATION | ROLL | FOV | POSITION | LOCK | HIDES_PLAYER;
    }

    @Override
    public int applied() {
        return applied;
    }

    /** {@code CameraHook}, at the head of {@code orientCamera}: the offset, and a held roll, as GL rotations on the modelview. */
    public static void rotate(float partialTicks) {
        begin(partialTicks);
        applied |= ROTATION | ROLL;
        // The held yaw and pitch are the view entity's, which orientCamera turns by after this.
        float r = roll + (HELD.held() ? HELD.roll : 0f);
        if (r != 0f) GL11.glRotatef(r, 0f, 0f, 1f);
        if (pitch != 0f) GL11.glRotatef(pitch, 1f, 0f, 0f);
        if (yaw != 0f) GL11.glRotatef(yaw, 0f, 1f, 0f);
    }

    public static float fov(float base, float partialTicks) {
        begin(partialTicks);
        applied |= FOV;
        return (HELD.held() ? HELD.fov : base) * fovScale;
    }

    /** {@code CameraHook}, at the head of {@code renderHand}: the player's hands, drawn whatever the view entity. */
    public static boolean handsHidden() {
        return HELD.held();
    }

    /** The head of each client tick: every key binding released while held, so no key acts on the player. */
    public static final class Ticks {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.START) return;
            // held, not live: the watchdog is judged at the camera hook alone (CgHeldCamera).
            if (HELD.held()) KeyBinding.unPressAllKeys();
        }
    }

    /** The local player not drawn where a held play asks. */
    public static final class Renders {
        @SubscribeEvent
        public void onRenderPlayer(RenderPlayerEvent.Pre event) {
            if (HELD.held() && HELD.hidesPlayer() && event.entityPlayer == Minecraft.getMinecraft().thePlayer) {
                event.setCanceled(true);
            }
        }
    }

    private static void begin(float partialTicks) {
        CgWorldBegin.fire(partialTicks);
        if (!HELD.live() && HELD.expired()) give();
    }

    private static void take(Minecraft mc) {
        replaced = mc.renderViewEntity;
        EntityClientPlayerMP player = mc.thePlayer;
        lookYaw = player.rotationYaw;
        lookPitch = player.rotationPitch;
        slot = player.inventory.currentItem;
        savedPerspective = mc.gameSettings.thirdPersonView;
        savedHud = mc.gameSettings.hideGUI;
        hudChanged = false;
    }

    private static void pose(Minecraft mc) {
        if (camera == null || camera.worldObj != mc.theWorld) camera = new CameraEntity1710(mc.theWorld);
        EntityLivingBase current = mc.renderViewEntity;
        if (current != camera) {
            // Another mod's view entity, set since the last frame: ours wins, and theirs comes back at the end.
            if (current != null) replaced = current;
            mc.renderViewEntity = camera;
        }
        // Nothing is targeted while held.
        mc.objectMouseOver = null;
        double feet = HELD.y - EYE;
        camera.setPosition(HELD.x, feet, HELD.z);
        camera.prevPosX = camera.lastTickPosX = HELD.x;
        camera.prevPosY = camera.lastTickPosY = feet;
        camera.prevPosZ = camera.lastTickPosZ = HELD.z;
        camera.rotationYaw = camera.prevRotationYaw = HELD.yaw;
        camera.rotationPitch = camera.prevRotationPitch = HELD.pitch;
        // The mouse turned the player earlier this frame: put it back, so neither its body nor the server sees it.
        EntityClientPlayerMP player = mc.thePlayer;
        player.rotationYaw = player.prevRotationYaw = lookYaw;
        player.rotationPitch = player.prevRotationPitch = lookPitch;
        player.inventory.currentItem = slot;
        mc.gameSettings.thirdPersonView = 0;
        boolean hide = HELD.hidesHud() || savedHud;
        if (mc.gameSettings.hideGUI != hide) {
            mc.gameSettings.hideGUI = hide;
            hudChanged = true;
        }
    }

    private static void give() {
        Minecraft mc = Minecraft.getMinecraft();
        if (camera != null && mc.renderViewEntity == camera) {
            EntityLivingBase back = replaced != null && !replaced.isDead && replaced.worldObj == mc.theWorld ? replaced : mc.thePlayer;
            if (back != null) mc.renderViewEntity = back;
        }
        replaced = null;
        mc.gameSettings.thirdPersonView = savedPerspective;
        if (hudChanged) mc.gameSettings.hideGUI = savedHud;
        hudChanged = false;
        // The bindings released while held, read again from the keyboard and mouse: one still held works at once.
        for (Object o : mc.gameSettings.keyBindings) {
            KeyBinding key = (KeyBinding) o;
            int code = key.getKeyCode();
            if (code != 0) KeyBinding.setKeyBindState(code, code < 0 ? Mouse.isButtonDown(code + 100) : Keyboard.isKeyDown(code));
        }
    }
}
