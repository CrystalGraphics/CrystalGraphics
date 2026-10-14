package com.crystalgraphics.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgWorldStimulus;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgPlayers;
import com.crystalgraphics.world.CgWorldQueries;

/**
 * Proves, in a running single-player game, what collision does with real entities: a ring hitbox hitting two still
 * targets once each, nearest first; an Ender Dragon's parts found and each reporting the dragon as its parent, its head
 * struck by a ray on the server and on the client; a painting's box thin against its wall and struck on its face; and
 * what a batch saves over a hundred targets. It summons what it hits through {@link CgWorldStimulus} and removes it
 * after.
 *
 * <pre>{@code
 * -Dcrystalgraphics.collisionprobe=true      // a dev client's autotest, or prodSmoke -PcgSmokeProps=...
 * // what it logs, and what prodSmoke reads:
 * collision probe ring.hits: true (stands 412 then 413, once each)
 * collision probe dragon.head server: true (part 431 of dragon 430)
 * collision probe batch.gain is 1000 overlaps over 100 stands: live 4.1 ms, batched 2.9 ms
 * collision probe: true (11 passed, 0 failed)
 * }</pre>
 *
 * <ul>
 *   <li>It changes the world: two targets and a hundred stands beside the player, a stone and a painting in front, a
 *       dragon high above, then every entity but players within 48 blocks removed.</li>
 *   <li>Skipped, with the reason, where there is no single-player server.</li>
 *   <li>{@link #running()} lets an autotest wait for it before quitting.</li>
 * </ul>
 */
public final class CgCollisionProbe {

    public static final boolean ENABLED = CgVar.bool("crystalgraphics.collisionprobe", false, CgVar.DEV | CgVar.RESTART,
            "Prove in a running game that collision finds and hits real entities -- a ring on stands, a dragon's parts,"
                    + " a painting -- logging a verdict per check for prodSmoke and a dev client's autotest.").get();

    private static final Logger LOGGER = LogManager.getLogger("CgCollisionProbe");

    /** Client ticks in a world before summoning; server ticks for summons to land; the ring's ticks. */
    private static final int SETTLE = 60, LAND = 20, FIND_LIMIT = 200, RING = 14;
    private static final int GRID = 10, OVERLAPS = 1000, ROUNDS = 5;
    private static final double CLEAR = 48;

    private enum Phase { WAITING, SETTLING, SUMMONED, RING, CLIENT, DONE }

    private static volatile Phase phase = Phase.WAITING;
    /** The block the player stood on when it summoned; everything is placed from it. */
    private static volatile int bx, by, bz;
    private static volatile int dragon = -1, head = -1, painting = -1;
    private static final AtomicInteger passed = new AtomicInteger(), failed = new AtomicInteger();
    private static int clientTicks, serverTicks;
    private static final List<Integer> ringHits = new ArrayList<>();
    private static int standNear = -1, standFar = -1;
    private static CgHitbox ring;
    private static boolean installed;

    private CgCollisionProbe() {
    }

    /** Both halves; each runs only where its side does. Once, from {@code CgNetwork.install}. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        CgNetwork.onClientTick(CgCollisionProbe::clientTick);
        CgNetwork.onServerTick(CgCollisionProbe::serverTick);
        LOGGER.info("collision probe: armed");
    }

    /** True from the first world tick to the verdict: an autotest that quits now loses it. */
    public static boolean running() {
        return ENABLED && phase != Phase.WAITING && phase != Phase.DONE;
    }

    // ── The client: summons, then its own half of the checks ─────────────────────────────────────

    private static void clientTick() {
        CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
        CgEntityQuery entities = CgEntityQueries.client();
        int me = entities.localPlayer();
        switch (phase) {
            case WAITING:
                if (me < 0) return;
                stimulus.keepRunning();
                clientTicks = 0;
                phase = Phase.SETTLING;
                return;
            case SETTLING:
                if (++clientTicks < SETTLE) return;
                summon(stimulus, entities, me);
                return;
            case CLIENT:
                clientChecks(entities);
                stimulus.clear(bx + 0.5, by, bz + 0.5, CLEAR);
                finish();
                return;
            default:
        }
    }

