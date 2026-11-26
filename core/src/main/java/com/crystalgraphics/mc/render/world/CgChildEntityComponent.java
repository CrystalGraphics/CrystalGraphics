package com.crystalgraphics.mc.render.world;

import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Matrix4fc;

import com.crystalgraphics.mc.entity.CgEntityHook;
import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.entity.CgHostEntities;
import com.crystalgraphics.mc.render.entity.CgEntityPose;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgHostDrawnComponent;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.world.actor.CgEntity;

/**
 * An entity in a scene that is the scene's, not the level's: Unreal's {@code UChildActorComponent}. One a
 * {@link CgEntityRef} names is made client-side as it spawns and dropped as it is removed, never added to the level,
 * never ticked (no AI, no sound, no collision); a gameplay {@link CgEntity} or the game's own object is drawn as it is.
 * Posed each frame; Minecraft's renderer draws it, a mod's mob and its layers included.
 *
 * <pre>{@code
 * CgChildEntityComponent zombie = CgChildEntityComponent.of(CgScene.client(), CgEntityRef.parse("zombie{IsBaby:1b}"))
 *         .at(x, y, z).rotation(new Quaternionf().rotateY(yaw))   // its feet at the origin, facing +Z turned
 *         .spawn();
 * zombie.pose(CgEntityPose.FRONT.body(20).head(45, -10).walking(0.3f));   // relative to the component's facing
 * zombie.hook(myHook);                                                     // around each draw: CgEntityHook
 * zombie.remove();
 * }</pre>
 *
 * <ul>
 *   <li>Its age runs from its spawn on the frame clock, so its idle animation (arms, tail, breathing) plays.</li>
 *   <li>A type the game does not know draws nothing.</li>
 *   <li>Culled as Minecraft culls the entity: by its culling box against the view, and past its render distance.</li>
 * </ul>
 */
public final class CgChildEntityComponent extends CgHostDrawnComponent<CgChildEntityComponent> {

    /** The world's partial tick for a child entity: it is posed whole each frame, never between ticks. */
    private static final float PARTIAL_TICK = 1;

    private final Object source;
    private CgEntityPose pose = CgEntityPose.FRONT;
    private CgEntityHook hook = CgEntityHook.NONE;
    @Nullable private volatile Object entity;
    private double spawnedAt = Double.NaN;

    private CgChildEntityComponent(CgScene scene, Object source) {
        super(scene);
        this.source = source;
    }

    /** An entity for {@code scene}, not yet spawned. Throws for a scene Minecraft does not draw. */
    public static CgChildEntityComponent of(CgScene scene, CgEntityRef ref) {
        return new CgChildEntityComponent(scene, Objects.requireNonNull(ref, "ref"));
    }

    public static CgChildEntityComponent of(CgScene scene, CgEntity entity) {
        return new CgChildEntityComponent(scene, Objects.requireNonNull(entity, "entity"));
    }

    /**
     * The game's own entity, drawn where this component stands and as it is, never copied.
     *
     * @throws IllegalArgumentException for an object that is no entity
     */
    public static CgChildEntityComponent of(CgScene scene, Object entity) {
        return new CgChildEntityComponent(scene, CgHostEntities.source(entity));
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

    /** The game's entity it drew last; null before then, or while there is none. Render thread to change it. */
    @Nullable
    public Object entity() {
        return entity;
    }

    public CgEntityPose pose() {
        return pose;
    }

    public CgChildEntityComponent pose(CgEntityPose pose) {
        this.pose = Objects.requireNonNull(pose, "pose");
        return this;
    }

    public CgEntityHook hook() {
        return hook;
    }

    /** Code of the author's around each draw; {@link CgEntityHook#NONE} for none. */
    public CgChildEntityComponent hook(CgEntityHook hook) {
        this.hook = Objects.requireNonNull(hook, "hook");
        return this;
    }

    @Override
    protected void hostDraw(Matrix4fc model) {
        Object was = entity;
        Object e = CgHostEntities.resolve(source, was);
        entity = e;
        if (e != was) hostBoundsChanged();
        if (e == null) return;
        double now = CgFrameClock.seconds();
        if (Double.isNaN(spawnedAt)) spawnedAt = now;
        CgEntityHook h = hook;
        h.before(e, PARTIAL_TICK);
        try {
            CgPlatform.get(CgHostWorld.HOST).drawEntity(e, pose, (float) ((now - spawnedAt) * 20), model);
        } finally {
            h.after(e);
        }
    }

    /** Minecraft's culling box for the entity, once made; until then it is never culled. */
    @Override
    protected boolean hostBounds(CgAABB out) {
        Object e = entity;
        return e != null && CgPlatform.get(CgHostWorld.HOST).bounds(e, out);
    }

    /** The entity's own render distance: its size, and the player's entity distance. */
    @Override
    protected boolean hostInRange(double distanceSq) {
        Object e = entity;
        return e == null || CgPlatform.get(CgHostWorld.HOST).inRange(e, distanceSq);
    }

    @Override
    protected void hostRelease() {
        entity = null;
        spawnedAt = Double.NaN;
    }

    @Override
    public String toString() {
        return "entity " + source;
    }
}
