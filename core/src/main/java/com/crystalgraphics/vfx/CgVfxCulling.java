package com.crystalgraphics.vfx;

import com.crystalgraphics.render.CgViewFrustum;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.vfx.scalability.CgVfxBudget;
import com.crystalgraphics.vfx.scalability.CgVfxScalability;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Its system's scalability pass, once an update against the latest frame's camera: each effect's distance to its bounds
 * and whether any of it is in view, the caps of each {@link CgVfxScalability} and each look, and the reaction to what
 * is culled. Render thread; allocates only when a new scalability or look first appears.
 *
 * <pre>{@code
 * culling.view(view);                          // the camera the world last drew with
 * culling.run(effects, dt, localPlayer, CgVfxBudget.get().worstAdjustedUsage());
 * // then each effect's wanted, lowered and distanceShare are set: the system reacts to each
 * }</pre>
 */
final class CgVfxCulling {

    /** The effects sharing one cap: those wanted this update, and their distances, nearest kept. */
    private static final class Cap {
        CgVfxEffect<?>[] members = new CgVfxEffect<?>[8];
        long[] keys = new long[8];
        int count;
        /** How many it keeps at rest, and its scalability's scale of that under the budget. */
        int most;
        float scale;

        void add(CgVfxEffect<?> effect) {
            if (count == members.length) {
                members = Arrays.copyOf(members, count * 2);
                keys = Arrays.copyOf(keys, count * 2);
            }
            members[count] = effect;
            // Sorted by distance, ties by arrival: a positive float's bits order as the float does.
            keys[count] = (long) Float.floatToRawIntBits(effect.cullDistance) << 32 | count;
            count++;
        }

        /**
         * Culls all but the nearest {@link #most} times its scale, truncated as Niagara's scaled counts are, keeping the
         * local player's whatever the count.
         */
        void keep(int localPlayer) {
            int kept = (int) (most * scale);
            if (count > kept) {
                Arrays.sort(keys, 0, count);
                for (int i = kept; i < count; i++) {
                    CgVfxEffect<?> e = members[(int) keys[i]];
                    if (localPlayer < 0 || e.followEntity != localPlayer) e.wanted = false;
                }
            }
            Arrays.fill(members, 0, count, null);
            count = 0;
        }
    }

    private final CgViewFrustum frustum = new CgViewFrustum();
    private final Matrix4f viewProjection = new Matrix4f();
    private double eyeX, eyeY, eyeZ;
    private boolean viewed;
    /** One cap per scalability and per look with a limit, found by key and walked in order. */
    private final Map<Object, Cap> caps = new IdentityHashMap<>();
    private final List<Cap> capList = new ArrayList<>();

    /** The camera to cull against: the world's latest. No view (projection all zero) culls nothing. */
    void view(CgHostView view) {
        viewProjection.set(view.projection()).mul(view.view());
        viewed = view.projection().m00() != 0f;
        if (viewed) frustum.set(viewProjection);
        eyeX = view.x();
        eyeY = view.y();
        eyeZ = view.z();
    }

    /** Whether there is a camera to cull against. */
    boolean viewed() {
        return viewed;
    }

    /** How far the camera is from {@code (x, y, z)}: what a play is pre-culled by, before it has bounds. */
    float distanceTo(double x, double y, double z) {
        double dx = x - eyeX, dy = y - eyeY, dz = z - eyeZ;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Decides every effect's {@code wanted}, {@code lowered} and {@code distanceShare} for this update, {@code dt}
     * seconds after the last, each scalability's distances and caps scaled by its ramps at the budget's {@code usage}
     * and its effects culled past its budget usage.
     */
    void run(List<CgVfxEffect<?>> effects, float dt, int localPlayer, float usage) {
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect<?> e = effects.get(i);
            if (e.state() == CgVfxEffect.State.DEAD) continue;
            CgVfxScalability s = e.scalability();
            if (!viewed || !s.culls() || e.authoritative()) {
                e.wanted = true;
                e.lowered = 0;
                e.distanceShare = 1f;
                e.unseenFor = 0f;
                continue;
            }
            boolean seen = measure(e);
            e.unseenFor = seen ? 0f : e.unseenFor + dt;
            float d = e.cullDistance;
            boolean own = localPlayer >= 0 && e.followEntity == localPlayer;
            float reach = s.distanceScale(usage);
            e.wanted = own || d <= s.maxDistance() * reach && e.unseenFor < s.unseen() && usage < s.cullUsage();
            float lod = s.lodDistance();
            e.lowered = d > 2f * lod ? 2 : d > lod ? 1 : 0;
            e.distanceShare = e.lowered == 2 ? 0.25f : e.lowered == 1 ? 0.5f : 1f;
            if (!e.wanted) continue;
            if (s.maxInstances() < Integer.MAX_VALUE) cap(s, s.maxInstances(), s.instanceScale(usage)).add(e);
            if (s.maxPerLook() < Integer.MAX_VALUE) cap(e.look(), s.maxPerLook(), s.perLookScale(usage)).add(e);
        }
        for (int i = 0; i < capList.size(); i++) capList.get(i).keep(localPlayer);
    }

    /**
     * Sets {@code e}'s distance from the camera to its bounds (0 inside them), its origin where it has drawn nothing yet;
     * answers whether any of them is in view.
     */
    private boolean measure(CgVfxEffect<?> e) {
        double ox = e.originX - eyeX, oy = e.originY - eyeY, oz = e.originZ - eyeZ;
        if (!e.bounded()) {
            e.cullDistance = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
            return frustum.testSphere((float) ox, (float) oy, (float) oz, 0.5f);
        }
        float minX = (float) ox + e.boundMinX, minY = (float) oy + e.boundMinY, minZ = (float) oz + e.boundMinZ;
        float maxX = (float) ox + e.boundMaxX, maxY = (float) oy + e.boundMaxY, maxZ = (float) oz + e.boundMaxZ;
        float dx = Math.max(0f, Math.max(minX, -maxX)), dy = Math.max(0f, Math.max(minY, -maxY));
        float dz = Math.max(0f, Math.max(minZ, -maxZ));
        e.cullDistance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return frustum.testAabb(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** {@code key}'s cap, keeping {@code most} at rest times {@code scale}: a scalability's or a look's. */
    private Cap cap(Object key, int most, float scale) {
        Cap cap = caps.get(key);
        if (cap == null) {
            caps.put(key, cap = new Cap());
            capList.add(cap);
        }
        cap.most = most;
        cap.scale = scale;
        return cap;
    }
}
