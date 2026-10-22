package com.crystalgraphics.vfx.system;

import com.crystalgraphics.asset.load.CgAssetFollower;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.look.CgVfxEnding;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.net.CgVfxCue;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxShape;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One playing system: Unreal's {@code FNiagaraSystemInstance}. {@code B} is its behaviour's type, known where the
 * instance is made and a wildcard where a file or a cue made it. It plays its definition's emitters from its origin, runs
 * its native behaviour if the definition has one ({@link CgVfxBehaviour}), and completes when both are done. Its
 * execution state is requested by source ({@link CgVfxExecutionSource}): a request below the standing one's source is
 * refused, so a scalability cull never overrides its owner.
 *
 * <pre>{@code
 * CgVfxSystemInstance<?> puff = VFX.play(new CgVfxSystemInstance<>(PUFF, x, y, z)).ground(floorY);
 * CgVfxSystemInstance<CgEnergyWave> beam = VFX.play(CgEnergyWave.instance(look, x, y, z));
 * beam.set(CgEnergyWave.AIM, dx, dy, dz);    // a user parameter: what its behaviour reads each tick
 * float reach = beam.behaviour().blastRadius();   // typed: the behaviour's own state
 * beam.set(BEAM_LENGTH, 40f);                // a user parameter: its look's value, read by its behaviour and layers
 * beam.stop();                               // INACTIVE, the owner's: no new spawns, particles finish
 * beam.kill();                               // INACTIVE_CLEAR: gone at its system's next step
 *
 * CgVfxSystemInstance<?> fromFile = VFX.play(new CgVfxSystemInstance<>(NOVA, x, y, z));   // follows its asset
 * fromFile.behaviour(CgEnergyWave.class);    // checked, for an instance whose type is not known here
 * }</pre>
 *
 * <ul>
 *   <li>A look alone ({@link #CgVfxSystemInstance(CgVfxLook, double, double, double)}) is a burst: its emitters started
 *       once at the origin.</li>
 *   <li>A stopped instance never restarts: an {@link CgVfxExecutionState#ACTIVE} request after one is refused.</li>
 *   <li>Set {@link #ground} before the first update, or the first tick's particles miss it.</li>
 *   <li>Its behaviour hears its cue: an instance is {@code CgVfxCue.Live} and {@code CgVfxCue.Authoritative} for a
 *       behaviour that is, and nothing for one that is not.</li>
 *   <li>Played from an asset's ref it follows it: a newer definition (a saved file, an editor's edit) reaches it at its
 *       next update, its values taken in place where its emitters keep their shape, else played again from its start
 *       up to its age. That restart makes its emitters and its behaviour anew: hold the instance, not its behaviour,
 *       across one.</li>
 * </ul>
 */
public final class CgVfxSystemInstance<B extends CgVfxBehaviour> extends CgVfxEffect<CgVfxSystemInstance<B>>
        implements CgVfxCue.Live, CgVfxCue.Authoritative {

    private CgVfxSystemDefinition definition;
    private CgVfxEmitterInstance[] emitters;
    @Nullable
    private B behaviour;
    /** The ref it follows when played from an asset; null for a definition handed over. */
    @Nullable
    private final CgAssetFollower<CgVfxSystemDefinition> follower;
    private CgVfxExecutionState requested = CgVfxExecutionState.ACTIVE;
    /** The standing request's source: the lowest until something asks. */
    private CgVfxExecutionSource source = CgVfxExecutionSource.SCALABILITY;
    private boolean grounded;
    /** The pool it goes back to once it completes; null for one made by {@code new}. */
    @Nullable
    private CgVfxSystemInstancePool pool;

    public CgVfxSystemInstance(CgVfxSystemDefinition definition, double x, double y, double z) {
        this(definition, null, x, y, z);
    }

    /**
     * {@code ref}'s definition, followed while it plays: a newer one its asset publishes reaches it at its next update.
     * Loads it now if it has not loaded ({@link CgAssetRef#get}, counted): load its bundle ahead.
     */
    public CgVfxSystemInstance(CgAssetRef<CgVfxSystemDefinition> ref, double x, double y, double z) {
        this(ref.get(), new CgAssetFollower<>(ref), x, y, z);
    }

    @SuppressWarnings("unchecked")   // B is the caller's word for what the definition's factory makes
    private CgVfxSystemInstance(CgVfxSystemDefinition definition, @Nullable CgAssetFollower<CgVfxSystemDefinition> follower,
                                double x, double y, double z) {
        super(definition.look(), x, y, z);
        this.definition = definition;
        this.follower = follower;
        emitters = emittersOf(definition);
        behaviour = definition.behaviour() == null ? null : (B) definition.behaviour().make(this);
    }

    private CgVfxEmitterInstance[] emittersOf(CgVfxSystemDefinition d) {
        List<CgVfxEmitter> kinds = d.emitters();
        CgVfxEmitterInstance[] made = new CgVfxEmitterInstance[kinds.size()];
        for (int i = 0; i < made.length; i++) {
            made[i] = new CgVfxEmitterInstance(kinds.get(i), seed + i * 0.137f);
            made[i].start(0f, 0f, 0f);
        }
        // A reader born on its sibling's particles, which its definition checked.
        for (int i = 0; i < made.length; i++) {
            if (kinds.get(i).spawnFrom() != null) made[i].bornOn(made[CgVfxSystemDefinition.sourceOf(d.name(), kinds, i)]);
        }
        return made;
    }

    /** Made by {@code pool}, which takes it back once it completes. */
    CgVfxSystemInstance(CgVfxSystemInstancePool pool, CgVfxSystemDefinition definition,
                        @Nullable CgAssetFollower<CgVfxSystemDefinition> follower) {
        this(definition, follower, 0, 0, 0);
        this.pool = pool;
    }

    /**
     * Starts it again at {@code (x, y, z)} as made: its effect's state, its emitters reused and started, its behaviour
     * made anew. Its pool's, on one no system plays.
     */
    @SuppressWarnings("unchecked")
    void reuse(double x, double y, double z) {
        pooledReuse(x, y, z);
        requested = CgVfxExecutionState.ACTIVE;
        source = CgVfxExecutionSource.SCALABILITY;
        grounded = false;
        for (int i = 0; i < emitters.length; i++) {
            emitters[i].reuse(seed + i * 0.137f);
            emitters[i].start(0f, 0f, 0f);
        }
        behaviour = definition.behaviour() == null ? null : (B) definition.behaviour().make(this);
    }

    @Override
    protected boolean pooled() {
        return pool != null;
    }

    @Override
    protected void reclaim() {
        if (pool == null) throw new IllegalStateException(definition.name() + " was made by new, not taken from a pool");
        pool.reclaim(this);
    }

    /** {@code look}'s emitters played once from the origin: a burst ({@link CgVfxSystemDefinition#of(CgVfxLook)}). */
    public CgVfxSystemInstance(CgVfxLook look, double x, double y, double z) {
        this(CgVfxSystemDefinition.of(look), x, y, z);
    }

    public CgVfxSystemDefinition definition() {
        return definition;
    }

    /** How many emitters it plays: its definition's, in its order. */
    public int emitters() {
        return emitters.length;
    }

    /** Emitter {@code i}'s running instance. */
    public CgVfxEmitterInstance emitter(int i) {
        return emitters[i];
    }

    /** Its emitter of {@code name}'s running instance, or null. */
    @Nullable
    public CgVfxEmitterInstance emitter(String name) {
        for (CgVfxEmitterInstance e : emitters) if (e.emitter().name().equals(name)) return e;
        return null;
    }

    /** Its behaviour, or null for a system with none. */
    @Nullable
    public B behaviour() {
        return behaviour;
    }

    /**
     * Its behaviour as {@code type}, to drive it.
     *
     * <pre>{@code
     * float reach = beam.behaviour(CgEnergyWave.class).blastRadius();
     * }</pre>
     *
     * @throws IllegalStateException if it carries none of that type
     */
    public <T extends CgVfxBehaviour> T behaviour(Class<T> type) {
        if (!type.isInstance(behaviour)) {
            throw new IllegalStateException(definition.name() + " carries " + behaviour + ", no " + type.getSimpleName());
        }
        return type.cast(behaviour);
    }

    /** What it is doing now. */
    public CgVfxExecutionState executionState() {
        if (state() == State.DEAD) return CgVfxExecutionState.COMPLETE;
        if (asleep()) return CgVfxExecutionState.ASLEEP;
        return requested;
    }

    /** The source of the standing request. */
    public CgVfxExecutionSource executionSource() {
        return source;
    }

    /**
     * Asks it into {@code state} for {@code by}: refused (false) when {@code by} is below the standing request's source,
     * when it has completed, or for an {@link CgVfxExecutionState#ACTIVE} after it stopped.
     *
     * @throws IllegalArgumentException for {@link CgVfxExecutionState#ASLEEP}, which only its scalability decides
     */
    public boolean request(CgVfxExecutionState state, CgVfxExecutionSource by) {
        if (state == CgVfxExecutionState.ASLEEP) throw new IllegalArgumentException("sleep is its scalability's to decide");
        if (state() == State.DEAD || !by.outranks(source)) return false;
        switch (state) {
            case ACTIVE -> {
                if (requested != CgVfxExecutionState.ACTIVE) return false;
            }
            case INACTIVE -> {
                if (requested == CgVfxExecutionState.ACTIVE) {
                    requested = CgVfxExecutionState.INACTIVE;
                    for (CgVfxEmitterInstance e : emitters) e.stop();
                    if (behaviour != null) behaviour.deactivated(this);
                    super.stop();
                }
            }
            case INACTIVE_CLEAR -> {
                requested = CgVfxExecutionState.INACTIVE_CLEAR;
                super.kill();
            }
            case COMPLETE -> {
                requested = CgVfxExecutionState.COMPLETE;
                die();
            }
            default -> throw new IllegalStateException(state.name());
        }
        source = by;
        return true;
    }

    /** Its owner's graceful end: {@link CgVfxExecutionState#INACTIVE}. */
    @Override
    public void stop() {
        request(CgVfxExecutionState.INACTIVE, CgVfxExecutionSource.OWNER);
    }

    /** Its owner's abrupt end: {@link CgVfxExecutionState#INACTIVE_CLEAR}. */
    @Override
    public void kill() {
        request(CgVfxExecutionState.INACTIVE_CLEAR, CgVfxExecutionSource.OWNER);
    }

    /** Its definition: every instance of it shares one simulation. */
    @Override
    protected Object definitionKey() {
        return definition;
    }

    /**
     * Its ref's newer definition, as Niagara's reinit and its desired age: a {@code VALUE} swap whose emitters each take
     * the new one's place in the same pools is taken in place; anything else plays it again up to its age.
     */
    @Override
    protected CgAssetFollower.Adoption adopt() {
        if (follower == null) return CgAssetFollower.Adoption.NONE;
        CgAssetFollower.Adoption adoption = follower.poll();
        if (adoption == CgAssetFollower.Adoption.NONE) return adoption;
        CgVfxSystemDefinition next = follower.definition();
        if (adoption == CgAssetFollower.Adoption.VALUE && takesValues(next)) {
            relook(next.look());
            for (int i = 0; i < emitters.length; i++) emitters[i].adopt(next.emitters().get(i));
            definition = next;
            return adoption;
        }
        restart(next);
        return CgAssetFollower.Adoption.RESTART;
    }

    /** Whether {@code next}'s emitters can each take its own's place, in the same pools, under the same behaviour. */
    private boolean takesValues(CgVfxSystemDefinition next) {
        List<CgVfxEmitter> kinds = next.emitters();
        if (kinds.size() != emitters.length || next.behaviour() != definition.behaviour()) return false;
        for (int i = 0; i < emitters.length; i++) {
            if (!emitters[i].canAdopt(kinds.get(i)) || !samePools(emitters[i].emitter(), kinds.get(i))) return false;
        }
        return true;
    }

    /** Whether {@code a} and {@code b}, and their events' children, simulate in pools of one shape. */
    private static boolean samePools(CgVfxEmitter a, CgVfxEmitter b) {
        if (!CgVfxShape.of(a).key().equals(CgVfxShape.of(b).key())) return false;
        for (int e = 0; e < a.events().size(); e++) {
            if (a.events().get(e).child() instanceof CgVfxEmitter ca && b.events().get(e).child() instanceof CgVfxEmitter cb
                    && !samePools(ca, cb)) {
                return false;
            }
        }
        return true;
    }

    /** Plays {@code next} from its start up to its age now: its old emitters killed, its behaviour made anew. */
    @SuppressWarnings("unchecked")
    private void restart(CgVfxSystemDefinition next) {
        for (CgVfxEmitterInstance e : emitters) e.kill();
        definition = next;
        relook(next.look());
        emitters = emittersOf(next);
        grounded = false;
        behaviour = next.behaviour() == null ? null : (B) next.behaviour().make(this);
        if (requested != CgVfxExecutionState.ACTIVE) for (CgVfxEmitterInstance e : emitters) e.stop();
        restartAtAge();
    }

    @Override
    protected void ending(CgVfxEnding ending) {
        if (behaviour != null) behaviour.ending(this, ending);
    }

    @Override
    protected void followed(double x, double y, double z) {
        if (behaviour == null || !behaviour.followed(this, x, y, z)) super.followed(x, y, z);
    }

    @Override
    public void cueChanged(CgVfxCue.Play cue) {
        if (behaviour instanceof CgVfxCue.Live live) live.cueChanged(cue);
    }

    @Override
    public void publish(CgVfxCue.Play cue) {
        if (behaviour instanceof CgVfxCue.Authoritative authoritative) authoritative.publish(cue);
    }

    @Override
    protected void culled(boolean kill) {
        request(kill ? CgVfxExecutionState.INACTIVE_CLEAR : CgVfxExecutionState.INACTIVE, CgVfxExecutionSource.SCALABILITY);
    }

    @Override
    protected void tick(float dt) {
        if (!grounded) {
            grounded = true;
            for (CgVfxEmitterInstance e : emitters) e.ground(groundHeight());
        }
        if (behaviour != null) behaviour.tick(this, dt);
        boolean done = true;
        for (CgVfxEmitterInstance e : emitters) {
            tick(e, dt);
            done &= e.finished();
        }
        // Its first second waits for its emitters' first steps, which a finished() of nothing booked cannot see.
        if (done && age > 1f && (behaviour == null || behaviour.done(this))) {
            request(CgVfxExecutionState.COMPLETE, CgVfxExecutionSource.COMPLETION);
        }
    }

    @Override
    protected void submit(CgVfxFrame frame) {
        if (behaviour != null) behaviour.submit(this, frame);
    }
}
