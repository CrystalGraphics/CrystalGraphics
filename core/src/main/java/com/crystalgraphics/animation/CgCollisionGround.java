package com.crystalgraphics.animation;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.joml.Vector3f;

import com.crystalgraphics.animation.graph.CgAnimGround;
import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.primitive.CgSphere;

/**
 * The ground under a character from a level's blocks: an anim node's sweeps through {@link CgCollisionWorld}, by the
 * blocks' real shapes (a stair's step, a slab's top), and its floor a short sweep under where it stands, as Unreal's
 * character movement finds its floor ({@code FindFloor}).
 *
 * <pre>{@code
 * anim.ground(CgCollisionGround.of(() -> CgCollisionWorld.client(partialTick), component::worldTransform));
 * anim.ground(CgCollisionGround.of(() -> CgCollisionWorld.of(level), placement).velocity(out -> out.set(mob.velocity)));
 * }</pre>
 *
 * <ul>
 *   <li>Asked on the game thread only: a collision world is its thread's.</li>
 *   <li>Walkable is Unreal's default: a surface facing up within 44.77 degrees ({@code WalkableFloorZ} 0.71).</li>
 *   <li>Its velocity is zero unless {@link #velocity} says how to read it.</li>
 * </ul>
 */
public final class CgCollisionGround implements CgAnimGround {

    /** {@code UCharacterMovementComponent::WalkableFloorZ}'s default. */
    public static final float WALKABLE_FLOOR_Z = 0.71f;
    /** {@code MAX_FLOOR_DIST}, 2.4 cm: how far below its feet a floor still holds a character up. */
    private static final double MAX_FLOOR_DIST = 0.024;
    /** The ball the floor is found with: a body's foot, about. */
    private static final double FOOT_RADIUS = 0.2;
    private static final CgShape FOOT = CgSphere.of(FOOT_RADIUS);

    private final Supplier<CgCollisionWorld> world;
    private final Supplier<? extends Matrix4dc> placement;
    @Nullable
    private Consumer<Vector3d> velocity;
    private final CgHit hit = new CgHit();
    private CgShape ball = FOOT;
    private double ballRadius = 0.2;
    private final Vector3d p = new Vector3d();
    private final Vector3f n = new Vector3f();

    private CgCollisionGround(Supplier<CgCollisionWorld> world, Supplier<? extends Matrix4dc> placement) {
        this.world = Objects.requireNonNull(world, "world");
        this.placement = Objects.requireNonNull(placement, "placement");
    }

    /** Over {@code world}, asked each time, for a character placed by {@code placement}. */
    public static CgCollisionGround of(Supplier<CgCollisionWorld> world, Supplier<? extends Matrix4dc> placement) {
        return new CgCollisionGround(world, placement);
    }

    /** How its velocity is read, blocks a second: an entity's motion. */
    public CgCollisionGround velocity(@Nullable Consumer<Vector3d> velocity) {
        this.velocity = velocity;
        return this;
    }

    @Override
    public boolean onGround() {
        return floor(p, n);
    }

    @Override
    public boolean floor(Vector3d point, Vector3f normal) {
        Matrix4dc m = placement.get();
        // from a ball's radius above its feet, down past them by the floor distance
        return sweep(FOOT, m.m30(), m.m31() + FOOT_RADIUS, m.m32(), 0f, -1f, 0f, FOOT_RADIUS + MAX_FLOOR_DIST, point, normal);
    }

    @Override
    public void velocity(Vector3d out) {
        if (velocity != null) velocity.accept(out);
        else out.zero();
    }

    @Override
    public boolean sweep(double x, double y, double z, float dx, float dy, float dz, double distance, double radius,
                         Vector3d point, Vector3f normal) {
        // a node sweeps one radius every frame: its ball is made once
        if (radius != ballRadius) {
            ball = CgSphere.of(radius);
            ballRadius = radius;
        }
        return sweep(ball, x, y, z, dx, dy, dz, distance, point, normal);
    }

    private boolean sweep(CgShape ball, double x, double y, double z, float dx, float dy, float dz, double distance,
                          Vector3d point, Vector3f normal) {
        if (!world.get().cast(ball).at(x, y, z).along(dx, dy, dz, distance).blocksOnly().first(hit)) return false;
        // the hit's normal points into the surface: the surface's own faces back
        normal.set((float) -hit.normalX(), (float) -hit.normalY(), (float) -hit.normalZ());
        if (normal.y < WALKABLE_FLOOR_Z) return false;
        point.set(hit.x(), hit.y(), hit.z());
        return true;
    }
}
