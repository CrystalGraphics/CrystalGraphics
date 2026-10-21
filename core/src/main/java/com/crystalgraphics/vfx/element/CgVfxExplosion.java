package com.crystalgraphics.vfx.element;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;

import java.util.List;

/**
 * The parts nearly every explosion and ki attack shares, as Sparking Zero reuses them: a cloud of cel-shaded billows in a
 * surge of dust along the ground, dark debris specks, glowing embers and hot streaks, the shock front running out along
 * the ground in the dust it lifts, and the ink an impact frame calls for ({@link #drawn}). Each part is an emitter with
 * its own physics and a layer that draws it; its colours are on a schema of its own, so every look carrying the kit has
 * them, tunable and sent with a cue like the effect's own.
 *
 * <pre>{@code
 * static final CgVfxExplosion BLAST = new CgVfxExplosion("mymod:bolt/blast");
 * static final CgVfxLook BLUE = CgVfxLook.builder(SCHEMA).with(BLAST.kit()).build();
 *
 * // another palette
 * static final CgVfxLook FIRE = BLUE.derive()
 *         .set(BLAST.body, 0xFFF24C0A).set(BLAST.hot, 0xFFFFE04C)
 *         .build();
 *
 * // other physics for one part: replace its emitter by name
 * static final CgVfxLook DRIFTING = BLUE.derive()
 *         .emitter(BLAST.sparkles.toBuilder().module(new CgVfxModule.Wind(2f)).build())
 *         .build();
 *
 * // where it bursts, in the effect's tick: the look's emitters, which may replace the kit's
 * for (CgVfxEmitter e : BLAST.emitters()) {
 *     CgVfxEmitter played = look().emitter(e.name());
 *     if (played != null && (framed || !BLAST.drawn(played))) start(played, x, y, z);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Two kits in one look need different names: the name is its schema's, and prefixes its emitters'.</li>
 *   <li>Sizes and speeds are in blocks, made for a blast about ten blocks across; a look scaled by
 *       {@code CgVfxLook.Builder.scaled} scales them.</li>
 * </ul>
 */
public final class CgVfxExplosion {

    private static final String PARTICLE = "crystalgraphics:shaders/vfx/particle/";

    /**
     * The cloud, rolling out from the foot of the blast's dome: puffs stream from a ring at its edge as it reaches it,
     * low and flat along the ground, small at first and swelling, braked by the air; rising a little as they cool and
     * swirling together in the turbulence. Nothing grows inside the dome, which stays the blast's to show. The ring
     * suits a dome about ten blocks in radius; a bigger dome replaces this with {@code shape} at its own edge. Each erodes
     * away within a few blocks of the camera, so a player standing in the blast still sees out (the billow shader's
     * {@code _NearFrom} and {@code _NearTo}).
     */
    public static final CgVfxEmitter BILLOWS = CgVfxEmitter.builder("billows")
            .state(CgVfxEmitterState.once(0.4f).delay(0.15f))
            .renderer(CgVfxEmitter.Renderer.MESHES)
            .capacity(130).rate(300f).shape(CgRing.of(9f, 11f, 1.92f)).at(0f, 1.38f, 0f).speed(5f, 12f)
            .life(3.5f, 5.5f).size(0.6f, 1.4f, 1.6f).spin(0.1f, 0.6f).heat(1f)
            .module(new CgVfxModule.Drag(0.8f, 0.08f))
            .module(new CgVfxModule.Buoyancy(1.2f, 2.5f))
            .module(new CgVfxModule.Turbulence(1.5f, 0.08f, 0.2f))
            .module(new CgVfxModule.Wind(0.2f))
            .module(new CgVfxModule.Spin(0.4f))
            .module(new CgVfxModule.Ground(0f, 0.3f, 0.3f, 0.4f))
            .size(CgKeyframes.start(0f, 0.35f).to(0.2f, 1f, CgEasings.OUT_CUBIC).to(1f, 2f, CgEasings.OUT_QUAD).build())
            .opacity(CgKeyframes.start(0f, 0f).to(0.05f, 1f, CgEasings.OUT_QUAD).to(0.55f, 0.92f, CgEasings.LINEAR)
                    .to(1f, 0f, CgEasings.OUT_QUAD).build())
            .build();

