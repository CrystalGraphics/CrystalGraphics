package com.crystalgraphics.vfx.element;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;

import java.util.List;

/**
 * The parts nearly every explosion and ki attack shares, as Sparking Zero reuses them: a cloud of cel-shaded billows in a
 * surge of dust along the ground, dark debris specks, glowing embers, and the shock's ink: curved streaks, straight spikes and expanding rings. Each part is an emitter with its own physics
 * (plan vfx-particles) and a layer that draws it. An effect makes one on its schema, which declares the parts' colours
 * there, adds it to its looks, and starts the look's emitters where the burst happens.
 *
 * <pre>{@code
 * static final CgVfxSchema SCHEMA = new CgVfxSchema();
 * static final CgVfxExplosion BLAST = new CgVfxExplosion(SCHEMA, "blast");
 * static final CgVfxLook BLUE = CgVfxLook.builder(SCHEMA).add(BLAST).build();
 *
 * // another palette
 * static final CgVfxLook FIRE = BLUE.toBuilder()
 *         .set(BLAST.body, 0.95f, 0.3f, 0.04f, 1f).set(BLAST.hot, 1f, 0.88f, 0.3f, 1f)
 *         .build();
 *
 * // other physics for one part: replace its emitter by name
 * static final CgVfxLook DRIFTING = BLUE.toBuilder()
 *         .emitter(BLAST.sparkles.toBuilder().module(new CgVfxModule.Wind(2f)).build())
 *         .build();
 *
 * // where it bursts, in the effect's tick
 * for (CgVfxEmitter e : look().emitters()) { CgVfxEmitterInstance i = new CgVfxEmitterInstance(e, seed); i.start(x, y, z); ... }
 * }</pre>
 *
 * <ul>
 *   <li>Two kits on one schema need different names: the name prefixes their colours, slots and emitters.</li>
 *   <li>Sizes and speeds are in blocks, made for a blast about ten blocks across.</li>
 * </ul>
 */
public final class CgVfxExplosion implements CgVfxLook.Part {

    private static final String PARTICLE = "crystalgraphics:shaders/vfx/particle/";

    /**
     * The cloud, rolling out from the foot of the blast's dome: puffs stream from a ring at its edge as it reaches it,
     * low and flat along the ground, small at first and swelling, braked by the air; rising a little as they cool and
     * swirling together in the turbulence. Nothing grows inside the dome, which stays the blast's to show. The ring
     * suits a dome about ten blocks in radius; a bigger dome replaces this with {@code shape} at its own edge. Each erodes
     * away within a few blocks of the camera, so a player standing in the blast still sees out (the billow shader's
     * {@code _NearFrom} and {@code _NearTo}).
     */
    public static final CgVfxEmitter BILLOWS = CgVfxEmitter.builder("billows").renderer(CgVfxEmitter.Renderer.MESHES)
            .capacity(130).rate(300f, 0.15f, 0.55f).shape(9f, 11f).launch(-0.05f, 0.3f, 2.2f).speed(5f, 12f)
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
    public static final CgVfxEmitter SPECKS = CgVfxEmitter.builder("specks").renderer(CgVfxEmitter.Renderer.QUADS)
            .optional().capacity(820).burst(0f, 800).shape(3f).launch(0.05f, 1f, 0.7f).speed(6f, 16f)
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
            .optional().shape(0.05f).launch(0.3f, 1f, 1.5f).speed(0.4f, 1.4f)
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
    public static final CgVfxEmitter SURGE = CgVfxEmitter.builder("surge").renderer(CgVfxEmitter.Renderer.QUADS)
            .optional().capacity(360).rate(980f, 0.08f, 0.42f).shape(9f, 11f).launch(0f, 0.04f, 1f).speed(9f, 19f)
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
     * Embers: thrown out fast past the cloud and braked mostly by quadratic drag, so they clear the billows, then held up
     * by their heat, swirled by turbulence and carried by the wind, sinking to the ground as they cool and dim.
     */
    public static final CgVfxEmitter SPARKLES = CgVfxEmitter.builder("sparkles").renderer(CgVfxEmitter.Renderer.QUADS)
            .optional().capacity(520).burst(0f, 500).shape(3f).launch(-0.05f, 0.8f, 1.6f).speed(18f, 36f)
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

    /** Ink streaks of the shock: strokes fly out fast from the dome's edge, braked by the air, thinning to nothing. */
    public static final CgVfxEmitter INK = CgVfxEmitter.builder("ink").renderer(CgVfxEmitter.Renderer.ARCS)
            .capacity(34).rate(36f, 0f, 0.8f).shape(6f).launch(-0.2f, 1f, 0.8f).speed(30f, 45f)
            .life(0.6f, 1.1f).size(0.25f, 0.45f, 1f)
            .module(new CgVfxModule.Drag(0f, 0.08f))
            .size(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build())
            .opacity(CgKeyframes.start(0f, 1f).to(0.7f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.LINEAR).build())
            .build();

    /**
     * Ink spikes: long straight strokes fanning up and out of the cloud, each stretched along its velocity by its
     * speed, so they shorten as the air brakes them.
     */
    public static final CgVfxEmitter RAYS = CgVfxEmitter.builder("rays").renderer(CgVfxEmitter.Renderer.ARCS)
            .capacity(32).rate(80f, 0.03f, 0.35f).shape(4f).launch(0.05f, 0.9f, 1.4f).speed(16f, 27f)
            .life(1.8f, 2.7f).size(0.14f, 0.32f, 1.5f)
            .module(new CgVfxModule.Drag(0f, 0.05f))
            .size(CgKeyframes.start(0f, 1f).to(0.4f, 1f, CgEasings.LINEAR).to(1f, 0.35f, CgEasings.IN_OUT_SINE).build())
            .opacity(CgKeyframes.start(0f, 1f).to(0.35f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_OUT_SINE).build())
            .build();

