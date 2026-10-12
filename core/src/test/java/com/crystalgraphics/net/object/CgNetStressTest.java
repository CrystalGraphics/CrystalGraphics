package com.crystalgraphics.net.object;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import com.sun.management.ThreadMXBean;
import org.junit.After;
import org.junit.Test;

import com.crystalgraphics.net.CgAudience;
import com.crystalgraphics.net.CgMessage;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.serialization.CgCodecs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The whole stack under load on the rig: what a tick costs, what reaches a player, how late, and what is left behind. */
public class CgNetStressTest {

    static final CgNetField<Float> HEAT = CgNetField.ofFloat("heat");
    static final CgNetField<Integer> AMMO = CgNetField.ofInt("ammo").ownerOnly();
    static final CgNetField<String> LABEL = CgNetField.of("label", CgCodecs.STRING);
    static final CgNetEvent<Integer> SHOT = CgNetEvent.of("shot", CgCodecs.INT);
    static final CgNetCommand<Integer> POKE = CgNetCommand.of("poke", CgCodecs.INT);

    static final CgNetType SMALL = CgNetType.define("stress:small").fields(HEAT).range(64).build();
    static final CgNetType RICH = CgNetType.define("stress:rich").fields(HEAT, AMMO, LABEL).events(SHOT).commands(POKE)
            .range(96).build();
    static final CgNetType FAR = CgNetType.define("stress:far").fields(HEAT).build();
    static final CgNetType MANA = CgNetType.define("stress:mana").fields(HEAT).perHolder().build();
    static final CgNetType TRACKER = CgNetType.define("stress:tracker").fields(HEAT).build();

    static final CgMessage<String> BULK = CgMessage.toClients("stress:bulk", CgCodecs.STRING);
    static final CgMessage<Integer> PING = CgMessage.toClients("stress:ping", CgCodecs.INT);

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final long THREAD = Thread.currentThread().getId();

    private final Random random = new Random(7);
    private CgNetTestRig rig;

    @After
    public void tearDown() {
        if (rig != null) rig.close();
    }

    private static void report(String line) {
        System.out.println("[net-stress] " + line);
    }

