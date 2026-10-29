package com.crystalgraphics.sequence.event;

import java.util.Arrays;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.sequence.event.CgSequenceEventListener.Phase;
import com.crystalgraphics.sequence.event.CgSequenceEventListener.Side;
import com.crystalgraphics.sequence.time.CgSequenceEvaluationRange;
import com.crystalgraphics.sequence.CgSequenceEvaluator;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceChannel;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.definition.CgSequenceSection;
import com.crystalgraphics.sequence.definition.CgSequenceTrack;

/**
 * The {@code event} kind: a trigger section's keys fired as play sweeps across them, as Unreal's
 * {@code FMovieSceneEventSectionTemplate::EvaluateSwept} fires them ({@code MovieSceneEventTemplate.cpp:152-213}:
 * nothing while silent, each direction switched on its own, every key in the swept range once, the frame's events run
 * after evaluation as one token); a span section begun and ended as play enters and leaves it, as a notify state's
 * {@code NotifyBegin} and {@code NotifyEnd}. A triggered section plays the same on its own clock, from its event.
 */
public final class CgSequenceEventEvaluator implements CgSequenceEvaluator, CgEventChannel.Listener {

    /** A track's bound state: per section, its keys and whether its span is open. */
    private static final class Track {
        final Side side;
        final CgEventChannel[] keys;
        final boolean[] open;
        /** Per triggered section, its run and the local tick its keys were swept to. */
        final int[] run, swept;
        /** The tick after the last its keys were swept to: a range starting before it moved play back. */
        int sweptEnd = Integer.MIN_VALUE;

        Track(Side side, int sections) {
            this.side = side;
            keys = new CgEventChannel[sections];
            open = new boolean[sections];
            run = new int[sections];
            swept = new int[sections];
        }
    }

    private final CgSequencer sequencer;
    /** The frame's events, heard after its evaluation. */
    private CgSequencePlayer[] players = new CgSequencePlayer[16];
    private Phase[] phases = new Phase[16];
    private String[] names = new String[16];
    private Object[] args = new Object[16];
    private Side[] sides = new Side[16];
    /** Each event's tick on its play's timeline, or NaN where it has none: a span's edge, a triggered section's key. */
    private float[] ticks = new float[16];
    private int count;
    /** What a key crossing queues for: the section being swept. */
    private CgSequencePlayer sweeping;
    private CgSequenceChannel sweepKeys;
    private Side sweepSide;
    private CgSequenceSection sweepSection;

    /** The {@code event} kind's evaluator, handing what it hears to {@code sequencer}'s listeners. */
    public CgSequenceEventEvaluator(CgSequencer sequencer) {
        this.sequencer = sequencer;
    }

    @Override
    public String kind() {
        return "event";
    }

    @Override
    public boolean headless() {
        return true;
    }

    @Override
    public boolean playsTriggered() {
        return true;
    }

    @Override
    public void bind(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection unused) {
        String at = player.definition().id() + ": tracks." + track.id();
        Side side = "rpg".equals(track.side()) ? Side.RPG : "ours".equals(track.side()) ? Side.OURS : null;
        if (side == null) throw new CgAssetException(at + ": an event track's side is rpg or ours, not " + track.side());
        Track t = new Track(side, track.sections().size());
        for (CgSequenceSection s : track.sections()) {
            CgSequenceChannel keys = s.channel("keys");
            if (keys != null) {
                int[] ticks = new int[keys.size()];
                String[] events = new String[keys.size()];
                for (int i = 0; i < ticks.length; i++) {
                    ticks[i] = keys.tick(i);
                    events[i] = keys.string(i);
                }
                t.keys[s.index()] = new CgEventChannel(ticks, events, track.fireForwards(), track.fireBackwards());
                t.keys[s.index()].place(s.trigger() != null ? -1 : player.range().isSet() ? player.range().keyFrom() - 1 : Integer.MIN_VALUE);
            } else if (!(s.fields().get("event") instanceof String)) {
                throw new CgAssetException(at + ".sections." + s.id() + ": an event section holds keys, or a range and an event");
            }
        }
        player.data(track, null, t);
    }

    @Override
    public void evaluate(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection triggered, float tick) {
        if (!(player.data(track, null) instanceof Track t)) return;
        // Presentation: a dedicated server hears the gameplay's alone.
        if (t.side == Side.OURS && sequencer.server()) return;
        if (triggered != null) {
            triggered(player, track, t, triggered, tick);
            return;
        }
        CgSequenceEvaluationRange range = player.range();
        if (!range.isSet()) return;
        int now = (int) Math.floor(tick);
        // Play moved back without a jump: a montage section handing play to an earlier one, a loop.
        boolean back = !range.jumped() && range.direction() == CgSequenceEvaluationRange.Direction.FORWARDS && range.keyFrom() < t.sweptEnd;
        for (CgSequenceSection s : track.sections()) {
            if (!s.active() || s.trigger() != null) continue;
            CgEventChannel keys = t.keys[s.index()];
            if (keys != null) {
                begin(player, t, s);
                if (range.jumped()) {
                    // A jump fires nothing it skips; a seek, what falls in the one-shot window before it.
                    int from = player.oneShotFrom();
                    keys.place(Math.min(from, now + 1) - 1);
                    if (from <= now) keys.sweep(from - 1, now, this);
                } else {
                    if (back) keys.place(range.keyFrom() - 1);
                    range.sweep(keys, this);
                }
                continue;
            }
            span(player, t, s, s.contains(now));
        }
        t.sweptEnd = range.jumped() ? now + 1 : range.keyEnd();
    }