    /**
     * Shockwave rings: thin ink bands at several heights, expanding fast to tens of blocks round the blast, thinning and
     * breaking into dashes as they go. Each is one particle, its drawn size the ring's radius.
     */
    public static final CgVfxEmitter RINGS = CgVfxEmitter.builder("rings").renderer(CgVfxEmitter.Renderer.MESHES)
            .capacity(6).rate(12f, 0f, 0.35f).shape(10f).launch(1f, 1f, 1f).speed(0.5f, 2f)
            .life(2.2f, 3f).size(22f, 34f, 1f)
            .size(CgKeyframes.start(0f, 0.25f).to(1f, 1f, CgEasings.OUT_QUAD).build())
            .opacity(CgKeyframes.start(0f, 0.85f).to(0.35f, 0.85f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_OUT_SINE).build())
            .build();

    /** The cloud's body and its hot core, the debris and ink, its dust, and a spark's white centre: colours on its schema. */
    public final CgVfxParam body, hot, debris, dustColor, sparkCore;
    /**
     * This kit's emitters: the defaults above, named for it, so a look can replace any by that name. Its specks raise
     * its {@link #dust} where they land: a look replacing the specks keeps that only by adding the event itself.
     */
    public final CgVfxEmitter billows, surge, specks, dust, sparkles, ink, rays, rings;
    /** The layers that draw them, each in its emitter's slot. */
    public final CgVfxLayer billowLayer, surgeLayer, speckLayer, dustLayer, sparkLayer, inkLayer, rayLayer,
            ringLayer;

    /** Declares this kit's colours on {@code schema}, defaulting to a blue blast, and builds its layers. */
    public CgVfxExplosion(CgVfxSchema schema, String name) {
        body = schema.color(name + "Body", 0.06f, 0.3f, 0.95f, 1f);
        hot = schema.color(name + "Hot", 0.42f, 0.7f, 1f, 1f);
        debris = schema.color(name + "Debris", 0.02f, 0.04f, 0.12f, 1f);
        dustColor = schema.color(name + "Dust", 0.6f, 0.57f, 0.53f, 0.6f);
        sparkCore = schema.color(name + "SparkCore", 1f, 1f, 1f, 1f);
        billows = named(BILLOWS, name);
        surge = named(SURGE, name);
        dust = named(DUST, name);
        specks = named(SPECKS, name).toBuilder()
                .event(CgVfxEvent.onLanding().spawn(dust, 1).inherit(0.15f)).build();
        sparkles = named(SPARKLES, name);
        ink = named(INK, name);
        rays = named(RAYS, name);
        rings = named(RINGS, name);
        billowLayer = CgVfxLayer.builder("crystalgraphics:shaders/vfx/smoke/billow.shader").slot(billows.layer())
                .colors(body, hot).order(CgVfxLayer.ORDER_SMOKE).build();
        surgeLayer = CgVfxLayer.builder(PARTICLE + "dust.shader").slot(surge.layer())
                .colors(dustColor, hot).order(CgVfxLayer.ORDER_SMOKE)
                .properties(b -> b.set1f("_Aspect", 0.6f).set1f("_Boil", 1.6f)).build();
        speckLayer = CgVfxLayer.builder(PARTICLE + "speck.shader").slot(specks.layer())
                .colors(debris, null).order(CgVfxLayer.ORDER_SMOKE).build();
        dustLayer = CgVfxLayer.builder(PARTICLE + "dust.shader").slot(dust.layer())
                .colors(dustColor, null).order(CgVfxLayer.ORDER_SMOKE).from(CgQuality.MEDIUM)
                .properties(b -> b.set1f("_Glow", 0f)).build();
        inkLayer = CgVfxLayer.builder(PARTICLE + "arc.shader").slot(ink.layer())
                .colors(debris, null).order(CgVfxLayer.ORDER_SMOKE).build();
        rayLayer = CgVfxLayer.builder(PARTICLE + "ray.shader").slot(rays.layer())
                .colors(debris, null).order(CgVfxLayer.ORDER_SMOKE).build();
        ringLayer = CgVfxLayer.builder(PARTICLE + "ring.shader").slot(rings.layer())
                .colors(debris, null).order(CgVfxLayer.ORDER_SMOKE).build();
        sparkLayer = CgVfxLayer.builder(PARTICLE + "spark.shader").slot(sparkles.layer())
                .colors(hot, sparkCore).order(CgVfxLayer.ORDER_BANDS).build();
    }

    /** Every emitter of this kit an effect starts, in that order; the dust runs inside the specks' instances. */
    public List<CgVfxEmitter> emitters() {
        return List.of(billows, surge, specks, sparkles, ink, rays, rings);
    }

    @Override
    public void addTo(CgVfxLook.Builder look) {
        look.layer(billowLayer).layer(surgeLayer).layer(speckLayer).layer(dustLayer).layer(inkLayer).layer(rayLayer).layer(ringLayer).layer(sparkLayer);
        for (CgVfxEmitter emitter : emitters()) look.emitter(emitter);
    }

    private static CgVfxEmitter named(CgVfxEmitter emitter, String kit) {
        String name = kit + "." + emitter.name();
        return emitter.toBuilder().name(name).layer(name).build();
    }
}
