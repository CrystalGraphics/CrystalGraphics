package com.crystalgraphics.sequence.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

import javax.annotation.Nullable;

import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.net.object.CgNetBehaviour;
import com.crystalgraphics.net.object.CgNetCommand;
import com.crystalgraphics.net.object.CgNetEvent;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.net.object.CgPredictionKey;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;

/**
 * A sequence played on the server and shown on every viewer: its replicated state, sent only when play leaves the
 * course its last state predicts (a jump, a seek, a rate, a relinked section). Each viewer plays its own copy from that
 * state, so a late joiner lands on the server's time and section. Mirrors GAS's replicated montage
 * ({@code FGameplayAbilityRepAnimMontage}): section, next section, rate and position, a viewer corrected only past a
 * threshold, and the owner's jumps its own predictions, never corrected.
 *
 * <pre>{@code
 * // server: a play, served from the caster
 * CgSequencePlayer shot = sequencer.play(definition, roles);
 * CgSequenceNet.serve(shot, CgAnchor.onEntity(caster), caster).owner(player).start();
 *
 * // every client, once: how a served sequence plays here
 * CgSequenceNet.client((obj, sequence, roles, seed) -> {
 *     CgSequenceDefinition d = definitions.loaded(sequence);
 *     return d == null ? null : sequencer.play(d, rolesOf(d, roles));   // null: still loading, asked again next tick
 * });
 * }</pre>
 *
 * <pre>{@code
 * // the owner's client, on an input: jumped now, undone if the server refuses
 * CgSequenceNet.jump(shot, "fire", CgPredictionKey.open());
 *
 * // server, at init: which jumps it takes (all of the owner's, without a rule)
 * CgSequenceNet.acceptJumps((player, section) -> !player.section().equals("end"));
 * }</pre>
 *
 * <ul>
 *   <li>Time is in the server's game ticks since the object spawned: a viewer's copy is exact however late it joins.</li>
 *   <li>A server change of section a viewer cannot predict goes through {@link CgSequencePlayer#jumpToSection},
 *       {@code seek} or {@code nextSection}; each one is sent. A section ending into its next is never sent.</li>
 *   <li>Stopping the served player despawns the object, and every viewer's copy stops.</li>
 * </ul>
 */
public final class CgSequenceNet {

    /** A viewer this far from the server's time, in the server's section, is put on it; nearer, left to play on. */
    static final double CORRECT_SECONDS = 0.1;
    /** Roles sent by entity: the first this many. */
    static final int ROLES = 4;

    static final CgNetField<String> SEQUENCE = CgNetField.of("sequence", CgCodecs.STRING).initialOnly();
    static final CgNetField<Long> SEED = CgNetField.ofLong("seed").initialOnly();
    /** The state's tick, as the object's age; its time after that tick's evaluation, the rate since, the section and its next. */
    static final CgNetField<Long> AT = CgNetField.ofLong("at");
    static final CgNetField<Double> SECONDS = CgNetField.ofDouble("seconds");
    static final CgNetField<Float> RATE = CgNetField.ofFloat("rate").fallback(1f);
    static final CgNetField<Integer> SECTION = CgNetField.ofInt("section").fallback(-1), NEXT = CgNetField.ofInt("next").fallback(-1);
    static final List<CgNetField<Integer>> ROLE = new ArrayList<>();
    /** The net id of the cue whose sequence it is, or -1: a viewer's copy is then that cue's own play. */
    static final CgNetField<Integer> CUE = CgNetField.ofInt("cue").fallback(-1).initialOnly();

    /** A predicted jump: the section, and the predicting client's key. */
    record Jump(int section, int key) {
        static final CgCodec<Jump> CODEC = CgCodecs.record(
                CgCodecs.field("s", CgCodecs.INT, Jump::section),
                CgCodecs.field("k", CgCodecs.INT, Jump::key),
                Jump::new);
    }