    /** {@code s}, started by its event: its keys and span on its own ticks, from the event's tick. */
    private void triggered(CgSequencePlayer player, CgSequenceTrack track, Track t, CgSequenceSection s, float tick) {
        CgEventTrigger trigger = player.trigger(track, s);
        int local = (int) Math.floor(tick);
        if (t.run[s.index()] != trigger.count()) {
            // Fired again: it starts over, its span ended and begun anew.
            t.run[s.index()] = trigger.count();
            t.swept[s.index()] = -1;
            if (t.keys[s.index()] != null) t.keys[s.index()].place(-1);
            if (t.open[s.index()]) queue(player, t.side, Phase.END, s);
            t.open[s.index()] = false;
        }
        CgEventChannel keys = t.keys[s.index()];
        if (keys != null) {
            if (local > t.swept[s.index()]) {
                begin(player, t, s);
                keys.sweep(t.swept[s.index()], local, this);
                t.swept[s.index()] = local;
            }
            return;
        }
        span(player, t, s, s.contains(local));
    }

    @Override
    public void ended(CgSequencePlayer player, CgSequenceTrack track, CgSequenceSection section) {
        if (player.data(track, null) instanceof Track t) span(player, t, section, false);
    }

    @Override
    public void stopped(CgSequencePlayer player) {
        for (CgSequenceTrack track : player.definition().tracks()) {
            if (!track.kind().equals("event") || !(player.data(track, null) instanceof Track t)) continue;
            for (CgSequenceSection s : track.sections()) span(player, t, s, false);
        }
        postEvaluation();
    }

    private void span(CgSequencePlayer player, Track t, CgSequenceSection s, boolean inside) {
        if (inside == t.open[s.index()]) return;
        t.open[s.index()] = inside;
        queue(player, t.side, inside ? Phase.BEGIN : Phase.END, s);
    }

    /** Sets what {@link #fired} queues for while {@code s}'s keys are swept. */
    private void begin(CgSequencePlayer player, Track t, CgSequenceSection s) {
        sweeping = player;
        sweepSide = t.side;
        sweepSection = s;
        sweepKeys = s.channel("keys");
    }

    @Override
    public void fired(int tick, String name) {
        // Keys outside their section's range are never in its evaluation, as Unreal sweeps a section's range alone.
        if (sweepSection.trigger() == null && !sweepSection.contains(tick)) return;
        int key = sweepKeys.heldIndex(tick);
        enqueue(sweeping, sweepSide, Phase.INSTANT, name, key >= 0 ? sweepKeys.args(key) : null,
                sweepSection.trigger() == null ? tick : Float.NaN);
    }

    private void queue(CgSequencePlayer player, Side side, Phase phase, CgSequenceSection s) {
        enqueue(player, side, phase, (String) s.fields().get("event"), s.fields().get("args"), Float.NaN);
    }

    private void enqueue(CgSequencePlayer player, Side side, Phase phase, String name, @Nullable Object arg, float tick) {
        if (count == players.length) {
            players = Arrays.copyOf(players, count * 2);
            phases = Arrays.copyOf(phases, count * 2);
            names = Arrays.copyOf(names, count * 2);
            args = Arrays.copyOf(args, count * 2);
            sides = Arrays.copyOf(sides, count * 2);
            ticks = Arrays.copyOf(ticks, count * 2);
        }
        players[count] = player;
        phases[count] = phase;
        names[count] = name;
        args[count] = arg;
        ticks[count] = tick;
        sides[count++] = side;
    }

    /**
     * How long before the frame's end the key at {@code tick} fell, in the viewer's seconds: from its pass's end, plus
     * what the frame played after that pass. 0 where it has no tick.
     */
    private static double secondsAgo(CgSequencePlayer player, float tick) {
        if (Float.isNaN(tick) || player.rate() <= 0) return 0;
        CgSequenceDefinition d = player.definition();
        double at = (tick - d.start()) * d.seconds(1);
        return Math.max(0, (player.passEndSeconds() - at + player.passRemainingSeconds()) / player.rate());
    }

    @Override
    public void postEvaluation() {
        for (int i = 0; i < count; i++) sequencer.hear(sides[i], players[i], phases[i], names[i], args[i], secondsAgo(players[i], ticks[i]));
        Arrays.fill(players, 0, count, null);
        Arrays.fill(args, 0, count, null);
        count = 0;
    }
}
