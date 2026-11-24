package com.crystalgraphics.mc.render.entity;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.render.CgHostPicture;
import com.crystalgraphics.mc.render.CgPictureLighting;
import com.crystalgraphics.mc.render.CgPictureRefresh;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.platform.CgPlatform;

/**
 * An entity as the inventory shows its player: any entity a {@link CgEntityRef} names, posed, drawn by Minecraft's own
 * entity renderers, so a mod's mob and its layers show as in the game.
 *
 * <pre>{@code
 * CgEntityPicture me = CgEntityPicture.of(CgEntityRef.player());
 * me.pose(CgEntityPose.followingPointer(pointerX - 25, pointerY - 20));   // each frame, as the inventory does
 * canvas.picture(me, x, y, 50, 70);                                       // centred in the rect
 *
 * CgEntityPicture sheep = CgEntityPicture.of(CgEntityRef.of(CgEntityData.parse("sheep{Color:2b}")))
 *         .pose(CgEntityPose.facing(30, 0)).scale(30);                    // 30 units a block
 *
 * CgEntityPicture player = CgEntityPicture.of(CgEntityRef.player()).inventory(true);   // as this version's inventory
 * float lookY = CgEntityPicture.inventoryLookY(70);                        // where its pointer is measured from
 * }</pre>
 *
 * <ul>
 *   <li>Centred in its rect and fitted to the rect's height unless given a {@link #scale}: the entity's height fills
 *       nine tenths of it. {@link #inventory} places it as this version's inventory places its player instead, 30
 *       units a block: the inventory's own rule changed in 1.20.2.</li>
 *   <li>Clipped to its rect, as Minecraft clips the inventory's player from 1.21.6: a model reaching past its bounding
 *       box (a plant's leaves, a dragon's wings) needs a rect with room, or a smaller {@link #scale}.</li>
 *   <li>An entity made from data or a profile is made once, client-side, and never joins the level; a live one shows
 *       while it is in the client's level and nothing after.</li>
 *   <li>Drawn every frame it is shown, as vanilla draws its own: the entity's animations play. Any thread may set its
 *       pose; the next draw takes it.</li>
 * </ul>
 */
public final class CgEntityPicture extends CgHostPicture {

    /** Units a block the inventory draws its player at, on every version. */
    private static final float INVENTORY_SCALE = 30;

    private final CgEntityRef ref;
    private volatile CgEntityPose pose = CgEntityPose.FRONT;
    private volatile float scale;
    private volatile boolean inventory;
    @Nullable private Object entity;
    @Nullable private volatile String name;

    private CgEntityPicture(CgEntityRef ref) {
        super("entity " + ref, CgPictureRefresh.REALTIME, CgPictureLighting.ENTITY_IN_UI);
        this.ref = Objects.requireNonNull(ref, "ref");
    }

    public static CgEntityPicture of(CgEntityRef ref) {
        return new CgEntityPicture(ref);
    }

    public CgEntityRef ref() {
        return ref;
    }

    public CgEntityPose pose() {
        return pose;
    }

    public CgEntityPicture pose(CgEntityPose pose) {
        this.pose = Objects.requireNonNull(pose, "pose");
        return this;
    }

    /** Units a block; 0 fits the entity to the rect. */
    public float scale() {
        return scale;
    }

    public CgEntityPicture scale(float unitsPerBlock) {
        this.scale = Math.max(0, unitsPerBlock);
        return this;
    }

    /** Whether it is placed as this version's inventory places its player, at 30 units a block unless {@link #scale}. */
    public boolean inventory() {
        return inventory;
    }

    public CgEntityPicture inventory(boolean asInventory) {
        this.inventory = asInventory;
        return this;
    }

    /**
     * Where this version's inventory measures the pointer from in a rect {@code h} units tall, from its top: what a
     * pose following the pointer subtracts, with the rect's middle across. Render thread or any; no game, the middle.
     */
    public static float inventoryLookY(float h) {
        return CgPlatform.get(CgEntityPictures.HOST).inventoryLookY(h);
    }

    /** Its name as its nametag shows it, once drawn: null before the game has made it. Any thread. */
    @Nullable
    public String name() {
        return name;
    }

    @Override
    protected void draw(CgHostDrawContext ctx) {
        CgEntityPictures.Host host = CgPlatform.get(CgEntityPictures.HOST);
        if (host.absent() != null) return;
        entity = host.entity(ref, entity);
        if (entity == null) return;
        name = host.name(entity);
        float tall = host.height(entity);
        float s = scale > 0 ? scale : inventory ? INVENTORY_SCALE : ctx.height() * 0.9f / Math.max(0.1f, tall);
        float centreY = inventory ? host.inventoryCentreY(ctx.height(), tall, s) : ctx.height() / 2;
        host.draw(ctx, entity, pose, s, 0, 0, ctx.width(), ctx.height(), centreY);
    }

    @Override
    protected String traceZone() {
        return "pictures.entity";
    }
}
