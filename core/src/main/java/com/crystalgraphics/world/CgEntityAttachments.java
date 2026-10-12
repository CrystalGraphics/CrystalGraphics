package com.crystalgraphics.world;

import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgModelSpace;
import com.crystalgraphics.render.stage.CgRenderStage;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3dc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Points on an entity an effect attaches to: its feet, chest, head, eyes and hands, and any point a mod names on its
 * own model, wherever that model is drawn this frame, whatever animates it. {@code follow} rides them.
 *
 * <p>Each point is answered, in order, by a {@link #mark} made this frame or the last; the first {@link Provider} that
 * knows it; else the proportions of Minecraft's humanoid model over the entity's {@link CgEntityQuery#pose} (its box,
 * eye height, body yaw and look). So a mod that does nothing still gets hands in about the right place.</p>
 *
 * <p><b>The least work: mark the point where the model is drawn.</b> Wherever the renderer has the part's matrix
 * (a GeckoLib renderer, a render layer over a vanilla model that playerAnimator or Emotecraft pose, a hand-written
 * renderer), one call per frame:</p>
 *
 * <pre>{@code
 * // At init: the name is the point's identity, the second argument where it falls back to unmarked
 * static final int PALM = CgEntityAttachments.declare("mymod:right_palm", CgEntityAttachments.RIGHT_HAND);
 *
 * // A render layer over a vanilla player model: the arm as posed this frame, 10 pixels down it
 * model.rightArm.translateAndRotate(poseStack);
 * CgEntityAttachments.mark(player.getId(), PALM, poseStack.last().pose(), 0f, 10f / 16f, 0f);
 *
 * // GeckoLib, with the bone's matrices tracked: it already knows where the bone is in the world
 * CgEntityAttachments.mark(entity.getId(), PALM, bone.getWorldPosition());
 *
 * // Anywhere: an effect follows it
 * owner.play(new MyEffect(look, x, y, z).follow(entityId, PALM));
 * }</pre>
 *
 * <p><b>Or answer when asked</b>, for a point worked out from state rather than drawn:</p>
 *
 * <pre>{@code
 * CgEntityAttachments.provide((entity, point, partialTick, pose, out) -> {
 *     if (point != PALM) return false;                      // not ours: the next provider, then the proportions
 *     out[0] = pose[CgEntityQuery.X];                       // pose: the entity's at partialTick
 *     out[1] = pose[CgEntityQuery.Y] + 1.2;
 *     out[2] = pose[CgEntityQuery.Z];
 *     return true;
 * });
 * }</pre>
 *
 * <p>Built-in points can be marked and answered too: marking {@link #HANDS_FORWARD} from a model's hands moves every
 * effect that already follows them, with no change to the code that plays them.</p>
 *
 * <ul>
 *   <li>Absolute world coordinates, into a {@code double[3]} the caller keeps.</li>
 *   <li>Marks and providers run on the render thread and must not allocate. A matrix mark is the matrix the entity is
 *       drawn with in the world, at the frame's partial tick; one taken elsewhere (the inventory's figure, a shader
 *       pack's shadow pass) lands far from the entity and is dropped. Hosts before Minecraft 1.15 have no pose stack,
 *       and drop matrix marks.</li>
 *   <li>An entity not drawn this frame (the player in first person) is not marked, so it falls to the providers and
 *       the proportions.</li>
 *   <li>{@link #declare} the same name twice and the same id comes back, so two mods naming one point share it.</li>
 * </ul>
 */
public final class CgEntityAttachments {

    public static final int FEET = 0, CENTRE = 1, CHEST = 2, HEAD = 3, EYES = 4, RIGHT_HAND = 5, LEFT_HAND = 6,
            RIGHT_HAND_FORWARD = 7, LEFT_HAND_FORWARD = 8, HANDS_FORWARD = 9;
    private static final int BUILT_IN = 10;

    // A player's model, as shares of its height: shoulders at 22 of 32 pixels, 5 out from the middle, the hand 11 below.
    private static final double SHOULDER = 22.0 / 32.0, SHOULDER_OUT = 5.0 / 32.0, ARM = 11.0 / 32.0;

    /** Answers attachment points of entities; {@code false} for a point or entity it does not know. */
    @FunctionalInterface
    public interface Provider {
        /**
         * Writes point {@code point} of entity {@code entity} at {@code partialTick} into {@code out} as absolute world
         * coordinates. {@code pose} is the entity's {@link CgEntityQuery#pose} at that partial tick.
         */
        boolean point(int entity, int point, float partialTick, double[] pose, double[] out);
    }

    /** A provider registered with {@link #provide}; closing it unregisters it. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private static volatile Provider[] providers = new Provider[0];
    private static final Map<String, Integer> IDS = new HashMap<>();
    private static final List<String> NAMES = new ArrayList<>();
    // Each declared point's built-in fallback, by id - BUILT_IN.
    private static volatile int[] fallbacks = new int[0];

    // Marks, render thread only: an open-addressed table keyed by entity and point.
    private static long[] markKeys = new long[64], markFrames = new long[64];
    private static double[] markPoints = new double[64 * 3];
    private static int markCount;
    private static final long EMPTY = Long.MIN_VALUE;

    // The model space of the frame matrix marks were last resolved in.
    private static long spaceFrame = -1;
    private static boolean spaceKnown;
    private static final double[] CAMERA = new double[3];
    private static final float[] BASE = new float[16];
    private static final Matrix4f UNBASE = new Matrix4f();
    private static final Vector3f SCRATCH = new Vector3f();
    private static final double[] GUARD_POSE = new double[CgEntityQuery.POSE_LENGTH];

    static {
        Arrays.fill(markKeys, EMPTY);
        String[] names = {"feet", "centre", "chest", "head", "eyes", "right_hand", "left_hand", "right_hand_forward",
                "left_hand_forward", "hands_forward"};
        for (int i = 0; i < names.length; i++) {
            IDS.put("crystalgraphics:" + names[i], i);
            NAMES.add("crystalgraphics:" + names[i]);
        }
    }

    private CgEntityAttachments() {
    }

    /**
     * The id of the point named {@code name} ({@code "mymod:right_palm"}), declaring it on first use with
     * {@code fallback}, the built-in point it is placed at when nothing marks or answers it. Call at init and keep it.
     */
    public static synchronized int declare(String name, int fallback) {
        if (fallback < 0 || fallback >= BUILT_IN) throw new IllegalArgumentException("not a built-in point: " + fallback);
        Integer known = IDS.get(name);
        if (known != null) return known;
        int id = NAMES.size();
        IDS.put(name, id);
        NAMES.add(name);
        int[] grown = Arrays.copyOf(fallbacks, id - BUILT_IN + 1);
        grown[id - BUILT_IN] = fallback;
        fallbacks = grown;
        return id;
    }

    /** The id of the point named {@code name} on this side, built in or declared; -1 for none. */
    public static synchronized int find(String name) {
        Integer id = IDS.get(name);
        return id == null ? -1 : id;
    }

    /** The name a point was declared with, for a log line. */
    public static synchronized String name(int point) {
        return point >= 0 && point < NAMES.size() ? NAMES.get(point) : "unknown point " + point;
    }

    /** Point {@code point} of {@code entity} is at world {@code (x, y, z)} this frame. Render thread. */
    public static void mark(int entity, int point, double x, double y, double z) {
        long key = key(entity, point);
        int slot = find(key);
        if (markKeys[slot] == EMPTY) {
            if (++markCount * 2 > markKeys.length) {
                rebuild();
                slot = find(key);
                markCount++;
            }
            markKeys[slot] = key;
        }
        markFrames[slot] = CgFrameRing.frame();
        markPoints[slot * 3] = x;
        markPoints[slot * 3 + 1] = y;
        markPoints[slot * 3 + 2] = z;
    }

    /** As {@link #mark(int, int, double, double, double)}, from a world position: GeckoLib's {@code getWorldPosition()}. */
    public static void mark(int entity, int point, Vector3dc world) {
        mark(entity, point, world.x(), world.y(), world.z());
    }

    /**
     * Point {@code point} of {@code entity} is the origin of {@code pose}, the matrix its model is being drawn with in
     * the world right now: {@code poseStack.last().pose()}. Render thread.
     */
    public static void mark(int entity, int point, Matrix4fc pose) {
        mark(entity, point, pose, 0f, 0f, 0f);
    }

    /** As {@link #mark(int, int, Matrix4fc)}, at {@code (x, y, z)} in the pose's space: model units, a pixel 1/16. */
    public static void mark(int entity, int point, Matrix4fc pose, float x, float y, float z) {
        long frame = CgFrameRing.frame();
        if (frame != spaceFrame) {
            spaceFrame = frame;
            spaceKnown = CgPlatform.get(CgModelSpace.SERVICE).frame(CAMERA, BASE);
            if (spaceKnown) UNBASE.set(BASE).invert();
        }
        if (!spaceKnown) return;
        Vector3f at = UNBASE.transformPosition(pose.transformPosition(x, y, z, SCRATCH));
        double wx = CAMERA[0] + at.x, wy = CAMERA[1] + at.y, wz = CAMERA[2] + at.z;
        // Drawn somewhere other than the world this frame: the inventory's figure, a shadow pass.
        CgEntityQuery entities = CgPlatform.get(CgEntityQuery.SERVICE);
        if (!entities.pose(entity, CgRenderStage.WORLD_OPAQUE.host().partialTick(), GUARD_POSE)) return;
        double reach = 2.0 * Math.max(GUARD_POSE[CgEntityQuery.HEIGHT], GUARD_POSE[CgEntityQuery.WIDTH]) + 2.0;
        double dx = wx - GUARD_POSE[CgEntityQuery.X], dy = wy - GUARD_POSE[CgEntityQuery.Y], dz = wz - GUARD_POSE[CgEntityQuery.Z];
        if (dx * dx + dy * dy + dz * dz > reach * reach) return;
        mark(entity, point, wx, wy, wz);
    }

    /** Registers {@code provider} after those already registered; the first that answers a point wins. */
    public static synchronized Registration provide(Provider provider) {
        if (provider == null) throw new IllegalArgumentException("provider");
        Provider[] grown = Arrays.copyOf(providers, providers.length + 1);
        grown[providers.length] = provider;
        providers = grown;
        return () -> remove(provider);
    }

    private static synchronized void remove(Provider provider) {
        for (int i = 0; i < providers.length; i++) {
            if (providers[i] != provider) continue;
            Provider[] shrunk = new Provider[providers.length - 1];
            System.arraycopy(providers, 0, shrunk, 0, i);
            System.arraycopy(providers, i + 1, shrunk, i, shrunk.length - i);
            providers = shrunk;
            return;
        }
    }

    /**
     * Point {@code which} of {@code entity}, posed by {@code pose} at {@code partialTick}, into {@code out}: its mark
     * from this frame or the last, else the first provider's answer, else the proportions. Render thread.
     */
    public static double[] point(int entity, int which, float partialTick, double[] pose, double[] out) {
        int slot = find(key(entity, which));
        if (markKeys[slot] != EMPTY && markFrames[slot] >= CgFrameRing.frame() - 1) {
            return set(out, markPoints[slot * 3], markPoints[slot * 3 + 1], markPoints[slot * 3 + 2]);
        }
        Provider[] registered = providers;
        for (Provider provider : registered) {
            if (provider.point(entity, which, partialTick, pose, out)) return out;
        }
        return point(which, pose, out);
    }

    /** The attachment point {@code which} of the entity posed by {@code pose}, from its proportions alone, into {@code out}. */
    public static double[] point(int which, double[] pose, double[] out) {
        if (which >= BUILT_IN) {
            int[] declared = fallbacks;
            which = which - BUILT_IN < declared.length ? declared[which - BUILT_IN] : FEET;
        }
        double x = pose[CgEntityQuery.X], y = pose[CgEntityQuery.Y], z = pose[CgEntityQuery.Z];
        double h = pose[CgEntityQuery.HEIGHT];
        switch (which) {
            case FEET: return set(out, x, y, z);
            case CENTRE: return set(out, x, y + h * 0.5, z);
            case CHEST: return set(out, x, y + h * 0.6, z);
            case HEAD: return set(out, x, y + Math.min(pose[CgEntityQuery.EYE_HEIGHT] + h * 0.03, h), z);
            case EYES: return set(out, x, y + pose[CgEntityQuery.EYE_HEIGHT], z);
            default: break;
        }
        // The body's right, from its yaw: yaw 0 faces +z, so its right hand is toward -x.
        double body = Math.toRadians(pose[CgEntityQuery.BODY_YAW]);
        double rightX = -Math.cos(body), rightZ = -Math.sin(body);
        double side = which == LEFT_HAND || which == LEFT_HAND_FORWARD ? -1.0 : which == HANDS_FORWARD ? 0.0 : 1.0;
        double sx = x + rightX * SHOULDER_OUT * h * side, sy = y + SHOULDER * h, sz = z + rightZ * SHOULDER_OUT * h * side;
        if (which == RIGHT_HAND || which == LEFT_HAND) return set(out, sx, sy - ARM * h, sz);
        // Held out along the look; both hands meet a little lower and nearer, in front of the chest.
        forward(pose, out);
        double reach = which == HANDS_FORWARD ? ARM * h * 0.9 : ARM * h, drop = which == HANDS_FORWARD ? h * 0.06 : 0.0;
        return set(out, sx + out[0] * reach, sy - drop + out[1] * reach, sz + out[2] * reach);
    }

    /** The way the entity looks, a unit vector, into {@code out}. */
    public static double[] forward(double[] pose, double[] out) {
        double yaw = Math.toRadians(pose[CgEntityQuery.YAW]), pitch = Math.toRadians(pose[CgEntityQuery.PITCH]);
        return set(out, -Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }

    private static long key(int entity, int point) {
        return ((long) entity << 32) | (point & 0xFFFFFFFFL);
    }

    /** The slot holding {@code key}, or the empty slot it would go in. */
    private static int find(long key) {
        int mask = markKeys.length - 1;
        int slot = (int) (key ^ (key >>> 29) ^ (key >>> 47)) * 0x9E3779B9 >>> 7 & mask;
        while (markKeys[slot] != EMPTY && markKeys[slot] != key) slot = slot + 1 & mask;
        return slot;
    }

    /** Drops marks too old to be read and grows the table if it is still half full. */
    private static void rebuild() {
        long[] keys = markKeys, frames = markFrames;
        double[] points = markPoints;
        long oldest = CgFrameRing.frame() - 1;
        int live = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i] != EMPTY && frames[i] >= oldest) live++;
        int size = keys.length;
        while ((live + 1) * 2 > size) size *= 2;
        markKeys = new long[size];
        markFrames = new long[size];
        markPoints = new double[size * 3];
        Arrays.fill(markKeys, EMPTY);
        markCount = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == EMPTY || frames[i] < oldest) continue;
            int slot = find(keys[i]);
            markKeys[slot] = keys[i];
            markFrames[slot] = frames[i];
            System.arraycopy(points, i * 3, markPoints, slot * 3, 3);
            markCount++;
        }
    }

    private static double[] set(double[] out, double x, double y, double z) {
        out[0] = x;
        out[1] = y;
        out[2] = z;
        return out;
    }
}
