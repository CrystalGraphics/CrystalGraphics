package com.crystalgraphics.sequence;

import java.util.Arrays;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.sequence.time.CgSequenceEvaluationRange;

/**
 * The {@code system} kind: a spawned system driven over all time by its track's sections, as Unreal drives one.
 * A section with {@code keys} is Cascade's particle section ({@code MovieSceneParticleTemplate.cpp:127-176}): the last
 * key at or before the range's end holds (activate, deactivate), a trigger fires only inside the range, and playing
 * backwards deactivates; leaving one that restores puts back whether the system was active before it
 * ({@code FActivePreAnimatedToken}, the same file). A section without is Niagara's spawn section
 * ({@code MovieSceneNiagaraSystemTrackTemplate.cpp:159-246}): activated as play crosses its start, kept active inside
 * ({@code evaluate: activate_if_inactive}), set inactive or deactivated after its end ({@code end}), its age the
 * sequence's where {@code age: desired_age}. A track naming a {@code handle} drives that emitter of the system alone
 * ({@link CgSequenceSystem#emitter}).
 */
final class CgSequenceSystemEvaluator implements CgSequenceEvaluator {

    /**
     * Per section: its last applied key, so a kill or a detach is applied once; whether it has animated its system since
     * play entered it, and whether the system was active before.
     */
    private record Keys(int[] applied, boolean[] animated, boolean[] wasActive, @Nullable CgSequenceSystem emitter) {
    }

    @Override
    public String kind() {
        return "system";
    }

    @Override
    public void bind(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection unused) {
        CgSequenceTarget target = player.target(track);
        if (target != null && !(target instanceof CgSequenceSystem)) {
            throw new CgAssetException(player.definition().id() + ": tracks." + track.id() + ": " + target + " is not a system");
        }
        CgSequenceSystem emitter = null;
        if (track.handle() != null && target instanceof CgSequenceSystem system) {
            String where = player.definition().id() + ": tracks." + track.id();
            emitter = system.emitter(track.handle());
            if (emitter == null) throw new CgAssetException(where + ": " + target + " has no emitter " + track.handle());
            for (CgSequenceSection s : track.sections()) {
                Object age = s.fields().get("age");
                if ("desired_age".equals(age) || "desired_age_no_seek".equals(age)) {
                    throw new CgAssetException(where + ".sections." + s.id() + ": a desired age on one emitter is not built yet");
                }
            }
        }
        int[] applied = new int[track.sections().size()];
        Arrays.fill(applied, -1);
        int n = track.sections().size();
        player.data(track, null, new Keys(applied, new boolean[n], new boolean[n], emitter));
    }

    @Override
    public void evaluate(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection unused, float tick) {
        if (!(player.target(track) instanceof CgSequenceSystem whole) || !(player.data(track, null) instanceof Keys keys)) return;
        CgSequenceSystem system = keys.emitter() != null ? keys.emitter() : whole;
        CgSequenceEvaluationRange range = player.range();
        int keyFrom = range.keyFrom(), keyEnd = range.keyEnd(), now = (int) Math.floor(tick);
        boolean backwards = range.direction() == CgSequenceEvaluationRange.Direction.BACKWARDS;
        for (CgSequenceSection s : track.sections()) {
            if (!s.active()) continue;
            CgSequenceChannel channel = s.channel("keys");
            if (channel != null && channel.size() > 0) {
                // Its keys apply while play is inside it, its end included: an importer keys the deactivate on the end.
                if (now < s.from() || (s.to() != CgSequenceSection.OPEN_END && now > s.to())) {
                    if (keys.animated()[s.index()]) {
                        keys.animated()[s.index()] = false;
                        keys.applied()[s.index()] = -1;
                        if (s.completion() != CgSequenceSection.Completion.KEEP_STATE) {
                            if (keys.wasActive()[s.index()]) system.activate(false);
                            else system.deactivate();
                        }
                    }
                    continue;
                }
                if (!keys.animated()[s.index()]) {
                    keys.animated()[s.index()] = true;
                    keys.wasActive()[s.index()] = system.active();
                }
                if (backwards) {
                    system.deactivate();
                    continue;
                }
                int last = lastKeyBefore(channel, keyEnd);
                if (last < 0) continue;
                CgSequenceChannel.Particle op = CgSequenceChannel.Particle.values()[channel.intValue(last)];
                switch (op) {
                    case ACTIVATE -> system.activate(false);
                    case DEACTIVATE -> system.deactivate();
                    case TRIGGER -> {
                        if (channel.tick(last) >= keyFrom && !range.jumped()) system.activate(true);
                    }
                    case KILL, DETACH -> {
                        if (keys.applied()[s.index()] != last) {
                            if (op == CgSequenceChannel.Particle.KILL) system.kill();
                            else system.detach();
                        }
                    }
                }
                keys.applied()[s.index()] = last;
                continue;
            }
            spawnSection(system, s, range, now, player, keyFrom, keyEnd);
        }
    }

    /** Niagara's spawn section on {@code system}: start, evaluate and end behaviours, and its desired age. */
    private static void spawnSection(CgSequenceSystem system, CgSequenceSection s, CgSequenceEvaluationRange range, int now,
                                     CgSequencePlayer player, int keyFrom, int keyEnd) {
        if (s.from() != CgSequenceSection.OPEN_START && now < s.from()) {
            if (system.active()) system.kill();
            return;
        }
        if (!range.jumped() && s.from() >= keyFrom && s.from() < keyEnd) {
            system.activate(true);
        } else if (s.to() == CgSequenceSection.OPEN_END || now < s.to()) {
            if (!"none".equals(s.fields().get("evaluate"))) system.activate(false);
        } else {
            Object end = s.fields().get("end");
            if ("deactivate".equals(end)) {
                if (system.active()) system.kill();
            } else if (!"none".equals(end)) {
                system.deactivate();
            }
            return;
        }
        if ("desired_age".equals(s.fields().get("age")) || "desired_age_no_seek".equals(s.fields().get("age"))) {
            int from = s.from() == CgSequenceSection.OPEN_START ? player.definition().start() : s.from();
            system.age((float) player.definition().seconds(now - from));
        }
    }

    /** The last key at or before {@code end - 1}, or -1. */
    private static int lastKeyBefore(CgSequenceChannel c, int end) {
        int lo = 0, hi = c.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (c.tick(mid) < end) lo = mid + 1;
            else hi = mid;
        }
        return lo - 1;
    }
}
