package com.crystalgraphics.world;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.platform.world.CgEntityMovement;
import com.crystalgraphics.sequence.notify.CgNotifyContext;
import com.crystalgraphics.sequence.notify.CgNotifyKind;

/**
 * Holding an entity's own movement while a move plays: the client's own player's keys, a mob's AI, gravity. Kakarot's
 * {@code SetCharacterMoveFlag}: a landing holds you until its clip lets go. Holds are counted, so two moves holding at
 * once release only when both have: the first records what the entity was doing and the last puts it back.
 *
 * <pre>{@code
 * CgMoveLocks.Hold h = CgMoveLocks.hold(entity, CgMoveLocks.INPUT | CgMoveLocks.AI, onServer);
 * ...
 * h.close();                                                 // released; once, and a second close does nothing
 * }</pre>
 *
 * <p>In a sequence, a span of the built-in notify kind holds for as long as it plays, on both sides:</p>
 * <pre>{@code
 * - { id: s3, kind: notify, binding: { role: caster }, sections: [
 *     { id: n1, range: [0, 900], kind: crystalgraphics:move_lock, properties: { input: true, ai: true, gravity: false } } ] }
 * }</pre>
 *
 * <ul>
 *   <li>Each hold applies on the side that owns it: the keys on the client whose player it is, the AI on the server,
 *       gravity on the server (Minecraft sends it to the entity's clients) and on the client for its own player.</li>
 *   <li>A span ends by playing past it, by its sequence stopping, or by a seek before it: each releases.</li>
 *   <li>The game thread of its side. 1.7.10 has no AI or gravity switch, Forge 1.8.9 no gravity one.</li>
 * </ul>
 */
public final class CgMoveLocks {

    /** What a hold holds: the client's own player's movement keys, a mob's AI, gravity. */
    public static final int INPUT = 1, AI = 2, GRAVITY = 4;

    /** The built-in notify kind: a span holding what its properties name, on both sides. */
    public static final String KIND = "crystalgraphics:move_lock";

    /** Its properties: each a bool, input and AI held unless said, gravity let be unless said. */
    public static final CgType PROPERTIES = CgTypes.struct("crystalgraphics:notify.move_lock")
            .property("input", CgValueType.BOOL).defaultValue(true)
            .property("ai", CgValueType.BOOL).defaultValue(true)
            .property("gravity", CgValueType.BOOL).defaultValue(false)
            .register();

    /** What each held entity was doing, and how many hold each part. */
    private static final class Held {
        int input, ai, gravity;
        boolean aiWas, gravityWas;
    }

    /** Held entities, weakly: a body gone takes its record with it. Each side's entities are its own objects. */
    private static final Map<Object, Held> HELD = new WeakHashMap<>();

    private CgMoveLocks() {
    }

    /** One hold: what it holds of one entity, released once. */
    public static final class Hold implements AutoCloseable {
        private final Object entity;
        private final int what;
        private boolean released;

        Hold(Object entity, int what) {
            this.entity = entity;
            this.what = what;
        }

        /** What it holds: {@link #INPUT}, {@link #AI}, {@link #GRAVITY}, of those that apply on its side. */
        public int holds() {
            return what;
        }

        /** Lets go; the entity's own movement returns once no other hold keeps it. */
        @Override
        public void close() {
            if (released) return;
            released = true;
            release(entity, what);
        }
    }

    /**
     * Holds {@code what} of {@code entity}, the loader's own object on this side, as far as it applies here: the keys
     * only on the client whose player it is, the AI only on the server, gravity on the server and on a client for its
     * own player.
     */
    public static Hold hold(Object entity, int what, boolean onServer) {
        boolean mine = !onServer && CgEntityQueries.idOf(entity) >= 0
                && CgEntityQueries.idOf(entity) == CgEntityQueries.client().localPlayer();
        int applies = what & (onServer ? AI | GRAVITY : mine ? INPUT | GRAVITY : 0);
        synchronized (HELD) {
            Held h = HELD.computeIfAbsent(entity, e -> new Held());
            if ((applies & INPUT) != 0 && h.input++ == 0) CgEntityMovement.holdInput(true);
            if ((applies & AI) != 0 && h.ai++ == 0) {
                h.aiWas = CgEntityMovement.aiOn(entity);
                CgEntityMovement.ai(entity, false);
            }
            if ((applies & GRAVITY) != 0 && h.gravity++ == 0) {
                h.gravityWas = CgEntityMovement.gravityOn(entity);
                CgEntityMovement.gravity(entity, false);
            }
        }
        return new Hold(entity, applies);
    }

    private static void release(Object entity, int what) {
        synchronized (HELD) {
            Held h = HELD.get(entity);
            if (h == null) return;
            if ((what & INPUT) != 0 && --h.input == 0) CgEntityMovement.holdInput(false);
            if ((what & AI) != 0 && --h.ai == 0) CgEntityMovement.ai(entity, h.aiWas);
            if ((what & GRAVITY) != 0 && --h.gravity == 0) CgEntityMovement.gravity(entity, h.gravityWas);
            if (h.input == 0 && h.ai == 0 && h.gravity == 0) HELD.remove(entity);
        }
    }

    /** Whether any hold keeps {@code what} of {@code entity} on this side. */
    public static boolean held(Object entity, int what) {
        synchronized (HELD) {
            Held h = HELD.get(entity);
            return h != null && ((what & INPUT) != 0 && h.input > 0 || (what & AI) != 0 && h.ai > 0
                    || (what & GRAVITY) != 0 && h.gravity > 0);
        }
    }

    /** Registers the {@code crystalgraphics:move_lock} notify kind, once. {@code CgBuiltInCompilers.register} calls it. */
    public static synchronized void install() {
        if (CgNotifyKind.get(KIND) != null) return;
        CgNotifyKind.builder(KIND).properties(PROPERTIES).runs(CgNotifyKind.Runs.BOTH).behaviour(new Span()).register();
    }

    /** The span: a hold from its begin to its end, on the entity its track binds. */
    private static final class Span implements CgNotifyKind.Behaviour {
        private final Map<CgNotifyContext, Hold> holds = new ConcurrentHashMap<>();

        @Override
        public void begin(CgNotifyContext c) {
            Object entity = c.target() == null ? null : c.target().hostEntity();
            if (entity == null) return;
            Map<?, ?> p = c.properties();
            int what = (flag(p, "input", true) ? INPUT : 0) | (flag(p, "ai", true) ? AI : 0) | (flag(p, "gravity", false) ? GRAVITY : 0);
            Hold last = holds.put(c, hold(entity, what, c.player().server()));
            if (last != null) last.close();
        }

        @Override
        public void end(CgNotifyContext c) {
            Hold h = holds.remove(c);
            if (h != null) h.close();
        }

        private static boolean flag(Map<?, ?> p, String key, boolean dflt) {
            Object v = p.get(key);
            return v instanceof Boolean b ? b : dflt;
        }
    }
}
