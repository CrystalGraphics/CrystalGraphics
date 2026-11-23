package com.crystalgraphics.mc.render.world;

import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Matrix4fc;

import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.render.entity.CgEntityPose;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgHostDrawnComponent;
import com.crystalgraphics.shape.bounds.CgAABB;

/**
 * An entity in a scene that is the scene's, not the level's: Unreal's {@code UChildActorComponent}. Made client-side as it
 * spawns and dropped as it is removed; never added to the level, never ticked (no AI, no sound, no collision), posed
 * each frame. Minecraft's renderer draws it, a mod's mob and its layers included.
 *
 * <pre>{@code
 * CgChildEntityComponent zombie = CgChildEntityComponent.of(CgScene.client(),
 *         CgEntityRef.of(CgEntityData.parse("minecraft:zombie{IsBaby:1b}")))
 *         .at(x, y, z).rotation(new Quaternionf().rotateY(yaw))   // its feet at the origin, facing +Z turned
 *         .spawn();
 * zombie.pose(CgEntityPose.of(20, 45, -10));                      // relative to the component's facing
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

    private final CgEntityRef ref;
    private CgEntityPose pose = CgEntityPose.FRONT;
    @Nullable private Object entity;
    private double spawnedAt = Double.NaN;

    private CgChildEntityComponent(CgScene scene, CgEntityRef ref) {
        super(scene);
        this.ref = Objects.requireNonNull(ref, "ref");
    }

    /** An entity for {@code scene}, not yet spawned. Throws for a scene Minecraft does not draw. */
    public static CgChildEntityComponent of(CgScene scene, CgEntityRef ref) {
        return new CgChildEntityComponent(scene, ref);
    }

    public CgEntityRef ref() {
        return ref;
    }

    public CgEntityPose pose() {
        return pose;
    }

    public CgChildEntityComponent pose(CgEntityPose pose) {
        this.pose = Objects.requireNonNull(pose, "pose");
        return this;
    }

    @Override
    protected void hostDraw(Matrix4fc model) {
        CgHostWorld.Host host = CgPlatform.get(CgHostWorld.HOST);
        Object was = entity;
        entity = host.entity(ref, entity);
        if (entity != was) hostBoundsChanged();
        if (entity == null) return;
        double now = CgFrameClock.seconds();
        if (Double.isNaN(spawnedAt)) spawnedAt = now;
        host.drawEntity(entity, pose, (float) ((now - spawnedAt) * 20), model);
    }

    /** Minecraft's culling box for the entity, once made; until then it is never culled. */
    @Override
    protected boolean hostBounds(CgAABB out) {
        return entity != null && CgPlatform.get(CgHostWorld.HOST).bounds(entity, out);
    }

    /** The entity's own render distance: its size, and the player's entity distance. */
    @Override
    protected boolean hostInRange(double distanceSq) {
        return entity == null || CgPlatform.get(CgHostWorld.HOST).inRange(entity, distanceSq);
    }

    @Override
    protected void hostRelease() {
        entity = null;
        spawnedAt = Double.NaN;
    }
}
