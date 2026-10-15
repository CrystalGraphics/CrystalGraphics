package com.crystalgraphics.vfx.effect;

import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.gas.CgVfxGas;
import com.crystalgraphics.vfx.gas.CgVfxSmoke;
import com.crystalgraphics.vfx.gas.CgVfxSmokeFill;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.net.CgVfxCue;

/**
 * A smoke grenade, Counter-Strike 2's: smoke floods out from where it lands, round walls and down stairs, hides what is
 * behind it for eighteen seconds, opens where shots and blasts carve it and heals. Played from an authoritative cue, the
 * server floods its world and sends the cells, so every client draws the same smoke and gameplay asks the server's copy
 * what it hides ({@link CgVfxSmoke}).
 *
 * <pre>{@code
 * // common, at init
 * public static final CgVfxCue SMOKE = VFX.cue("smoke", CgSmokeGrenade.LOOK, CgSmokeGrenade::fromCue)
 *         .range(128).authoritative().build();
 *
 * // server
 * CgVfxCue.Playing smoke = SMOKE.at(level, x, y, z).start();
 * CgSmokeGrenade grenade = smoke.effect();
 * grenade.shot(eyeX, eyeY, eyeZ, hitX, hitY, hitZ);                 // every viewer's opens too
 * if (grenade.blocksSight(eyeX, eyeY, eyeZ, targetX, targetY, targetZ)) hidden();
 * }</pre>
 *
 * <pre>{@code
 * VFX.play(new CgSmokeGrenade(CgSmokeGrenade.LOOK, x, y, z));     // one client alone: it floods that client's world
 * }</pre>
 *
 * <ul>
 *   <li>A plain cue (not authoritative) floods each client's own world, which can differ where chunks differ.</li>
 *   <li>Its look is its smoke's ({@link CgVfxSmoke#SCHEMA}) and its gas's: tune how far it spreads, how long it holds
 *       and how it churns there.</li>
 * </ul>
 */
public final class CgSmokeGrenade extends CgVfxEffect<CgSmokeGrenade> implements CgVfxCue.Live, CgVfxCue.Authoritative {

    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:smoke_grenade");
    /** The server's cells and carves, as its viewers take them. */
    public static final CgNetField<byte[]> CELLS = CgNetField.of("smoke/cells", CgCodecs.BYTES);
    public static final CgNetField<byte[]> CARVES = CgNetField.of("smoke/carves", CgCodecs.BYTES);

    static {
        SCHEMA.networked(CELLS, CARVES);
    }

    /** Grey smoke on half-block cells, held thick and churning slowly. */
    public static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).with(CgVfxGas.SMOKE).params(CgVfxSmoke.SCHEMA)
            .set(CgVfxGas.CELL, CgVfxSmokeFill.CELL).set(CgVfxGas.BUOYANCY, 0.15f).set(CgVfxGas.WEIGHT, 0f)
            .set(CgVfxGas.DISSIPATION, 0f).set(CgVfxGas.DAMPING, 1.2f).set(CgVfxGas.VORTICITY, 2f)
            .set(CgVfxGas.TURBULENCE, 1.6f).set(CgVfxGas.TURBULENCE_SIZE, 2.2f)
            .set(CgVfxGas.DISTURBANCE, 0.8f).set(CgVfxGas.DISTURBANCE_SIZE, 1.2f)
            .set(CgVfxGas.EXTINCTION, 3f).set(CgVfxGas.SCATTERING, 0.5f).set(CgVfxGas.SOOT, 0f)
            .set(CgVfxGas.DETAIL, 0.6f).set(CgVfxGas.DETAIL_SIZE, 1f).set(CgVfxGas.SMOKE_COLOR, 0xFFC4C6C8)
            .build();

    private final CgVfxSmoke smoke = new CgVfxSmoke(this);
    /** Whether its cells come from a cue's server: it waits for them rather than flooding. */
    private boolean fromServer;
    private boolean cellsPublished;
    private int carvesPublished = -1;

    public CgSmokeGrenade(CgVfxLook look, double x, double y, double z) {
        super(look, x, y, z);
    }

    /** What a cue plays: the server's copy floods, a viewer takes the cells the server sent. */
    public static CgSmokeGrenade fromCue(CgVfxLook look, CgVfxCue.Play cue) {
        CgSmokeGrenade grenade = new CgSmokeGrenade(look, cue.x(), cue.y(), cue.z());
        if (!cue.authoritative() && cue.has(CELLS)) {
            grenade.fromServer = true;
            grenade.cueChanged(cue);
        }
        return grenade;
    }

    /** Its smoke: what it hides, its cells, its gas. */
    public CgVfxSmoke smoke() {
        return smoke;
    }

    /** Whether its smoke hides {@code (bx, by, bz)} from {@code (ax, ay, az)}: absolute world coordinates. */
    public boolean blocksSight(double ax, double ay, double az, double bx, double by, double bz) {
        return smoke.blocksSight(ax, ay, az, bx, by, bz);
    }

    /** A shot through its smoke: a hole along the line that heals. On a server's copy, every viewer's opens too. */
    public CgSmokeGrenade shot(double ax, double ay, double az, double bx, double by, double bz) {
        smoke.shot(ax, ay, az, bx, by, bz);
        return this;
    }

    /** A blast in its smoke: cleared within {@code radius} blocks and thrown out, healing over five seconds. */
    public CgSmokeGrenade blast(double x, double y, double z, float radius) {
        smoke.blast(x, y, z, radius);
        return this;
    }

    @Override
    protected void tick(float dt) {
        if (smoke.fill() == null && !fromServer) smoke.flood();
        smoke.tick(dt);
        if (smoke.done()) die();
    }

    @Override
    protected void submit(CgVfxFrame frame) {
        smoke.submit(frame);
    }

    /** On a server, its cells once and its carves as they change. */
    @Override
    public void publish(CgVfxCue.Play cue) {
        if (smoke.fill() == null) smoke.flood();
        if (!cellsPublished) {
            cue.set(CELLS, smoke.fill().encode());
            cellsPublished = true;
        }
        if (smoke.carveRevision() != carvesPublished) {
            cue.set(CARVES, smoke.encodeCarves());
            carvesPublished = smoke.carveRevision();
        }
    }

    /** On a viewer, the server's cells and carves. */
    @Override
    public void cueChanged(CgVfxCue.Play cue) {
        if (authoritative()) return;
        if (smoke.fill() == null && cue.has(CELLS)) {
            fromServer = true;
            smoke.fill(CgVfxSmokeFill.decode(cue.get(CELLS)));
        }
        if (cue.has(CARVES)) smoke.carves(cue.get(CARVES));
    }
}
