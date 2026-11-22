package com.crystalgraphics.mc.render.entity;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;

/**
 * The host of {@link CgEntityPictures} where no Minecraft runs (the GL debug harness, a test): a figure the entity's
 * height, its body narrowing as it turns and its head moved by the head's yaw and pitch, so fitting, centring and posing
 * run as in a game.
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

    /** The reference itself: the stand-in draws from the value. */
    @Override
    public Object entity(CgEntityRef ref, Object previous) {
        return ref;
    }

    /** A player's height; one block for anything else. */
    @Override
    public float height(Object entity) {
        CgEntityRef.Kind k = ((CgEntityRef) entity).kind();
        return k == CgEntityRef.Kind.PLAYER || k == CgEntityRef.Kind.PROFILE ? 1.8f : 1f;
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object entity, CgEntityPose pose, float scale, float x, float y, float w, float h) {
        CgCanvas canvas = (CgCanvas) ctx.gui();
        float tall = height(entity) * scale, cx = x + w / 2, top = y + h / 2 - tall / 2;
        int colour = colour(entity.toString());
        float body = scale * (0.3f + 0.3f * Math.abs((float) Math.cos(pose.bodyYaw() * DEG)));
        canvas.fill(cx - body / 2, top + tall * 0.3f, body, tall * 0.7f, colour);
        float head = scale * 0.5f;
        float hx = cx - head / 2 - (float) Math.sin(pose.headYaw() * DEG) * head * 0.5f;
        float hy = top + (float) Math.sin(pose.pitch() * DEG) * head * 0.3f;
        canvas.fill(hx, hy, head, head, colour | 0x00404040);
    }

    // Opaque, the entity's own, the same each run
    private static int colour(String key) {
        return 0xFF000000 | (key.hashCode() * 0x9E3779B1 >>> 8) & 0x7F7F7F;
    }
}
