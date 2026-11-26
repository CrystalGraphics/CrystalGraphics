package com.crystalgraphics.mc.render.entity;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.entity.CgEntityHook;
import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.entity.CgHostEntities;
import com.crystalgraphics.mc.render.CgHostPicture;
import com.crystalgraphics.mc.render.CgPictureLighting;
import com.crystalgraphics.mc.render.CgPictureRefresh;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.world.actor.CgEntity;

/**
 * An entity as the inventory shows its player, drawn by Minecraft's own entity renderers, so a mod's mob and its layers
 * show as in the game: one a {@link CgEntityRef} names, a gameplay {@link CgEntity}, or the game's own object.
 *
 * <pre>{@code
 * CgEntityPicture me = CgEntityPicture.of(CgEntityRef.player());
 * me.pose(CgEntityPose.followingPointer(pointerX - 25, pointerY - 20));   // each frame, as the inventory does
 * canvas.picture(me, x, y, 50, 70);                                       // centred in the rect
 *
 * CgEntityPicture sheep = CgEntityPicture.of(CgEntityRef.parse("sheep{Color:2b}"))
 *         .pose(CgEntityPose.facing(30, 0).walking(0.4f)).scale(30);      // 30 units a block, walking
 *
 * CgEntityPicture boss = CgEntityPicture.of(CgEntities.of(bossId));       // a live entity, by its handle
 * CgEntityPicture mine = CgEntityPicture.of(myZombie);                    // the game's object: drawn as it is
 * }</pre>
 *
 * The object drawn is the author's to reach, for good or for one draw:
 *
 * <pre>{@code
 * Zombie z = (Zombie) mine.entity();                    // render thread; null before the first draw
 * mine.hook(new CgEntityHook() {                         // around each draw, render thread
 *     public void before(Object e, float partialTick) { ((Zombie) e).setCustomName(title); }
 *     public void after(Object e) { ((Zombie) e).setCustomName(null); }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Centred in its rect and fitted to the rect's height unless given a {@link #scale}: the entity's height fills
 *       nine tenths of it. {@link #inventory} places it as this version's inventory places its player instead, 30
 *       units a block: the inventory's own rule changed in 1.20.2.</li>
 *   <li>Clipped to its rect, as Minecraft clips the inventory's player from 1.21.6: a model reaching past its bounding
 *       box (a plant's leaves, a dragon's wings) needs a rect with room, or a smaller {@link #scale}.</li>
 *   <li>An entity made from a ref's data or profile is made once, client-side, and never joins the level; a live one
 *       shows while it is in the client's level and nothing after. An object of the author's is held while the picture
 *       is, and only a living entity draws.</li>
 *   <li>Drawn every frame it is shown, as vanilla draws its own; its age runs from its first draw, so its animations
 *       play. Any thread may set its pose; the next draw takes it.</li>
 * </ul>
 */
public final class CgEntityPicture extends CgHostPicture {

    /** Units a block the inventory draws its player at, on every version. */
    private static final float INVENTORY_SCALE = 30;
    /** The inventory's partial tick: Minecraft draws its player at 1. */
    private static final float PARTIAL_TICK = 1;

    private final Object source;
    private volatile CgEntityPose pose = CgEntityPose.FRONT;
    private volatile CgEntityHook hook = CgEntityHook.NONE;
    private volatile float scale;
    private volatile boolean inventory;
    @Nullable private volatile Object entity;
    @Nullable private volatile String name;
    private double firstDrawn = Double.NaN;

    private CgEntityPicture(Object source) {
        super("entity " + source, CgPictureRefresh.REALTIME, CgPictureLighting.ENTITY_IN_UI);
        this.source = source;
    }

    public static CgEntityPicture of(CgEntityRef ref) {
        return new CgEntityPicture(Objects.requireNonNull(ref, "ref"));
    }

    public static CgEntityPicture of(CgEntity entity) {
        return new CgEntityPicture(Objects.requireNonNull(entity, "entity"));
    }

    /**
     * The game's own entity, drawn as it is and never copied: changes to it show at the next draw.
     *
     * @throws IllegalArgumentException for an object that is no entity
     */
    public static CgEntityPicture of(Object entity) {
        return new CgEntityPicture(CgHostEntities.source(entity));
    }

    /** What it was made from: a {@link CgEntityRef}, a {@link CgEntity} or the game's entity. */
    public Object source() {
        return source;
    }

    /** Its ref, where it was made from one; null for a handle or an object. */
    @Nullable
    public CgEntityRef ref() {
        return source instanceof CgEntityRef ? (CgEntityRef) source : null;
    }

    /** The game's entity it drew last: null before its first draw, or while there is none. Render thread to change it. */
    @Nullable
    public Object entity() {
        return entity;
    }

    public CgEntityPose pose() {
        return pose;
    }

    public CgEntityPicture pose(CgEntityPose pose) {
        this.pose = Objects.requireNonNull(pose, "pose");
        return this;
    }

    public CgEntityHook hook() {
        return hook;
    }

    /** Code of the author's around each draw; {@link CgEntityHook#NONE} for none. */
    public CgEntityPicture hook(CgEntityHook hook) {
        this.hook = Objects.requireNonNull(hook, "hook");
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
        Object e = CgHostEntities.resolve(source, entity);
        entity = e;
        if (e == null) return;
        double now = CgFrameClock.seconds();
        if (Double.isNaN(firstDrawn)) firstDrawn = now;
        CgEntityHook h = hook;
        h.before(e, PARTIAL_TICK);
        try {
            name = CgHostEntities.name(e);
            float tall = CgHostEntities.height(e);
            float s = scale > 0 ? scale : inventory ? INVENTORY_SCALE : ctx.height() * 0.9f / Math.max(0.1f, tall);
            float centreY = inventory ? host.inventoryCentreY(ctx.height(), tall, s) : ctx.height() / 2;
            host.draw(ctx, e, pose, (float) ((now - firstDrawn) * 20), s, 0, 0, ctx.width(), ctx.height(), centreY);
        } finally {
            h.after(e);
        }
    }

    @Override
    protected String traceZone() {
        return "pictures.entity";
    }
}
