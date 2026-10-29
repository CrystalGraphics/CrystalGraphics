package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.world.CgHeldCamera;
import com.crystalgraphics.platform.world.CgHostCamera;
import com.crystalgraphics.platform.world.CgWorldBegin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
//? if <1.10 {
/*import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
*///?}

/**
 * {@link CgHostCamera} on Forge 1.8.9 to 1.12.2: holds the last offset core gave and adds it in Forge's camera-setup
 * and field-of-view events. A held pose is ours alone: an armour stand of ours, never in the world, is the render view
 * entity, so the frustum and the chunk order follow it, and the events, at the lowest priority, write its angles and
 * field of view after every other mod's. Client only: provided by {@code PlatformServiceLegacy.register}, which also
 * registers this instance on Forge's event bus and {@link Ticks} on FML's.
 *
 * <ul>
 *   <li>An offset's translation is dropped: no event offers the camera's position. A held pose's is the view entity's.</li>
 *   <li>While held: every key binding released each tick, the player's look and hotbar slot put back each frame, the
 *       view first person, the hands not drawn, the HUD and the player hidden where the play asks; all of it restored when
 *       the hold ends, by core or by the watchdog ({@link CgHeldCamera}).</li>
 *   <li>Render thread only, like the events that read it.</li>
 * </ul>
 */
public final class HostCameraLegacy implements CgHostCamera {

    private float yaw, pitch, roll, fovScale = 1f;
    private int applied;

    private static final CgHeldCamera HELD = new CgHeldCamera();
    private static EntityArmorStand camera;
    private static Entity replaced;
    private static float lookYaw, lookPitch;
    private static int slot, savedPerspective;
    private static boolean savedHud, hudChanged;

    @Override
    public void offset(float x, float y, float z, float yaw, float pitch, float roll, float fovScale) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.fovScale = fovScale;
    }

    @Override
    public void hold(double x, double y, double z, float yaw, float pitch, float roll, float fov, int flags) {
        Minecraft mc = Minecraft.getMinecraft();
        if (ClientLegacy.player() == null || ClientLegacy.world() == null) return;
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

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onCameraSetup(EntityViewRenderEvent.CameraSetup event) {
        begin(partialTicks(event));
        applied |= ROTATION | ROLL;
        boolean held = HELD.held();
        // The event's yaw is the view entity's plus 180, as orientCamera turns by it.
        //? if >=1.9 {
        event.setYaw((held ? HELD.yaw + 180f : event.getYaw()) + yaw);
        event.setPitch((held ? HELD.pitch : event.getPitch()) + pitch);
        event.setRoll((held ? HELD.roll : event.getRoll()) + roll);
        //?} else {
        /*event.yaw = (held ? HELD.yaw + 180f : event.yaw) + yaw;
        event.pitch = (held ? HELD.pitch : event.pitch) + pitch;
        event.roll = (held ? HELD.roll : event.roll) + roll;
        *///?}
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onFov(EntityViewRenderEvent.FOVModifier event) {
        begin(partialTicks(event));
        applied |= FOV;
        event.setFOV((HELD.held() ? HELD.fov : event.getFOV()) * fovScale);
    }

    /** The first-person hands, drawn from the player whatever the view entity: not while a camera is held. */
    @SubscribeEvent
    public void onRenderHand(RenderHandEvent event) {
        if (HELD.held()) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onRenderPlayer(RenderPlayerEvent.Pre event) {
        //? if >=1.10 {
        Object player = event.getEntityPlayer();
        //?} else {
        /*Object player = event.entityPlayer;
        *///?}
        if (HELD.held() && HELD.hidesPlayer() && player == ClientLegacy.player()) event.setCanceled(true);
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

    private static void begin(float partialTicks) {
        CgWorldBegin.fire(partialTicks);
        if (!HELD.live() && HELD.expired()) give();
    }

    private static void take(Minecraft mc) {
        replaced = mc.getRenderViewEntity();
        EntityPlayerSP player = ClientLegacy.player();
        lookYaw = player.rotationYaw;
        lookPitch = player.rotationPitch;
        slot = player.inventory.currentItem;
        savedPerspective = mc.gameSettings.thirdPersonView;
        savedHud = mc.gameSettings.hideGUI;
        hudChanged = false;
    }

    private static void pose(Minecraft mc) {
        if (camera == null || world(camera) != ClientLegacy.world()) {
            camera = new EntityArmorStand(ClientLegacy.world(), HELD.x, HELD.y, HELD.z);
        }
        Entity current = mc.getRenderViewEntity();
        if (current != camera) {
            // Another mod's view entity, set since the last frame: ours wins, and theirs comes back at the end.
            if (current != null) replaced = current;
            mc.setRenderViewEntity(camera);
        }
        // Nothing is targeted while held. The frame's block outline was chosen for the player before this hook, and
        // drawing it casts the view entity to a player.
        mc.objectMouseOver = null;
        // orientCamera puts the eye at the entity's position plus its eye height.
        double feet = HELD.y - camera.getEyeHeight();
        camera.setPosition(HELD.x, feet, HELD.z);
        camera.prevPosX = camera.lastTickPosX = HELD.x;
        camera.prevPosY = camera.lastTickPosY = feet;
        camera.prevPosZ = camera.lastTickPosZ = HELD.z;
        camera.rotationYaw = camera.prevRotationYaw = HELD.yaw;
        camera.rotationPitch = camera.prevRotationPitch = HELD.pitch;
        // The mouse turned the player earlier this frame: put it back, so neither its body nor the server sees it.
        EntityPlayerSP player = ClientLegacy.player();
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
        if (camera != null && mc.getRenderViewEntity() == camera) {
            Entity back = replaced != null && !replaced.isDead && world(replaced) == ClientLegacy.world()
                    ? replaced : ClientLegacy.player();
            if (back != null) mc.setRenderViewEntity(back);
        }
        replaced = null;
        mc.gameSettings.thirdPersonView = savedPerspective;
        if (hudChanged) mc.gameSettings.hideGUI = savedHud;
        hudChanged = false;
        // The bindings released while held, read again from the keyboard and mouse: one still held works at once.
        //? if >=1.10 {
        KeyBinding.updateKeyBindState();
        //?} else {
        /*for (KeyBinding key : mc.gameSettings.keyBindings) {
            int code = key.getKeyCode();
            if (code != 0) KeyBinding.setKeyBindState(code, code < 0 ? Mouse.isButtonDown(code + 100) : Keyboard.isKeyDown(code));
        }
        *///?}
    }

    private static Object world(Entity entity) {
        //? if >=1.10 {
        return entity.world;
        //?} else {
        /*return entity.worldObj;
        *///?}
    }

    private static float partialTicks(EntityViewRenderEvent event) {
        //? if >=1.9 {
        return (float) event.getRenderPartialTicks();
        //?} else {
        /*return (float) event.renderPartialTicks;
        *///?}
    }
}