    private static void summon(CgWorldStimulus stimulus, CgEntityQuery entities, int me) {
        double[] pose = new double[CgEntityQuery.POSE_LENGTH];
        if (!entities.pose(me, 1f, pose)) return;
        bx = (int) Math.floor(pose[CgEntityQuery.X]);
        by = (int) Math.floor(pose[CgEntityQuery.Y]);
        bz = (int) Math.floor(pose[CgEntityQuery.Z]);
        // Two targets east and west, the near one first; a painting ahead (north of its stone); a dragon far above;
        // a hundred stands to the east, past the ring's reach, for the batch.
        boolean single = stimulus.stand(bx + 3.5, by, bz + 0.5) & stimulus.stand(bx - 4.5, by, bz + 0.5)
                & stimulus.painting(bx, by + 1, bz + 4) & stimulus.dragon(bx + 0.5, by + 40, bz + 0.5);
        if (!single) {
            skip("everything", "no single-player server to summon on");
            finish();
            return;
        }
        for (int i = 0; i < GRID * GRID; i++) stimulus.stand(bx + 12.5 + i % GRID, by, bz - 4.5 + i / GRID);
        LOGGER.info("collision probe: started at block {} {} {}", bx, by, bz);
        serverTicks = 0;
        phase = Phase.SUMMONED;
    }

    private static void clientChecks(CgEntityQuery entities) {
        CgCollisionWorld world = CgCollisionWorld.client(1f);
        if (head >= 0) headCheck("client", world, entities);
        else skip("dragon.head client", "no head on the server either");
        if (painting >= 0) paintingRay("client", world, entities);
        else skip("painting.ray client", "no painting on the server either");
    }

    // ── The server: finds what landed, runs the ring and its checks ──────────────────────────────

    private static void serverTick() {
        if (phase == Phase.SUMMONED) {
            if (++serverTicks < LAND) return;
            Object player = firstPlayer();
            if (player == null) return;
            Object level = CgWorldQueries.level(player, CgWorldQueries.dimension(player));
            CgEntityQuery entities = CgEntityQueries.of(level);
            int me = CgEntityQueries.idOf(player);
            // 2.5 blocks: 1.7.10's stand-in pigs wander while the summons land.
            standNear = nearest(entities, me, bx + 3.5, by + 0.5, bz + 0.5, 2.5);
            standFar = nearest(entities, me, bx - 4.5, by + 0.5, bz + 0.5, 2.5);
            painting = nearest(entities, me, bx + 0.5, by + 1.5, bz + 4.5, 1.5);
            findDragon(entities);
            boolean all = standNear >= 0 && standFar >= 0 && painting >= 0 && head >= 0;
            if (!all && serverTicks < FIND_LIMIT) return;
            check("targets.found", standNear >= 0 && standFar >= 0, "stands " + standNear + ", " + standFar);
            CgCollisionWorld world = CgCollisionWorld.of(level);
            if (painting >= 0) {
                paintingBox(entities);
                paintingRay("server", world, entities);
            } else {
                check("painting.found", false, "nothing hangs at " + bx + " " + (by + 1) + " " + (bz + 4));
            }
            if (head >= 0) {
                headCheck("server", world, entities);
            } else {
                check("dragon.found", false, "no dragon with parts within 64 blocks of the summon");
                info("dragon.search", around(entities, bx + 0.5, by + 40, bz + 0.5, 64));
                if (painting >= 0) info("dragon.ids", byId(entities, painting + 1, 10));
            }
            batchGain(world);
            ringHits.clear();
            ring = CgHitbox.of(CgRing.front(1.2, 1.0)).at(bx + 0.5, by + 0.5, bz + 0.5).radius(0, 8, 8)
                    .source(player).onHit(h -> ringHits.add(h.entityId())).start(level);
            serverTicks = 0;
            phase = Phase.RING;
        } else if (phase == Phase.RING) {
            if (++serverTicks < RING) return;
            ring.stop();
            int near = count(standNear), far = count(standFar);
            int order = ringHits.indexOf(standNear) < ringHits.indexOf(standFar) ? 1 : 0;
            check("ring.hits", near == 1 && far == 1 && order == 1, "stands " + standNear + " " + near + "x, "
                    + standFar + " " + far + "x, the near one " + (order == 1 ? "first" : "not first") + "; every hit "
                    + ringHits);
            phase = Phase.CLIENT;
        }
    }

    private static Object firstPlayer() {
        UUID[] first = new UUID[1];
        CgNetwork.forEachPlayer(id -> { if (first[0] == null) first[0] = id; });
        return first[0] == null ? null : CgPlayers.player(first[0]);
    }

    /** The entity nearest a point within {@code reach}, but the player: what landed there. */
    private static int nearest(CgEntityQuery entities, int me, double x, double y, double z, double reach) {
        int[] best = {-1};
        double[] bestDistance = {Double.MAX_VALUE};
        double[] pose = new double[CgEntityQuery.POSE_LENGTH];
        entities.within(x - reach, y - reach, z - reach, x + reach, y + reach, z + reach, id -> {
            if (id == me || entities.parent(id) != id || !entities.pose(id, 1f, pose)) return;
            double dx = pose[CgEntityQuery.X] - x, dz = pose[CgEntityQuery.Z] - z;
            double d = dx * dx + dz * dz;
            if (d < bestDistance[0]) {
                bestDistance[0] = d;
                best[0] = id;
            }
        });
        return best[0];
    }