    /** Dark debris: heavy and ballistic, it lands, bounces a little, tumbles to a stop and fades. */
    public static final CgVfxEmitter SPECKS = CgVfxEmitter.builder("specks").state(CgVfxEmitterState.ONCE)
            .renderer(CgVfxEmitter.Renderer.QUADS)
            .optional().capacity(820).burst(0f, 800).shape(CgSphere.of(3f)).hemisphere().speed(6f, 16f)
            .life(4f, 6f).size(0.035f, 0.25f, 2.4f).spin(4f, 14f)
            .module(new CgVfxModule.Gravity(9.8f))
            .module(new CgVfxModule.Drag(0.25f, 0f))
            .module(new CgVfxModule.Wind(0.05f))
            .module(new CgVfxModule.Ground(0.3f, 0.5f, 0.8f))
            .module(new CgVfxModule.Spin(0.5f))
            .opacity(CgKeyframes.start(0f, 0f).to(0.03f, 1f, CgEasings.LINEAR).to(0.8f, 1f, CgEasings.LINEAR)
                    .to(1f, 0f, CgEasings.OUT_QUAD).build())
            .build();

    /**
     * Dust a speck kicks up where it comes to rest: a puff thrown up off the ground with a little of the speck's slide,
     * braked hard, swelling and thinning as it settles on the ground under it. Spawned only by the specks' landings.
     */
    public static final CgVfxEmitter DUST = CgVfxEmitter.builder("dust").renderer(CgVfxEmitter.Renderer.QUADS)
            .optional().shape(CgSphere.of(0.05f)).hemisphere().direction(0, 1, 0, 72.54f).speed(0.4f, 1.4f)
            .life(0.9f, 1.6f).size(0.18f, 0.45f, 1.5f).spin(0f, 0.6f)
            .module(new CgVfxModule.Gravity(0.6f))
            .module(new CgVfxModule.Drag(2.5f, 0f))
            .module(new CgVfxModule.Wind(0.3f))
            .module(new CgVfxModule.Ground(0f, 0.3f, 0f, 0.3f))
            .size(CgKeyframes.start(0f, 0.45f).to(1f, 1.7f, CgEasings.OUT_CUBIC).build())
            .opacity(CgKeyframes.start(0f, 0f).to(0.08f, 1f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_OUT_SINE).build())
            .build();

    /**
     * The base surge: a ring of dust the shock front kicks up off the ground as it passes the dome's foot, spreading as a
     * gravity current does. Thrown flat along the ground, it slides on it braked by the air; its fast front rides up into
     * a head that rolls, its top lagging and falling back over ({@link CgVfxModule.Current}); small and dense at first, it
     * swells and thins as it takes in air, and once it stalls its warm dust lofts and breaks up, so the billows stand in a
     * skirt of it rather than on a hard line. None starts inside the dome, where it would brown the blast seen through its
     * wall; like {@link #BILLOWS}, the ring suits a dome about ten blocks in radius.
     */
    public static final CgVfxEmitter SURGE = CgVfxEmitter.builder("surge")
            .state(CgVfxEmitterState.once(0.34f).delay(0.08f))
            .renderer(CgVfxEmitter.Renderer.QUADS)
            .optional().capacity(360).rate(980f).shape(CgRing.of(9f, 11f, 0.22f)).at(0f, 0.22f, 0f).speed(9f, 19f)
            .life(2.6f, 4f).size(0.25f, 0.55f, 1.4f)
            .module(new CgVfxModule.Gravity(1.5f))
            .module(new CgVfxModule.Drag(0.9f, 0.05f))
            .module(new CgVfxModule.Turbulence(1.4f, 0.12f, 0.35f))
            .module(new CgVfxModule.Wind(0.5f))
            .module(new CgVfxModule.Current(2.2f, 0.3f, 2.2f, 2.5f))
            .module(new CgVfxModule.Ground(0f, 0.02f, 0f, 0.3f))
            .size(CgKeyframes.start(0f, 0.35f).to(0.25f, 1f, CgEasings.OUT_CUBIC).to(1f, 2f, CgEasings.OUT_QUAD).build())
            .opacity(CgKeyframes.start(0f, 0f).to(0.05f, 1f, CgEasings.OUT_QUAD).to(0.45f, 0.85f, CgEasings.LINEAR)
                    .to(1f, 0f, CgEasings.IN_OUT_SINE).build())
            .build();