    private static String spread(long[] values, String unit, double scale) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return String.format("median %.1f, p99 %.1f, max %.1f %s", sorted[sorted.length / 2] * scale,
                sorted[(int) (sorted.length * 0.99)] * scale, sorted[sorted.length - 1] * scale, unit);
    }

    private long bytesToAll() {
        long total = 0;
        for (int i = 0; i < 64; i++) {
            try {
                total += rig.client(i).bytesReceived();
            } catch (IndexOutOfBoundsException end) {
                break;
            }
        }
        return total;
    }

    private List<CgNetObject> crowd(int small, int rich, int far, double size) {
        List<CgNetObject> all = new ArrayList<>();
        for (int i = 0; i < small; i++) all.add(SMALL.spawn(rig.at(random.nextDouble() * size, 64, random.nextDouble() * size)).start());
        for (int i = 0; i < rich; i++) {
            all.add(RICH.spawn(rig.at(random.nextDouble() * size, 64, random.nextDouble() * size)).owner(rig.client(i % 4).id())
                    .setInt(AMMO, i).set(LABEL, "turret " + i).start());
        }
        for (int i = 0; i < far; i++) all.add(FAR.spawn(rig.at(random.nextDouble() * size, 64, random.nextDouble() * size)).start());
        return all;
    }

    @Test
    public void aCrowdedServer() {
        int players = 20;
        double size = 512;
        rig = CgNetTestRig.of(players);
        for (int i = 0; i < players; i++) rig.client(i).moveTo(random.nextDouble() * size, 64, random.nextDouble() * size);
        List<CgNetObject> all = crowd(4000, 800, 200, size);
        rig.tick(5);

        int measured = 600, warm = 200;
        long[] server = new long[measured], client = new long[measured], bytes = new long[measured], alloc = new long[measured];
        for (int t = -warm; t < measured; t++) {
            for (int c = 0; c < all.size() / 10; c++) all.get(random.nextInt(all.size())).setFloat(HEAT, t);
            for (int c = 0; c < all.size() / 100; c++) {
                CgNetObject obj = all.get(random.nextInt(all.size()));
                obj.moveTo(obj.x() + random.nextDouble() - 0.5, 64, obj.z() + random.nextDouble() - 0.5);
            }
            for (int c = 0; c < 8; c++) all.get(4000 + random.nextInt(800)).emit(SHOT, t);
            for (int c = 0; c < all.size() / 500; c++) {
                int i = random.nextInt(4000);
                all.get(i).despawn();
                all.set(i, SMALL.spawn(rig.at(random.nextDouble() * size, 64, random.nextDouble() * size)).start());
            }
            long sent = bytesToAll();
            long before = THREADS.getThreadAllocatedBytes(THREAD);
            rig.tick(1);
            long after = THREADS.getThreadAllocatedBytes(THREAD);
            if (t < 0) continue;
            server[t] = rig.serverNanos;
            client[t] = rig.clientNanos;
            bytes[t] = (bytesToAll() - sent) / players;
            alloc[t] = after - before;
        }
        report("crowd: " + all.size() + " objects, " + players + " players, 10% changing a tick");
        report("  server tick  " + spread(server, "ms", 1e-6));
        report("  client ticks " + spread(client, "ms", 1e-6) + " (all " + players + ")");
        report("  per player   " + spread(bytes, "B a tick", 1));
        report("  allocated    " + spread(alloc, "KB a tick", 1e-3) + " (server and clients)");
    }

    @Test
    public void aPlayerJoiningACrowdedWorld() {
        rig = CgNetTestRig.of(2);
        rig.client(1).disconnect();
        crowd(0, 0, 5000, 2000);
        rig.tick(5);

        rig.client(1).connect();
        long start = rig.client(1).bytesReceived();
        int ticks = 0;
        long worst = 0, worstServer = 0, worstTick = 0;
        while (rig.client(1).visible(FAR).size() < 5000 && ticks < 200) {
            long before = rig.client(1).bytesReceived();
            rig.tick(1);
            ticks++;
            long tick = rig.client(1).bytesReceived() - before;
            if (tick > worst) {
                worst = tick;
                worstTick = ticks;
            }
            worstServer = Math.max(worstServer, rig.serverNanos);
        }
        report("join: 5000 objects reached the newcomer in " + ticks + " ticks, " + (rig.client(1).bytesReceived() - start)
                + " B; the largest tick sent " + worst + " B (tick " + worstTick + "), the slowest server tick took "
                + worstServer / 1000 + " us");
        assertEquals(5000, rig.client(1).visible(FAR).size());
    }

    @Test
    public void aChangeArrivesOnTimeBehindABulkTransfer() {
        rig = CgNetTestRig.of(2);
        long[] objectLag = {0, 0}, pingLag = {0, 0};
        int[] bulks = {0};
        TRACKER.onChange(HEAT, (obj, heat) -> {
            if (heat > 0) objectLag[0] = Math.max(objectLag[0], CgNetwork.serverTicks() - heat.longValue());
        });
        PING.onReceive(sent -> {
            pingLag[0] = Math.max(pingLag[0], CgNetwork.serverTicks() - sent);
            pingLag[1]++;
        });
        BULK.onReceive(text -> bulks[0]++);
        CgNetObject tracker = TRACKER.spawn(rig.at(0, 64, 0)).start();
        rig.tick(5);

        char[] megabyte = new char[1 << 20];
        Arrays.fill(megabyte, 'x');
        String payload = new String(megabyte);
        int deliveredAt = -1;
        for (int t = 0; t < 120; t++) {
            if (t == 0) {
                for (int b = 0; b < 4; b++) BULK.send(CgAudience.player(rig.client(0).id()), payload);
                for (int p = 0; p < 300; p++) PING.send(CgAudience.player(rig.client(0).id()), (int) CgNetwork.serverTicks() + 1);
            }
            tracker.setFloat(HEAT, CgNetwork.serverTicks() + 1);
            rig.tick(1);
            if (deliveredAt < 0 && bulks[0] == 4) deliveredAt = t;
        }
        report("contention: 4 MB and 300 pings to one player at once; the bulk arrived after " + deliveredAt
                + " ticks; an object change was at most " + objectLag[0] + " ticks late, a ping " + pingLag[0]
                + " (" + pingLag[1] + " arrived)");
    }

    @Test
    public void churnLeavesNothingBehind() {
        int players = 20;
        rig = CgNetTestRig.of(players);
        long baseline = 0;
        for (int cycle = 0; cycle < 40; cycle++) {
            for (int i = 0; i < players; i++) rig.client(i).connect().moveTo(random.nextDouble() * 128, 64, random.nextDouble() * 128);
            rig.tick(3);
            List<CgNetObject> all = crowd(300, 100, 20, 128);
            for (int i = 0; i < players; i++) MANA.of(rig.client(i).id()).setFloat(HEAT, i);
            rig.tick(2);
            for (int i = 0; i < 4; i++) {
                for (CgNetObject seen : rig.client(i).visible(RICH)) {
                    if (seen.isMine()) {
                        seen.send(POKE, i);
                        break;
                    }
                }
                rig.client(i).predict(SMALL, CgAnchor.at(1, 64, 1)).fire();
            }
            rig.tick(5);
            for (CgNetObject obj : all) obj.despawn();
            for (int i = 0; i < players; i++) rig.client(i).disconnect();
            rig.tick(5);
            assertEquals("cycle " + cycle, "viewers 0, by id 0, slots 0, live 0, indexed 0, fired 0", CgNetObjects.footprint());
            assertEquals(0, CgNetwork.openConnections());
            assertEquals(0, MANA.holders.size());
            if (cycle == 4) baseline = settledHeap();
        }
        long grown = settledHeap() - baseline;
        report("churn: 40 cycles of 20 players and 440 objects; heap " + (grown >= 0 ? "grew " : "shrank ")
                + Math.abs(grown) / 1024 + " KB between cycle 5 and 40");
    }

    private static long settledHeap() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) System.gc();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    @Test
    public void aQuietCrowdCostsNothing() {
        int players = 20;
        rig = CgNetTestRig.of(players);
        for (int i = 0; i < players; i++) rig.client(i).moveTo(random.nextDouble() * 512, 64, random.nextDouble() * 512);
        crowd(4000, 800, 200, 512);
        rig.tick(3000);
        long sent = bytesToAll();
        CgNetwork.measureAllocation(true);
        long allocated = 0, network = 0;
        int allocating = 0;
        long[] server = new long[500];
        // Each tick bracketed alone: the loop around them is the test's, not the stack's.
        for (int t = 0; t < 500; t++) {
            long before = THREADS.getThreadAllocatedBytes(THREAD);
            rig.tick(1);
            long tick = THREADS.getThreadAllocatedBytes(THREAD) - before;
            allocated += tick;
            if (tick > 0) allocating++;
            server[t] = rig.serverNanos;
            network += CgNetwork.serverTickAllocated();
        }
        CgNetwork.measureAllocation(false);
        report("quiet: 5000 objects, 20 players, nothing changing: server tick " + spread(server, "ms", 1e-6) + "; "
                + allocated + " B allocated in " + allocating + " ticks (" + network + " B in the server's network work) and "
                + (bytesToAll() - sent) + " B sent over 500 ticks");
        assertEquals("bytes sent", 0, bytesToAll() - sent);
        // One tick may: after other tests' paths, C2 recompiles the tick once and that tick allocates.
        assertTrue("ticks that allocated: " + allocating, allocating <= 1);
    }
}
