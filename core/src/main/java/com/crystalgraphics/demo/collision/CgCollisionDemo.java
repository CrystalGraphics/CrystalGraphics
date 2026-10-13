package com.crystalgraphics.demo.collision;

import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.CgQuery;
import com.crystalgraphics.collision.debug.CgCollisionDebug;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgCapsule;
import com.crystalgraphics.shape.primitive.CgCompound;
import com.crystalgraphics.shape.primitive.CgCone;
import com.crystalgraphics.shape.primitive.CgConvex;
import com.crystalgraphics.shape.primitive.CgCylinder;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.shape.query.CgShapes;

import org.joml.Quaterniond;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * The collision engine on show, drawn as {@link CgCollisionDebug} lines round a point on the ground: every shape, a
 * cast, rays and a hitbox, each tested against props of its own and, when given one, a host's world. The harness's
 * {@code collision-shapes} and {@code CgRenderDemo}'s {@code collision}.
 *
 * <pre>{@code
 * CgCollisionDemo demo = new CgCollisionDemo();
 * demo.submit(CgCollisionWorld.client(partialTick), x, y, z, seconds);   // each frame, in Minecraft
 * demo.submit(null, 0, 0, 0, seconds);                                   // with no world: a floor at y instead
 * demo.clear();                                                          // switched away from
 * }</pre>
 *
 * <ul>
 *   <li>The row: every shape turning and bobbing through a box floating at the middle of its bob. Green touching its
 *       box (the box red), orange touching the ground, a block or an entity, white apart.</li>
 *   <li>Behind: a ball cast through three walls moving up and down and the world, stopping red at the first it meets
 *       with that surface's normal; over it, rays down, each ending at what it meets. A hit is a cross on the surface
 *       and its normal.</li>
 *   <li>In front: a ring hitbox's front growing through six boxes, each flashing red as it is hit, and through the
 *       world's entities. Every entity within {@link #ENTITY_RANGE} is boxed yellow, and red for a moment once
 *       anything here hits it: a shape, the cast, a ray or the ring.</li>
 *   <li>The props (the ring's boxes and the walls) are passive hittable hitboxes in an empty world of the demo's own,
 *       each standing for its id.</li>
 *   <li>Render thread, once a frame. {@code seconds} is the frame clock: the hitbox ticks at 20 a second of it.</li>
 * </ul>
 */
public final class CgCollisionDemo {

    /** How far round the point entities are boxed. */
    public static final double ENTITY_RANGE = 48;

    private static final int WHITE = 0xFFFFFFFF, GREEN = 0xFF40FF60, GREY = 0xFF808080, RED = 0xFFFF4040,
            YELLOW = 0xFFFFD040, CYAN = 0xFF40D0FF, ORANGE = 0xFFFF9030;
    private static final double SPACING = 4;
    /** The row's bob, and its boxes' height: the shapes pass through a box mid-bob and reach the ground below it. */
    private static final double BOB_Y = 2.4, BOB = 2, TARGET_Y = 2.8;
    private static final CgBox TARGET = CgBox.of(0.6, 0.6, 0.6);
    private static final CgPlane FLOOR = CgPlane.of(0, 1, 0);
    private static final CgSphere NEAR = CgSphere.of(ENTITY_RANGE);

    private static final CgShape[] SHAPES = {
            CgSphere.of(1),
            CgBox.of(1, 0.5, 0.8),
            CgCapsule.of(0.8, 0.5),
            CgCylinder.of(0.8, 0.9),
            CgCone.of(1.6, Math.toRadians(35)),
            CgShell.of(0.6, 1.1),
            CgRing.of(0.6, 1.2, 0.4),
            CgCompound.builder().add(CgCapsule.of(0.7, 0.15), 0, 0, 0).add(CgBox.of(0.5, 0.3, 0.3), 0, 0, 1).build(),
            CgConvex.hull(new double[]{-1, 0, -0.6, 1, 0, -0.6, -1, 0, 0.8, 1, 0, 0.8, -0.6, 0.9, 0, 0.6, 0.9, 0}),
            CgPlane.of(0, 1, 0, 1.4),
    };

    // the cast and the rays, behind the row
    private static final CgSphere BALL = CgSphere.of(0.6);
    private static final double SWEEP_Z = -8, SWEEP_FROM = -15, SWEEP_LENGTH = 30;
    private static final double[] WALLS_X = {-6, 3, 10};
    private static final CgBox WALL = CgBox.of(0.5, 1.5, 1.5);
    private static final int RAYS = 13;
    private static final double RAY_FROM = 9, RAY_LENGTH = 16;

    // the hitbox, in front of the row
    private static final double RING_Z = 10, RING_REACH = 9, RING_TICKS = 40;
    private static final int TARGETS = 6, FLASH_TICKS = 10, FLASHED = 16;
    /** Prop ids in the demo's own world: the ring's boxes, then the walls. */
    private static final int WALL_ID = TARGETS;

    private final CgCollisionWorld props = CgCollisionWorld.empty();
    private final CgHitbox[] targets = new CgHitbox[TARGETS], walls = new CgHitbox[WALLS_X.length];
    private final CgPose pose = new CgPose(), other = new CgPose(), floor = new CgPose();
    private final Quaterniond rotation = new Quaterniond();
    private final CgHit hit = new CgHit(), worldHit = new CgHit();
    private final CgAABB box = new CgAABB();
    private final double[] flash = new double[TARGETS];
    private final int[] flashedIds = new int[FLASHED];
    private final double[] flashedFor = new double[FLASHED];
    private CgHitbox ring, worldRing;
    private double ox, oy, oz;
    private double lastSeconds = Double.NaN, ticks;
    private @Nullable CgCollisionWorld world;
    private final Consumer<CgHit> boxEntity = h -> {
        if (!flashed(h.entity()) && world.entities().box(h.entity(), world.partialTick(), box)) drawBox(YELLOW);
    };

    /** Draws it standing at {@code x, y, z} this frame, against its props and {@code world} (null: a floor at y). */
    public void submit(@Nullable CgCollisionWorld world, double x, double y, double z, double seconds) {
        ticks = Double.isNaN(lastSeconds) ? 0 : Math.max(0, Math.min(10, (seconds - lastSeconds) * 20));
        lastSeconds = seconds;
        if (x != ox || y != oy || z != oz) {
            ox = x;
            oy = y;
            oz = z;
            clear();
        }
        this.world = world;
        if (targets[0] == null) {
            for (int i = 0; i < TARGETS; i++) targets[i] = prop(TARGET, i);
            for (int w = 0; w < WALLS_X.length; w++) walls[w] = prop(WALL, WALL_ID + w);
        }
        placeTargets();
        row(seconds);
        sweep(seconds);
        rays();
        ring();
        if (world != null) entities();
    }

    /** Stops its hitboxes and props: the next submit starts them again. */
    public void clear() {
        if (ring != null) ring.stop();
        if (worldRing != null) worldRing.stop();
        ring = worldRing = null;
        for (int i = 0; i < TARGETS; i++) {
            if (targets[i] != null) targets[i].stop();
            targets[i] = null;
        }
        for (int w = 0; w < WALLS_X.length; w++) {
            if (walls[w] != null) walls[w].stop();
            walls[w] = null;
        }
        Arrays.fill(flash, 0);
        Arrays.fill(flashedFor, 0);
    }

    // ---- the row

    private void row(double seconds) {
        double left = -(SHAPES.length - 1) * SPACING / 2;
        for (int i = 0; i < SHAPES.length; i++) {
            double x = ox + left + i * SPACING;
            pose.at(x, oy + BOB_Y + BOB * Math.sin(seconds * 0.9 + i), oz)
                    .facing(seconds * 0.5 + i, Math.sin(seconds * 0.4 + i));
            other.identity().at(x, oy + TARGET_Y, oz);
            boolean touching = CgShapes.overlaps(SHAPES[i], pose, TARGET, other);
            boolean grounded = !touching && touchesGround(SHAPES[i], pose);
            CgCollisionDebug.shape(SHAPES[i], pose, touching ? GREEN : grounded ? ORANGE : WHITE);
            CgCollisionDebug.shape(TARGET, other, touching ? RED : GREY);
        }
    }

    private boolean touchesGround(CgShape shape, CgPose at) {
        if (world == null) return CgShapes.overlaps(shape, at, FLOOR, floor.identity().at(ox, oy, oz));
        if (!world.overlap(shape).at(at.x, at.y, at.z).rotation(at.getRotation(rotation)).blocksToo().allEntities()
                .first(worldHit)) {
            return false;
        }
        if (worldHit.entity() >= 0) flashEntity(worldHit.entity());
        return true;
    }

    // ---- the cast and the rays

    private void sweep(double seconds) {
        double y = oy + 1.5, z = oz + SWEEP_Z, from = ox + SWEEP_FROM;
        for (int w = 0; w < WALLS_X.length; w++) {
            // from standing on the ground to its bottom over the ball, never under the ground
            double rise = WALL.halfY() * (1 + Math.sin(seconds * (0.7 + 0.3 * w) + w * 2));
            other.identity().at(ox + WALLS_X[w], oy + WALL.halfY() + rise, z);
            walls[w].pose(other).tick(props, 1);
            CgCollisionDebug.shape(WALL, other, YELLOW);
        }
        boolean met = props.cast(BALL).at(from, y, z).along(1, 0, 0, SWEEP_LENGTH).allEntities().first(hit);
        if (world != null
                && world.cast(BALL).at(from, y, z).along(1, 0, 0, SWEEP_LENGTH).blocksToo().allEntities()
                .first(worldHit)
                && (!met || worldHit.distance() < hit.distance())) {
            hit.set(worldHit);
            met = true;
            if (hit.entity() >= 0) flashEntity(hit.entity());
        }
        double end = from + (met ? hit.distance() : SWEEP_LENGTH);
        CgCollisionDebug.shape(BALL, pose.identity().at(from, y, z), WHITE);
        CgCollisionDebug.line(from, y, z, end, y, z, WHITE);
        CgCollisionDebug.shape(BALL, pose.identity().at(end, y, z), met ? RED : WHITE);
        if (met) CgCollisionDebug.mark(hit, RED);
    }

    private void rays() {
        double z = oz + SWEEP_Z, top = oy + RAY_FROM;
        for (int i = 0; i < RAYS; i++) {
            double x = ox + SWEEP_FROM + 2.5 * i;
            boolean met = props.ray().at(x, top, z).along(0, -1, 0, RAY_LENGTH).allEntities().first(hit);
            if (world != null
                    && world.ray().at(x, top, z).along(0, -1, 0, RAY_LENGTH).blocksToo().allEntities()
                    .first(worldHit)
                    && (!met || worldHit.distance() < hit.distance())) {
                hit.set(worldHit);
                met = true;
                if (hit.entity() >= 0) flashEntity(hit.entity());
            }
            if (met) {
                CgCollisionDebug.line(x, top, z, x, hit.y(), z, CYAN);
                CgCollisionDebug.mark(hit, RED);
            } else if (world == null) {
                CgCollisionDebug.line(x, top, z, x, oy, z, CYAN);
                CgCollisionDebug.mark(x, oy, z, 0, 1, 0, RED);
            } else {
                CgCollisionDebug.line(x, top, z, x, top - RAY_LENGTH, z, GREY);
            }
        }
    }

    // ---- the hitbox

    private void placeTargets() {
        for (int i = 0; i < TARGETS; i++) {
            double angle = 2 * Math.PI * i / TARGETS, at = 2 + i * 1.2;
            targets[i].pose(other.identity().at(ox + Math.cos(angle) * at, oy + 0.6, oz + RING_Z + Math.sin(angle) * at))
                    .tick(props, 1);
        }
    }

    private void ring() {
        if (ring == null || ring.stopped()) {
            if (worldRing != null) worldRing.stop();
            ring = grow().query(q -> q.where(id -> id < TARGETS)).onHit(h -> flash[h.entity()] = FLASH_TICKS);
            worldRing = world == null ? null : grow().query(CgQuery::allEntities).onHit(h -> flashEntity(h.entity()));
        }
        ring.tick(props, ticks);
        if (worldRing != null && world != null) worldRing.tick(world, ticks);
        CgCollisionDebug.hitbox(ring, CYAN);
        for (int i = 0; i < TARGETS; i++) {
            double angle = 2 * Math.PI * i / TARGETS, at = 2 + i * 1.2;
            other.identity().at(ox + Math.cos(angle) * at, oy + 0.6, oz + RING_Z + Math.sin(angle) * at);
            CgCollisionDebug.shape(TARGET, other, flash[i] > 0 ? RED : GREY);
            flash[i] = Math.max(0, flash[i] - ticks);
        }
    }

    /** A prop standing for {@code id}: met by the demo's queries and hitboxes, hitting nothing itself. */
    private static CgHitbox prop(CgShape shape, int id) {
        return CgHitbox.of(shape).source(id).hittable().passive();
    }

    private CgHitbox grow() {
        return CgHitbox.of(CgRing.front(1.0, 0.8)).at(ox, oy + 0.8, oz + RING_Z).radius(0, RING_REACH, RING_TICKS)
                .activeFor(RING_TICKS);
    }

    private boolean flashed(int id) {
        for (int i = 0; i < FLASHED; i++) {
            if (flashedFor[i] > 0 && flashedIds[i] == id) return true;
        }
        return false;
    }

    private void flashEntity(int id) {
        int free = -1;
        for (int i = 0; i < FLASHED; i++) {
            if (flashedFor[i] > 0 && flashedIds[i] == id) free = i;
            else if (free < 0 && flashedFor[i] <= 0) free = i;
        }
        if (free < 0) return;
        flashedIds[free] = id;
        flashedFor[free] = FLASH_TICKS;
    }

    // ---- the world's entities

    private void entities() {
        world.overlap(NEAR).at(ox, oy, oz).allEntities().forEach(boxEntity);
        for (int i = 0; i < FLASHED; i++) {
            if (flashedFor[i] <= 0) continue;
            if (world.entities().box(flashedIds[i], world.partialTick(), box)) drawBox(RED);
            flashedFor[i] -= ticks;
        }
    }

    private void drawBox(int argb) {
        double x0 = box.minX(), y0 = box.minY(), z0 = box.minZ(), x1 = box.maxX(), y1 = box.maxY(), z1 = box.maxZ();
        for (int i = 0; i < 4; i++) {
            double x = (i & 1) == 0 ? x0 : x1, z = (i & 2) == 0 ? z0 : z1;
            CgCollisionDebug.line(x, y0, z, x, y1, z, argb);
            CgCollisionDebug.line(x0, (i & 1) == 0 ? y0 : y1, (i & 2) == 0 ? z0 : z1,
                    x1, (i & 1) == 0 ? y0 : y1, (i & 2) == 0 ? z0 : z1, argb);
            CgCollisionDebug.line((i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, z0,
                    (i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, z1, argb);
        }
    }
}