    /**
     * Dust the shock front whips off the ground as it passes: born on the front as it sweeps out with {@link #RINGS}
     * ({@code sweep}), thrown out flat behind it, sliding on the floor and lofting as it stalls, swelling and thinning
     * into a low skirt that settles behind the front. What makes the front, invisible itself, read.
     */
    public static final CgVfxEmitter SKIRT = CgVfxEmitter.builder("skirt").state(CgVfxEmitterState.once(2.2f))
            .renderer(CgVfxEmitter.Renderer.QUADS)
            .optional().capacity(560).rate(240f).shape(CgRing.of(9.2f, 9.6f, 0.1f)).sweep(33.5f).at(0f, 0.1f, 0f)
            .speed(3f, 9f).life(1.1f, 2.2f).size(0.35f, 0.8f, 1.4f)
            .module(new CgVfxModule.Gravity(1.5f))
            .module(new CgVfxModule.Drag(1.2f, 0.05f))
            .module(new CgVfxModule.Turbulence(1.2f, 0.15f, 0.4f))
            .module(new CgVfxModule.Wind(0.4f))
            .module(new CgVfxModule.Current(1.6f, 0.3f, 3f, 4f))
            .module(new CgVfxModule.Ground(0f, 0.02f, 0f, 0.3f))
            .size(CgKeyframes.start(0f, 0.4f).to(0.3f, 1f, CgEasings.OUT_CUBIC).to(1f, 1.8f, CgEasings.OUT_QUAD).build())
            .opacity(CgKeyframes.start(0f, 0f).to(0.06f, 1f, CgEasings.OUT_QUAD).to(0.4f, 0.7f, CgEasings.LINEAR)
                    .to(1f, 0f, CgEasings.IN_OUT_SINE).build())
            .build();

    /**
     * Embers: thrown out fast past the cloud and braked mostly by quadratic drag, so they clear the billows, then held up
     * by their heat, swirled by turbulence and carried by the wind, sinking to the ground as they cool and dim.
     */
    public static final CgVfxEmitter SPARKLES = CgVfxEmitter.builder("sparkles").state(CgVfxEmitterState.ONCE)
            .renderer(CgVfxEmitter.Renderer.QUADS)
            .optional().capacity(520).burst(0f, 500).shape(CgSphere.of(3f)).hemisphere().speed(18f, 36f)
            .life(4f, 7f).size(0.1f, 0.5f, 2f).heat(1f)
            .module(new CgVfxModule.Gravity(9.8f))
            .module(new CgVfxModule.Drag(1.5f, 0.05f))
            .module(new CgVfxModule.Buoyancy(11f, 8f))
            .module(new CgVfxModule.Turbulence(5f, 0.12f, 0.5f))
            .module(new CgVfxModule.Wind(1f))
            .module(new CgVfxModule.Updraft(6f, 5f, 12f, 2f))
            .module(new CgVfxModule.Ground(0.2f, 0.7f, 0.6f))
            .opacity(CgKeyframes.start(0f, 0f).to(0.05f, 1f, CgEasings.LINEAR).to(0.6f, 1f, CgEasings.LINEAR)
                    .to(1f, 0f, CgEasings.OUT_QUAD).build())
            .build();

