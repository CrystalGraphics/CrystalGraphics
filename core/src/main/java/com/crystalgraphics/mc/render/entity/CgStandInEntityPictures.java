package com.crystalgraphics.mc.render.entity;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.mc.entity.CgHostEntities;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;

/**
 * The host of {@link CgEntityPictures} where no Minecraft runs (the GL debug harness, a test), beside
 * {@code CgStandInEntities}: a figure the entity's height, its body narrowing as it turns, its head moved by the head's
 * yaw and pitch, its legs apart as it walks, so fitting, centring and posing run as in a game.
 *
 * <pre>{@code
 * CgPlatform.provide(CgEntityPictures.HOST, new CgStandInEntityPictures());
 * }</pre>
 */
public final class CgStandInEntityPictures implements CgEntityPictures.Host {

    private static final float DEG = (float) (Math.PI / 180);

    @Override
    public String absent() {
        return null;
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object entity, CgEntityPose pose, float ageTicks, float scale, float x, float y,
                     float w, float h, float centreY) {
        CgCanvas canvas = (CgCanvas) ctx.gui();
        float tall = CgHostEntities.height(entity) * scale, cx = x + w / 2, top = y + centreY - tall / 2;
        int colour = colour(entity.toString());
        float body = scale * (0.3f + 0.3f * Math.abs((float) Math.cos(pose.bodyYaw() * DEG)));
        // Legs apart by the walk cycle's swing, as a model's limbs follow its position
        float stride = pose.ownWalk() ? 0 : (float) Math.sin(ageTicks * pose.walkSpeed() * 0.6662f) * body * 0.25f;
        canvas.fill(cx - body / 2 + stride, top + tall * 0.3f, body, tall * 0.7f, colour);
        float head = scale * 0.5f;
        float hx = cx - head / 2 - (float) Math.sin(pose.headYaw() * DEG) * head * 0.5f;
        float hy = top + (float) Math.sin(pose.pitch() * DEG) * head * 0.3f;
        canvas.fill(hx, hy, head, head, colour | 0x00404040);
    }

    /** As the newest versions' inventory: the middle 1/16 block below the box's. */
    @Override
    public float inventoryCentreY(float h, float entityHeight, float scale) {
        return h / 2 + scale / 16;
    }

    @Override
    public float inventoryLookY(float h) {
        return h / 2;
    }

    // Opaque, the entity's own, the same each run
    private static int colour(String key) {
        return 0xFF000000 | (key.hashCode() * 0x9E3779B1 >>> 8) & 0x7F7F7F;
    }
}
