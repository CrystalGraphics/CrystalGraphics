package com.crystalgraphics.animation.sync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * One instance's asset players ticked together: Unreal's {@code FAnimSync} (AnimSync.cpp:79-411). During the graph's
 * update each relevant player is {@link #add added} with its weight and group; after it, {@link #tick} moves every
 * group's leader, then its followers after it, then the ungrouped players. Groups are by name and keep last frame's
 * state, so a group carries its place from frame to frame.
 *
 * <pre>{@code
 * sync.begin();                                  // the frame's start: this frame's groups emptied
 * sync.add(walk, 0.7f, CgSyncGroup.of("feet"));  // during the update, each relevant player
 * sync.add(run, 0.3f, CgSyncGroup.of("feet"));
 * sync.tick(dt);                                 // after it: walk leads, run follows its markers
 * }</pre>
 *
 * <ul>
 *   <li>The leader is the highest scoring member ({@link CgAnimGroupRole}); one whose markers cannot be placed gives the
 *       lead to the next.</li>
 *   <li>A group syncs by markers when its leader has markers and every marker-bearing member shares some; members
 *       without markers follow by length ratio.</li>
 *   <li>Allocates nothing once each group has been seen at its largest.</li>
 * </ul>
 */
public final class CgAnimSync {

    private final Map<String, Group> groups = new HashMap<>();
    private final List<Group> order = new ArrayList<>();
    private final CgAnimTickContext ctx = new CgAnimTickContext();
    private Record[] ungrouped = new Record[8];
    private int ungroupedCount, write;
    private final String[] none = new String[0];

    /** A player's entry this frame: {@code FAnimTickRecord}. */
    private static final class Record {
        CgAnimAssetPlayer player;
        @Nullable
        Object asset;
        float weight, score;
        CgAnimGroupRole role;
        boolean canUseMarkerSync, exclusive;
    }

    /** One buffer of a group: {@code FAnimGroupInstance}. */
    private static final class State {
        boolean exists;
        Record[] players = new Record[4];
        int count, leader = -1;
        String[] valid = new String[4];
        int validCount;
        boolean canUseMarkerSync;
        float previousRatio, ratio;
        final CgMarkerSyncPosition end = new CgMarkerSyncPosition();

        void reset() {
            count = 0;
            leader = -1;
            canUseMarkerSync = false;
            previousRatio = ratio = 0f;
            end.clear();
        }

        Record add() {
            if (count == players.length) players = Arrays.copyOf(players, count * 2);
            Record r = players[count];
            if (r == null) players[count] = r = new Record();
            count++;
            return r;
        }

        boolean holds(CgAnimAssetPlayer p) {
            for (int i = 0; i < count; i++) if (players[i].player == p) return true;
            return false;
        }
    }

    private static final class Group {
        final State[] states = {new State(), new State()};
    }

    /** The frame's start: this frame's groups and ungrouped players emptied ({@code FAnimSync::Reset}). */
    public void begin() {
        for (int i = 0; i < order.size(); i++) order.get(i).states[write].reset();
        ungroupedCount = 0;
    }

    /**
     * {@code AddTickRecord}: {@code player} ticks this frame at {@code weight}, in {@code group} or ungrouped. A transition
     * role joins its group only once it has played at full weight ({@code fullWeightOnce}).
     */
    public void add(CgAnimAssetPlayer player, float weight, @Nullable CgSyncGroup group, boolean fullWeightOnce) {
        if (group != null && group.role().transitional() && !fullWeightOnce) group = null;
        Record r = add(player, weight, group == null ? null : group.name(), group == null ? CgAnimGroupRole.CAN_BE_LEADER : group.role());
        r.score = r.role.leaderScore(weight);
    }

    /**
     * A montage's record in {@code group}: {@code UpdateMontageSyncGroup}'s {@code AddTickRecord}, scored 3, above any
     * always-leader ({@code TestTickRecordForLeadership}, AnimSync.cpp:63-65).
     */
    public void addMontage(CgAnimAssetPlayer montage, float weight, String group) {
        add(montage, weight, group, CgAnimGroupRole.CAN_BE_LEADER).score = MONTAGE_SCORE;
    }

    /** A montage's leader score: above an always-leader's 2. */
    private static final float MONTAGE_SCORE = 3f;

    private Record add(CgAnimAssetPlayer player, float weight, @Nullable String group, CgAnimGroupRole role) {
        Record r;
        if (group == null) {
            if (ungroupedCount == ungrouped.length) ungrouped = Arrays.copyOf(ungrouped, ungroupedCount * 2);
            r = ungrouped[ungroupedCount];
            if (r == null) ungrouped[ungroupedCount] = r = new Record();
            ungroupedCount++;
            r.role = CgAnimGroupRole.CAN_BE_LEADER;
        } else {
            Group g = groups.get(group);
            if (g == null) {
                groups.put(group, g = new Group());
                order.add(g);
            }
            State s = g.states[write];
            s.exists = true;
            r = s.add();
            r.role = role;
        }
        r.player = player;
        r.asset = player.asset();
        r.weight = weight;
        r.canUseMarkerSync = false;
        r.exclusive = r.role == CgAnimGroupRole.EXCLUSIVE_ALWAYS_LEADER;
        return r;
    }

    /** {@code TickAssetPlayerInstances}: every group's leader then followers, then the ungrouped players; the buffers flip. */
    public void tick(float delta) {
        int read = 1 - write;
        for (int gi = 0; gi < order.size(); gi++) {
            Group g = order.get(gi);
            State s = g.states[write];
            if (s.count == 0) continue;
            State previous = g.states[read].exists ? g.states[read] : null;
            prepare(s, previous);
            ctx.begin(delta, s.count == 1, s.valid, s.validCount);
            if (previous != null) {
                ctx.ratios(previous.ratio, previous.ratio);
                CgMarkerSyncPosition end = previous.end;
                if (end.valid() && (end.previous == null || ctx.isValid(end.previous)) && (end.next == null || ctx.isValid(end.next))) {
                    ctx.start().set(end);
                }
            }
            for (int leader = 0; leader < s.count; leader++) {
                Record l = s.players[leader];
                ctx.clearPassed();
                l.player.tick(ctx, l.canUseMarkerSync);
                s.previousRatio = ctx.previousRatio();
                s.ratio = ctx.ratio();
                s.leader = leader;
                if (!ctx.canUseMarkerPosition()) break;
                if (ctx.end().valid()) {
                    s.end.set(ctx.end());
                    break;
                }
            }
            // Finalize: followers forget their markers when the leader changed
            if (previous == null || previous.leader != s.leader) {
                for (int i = s.leader + 1; i < s.count; i++) s.players[i].player.markerRecord().reset();
            }
            for (int i = s.leader + 1; i < s.count; i++) if (s.players[i].exclusive) pushUngrouped(s.players[i]);
            if (ctx.canUseMarkerPosition()) {
                CgMarkerSyncPosition start = ctx.start();
                if (start.previous != null && !ctx.isValid(start.previous) || start.next != null && !ctx.isValid(start.next)) {
                    ctx.invalidateMarkerSync();
                }
            }
            if (s.count > s.leader + 1) {
                ctx.toFollower();
                for (int i = s.leader + 1; i < s.count; i++) {
                    Record f = s.players[i];
                    if (f.exclusive) continue;
                    ctx.resync(false);
                    f.player.tick(ctx, f.canUseMarkerSync);
                }
            }
        }
        for (int i = 0; i < ungroupedCount; i++) {
            Record r = ungrouped[i];
            String[] names = r.player.uniqueMarkers();
            ctx.begin(delta, true, names == null ? none : names, names == null ? 0 : names.length);
            r.player.tick(ctx, false);
        }
        write = read;
    }

    private void pushUngrouped(Record from) {
        if (ungroupedCount == ungrouped.length) ungrouped = Arrays.copyOf(ungrouped, ungroupedCount * 2);
        Record r = ungrouped[ungroupedCount];
        if (r == null) ungrouped[ungroupedCount] = r = new Record();
        ungroupedCount++;
        r.player = from.player;
        r.asset = from.asset;
        r.weight = from.weight;
        r.role = from.role;
        r.exclusive = false;
        r.canUseMarkerSync = false;
    }

    /** {@code FAnimGroupInstance::Prepare} (AnimationAsset.cpp:121-227): sorted by score; the markers the group shares. */
    private void prepare(State s, @Nullable State previous) {
        // Highest score first; equal scores keep the order they were added in
        for (int i = 1; i < s.count; i++) {
            Record r = s.players[i];
            int j = i - 1;
            while (j >= 0 && s.players[j].score < r.score) {
                s.players[j + 1] = s.players[j];
                j--;
            }
            s.players[j + 1] = r;
        }
        String[] leaderMarkers = s.players[0].player.uniqueMarkers();
        boolean leaderHas = leaderMarkers != null && leaderMarkers.length > 0;
        if (leaderHas) {
            if (s.valid.length < leaderMarkers.length) s.valid = new String[leaderMarkers.length];
            System.arraycopy(leaderMarkers, 0, s.valid, 0, leaderMarkers.length);
            s.validCount = leaderMarkers.length;
            s.players[0].canUseMarkerSync = true;
            for (int i = 0; i < s.count; i++) {
                Record c = s.players[i];
                if (previous != null) {
                    Record was = null;
                    for (int k = 0; k < previous.count; k++) if (previous.players[k].player == c.player) was = previous.players[k];
                    if (was == null || was.asset != c.asset) c.player.markerRecord().reset();
                }
                if (i != 0 && s.validCount > 0) {
                    String[] own = c.player.uniqueMarkers();
                    if (own != null && own.length > 0) {
                        c.canUseMarkerSync = true;
                        for (int v = s.validCount - 1; v >= 0; v--) {
                            if (!contains(own, s.valid[v])) s.valid[v] = s.valid[--s.validCount];
                        }
                    }
                }
            }
            s.canUseMarkerSync = s.validCount > 0;
            Arrays.sort(s.valid, 0, s.validCount);
            if (previous == null || !sameValid(s, previous)) {
                for (int i = 0; i < s.count; i++) s.players[i].player.markerRecord().reset();
            }
        }
        if (!leaderHas || !s.canUseMarkerSync) {
            s.canUseMarkerSync = false;
            s.validCount = 0;
            for (int i = 0; i < s.count; i++) {
                s.players[i].player.markerRecord().reset();
                s.players[i].canUseMarkerSync = false;
            }
        }
    }

    private static boolean sameValid(State s, State previous) {
        if (s.validCount != previous.validCount) return false;
        for (int i = 0; i < s.validCount; i++) if (!s.valid[i].equals(previous.valid[i])) return false;
        return true;
    }

    private static boolean contains(String[] names, String name) {
        for (String n : names) if (n.equals(name)) return true;
        return false;
    }

    /** The group's fraction of its leader's clip after last tick, or 0 for a group never ticked: {@code GetSyncGroupPosition}'s ratio. */
    public float ratio(String group) {
        Group g = groups.get(group);
        return g == null ? 0f : g.states[1 - write].ratio;
    }

    /** Whether {@code player} led its group last tick. */
    public boolean leads(String group, CgAnimAssetPlayer player) {
        Group g = groups.get(group);
        if (g == null) return false;
        State s = g.states[1 - write];
        return s.leader >= 0 && s.leader < s.count && s.players[s.leader].player == player;
    }
}