    /**
     * Ink streaks of the shock, as anime draws them: strokes flying out fast from the dome's edge, upward only so none
     * cuts into the ground, braked by the air, thinning to nothing. A drawn mark, so an effect starts them only with an
     * impact frame ({@link #drawn}).
     */
    public static final CgVfxEmitter INK = CgVfxEmitter.builder("ink").state(CgVfxEmitterState.once(0.8f))
            .renderer(CgVfxEmitter.Renderer.ARCS)
            .capacity(34).rate(36f).shape(CgShell.of(7f, 10f)).hemisphere().speed(22f, 55f)
            .life(0.5f, 1.2f).size(0.25f, 0.45f, 1f)
            .module(new CgVfxModule.Drag(0f, 0.08f))
            .size(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build())
            .opacity(CgKeyframes.start(0f, 1f).to(0.7f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.LINEAR).build())
            .build();

    /**
     * Hot streaks: glowing fragments shot up and out of the blast over its first half second, most short and a few long
     * (the ray shader's length by seed), the low ones skimming the ground; each drawn out along its velocity by its speed,
     * so it shortens as the air brakes it, drooping as it slows and dying out as it cools.
     */
    public static final CgVfxEmitter RAYS = CgVfxEmitter.builder("rays").state(CgVfxEmitterState.once(0.5f))
            .renderer(CgVfxEmitter.Renderer.ARCS)
            .capacity(120).rate(200f).shape(CgShell.of(2f, 5f)).hemisphere().speed(18f, 60f)
            .life(0.6f, 1.4f).size(0.18f, 0.45f, 2f)
            .module(new CgVfxModule.Gravity(6f))
            .module(new CgVfxModule.Drag(0f, 0.04f))
            .size(CgKeyframes.start(0f, 1f).to(1f, 0.4f, CgEasings.IN_QUAD).build())
            .opacity(CgKeyframes.start(0f, 1f).to(0.3f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.OUT_QUAD).build())
            .build();

    /**
     * The shock front along the ground: one ring born at the dome's foot, racing out ahead of the surge and slowing to a
     * stop, 9.2 blocks out to 46 over 2.2 seconds, easing out as {@link #SKIRT}'s front does. Its air bends the scene
     * ({@code shock_ring.shader}) over its foot glowing on the ground for the first part of its run ({@code ring.shader}). One particle at the
     * burst's centre, its drawn size the ring's radius: the burst must be on the ground, or within the flash's reach.
     */
    public static final CgVfxEmitter RINGS = CgVfxEmitter.builder("rings").state(CgVfxEmitterState.ONCE)
            .renderer(CgVfxEmitter.Renderer.MESHES)
            .capacity(1).burst(0f, 1).direction(0, 1, 0, 0f).speed(0f, 0f)
            .life(2.2f, 2.2f).size(46f, 46f, 1f)
            .size(CgKeyframes.start(0f, 0.2f).to(1f, 1f, CgEasings.OUT_QUAD).build())
            .opacity(CgKeyframes.start(0f, 0f).to(0.04f, 1f, CgEasings.LINEAR).to(0.5f, 0.7f, CgEasings.LINEAR)
                    .to(1f, 0f, CgEasings.IN_QUAD).build())
            .build();

    /** Its colours' schema, named as the kit is. */
    public final CgVfxSchema schema;
    /** The cloud's body and its hot core, the debris and ink, its dust, and a spark's white centre. */
    public final CgVfxParam.Color body, hot, debris, dust, sparkCore;
    /**
     * This kit's emitters: the defaults above, named for it, so a look can replace any by that name. Its specks raise
     * {@link #puffs} where they land: a look replacing the specks keeps that only by adding the event itself.
     */
    public final CgVfxEmitter billows, surge, specks, puffs, sparkles, ink, rays, rings, skirt;
    private final CgVfxKit kit;

