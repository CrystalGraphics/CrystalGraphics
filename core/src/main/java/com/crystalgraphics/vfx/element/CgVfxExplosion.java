package com.crystalgraphics.vfx.element;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModule;

import java.util.List;

/**
 * The parts nearly every explosion and ki attack shares, as Sparking Zero reuses them: a cloud of cel-shaded billows,
 * dark debris specks, glowing embers, and the shock's ink: curved streaks, straight spikes and expanding rings. Each part is an emitter with its own physics
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
     * The cloud: billows burst out mostly sideways, braked hard by the air, then rise a little on their heat as it cools
     * and drift with the turbulence, swelling as they go. Each erodes away within a few blocks of the camera, so a
     * player standing in the blast still sees out (the billow shader's {@code _NearFrom} and {@code _NearTo}).
     */
    public static final CgVfxEmitter BILLOWS = CgVfxEmitter.builder("billows").renderer(CgVfxEmitter.Renderer.MESHES)
            .capacity(64).burst(0f, 64).shape(2.4f).launch(-0.1f, 0.75f, 1.5f).speed(8f, 14f)
            .life(5f, 6.5f).size(1.1f, 2f, 1f).spin(0.05f, 0.25f).heat(1f)
            .module(new CgVfxModule.Drag(0.3f, 0.15f))
            .module(new CgVfxModule.Buoyancy(1.2f, 2.5f))
            .module(new CgVfxModule.Turbulence(1f, 0.06f, 0.15f))
            .module(new CgVfxModule.Wind(0.2f))
            .size(CgKeyframes.start(0f, 1f).to(1f, 1.8f, CgEasings.OUT_CUBIC).build())
            .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD).to(0.5f, 0.92f, CgEasings.LINEAR)
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

    /** The cloud's body and its hot core, the debris and ink, and a spark's white centre: colours on its schema. */
    public final CgVfxParam body, hot, debris, sparkCore;
    /** This kit's emitters: the defaults above, named for it, so a look can replace any by that name. */
    public final CgVfxEmitter billows, specks, sparkles, ink, rays, rings;
    /** The layers that draw them, each in its emitter's slot. */
    public final CgVfxLayer billowLayer, speckLayer, sparkLayer, inkLayer, rayLayer, ringLayer;

    /** Declares this kit's colours on {@code schema}, defaulting to a blue blast, and builds its layers. */
    public CgVfxExplosion(CgVfxSchema schema, String name) {
        body = schema.color(name + "Body", 0.06f, 0.3f, 0.95f, 1f);
        hot = schema.color(name + "Hot", 0.3f, 0.88f, 1f, 1f);
        debris = schema.color(name + "Debris", 0.02f, 0.04f, 0.12f, 1f);
        sparkCore = schema.color(name + "SparkCore", 1f, 1f, 1f, 1f);
        billows = named(BILLOWS, name);
        specks = named(SPECKS, name);
        sparkles = named(SPARKLES, name);
        ink = named(INK, name);
        rays = named(RAYS, name);
        rings = named(RINGS, name);
        billowLayer = CgVfxLayer.builder("crystalgraphics:shaders/vfx/smoke/billow.shader").slot(billows.layer())
                .colors(body, hot).priority(CgVfxLayer.PRIORITY_SMOKE).build();
        speckLayer = CgVfxLayer.builder(PARTICLE + "speck.shader").slot(specks.layer())
                .colors(debris, null).priority(CgVfxLayer.PRIORITY_SMOKE).build();
        inkLayer = CgVfxLayer.builder(PARTICLE + "arc.shader").slot(ink.layer())
                .colors(debris, null).priority(CgVfxLayer.PRIORITY_SMOKE).build();
        rayLayer = CgVfxLayer.builder(PARTICLE + "ray.shader").slot(rays.layer())
                .colors(debris, null).priority(CgVfxLayer.PRIORITY_SMOKE).build();
        ringLayer = CgVfxLayer.builder(PARTICLE + "ring.shader").slot(rings.layer())
                .colors(debris, null).priority(CgVfxLayer.PRIORITY_SMOKE).build();
        sparkLayer = CgVfxLayer.builder(PARTICLE + "spark.shader").slot(sparkles.layer())
                .colors(hot, sparkCore).priority(CgVfxLayer.PRIORITY_BANDS).build();
    }

    /** Every emitter of this kit, in the order they start. */
    public List<CgVfxEmitter> emitters() {
        return List.of(billows, specks, sparkles, ink, rays, rings);
    }

    @Override
    public void addTo(CgVfxLook.Builder look) {
        look.layer(billowLayer).layer(speckLayer).layer(inkLayer).layer(rayLayer).layer(ringLayer).layer(sparkLayer);
        for (CgVfxEmitter emitter : emitters()) look.emitter(emitter);
    }

    private static CgVfxEmitter named(CgVfxEmitter emitter, String kit) {
        String name = kit + "." + emitter.name();
        return emitter.toBuilder().name(name).layer(name).build();
    }
}
