package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgService;

import java.util.function.IntConsumer;

/**
 * Answers questions about the host's entities on the client: where one is this frame, how it is posed and moving, what
 * kind it is, and which are inside a box. What an effect needs to fire from a player's hands, wrap an aura round them,
 * follow a mob, or stop a beam on whatever it hits. Entities are named by int ids, which mean one entity for as long as
 * {@link CgWorldQuery#levelEpoch} stays the same; {@code com.crystalgraphics.world.CgEntityAttachments} turns a pose into
 * hand, head and feet points, once, for every host.
 *
 * <pre>{@code
 * CgEntityQuery entities = CgPlatform.get(CgEntityQuery.SERVICE);
 * double[] pose = new double[CgEntityQuery.POSE_LENGTH];         // once, and reused
 * int me = entities.localPlayer();
 * if (me >= 0 && entities.pose(me, partialTick, pose)) aimFrom(pose[CgEntityQuery.X], pose[CgEntityQuery.Y], ...);
 *
 * // every entity in a box: the visitor gets ids, and is best held in a field
 * entities.within(minX, minY, minZ, maxX, maxY, maxZ, hitEntity);
 *
 * // a host, once, on a client only
 * CgPlatform.provide(CgEntityQuery.SERVICE, new EntityQueryModern());
 * }</pre>
 *
 * <ul>
 *   <li>Positions are absolute world coordinates, interpolated by the {@code partialTick} given: the frame's
 *       ({@code CgHostFrame.partialTick()}) puts an effect where the entity is drawn.</li>
 *   <li>Render thread only, like {@link CgWorldQuery}. Nothing here hands out a Minecraft object; only
 *       {@link #within} may allocate, the iterator over the host's entity list.</li>
 *   <li>{@link #NONE} answers -1, false and nothing: the harness, a dedicated server, no level.</li>
 * </ul>
 */
public interface CgEntityQuery {

    /** Where each value of a {@link #pose} lands. Angles are Minecraft's, in degrees: yaw 0 faces +z, pitch down positive. */
    int X = 0, Y = 1, Z = 2, VELOCITY_X = 3, VELOCITY_Y = 4, VELOCITY_Z = 5, WIDTH = 6, HEIGHT = 7, EYE_HEIGHT = 8,
            YAW = 9, PITCH = 10, BODY_YAW = 11, HEAD_YAW = 12, LIMB_SWING = 13, LIMB_SWING_AMOUNT = 14, POSE_LENGTH = 15;

    /** {@link #flags} bits. */
    int ALIVE = 1, LIVING = 1 << 1, PLAYER = 1 << 2, LOCAL_PLAYER = 1 << 3, ON_GROUND = 1 << 4, SNEAKING = 1 << 5,
            SPRINTING = 1 << 6, SWIMMING = 1 << 7, GLIDING = 1 << 8, IN_WATER = 1 << 9, ON_FIRE = 1 << 10,
            INVISIBLE = 1 << 11;

    /** What an entity is, as an effect would sort it: {@link #kind}. */
    int KIND_OTHER = 0, KIND_PLAYER = 1, KIND_MONSTER = 2, KIND_ANIMAL = 3, KIND_ITEM = 4, KIND_PROJECTILE = 5,
            KIND_VEHICLE = 6;

    /** No entities: what a server, the harness or a client between levels reads. */
    CgEntityQuery NONE = new CgEntityQuery() {
        @Override public int localPlayer() { return -1; }
        @Override public int cameraEntity() { return -1; }
        @Override public boolean pose(int id, float partialTick, double[] out) { return false; }
        @Override public int flags(int id) { return 0; }
        @Override public int kind(int id) { return KIND_OTHER; }
        @Override public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                     IntConsumer visitor) { }
    };

    CgService<CgEntityQuery> SERVICE = CgService.of("crystalgraphics:entity_query", NONE);

    /** The player at this client's keyboard; -1 for none. */
    int localPlayer();

    /** The entity the camera follows: the local player, unless spectating another; -1 for none. */
    int cameraEntity();

    /**
     * Entity {@code id}'s pose at {@code partialTick} between its last tick and the next, into {@code out} at the indices
     * above (at least {@link #POSE_LENGTH} long). False, with {@code out} untouched, when there is no such entity.
     * Velocity is in blocks a tick; width and height its box, eye height from its feet.
     */
    boolean pose(int id, float partialTick, double[] out);

    /** Entity {@code id}'s state as bits ({@link #ALIVE}, {@link #SNEAKING}, ...); 0 when there is no such entity. */
    int flags(int id);

    /** What entity {@code id} is: a {@code KIND_} constant. */
    int kind(int id);

    /** Hands {@code visitor} the id of every entity whose box touches the box given, in absolute world coordinates. */
    void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, IntConsumer visitor);
}