    /** The server's answer to a jump, to its owner alone. */
    record Verdict(int key, boolean accepted) {
        static final CgCodec<Verdict> CODEC = CgCodecs.record(
                CgCodecs.field("k", CgCodecs.INT, Verdict::key),
                CgCodecs.field("a", CgCodecs.BOOL, Verdict::accepted),
                Verdict::new);
    }

    static final CgNetCommand<Jump> JUMP = CgNetCommand.of("jump", Jump.CODEC).perSecond(10);
    static final CgNetEvent<Verdict> VERDICT = CgNetEvent.of("verdict", Verdict.CODEC);

    /** The type every served sequence plays as. */
    public static final CgNetType TYPE;

    /** A viewer's copy by its player: where an owner's jump finds the object it asks through. */
    private static final Map<CgSequencePlayer, CgNetObject> SHOWN = new IdentityHashMap<>();
    private static BiPredicate<CgSequencePlayer, String> accepts = (player, section) -> true;
    @Nullable
    private static Viewer cues;

    static {
        List<CgNetField<?>> fields = new ArrayList<>(List.of(SEQUENCE, SEED, AT, SECONDS, RATE, SECTION, NEXT, CUE));
        for (int i = 0; i < ROLES; i++) ROLE.add(CgNetField.ofInt("r" + i).fallback(-1).initialOnly());
        fields.addAll(ROLE);
        TYPE = CgNetType.define("crystalgraphics:sequence").fields(fields.toArray(new CgNetField<?>[0]))
                .events(VERDICT).commands(JUMP).range(128).build();
        TYPE.onCommand(JUMP, (obj, sender, jump) -> {
            Served served = obj.local();
            if (served == null || jump == null) return;
            CgSequencePlayer player = served.player;
            List<CgSequenceDefinition.CompositeSection> cs = player.definition().compositeSections();
            boolean ok = !player.stopped() && jump.section() >= 0 && jump.section() < cs.size()
                    && accepts.test(player, cs.get(jump.section()).name());
            if (ok) player.jumpToSection(cs.get(jump.section()).name());
            obj.emitToOwner(VERDICT, new Verdict(jump.key(), ok));
        });
    }

    /** How a viewer plays a served sequence: its play, started from these, or null while its definition loads. */
    @FunctionalInterface
    public interface Viewer {
        @Nullable
        CgSequencePlayer start(CgNetObject obj, String sequence, int[] roleEntities, long seed);
    }

    private CgSequenceNet() {
    }

    /** Declares the type: at network init, on both sides, before anyone connects. */
    public static void install() {
    }

    /**
     * Server: {@code player}'s object, anchored at {@code anchor}, its roles' entities {@code roleEntities} in role
     * order. Set its owner, then {@code start()} it.
     */
    public static CgNetObject serve(CgSequencePlayer player, CgAnchor anchor, int... roleEntities) {
        if (roleEntities.length > ROLES) throw new IllegalArgumentException(roleEntities.length + " roles, past " + ROLES);
        CgNetObject obj = TYPE.spawn(anchor);
        obj.set(SEQUENCE, player.definition().id()).setLong(SEED, player.seed());
        for (int i = 0; i < roleEntities.length; i++) obj.setInt(ROLE.get(i), roleEntities[i]);
        Served served = new Served(player);
        served.publish(obj, -1);   // before its first evaluation: one tick before it spawns
        return obj.local(served).behaviour(served);
    }

    /**
     * Server: the sequence a lasting cue plays as its timeline, served beside the cue's object {@code cue}, which must
     * have started: the same owner, its viewers' copy the cue's own play. Set its range, then {@code start()} it.
     */
    public static CgNetObject serveWith(CgNetObject cue, CgSequencePlayer player, CgAnchor anchor) {
        CgNetObject obj = serve(player, anchor).setInt(CUE, cue.id());
        if (cue.owner() != null) obj.owner(cue.owner());
        return obj;
    }