    /** A kit named {@code name}, its schema's name ({@code namespace:path}), defaulting to a blue blast. */
    public CgVfxExplosion(String name) {
        schema = CgVfxSchema.of(name);
        body = schema.color("body", 0xFF0F4CF2);
        hot = schema.color("hot", 0xFF6BB2FF);
        debris = schema.color("debris", 0xFF050A1F);
        dust = schema.color("dust", 0x99999187);
        sparkCore = schema.color("sparkCore", 0xFFFFFFFF);
        billows = named(BILLOWS, name);
        surge = named(SURGE, name);
        puffs = named(DUST, name);
        specks = named(SPECKS, name).toBuilder()
                .event(CgVfxEvent.onLanding().spawn(puffs, 1).inherit(0.15f)).build();
        sparkles = named(SPARKLES, name);
        ink = named(INK, name);
        rays = named(RAYS, name);
        rings = named(RINGS, name);
        skirt = named(SKIRT, name);
        kit = CgVfxKit.of(name).params(schema)
                .emitter(billows).emitter(surge).emitter(specks).emitter(sparkles).emitter(ink).emitter(rays)
                .emitter(rings).emitter(skirt)
                .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/smoke/billow.shader").on(billows)
                        .bind("_Color", body).bind("_Hot", hot).role(CgVfxLayer.Role.SMOKE).build())
                .layer(dustLayer(surge).set("_Aspect", 0.6f).set("_Boil", 1.6f).bind("_Hot", hot).build())
                .layer(dustLayer(skirt).set("_Aspect", 0.55f).set("_Boil", 1.8f).bind("_Hot", hot).build())
                .layer(CgVfxLayer.of(PARTICLE + "speck.shader").on(specks).bind("_Color", debris)
                        .role(CgVfxLayer.Role.SMOKE).build())
                .layer(dustLayer(puffs).set("_Glow", 0f).from(CgQuality.MEDIUM).build())
                .layer(CgVfxLayer.of(PARTICLE + "arc.shader").on(ink).bind("_Color", debris)
                        .role(CgVfxLayer.Role.SMOKE).build())
                .layer(glow(PARTICLE + "ray.shader", rays))
                .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/air/shock_ring.shader").on(rings)
                        .role(CgVfxLayer.Role.DISTORTION).from(CgQuality.MEDIUM).build())
                .layer(glow(PARTICLE + "ring.shader", rings))
                .layer(glow(PARTICLE + "spark.shader", sparkles))
                .build();
    }

    /** Its emitters, their layers and its colours, for a look's {@code with}. */
    public CgVfxKit kit() {
        return kit;
    }

    /** Every emitter of this kit an effect starts, in that order; the puffs run inside the specks' instances. */
    public List<CgVfxEmitter> emitters() {
        return List.of(billows, surge, specks, sparkles, ink, rays, rings, skirt);
    }

    /**
     * Whether {@code emitter} is this kit's ink, or a look's replacement of it by name: a drawn mark among lit and glowing
     * parts, which an effect starts only when its burst has an impact frame.
     */
    public boolean drawn(CgVfxEmitter emitter) {
        return emitter.name().equals(ink.name());
    }

    private CgVfxLayer.Builder dustLayer(CgVfxEmitter emitter) {
        return CgVfxLayer.of(PARTICLE + "dust.shader").on(emitter).bind("_Color", dust).role(CgVfxLayer.Role.SMOKE);
    }

    private CgVfxLayer glow(String shader, CgVfxEmitter emitter) {
        return CgVfxLayer.of(shader).on(emitter).bind("_Color", hot).bind("_Hot", sparkCore).build();
    }

    private static CgVfxEmitter named(CgVfxEmitter emitter, String kit) {
        return emitter.toBuilder().name(kit + "." + emitter.name()).build();
    }
}