    /** The dragon is the parent of the parts within reach of its summon; its head is the part made first. */
    private static void findDragon(CgEntityQuery entities) {
        double x = bx + 0.5, y = by + 40, z = bz + 0.5, r = 64;
        int[] found = {-1};
        entities.within(x - r, y - r, z - r, x + r, y + r, z + r, id -> {
            int parent = entities.parent(id);
            if (parent != id) found[0] = parent;
        });
        if (found[0] < 0) return;
        dragon = found[0];
        int[] parts = {0};
        boolean[] headSeen = {false};
        entities.within(x - r, y - r, z - r, x + r, y + r, z + r, id -> {
            if (id != dragon && entities.parent(id) == dragon) {
                parts[0]++;
                headSeen[0] |= id == dragon + 1;
            }
        });
        if (head < 0 && headSeen[0]) {
            head = dragon + 1;
            // 8 from 1.9, which gave the dragon a neck; 7 before.
            check("dragon.parts", parts[0] >= 7, parts[0] + " parts of dragon " + dragon + ", each its parent");
        }
    }

    // ── Checks either side runs ──────────────────────────────────────────────────────────────────

    /**
     * A ray back at the head from in front of it, along the line from the dragon's middle through it: the head is its
     * front, so it is the first thing met, and its parent is the dragon. The head is found as a hit finds it, by a box
     * walk: a client knows a part only once one has handed it out.
     */
    private static void headCheck(String side, CgCollisionWorld world, CgEntityQuery entities) {
        double[] pose = new double[CgEntityQuery.POSE_LENGTH];
        if (!entities.pose(dragon, 1f, pose)) {
            check("dragon.head " + side, false, "dragon " + dragon + " is not on this side");
            return;
        }
        double x = pose[CgEntityQuery.X], y = pose[CgEntityQuery.Y], z = pose[CgEntityQuery.Z], r = 16;
        CgAABB box = new CgAABB();
        boolean[] found = {false};
        entities.within(x - r, y - r, z - r, x + r, y + r, z + r, id -> {
            if (id == head) found[0] = entities.box(id, 1f, box);
        });
        if (!found[0]) {
            check("dragon.head " + side, false, "head " + head + " not among the entities round dragon " + dragon);
            info("dragon.search " + side, around(entities, x, y, z, r));
            return;
        }
        double hx = (box.minX() + box.maxX()) / 2, hy = (box.minY() + box.maxY()) / 2, hz = (box.minZ() + box.maxZ()) / 2;
        double dx = hx - x, dy = hy - (y + 1.5), dz = hz - z, length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= length;
        dy /= length;
        dz /= length;
        double sx = hx + dx * 4, sy = hy + dy * 4, sz = hz + dz * 4;
        CgHit hit = new CgHit();
        boolean any = world.ray().at(sx, sy, sz).along(-dx, -dy, -dz, 6).first(hit);
        check("dragon.part " + side, any && hit.entityId() != dragon && hit.parentId() == dragon,
                any ? "part " + hit.entityId() + " of dragon " + hit.parentId() : "the ray met nothing");
        // The head's box lies inside the neck's, its front face shared, in nearly every pose: the neck, made second,
        // is put aside to show the head is found and named.
        any = world.ray().at(sx, sy, sz).along(-dx, -dy, -dz, 6).except(head + 1).first(hit);
        check("dragon.head " + side, any && hit.entityId() == head && hit.parentId() == dragon,
                any ? "part " + hit.entityId() + " of dragon " + hit.parentId() + ", the neck " + (head + 1) + " aside"
                        : "the ray met nothing");
    }

    /** A painting is thin, at its wall, the stone south of its block: 1/16 against it, or 1/32 and 3/64 off on 1.7.10. */
    private static void paintingBox(CgEntityQuery entities) {
        CgAABB box = new CgAABB();
        boolean known = entities.box(painting, 1f, box);
        double depth = box.maxZ() - box.minZ(), wall = bz + 5;
        check("painting.box", known && depth > 0 && depth < 0.1 && Math.abs(box.maxZ() - wall) < 0.1, String.format(
                "z %.4f to %.4f, %.4f thick, the wall at %.1f", box.minZ(), box.maxZ(), depth, wall));
    }

    /** A ray north from in front of the painting meets it on its face, the side away from the wall. */
    private static void paintingRay(String side, CgCollisionWorld world, CgEntityQuery entities) {
        CgAABB box = new CgAABB();
        entities.box(painting, 1f, box);
        CgHit hit = new CgHit();
        boolean any = world.ray().at(bx + 0.5, by + 1.5, bz + 1.5).along(0, 0, 1, 4).allEntities().first(hit);
        check("painting.ray " + side, any && hit.entityId() == painting && Math.abs(hit.z() - box.minZ()) < 0.01,
                any ? String.format("entity %d at z %.4f, the face at %.4f", hit.entityId(), hit.z(), box.minZ())
                        : "the ray met nothing");
    }