    /** Server: which of an owner's jumps it takes, by the play and the section asked for. Every one, until set. */
    public static void acceptJumps(BiPredicate<CgSequencePlayer, String> rule) {
        accepts = Objects.requireNonNull(rule, "rule");
    }

    /**
     * Client, once: how a cue's served sequence finds the play its cue already started here, by the cue's net id
     * ({@code CgCueNet.client} installs it). Null while that cue has not started; asked again next tick.
     */
    public static void cues(Viewer viewer) {
        cues = Objects.requireNonNull(viewer, "viewer");
    }

    /** The net id of the cue {@code obj} plays with, or -1 for a sequence served alone. */
    public static int cueOf(CgNetObject obj) {
        return obj.getInt(CUE);
    }

    /** Client, once: how this client plays each served sequence it is sent. */
    public static void client(Viewer viewer) {
        Objects.requireNonNull(viewer, "viewer");
        TYPE.onClient(obj -> new View(viewer));
    }

    /**
     * The owner's client: jumps {@code player} to {@code section} now and asks the server for it under {@code key}.
     * Refused, it goes back as {@link CgSequencePlayer#jumpToSection(String, CgPredictionKey)} does, with everything
     * else under the key. A play this client was not sent jumps locally alone.
     */
    public static void jump(CgSequencePlayer player, String section, CgPredictionKey key) {
        CgSequenceDefinition.CompositeSection c = player.definition().compositeSection(section);
        if (c == null) throw new IllegalArgumentException(player.definition().id() + " has no section " + section);
        player.jumpToSection(section, key);
        CgNetObject obj = SHOWN.get(player);
        if (obj == null) return;
        View view = obj.local();
        view.keys.put(key.id(), key);
        obj.send(JUMP, new Jump(player.definition().compositeSections().indexOf(c), key.id()));
    }

    /**
     * Where play stands {@code elapsed} seconds after it stood at {@code at} in section {@code section}, handing on to
     * {@code next} at that section's end and to each later section's own next: a montage's advance, run ahead.
     */
    static double position(CgSequenceDefinition d, int section, int next, double at, double elapsed) {
        double t = at + elapsed;
        List<CgSequenceDefinition.CompositeSection> cs = d.compositeSections();
        int s = section, n = next;
        for (int guard = 0; s >= 0 && guard < 64; guard++) {
            CgSequenceDefinition.CompositeSection c = cs.get(s);
            double end = d.seconds(c.to() - d.start());
            if (t < end) return t;
            if (n < 0) return end;
            CgSequenceDefinition.CompositeSection to = cs.get(n);
            double start = d.seconds(to.from() - d.start());
            if (n == s) return start + (t - end) % (end - start);   // a loop, laps however many
            t = start + (t - end);
            s = n;
            n = to.next();
        }
        return t;
    }

    /** The montage section {@code seconds} falls in, or -1. */
    static int sectionAt(CgSequenceDefinition d, double seconds) {
        List<CgSequenceDefinition.CompositeSection> cs = d.compositeSections();
        for (int i = 0; i < cs.size(); i++) {
            CgSequenceDefinition.CompositeSection c = cs.get(i);
            if (seconds >= d.seconds(c.from() - d.start()) && seconds < d.seconds(c.to() - d.start())) return i;
        }
        return -1;
    }

    private static int sectionIndex(CgSequencePlayer player, @Nullable String name) {
        if (name == null) return -1;
        return player.definition().compositeSections().indexOf(player.definition().compositeSection(name));
    }

    /** The server's side: sends the play's state each time its revision moves, and despawns with it. */
    private static final class Served implements CgNetBehaviour {
        final CgSequencePlayer player;
        int sent = Integer.MIN_VALUE;
        boolean ended;

        Served(CgSequencePlayer player) {
            this.player = player;
        }

