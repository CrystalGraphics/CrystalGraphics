package com.crystalgraphics.vfx.system;

import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.look.CgVfxEnding;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.net.CgVfxCue;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;

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
 * beam.behaviour().hold().aim(dx, dy, dz);   // typed: the wave's own methods
 * beam.set(BEAM_LENGTH, 40f);                // a user parameter: its look's value, read by its behaviour and layers
 * beam.stop();                               // INACTIVE, the owner's: no new spawns, particles finish
 * beam.kill();                               // INACTIVE_CLEAR: gone at its system's next step
 *
 * CgVfxSystemInstance<?> fromFile = VFX.play(new CgVfxSystemInstance<>(NOVA.get(), x, y, z));
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
 * </ul>
 */
public final class CgVfxSystemInstance<B extends CgVfxBehaviour> extends CgVfxEffect<CgVfxSystemInstance<B>>
        implements CgVfxCue.Live, CgVfxCue.Authoritative {

    private final CgVfxSystemDefinition definition;
    private final CgVfxEmitterInstance[] emitters;
    @Nullable
    private final B behaviour;
    private CgVfxExecutionState requested = CgVfxExecutionState.ACTIVE;
    /** The standing request's source: the lowest until something asks. */
    private CgVfxExecutionSource source = CgVfxExecutionSource.SCALABILITY;
    private boolean grounded;

    @SuppressWarnings("unchecked")   // B is the caller's word for what the definition's factory makes
    public CgVfxSystemInstance(CgVfxSystemDefinition definition, double x, double y, double z) {
        super(definition.look(), x, y, z);
        this.definition = definition;
        List<CgVfxEmitter> kinds = definition.emitters();
        emitters = new CgVfxEmitterInstance[kinds.size()];
        for (int i = 0; i < emitters.length; i++) {
            emitters[i] = new CgVfxEmitterInstance(kinds.get(i), seed + i * 0.137f);
            emitters[i].start(0f, 0f, 0f);
        }
        behaviour = definition.behaviour() == null ? null : (B) definition.behaviour().make(this);
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
     * CgEnergyWave wave = beam.behaviour(CgEnergyWave.class).aim(dx, dy, dz);
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