    /** S8's grid in game: overlaps over the hundred stands, alone and in a batch, alternated. */
    private static void batchGain(CgCollisionWorld world) {
        CgSphere sphere = CgSphere.of(3);
        Random random = new Random(7);
        double[] xs = new double[OVERLAPS], zs = new double[OVERLAPS];
        for (int i = 0; i < OVERLAPS; i++) {
            xs[i] = bx + 12.5 + random.nextDouble() * GRID;
            zs[i] = bz - 4.5 + random.nextDouble() * GRID;
        }
        long[] live = new long[ROUNDS], batched = new long[ROUNDS];
        int hits = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long t = System.nanoTime();
            for (int i = 0; i < OVERLAPS; i++) hits += world.overlap(sphere).at(xs[i], by + 1, zs[i]).count();
            live[round] = System.nanoTime() - t;
            t = System.nanoTime();
            try (CgCollisionWorld.Batch ignored = CgCollisionWorld.batch()) {
                for (int i = 0; i < OVERLAPS; i++) hits += world.overlap(sphere).at(xs[i], by + 1, zs[i]).count();
            }
            batched[round] = System.nanoTime() - t;
        }
        Arrays.sort(live);
        Arrays.sort(batched);
        info("batch.gain", String.format("%d overlaps over %d stands: live %.2f ms, batched %.2f ms (medians of %d; %d hits)",
                OVERLAPS, GRID * GRID, live[ROUNDS / 2] / 1e6, batched[ROUNDS / 2] / 1e6, ROUNDS, hits));
    }

    /**
     * What a box walk found: how many, how many are parts, and the largest box, a dragon's being 16 by 8. Tells no
     * dragon from a dragon whose parts were not handed out.
     */
    private static String around(CgEntityQuery entities, double x, double y, double z, double r) {
        int[] n = {0, 0, -1};
        double[] largest = {0, 0, 0};
        CgAABB box = new CgAABB();
        entities.within(x - r, y - r, z - r, x + r, y + r, z + r, id -> {
            n[0]++;
            if (entities.parent(id) != id) n[1]++;
            if (!entities.box(id, 1f, box)) return;
            double w = box.maxX() - box.minX(), h = box.maxY() - box.minY();
            if (w * h > largest[0] * largest[1]) {
                largest[0] = w;
                largest[1] = h;
                largest[2] = entities.parent(id);
                n[2] = id;
            }
        });
        return String.format("%d entities, %d of them parts; the largest box %d (%.1f by %.1f, kind %d, parent %d)",
                n[0], n[1], n[2], largest[0], largest[1], n[2] < 0 ? -1 : entities.kind(n[2]), (int) largest[2]);
    }

    /** Each of {@code n} ids from {@code from}, as the level resolves it: summoned after the painting, the dragon's. */
    private static String byId(CgEntityQuery entities, int from, int n) {
        StringBuilder out = new StringBuilder();
        double[] pose = new double[CgEntityQuery.POSE_LENGTH];
        for (int id = from; id < from + n; id++) {
            out.append(id).append(' ');
            if (!entities.pose(id, 1f, pose)) {
                out.append("none; ");
                continue;
            }
            out.append(String.format("at %.1f %.1f %.1f, %.1f by %.1f, parent %d, flags %x; ", pose[CgEntityQuery.X],
                    pose[CgEntityQuery.Y], pose[CgEntityQuery.Z], pose[CgEntityQuery.WIDTH], pose[CgEntityQuery.HEIGHT],
                    entities.parent(id), entities.flags(id)));
        }
        return out.toString();
    }

    // ── Reporting ────────────────────────────────────────────────────────────────────────────────

    private static int count(int id) {
        int n = 0;
        for (int hit : ringHits) if (hit == id) n++;
        return n;
    }

    private static void finish() {
        int bad = failed.get();
        LOGGER.info("collision probe: {} ({} passed, {} failed)", bad == 0, passed.get(), bad);
        phase = Phase.DONE;
    }

    private static void check(String name, boolean ok, String detail) {
        (ok ? passed : failed).incrementAndGet();
        LOGGER.info("collision probe {}: {} ({})", name, ok, detail);
    }

    private static void skip(String name, String reason) {
        LOGGER.info("collision probe {}: skipped ({})", name, reason);
    }

    // "is", never ": ": a reader takes "name: false" for a failed check.
    private static void info(String name, String detail) {
        LOGGER.info("collision probe {} is {}", name, detail);
    }
}