        /** The play's time after this tick's evaluation, at {@code at}, the object's age. */
        void publish(CgNetObject obj, long at) {
            sent = player.revision();
            // From its time, not section(): a jump's section is the player's only from its next evaluation.
            int section = sectionAt(player.definition(), player.seconds());
            String name = section < 0 ? null : player.definition().compositeSections().get(section).name();
            obj.setLong(AT, at).setDouble(SECONDS, player.seconds()).setFloat(RATE, player.rate())
                    .setInt(SECTION, section)
                    .setInt(NEXT, name == null ? -1 : sectionIndex(player, player.next(name)));
        }

        @Override
        public void tick(CgNetObject obj) {
            if (ended) return;
            if (player.stopped()) {
                ended = true;
                obj.despawn();
            } else if (player.revision() != sent) {
                publish(obj, obj.age());
            }
        }
    }

    /** A viewer's copy: started from the state, kept on it, stopped with the object. */
    private static final class View implements CgNetBehaviour {
        @Nullable
        final Viewer viewer;
        final Map<Integer, CgPredictionKey> keys = new HashMap<>();
        @Nullable
        CgSequencePlayer player;

        View(@Nullable Viewer viewer) {
            this.viewer = viewer;
        }

        @Override
        public void spawned(CgNetObject obj, float age) {
            obj.local(this);
            tryStart(obj);
        }

        @Override
        public void tick(CgNetObject obj) {
            if (player == null) tryStart(obj);
        }

        @Override
        public void received(CgNetObject obj) {
            if (player != null) follow(obj, false);
        }

        @Override
        public void event(CgNetObject obj, CgNetEvent<?> event, Object value) {
            if (event != VERDICT || !(value instanceof Verdict v)) return;
            CgPredictionKey key = keys.remove(v.key());
            if (key == null) return;
            if (v.accepted()) key.accept();
            else key.refuse();
        }

        @Override
        public void despawned(CgNetObject obj, boolean killed) {
            if (player == null) return;
            SHOWN.remove(player);
            player.stop();
            player = null;
        }

        private void tryStart(CgNetObject obj) {
            int[] roles = new int[ROLES];
            for (int i = 0; i < ROLES; i++) roles[i] = obj.getInt(ROLE.get(i));
            // A cue's play is placed already, at its age: corrected like any other state, never re-sought from scratch.
            boolean adopted = obj.getInt(CUE) >= 0;
            Viewer by = adopted ? cues : viewer;
            if (by == null) return;
            player = by.start(obj, obj.get(SEQUENCE), roles, obj.getLong(SEED));
            if (player == null) return;
            player.seed(obj.getLong(SEED));
            SHOWN.put(player, obj);
            follow(obj, !adopted);
        }

        /**
         * Puts the copy on the server's state: on a viewer, past {@link #CORRECT_SECONDS} or in another section, as GAS
         * corrects a simulated montage; on the owner only at its start or out of a section it did not predict, its own
         * jumps ahead of the server's by the trip there.
         */
        private void follow(CgNetObject obj, boolean start) {
            CgSequencePlayer p = player;
            CgSequenceDefinition d = p.definition();
            int section = obj.getInt(SECTION), next = obj.getInt(NEXT);
            float rate = obj.getFloat(RATE);
            double elapsed = (obj.age() - obj.getLong(AT)) * CgWorld.TICK_SECONDS * rate;
            double target = position(d, section, next, obj.getDouble(SECONDS), elapsed);
            if (p.rate() != rate) p.rate(rate);
            List<CgSequenceDefinition.CompositeSection> cs = d.compositeSections();
            if (section >= 0) p.nextSection(cs.get(section).name(), next < 0 ? null : cs.get(next).name());
            boolean otherSection = section >= 0 && sectionAt(d, p.seconds()) != section;
            boolean far = Math.abs(p.seconds() - target) > CORRECT_SECONDS;
            boolean correct = start || (obj.isMine() ? otherSection && keys.isEmpty() : otherSection || far);
            if (!correct) return;
            if (start || target >= p.seconds()) p.seek(target);
            else p.jumpTo(target);
        }
    }
}
