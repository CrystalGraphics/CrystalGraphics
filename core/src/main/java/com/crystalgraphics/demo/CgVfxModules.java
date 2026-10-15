package com.crystalgraphics.demo;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.mesh.CgProceduralMesh;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgCapsule;
import com.crystalgraphics.shape.primitive.CgCone;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxOwner;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.CgVfxTrace;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxField;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.trail.CgVfxTrail;
import com.crystalgraphics.world.CgWorldQueries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * X6's module catalogue, a station each, to walk round and compare: energy gathering into a core, a tornado, a bar
 * magnet's field, a shield taking shape; colliders (a sphere with collision events, a cage, a slope, a block, an
 * obstacle course); damping beside drag, a speed limit; flipbook smoke and fire, and the five facing modes. Every station
 * replays every {@link #PERIOD} seconds. The stations span about 60 blocks along x and 30 along z round the origin; each stands on the world's ground under it
 * ({@link CgWorldQueries#groundBelow}), else at the origin's height, and its particles land on the world's ground
 * wherever it stands, else on its floor. The boulder's sparks and chips and the block's rain also hit whatever is on screen
 * through the scene's depth ({@link CgVfxModule.CollideDepth}): a mob or player standing under them. The harness's
 * {@code vfx-modules}, and a scene of {@link CgRenderDemo}.
 *
 * <pre>{@code
 * CgVfxModules modules = new CgVfxModules(font);    // on the render thread: its textures and materials; the
 *                                                   // flipbook sheets fill from a worker a moment later
 * world.onFrame(view -> modules.submit(world, x, y, z, CgFrameClock.seconds()));
 * modules.delete();
 * }</pre>
 *
 * <p>Labels are drawn only with a font; {@code null} leaves them out.</p>
 */
public final class CgVfxModules {

    private static final Logger LOG = LogManager.getLogger("CgVfxModules");
    private static final String PARTICLE = "crystalgraphics:shaders/vfx/particle/";
    private static final float PERIOD = 7f, FOREVER = 1.0e6f;
    /** How deep behind the scene's depth a particle still counts as hitting it: under half a block, so one passing behind a mob is not snapped onto it. */
    private static final float DEPTH_THICKNESS = 0.4f;
    private static final int SHEETS_ZONE = CgTrace.name("demo.modules.sheets");
    /** The station grid: columns along x, rows along z, toward the camera. */
    private static final float[] COLUMNS = {-21f, -7f, 7f, 21f}, ROWS = {-14f, 0f, 14f, 28f, 45f, 63f};

    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:demo/modules");
    private static final CgVfxParam.Color GOLD = SCHEMA.color("gold", 0xFFFF8C1F);
    private static final CgVfxParam.Color WHITE = SCHEMA.color("white", 0xFFFFFFFF);
    private static final CgVfxParam.Color CYAN = SCHEMA.color("cyan", 0xFF2095FF, 1.2f);
    private static final CgVfxParam.Color VIOLET = SCHEMA.color("violet", 0xFF8931FF, 1.3f);
    private static final CgVfxParam.Color SMOKE = SCHEMA.color("smoke", 0xCC8C8C99);
    private static final CgVfxParam.Color EMBER = SCHEMA.color("ember", 0xFFFF4A0B, 1.2f);
    private static final CgVfxParam.Color FLAME = SCHEMA.color("flame", 0xFFFFC85B, 1.4f);
    private static final CgVfxParam.Color DEBRIS = SCHEMA.color("debris", 0xFF4C4238);
    private static final CgVfxParam.Color DUST = SCHEMA.color("dust", 0x8073614C);
    private static final CgVfxParam.Color FUNNEL = SCHEMA.color("funnel", 0xD980858F);
    private static final CgVfxParam.Color SOOT = SCHEMA.color("soot", 0x80333030);
    // The fire sheet carries its own colours: a flame's tint reddens from these as it cools. Flames overlap and add, so
    // each is faint (A's alpha): bright where many cross, never a white blob.
    private static final CgVfxParam.Color FIRE_COOL = SCHEMA.color("fireCool", 0x73E6521A);
    private static final CgVfxParam.Color FIRE_HOT = SCHEMA.color("fireHot", 0xFFFFC780);
    private static final CgVfxParam.Color MAGENTA = SCHEMA.color("magenta", 0xFFFF2BBF, 1.2f);
    private static final CgVfxParam.Color ICE = SCHEMA.color("ice", 0xE64A9FFF, 1.2f);
    private static final CgVfxParam.Color SNOW = SCHEMA.color("snow", 0x8CD9E6FF);
    private static final CgVfxParam.Color SPIRIT_COOL = SCHEMA.color("spiritCool", 0x801A4CFF);
    private static final CgVfxParam.Color SPIRIT_HOT = SCHEMA.color("spiritHot", 0xFF80DCFF, 1.1f);

    private static final CgKeyframes FADE = CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD)
            .to(0.7f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_QUAD).build();

    /**
     * One station: its emitters started at their sources, its look, what it stands beside, what moves them, and what it
     * draws besides (a show made anew each play, since a trail's points are one play's).
     */
    private record Station(String name, float x, float z, CgVfxLook look, List<CgVfxEmitter> emitters, float[][] sources,
                           Consumer<Props> props, Mover mover, Supplier<Show> show, float[] box) {
        Station(String name, float x, float z, CgVfxLook look, List<CgVfxEmitter> emitters, float[][] sources,
                Consumer<Props> props) {
            this(name, x, z, look, emitters, sources, props, null, null, null);
        }

        Station(String name, float x, float z, CgVfxLook look, List<CgVfxEmitter> emitters, float[][] sources,
                Consumer<Props> props, Mover mover) {
            this(name, x, z, look, emitters, sources, props, mover, null, null);
        }
    }

    /** What a station draws on its slots each frame of a play, beside its particles, and what it moves each tick. */
    private interface Show {
        void draw(CgVfxEffect effect, CgVfxFrame frame, float age);

        default void tick(CgVfxEffect effect, float dt, float age) {
        }

        /** Whether it has nothing more to draw: the play ends once this and its emitters are done. */
        default boolean done(float age) {
            return true;
        }
    }

    /** Where emitter {@code i}'s source is at {@code age}, written into {@code at}: a station whose sources travel. */
    private interface Mover {
        void at(int i, float age, float[] at);
    }

    /** What a station draws besides its particles: a collider's shape, a label over part of it. */
    private interface Props {
        void sphere(float x, float y, float z, float radius);

        void box(float x, float y, float z, Matrix4f transform);

        void label(float x, float y, float z, float height, String text, int color);
    }

    private final CgVfxOwner vfx;
    /** A system of its own, updated and drawn by {@link #submit}; false on the world's. */
    private final boolean drives;
    private final List<Station> stations = new ArrayList<>();
    private final List<CgTexture> sheets = new ArrayList<>();
    /** Each sheet's texels, waiting for {@link #fillSheets()}'s worker; and whether {@link #delete()} has run. */
    private final List<Runnable> fills = new ArrayList<>();
    private volatile boolean deleted;
    private final Matrix4f transform = new Matrix4f();
    private float nextPlay;
    private CgMesh sphere, cube;
    private CgMaterial mercury, copper;
    private CgWorldRenderer world;
    private final CgFont font;
    /** Each station's floor this play, absolute. */
    private float[] floors;

    /**
     * A station playing: its emitters ticked until the period ends and they have all finished. Its particles land on the
     * world's ground, wherever the station stands, else on the station's floor.
     */
    private static final class Play extends CgVfxEffect {
        private final List<CgVfxEmitterInstance> emitters = new ArrayList<>();
        private final Mover mover;
        private final Show show;
        private final float[] at = new float[3];

        Play(Station station, double x, double y, double z) {
            super(station.look(), x + station.x(), y, z + station.z());
            mover = station.mover();
            show = station.show() == null ? null : station.show().get();
            float[] b = station.box();
            if (b != null) spawnBox(originX + b[0], originY + b[1], originZ + b[2], b[3], b[4], b[5]);
            for (int i = 0; i < station.emitters().size(); i++) {
                CgVfxEmitterInstance emitter = new CgVfxEmitterInstance(station.emitters().get(i), seed + i * 0.137f);
                float[] s = station.sources()[i];
                emitter.start(s[0], s[1], s[2]);
                emitter.ground(0f);
                emitters.add(emitter);
            }
        }

        @Override
        protected void tick(float dt) {
            boolean done = true;
            for (int i = 0; i < emitters.size(); i++) {
                if (mover != null) {
                    mover.at(i, age, at);
                    emitters.get(i).moveTo(at[0], at[1], at[2]);
                }
                tick(emitters.get(i), dt);
                done &= emitters.get(i).finished();
            }
            if (show != null) {
                show.tick(this, dt, age);
                done &= show.done(age);
            }
            if (done && age > 1f) die();
        }

        @Override
        protected void submit(CgVfxFrame frame) {
            for (int i = 0; i < emitters.size(); i++) frame.particles(this, emitters.get(i));
            if (show != null) show.draw(this, frame, age);
        }
    }

    /** Builds every station, its textures and materials, on a system of its own: on the render thread. */
    public CgVfxModules(CgFont font) {
        this(font, null);
    }

    /** As {@link #CgVfxModules(CgFont)}, played by {@code owner}: the world's system, which the engine updates and draws. */
    public CgVfxModules(CgFont font, CgVfxOwner owner) {
        drives = owner == null;
        vfx = drives ? CgVfx.owner("crystalgraphics", new CgVfxSystem()) : owner;
        this.font = font;
        sphere = CgMeshShapes.sphere(40, 80);
        cube = CgMeshShapes.cube();
        mercury = CgMaterial.load("crystalgraphics:shaders/demo/vfx_mercury.shader");
        copper = CgMaterial.load("crystalgraphics:shaders/demo/vfx_copper.shader");
        CgTexture smokeSheet = sheet(8, 8, 128, CgVfxModules::smokeFrame);
        CgTexture fireSheet = sheet(4, 4, 128, CgVfxModules::fireFrame);
        CgTexture dot = sheet(1, 1, 64, (f, u, v, out) -> dot(u, v, out));
        CgTexture wispSheet = sheet(4, 4, 128, CgVfxModules::wispFrame);
        CgTexture star = sheet(1, 1, 128, (f, u, v, out) -> star(u, v, out));
        CgTexture rune = sheet(1, 1, 128, (f, u, v, out) -> rune(u, v, out));
        CgTexture ring = sheet(1, 1, 128, (f, u, v, out) -> ring(u, v, out));
        CgTexture shard = sheet(1, 1, 64, (f, u, v, out) -> shard(u, v, out));
        fillSheets();

        gather(COLUMNS[0], ROWS[0]);
        tornado(COLUMNS[1], ROWS[0], smokeSheet);
        field(COLUMNS[2], ROWS[0], dot, ring);
        // The uncapped fountain in limit sprays far: it stands in a corner.
        limit(COLUMNS[3], ROWS[0]);
        boulder(COLUMNS[0], ROWS[1]);
        cage(COLUMNS[1], ROWS[1]);
        slope(COLUMNS[2], ROWS[1], shard, smokeSheet);
        block(COLUMNS[3], ROWS[1], dot, ring);
        // Past the last column: the course runs ten blocks along x.
        obstacles(COLUMNS[3] + 15f, ROWS[1]);
        dampingAndDrag(COLUMNS[0], ROWS[2]);
        shield(COLUMNS[1], ROWS[2], dot);
        flipbooks(COLUMNS[2], ROWS[2], smokeSheet, fireSheet);
        facings(COLUMNS[3], ROWS[2], star, rune, wispSheet, dot, ring);
        shapes(COLUMNS[0], ROWS[3]);
        moving(COLUMNS[1], ROWS[3]);
        wake(COLUMNS[2], ROWS[3]);
        meshes(COLUMNS[3], ROWS[3]);
        aura(COLUMNS[3] + 15f, ROWS[3]);
        dash(COLUMNS[0], ROWS[4], smokeSheet);
        rush(COLUMNS[1], ROWS[4]);
        volley(COLUMNS[2], ROWS[4], smokeSheet);
        anime(COLUMNS[3], ROWS[4]);
        storm(COLUMNS[0], ROWS[5]);
        meteor(COLUMNS[1] + 4f, ROWS[5], smokeSheet);
        sparks(COLUMNS[2] + 6f, ROWS[5]);
        streams(COLUMNS[3] + 10f, ROWS[5]);
        floors = new float[stations.size()];
        for (Station s : stations) LOG.info("[vfx-modules] {} at x {}, z {}", s.name(), s.x(), s.z());
        LOG.info("[vfx-modules] every station replays every {}s; a simulation switch reaches it from the next", PERIOD);
    }

    // ── stations ──────────────────────────────────────────────────────────────

    /**
     * Where particles are born, one shape each and a colour each, labelled under it, left to right: a cone's spray aimed
     * up, 24 even places round a ring's edge, a dome's surface flying outward, a thin box's sheet launched up within 10
     * degrees, and 12 even places along a capsule's segment.
     */
    private void shapes(float x, float z) {
        CgVfxEmitter cone = CgVfxEmitter.builder("shapeCone").capacity(900).rate(260f, 0f, PERIOD - 3f)
                .shape(CgCone.of(0.4, Math.toRadians(14))).pointing(0f, 1f, 0f).speed(5f, 7f).life(0.8f, 1.1f)
                .size(0.05f, 0.08f, 1f).heat(1f).module(new CgVfxModule.Gravity(6f)).opacity(FADE).build();
        CgVfxEmitter ring = CgVfxEmitter.builder("shapeRing").capacity(600).rate(72f, 0f, PERIOD - 3f)
                .shape(CgRing.of(1.1, 1.1, 0)).edge().ordered(24).speed(0f, 0f).life(2.2f, 2.2f)
                .size(0.08f, 0.08f, 1f).heat(1f).module(new CgVfxModule.Force(0f, 0.9f, 0f)).opacity(FADE).build();
        CgVfxEmitter dome = CgVfxEmitter.builder("shapeDome").capacity(1200).rate(420f, 0f, PERIOD - 3f)
                .shape(CgSphere.of(1.1)).surface().hemisphere().speed(0.05f, 0.15f).life(1f, 1.4f)
                .size(0.04f, 0.06f, 1f).heat(1f).opacity(FADE).build();
        CgVfxEmitter sheet = CgVfxEmitter.builder("shapeSheet").capacity(800).rate(220f, 0f, PERIOD - 3f)
                .shape(CgBox.of(1, 0.02, 1)).direction(0f, 1f, 0f, 8f).speed(1.6f, 2.2f).life(0.9f, 1.2f)
                .size(0.04f, 0.06f, 1f).heat(1f).opacity(FADE).build();
        CgVfxEmitter line = CgVfxEmitter.builder("shapeLine").capacity(500).rate(90f, 0f, PERIOD - 3f)
                .shape(CgCapsule.between(0, 0, -1.2, 0, 0, 1.2, 0)).edge().ordered(12).speed(0.25f, 0.25f)
                .life(1.6f, 1.6f).size(0.07f, 0.07f, 1f).heat(1f).opacity(FADE).build();
        List<CgVfxEmitter> emitters = List.of(cone, ring, dome, sheet, line);
        CgVfxParam.Color[] colours = {GOLD, CYAN, MAGENTA, ICE, VIOLET};
        String[] names = {"cone", "ring", "sphere", "box", "capsule"};
        String[] hints = {"pointed up", "edge, 24 ordered", "surface, hemisphere", "a sheet, upward", "segment, 12 ordered"};
        float[] at = {-6f, -3f, 0f, 3f, 6f};
        CgVfxLook.Builder look = CgVfxLook.builder(SCHEMA);
        for (int i = 0; i < emitters.size(); i++) look.emitter(emitters.get(i)).layer(sparkLayer(emitters.get(i), colours[i], WHITE));
        float[][] sources = {{at[0], 0.3f, 0f}, {at[1], 0.5f, 0f}, {at[2], 0.2f, 0f}, {at[3], 0.4f, 0f}, {at[4], 1f, 0f}};
        stations.add(new Station("shapes: where particles are born", x, z, look.build(), emitters, sources, p -> {
            for (int i = 0; i < names.length; i++) {
                p.label(at[i], 5.2f, 0f, 0.42f, names[i], 0xFFFFFFFF);
                p.label(at[i], 4.7f, 0f, 0.26f, hints[i], 0xFFB8D8FF);
            }
        }));
    }

    /**
     * A source moving 30 blocks a second round a circle: its rate spread along where it went each step, each particle
     * stepped for the rest of the step from its birth, so it leaves an even line rather than clumps a step apart.
     */
    private void moving(float x, float z) {
        CgVfxEmitter trail = CgVfxEmitter.builder("movingTrail").capacity(2000).rate(600f, 0f, PERIOD - 3f)
                .speed(0f, 0.15f).life(1.2f, 1.6f).size(0.05f, 0.05f, 1f).heat(1f).opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(trail).layer(sparkLayer(trail, GOLD, WHITE)).build();
        Mover round = (i, age, at) -> {
            float a = age * 30f / 3.5f;
            at[0] = 3.5f * (float) Math.cos(a);
            at[1] = 1.5f;
            at[2] = 3.5f * (float) Math.sin(a);
        };
        stations.add(new Station("moving: 30 blocks a second, no gaps", x, z, look, List.of(trail),
                new float[][]{{3.5f, 1.5f, 0f}}, null, round));
    }

    /**
     * A wake by distance: sixteen particles a block its source travels, each taking 40% of its velocity, along a line it
     * runs on, faster as it goes; at the end it teleports back, spawning nothing over the jump.
     */
    private void wake(float x, float z) {
        CgVfxEmitter wake = CgVfxEmitter.builder("wake").capacity(3000).ratePerBlock(16f, 0f, PERIOD - 3f).inherit(0.4f)
                .shape(CgShell.of(0.05, 0.25)).launch(0.2f, 0.8f, 1f).speed(0.2f, 0.6f).life(1.6f, 2.4f)
                .size(0.06f, 0.1f, 1f).heat(1f).module(new CgVfxModule.Drag(1.5f, 0f)).opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(wake).layer(sparkLayer(wake, CYAN, WHITE)).build();
        Mover run = (i, age, at) -> {
            // A run of 1.4 s from x -5 to 5, quicker as it goes; then it is back at -5 at once.
            float t = (age % 1.4f) / 1.4f;
            at[0] = -5f + 10f * t * t;
            at[1] = 0.4f;
            at[2] = 0f;
        };
        stations.add(new Station("wake: 16 a block travelled", x, z, look, List.of(wake),
                new float[][]{{-5f, 0.4f, 0f}}, p -> {
                    p.label(-5f, 5.2f, 0f, 0.42f, "start", 0xFFFFFFFF);
                    p.label(-5f, 4.7f, 0f, 0.26f, "teleported back to", 0xFFB8D8FF);
                    p.label(5f, 5.2f, 0f, 0.42f, "end", 0xFFFFFFFF);
                    p.label(5f, 4.7f, 0f, 0.26f, "fastest here", 0xFFB8D8FF);
                }, run));
    }

    /** One mesh drawn by the meshes station: its slot, where it stands, and how it moves and reveals over a play. */
    private interface MeshMotion {
        /** Writes its transform at {@code age} into {@code m} and answers its reveal, strength and trail into {@code fx}. */
        boolean at(float age, Matrix4f m, float[] fx);
    }

    /**
     * Meshes made by {@link CgProceduralMesh} and drawn on mesh slots through the flow shader, with particles born on
     * them: a combo of crescent slashes at changing tilts, each swept in along its arc and fading behind; twin swirls
     * of ribbons turning up a cone, the inner one against the outer; a shield dome rippling under shock rings that run
     * out from its foot over a turning rune circle; sparks born on the dome's surface and at the swirl's vertices in
     * order; and rocks thrown as meshes of their own.
     */
    private void meshes(float x, float z) {
        CgMesh slash = CgProceduralMesh.revolve(CgProceduralMesh.cylinder(1.25f, 1.6f, 0.06f)).angles(-75f, 75f)
                .divisions(48, 3).build();
        CgMesh swirl = CgProceduralMesh.ribbons(CgProceduralMesh.cone(1.3f, 3.2f)).count(3).twist(1.25f)
                .width(0.45f, 0.05f).vertices(48).build();
        CgMesh inner = CgProceduralMesh.ribbons(CgProceduralMesh.cylinder(0.35f, 0.9f, 3.6f)).count(2).twist(-2f)
                .width(0.2f, 0.08f).vertices(48).build();
        CgMesh dome = CgProceduralMesh.revolve(CgProceduralMesh.sphere(1.6f, 0f, 1.6f)).divisions(40, 12)
                .wave(2.4f, 0.06f).build();
        CgMesh shock = CgProceduralMesh.revolve(CgProceduralMesh.cylinder(1f, 1f, 0.12f)).divisions(64, 2).build();
        CgMesh rune = CgProceduralMesh.revolve(CgProceduralMesh.cylinder(2f, 2.3f, 0f)).divisions(64, 2).build();
        CgMesh rock = CgProceduralMesh.revolve(CgProceduralMesh.sphere(1f, -1f, 1f)).divisions(12, 8).curl(1.3f, 0.25f)
                .noiseSeed(7).build();

        CgVfxSlot slashSlot = CgVfxSlot.mesh("crystalgraphics:demo/modules/slash");
        CgVfxSlot swirlSlot = CgVfxSlot.mesh("crystalgraphics:demo/modules/swirl");
        CgVfxSlot innerSlot = CgVfxSlot.mesh("crystalgraphics:demo/modules/swirl_inner");
        CgVfxSlot domeSlot = CgVfxSlot.mesh("crystalgraphics:demo/modules/dome");
        CgVfxSlot shockSlot = CgVfxSlot.mesh("crystalgraphics:demo/modules/shock");
        CgVfxSlot runeSlot = CgVfxSlot.mesh("crystalgraphics:demo/modules/rune");

        CgVfxEmitter onDome = CgVfxEmitter.builder("meshDome").capacity(1200).rate(260f, 0f, PERIOD - 2f).mesh(dome)
                .speed(0.3f, 0.7f).life(0.8f, 1.2f).size(0.03f, 0.05f, 1f).heat(1f).opacity(FADE).build();
        CgVfxEmitter onSwirl = CgVfxEmitter.builder("meshSwirl").capacity(900).rate(180f, 0f, PERIOD - 2f).mesh(swirl)
                .vertices().ordered(96).speed(0.4f, 0.8f).life(0.7f, 1f).size(0.04f, 0.04f, 1f).heat(1f)
                .module(new CgVfxModule.Drag(1f, 0f)).opacity(FADE).build();
        CgVfxEmitter rocks = CgVfxEmitter.builder("meshRocks").renderer(CgVfxEmitter.Renderer.MESHES).capacity(40)
                .burst(0.5f, 24).shape(CgSphere.of(0.5)).launch(0.5f, 1f, 1f).speed(4f, 7f).life(3f, 4f)
                .size(0.12f, 0.3f, 1f).spin(1f, 4f).module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Ground(0.2f, 0.4f, 0.6f)).opacity(FADE).build();

        String flow = "crystalgraphics:shaders/vfx/mesh/flow.shader";
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(onDome).emitter(onSwirl).emitter(rocks)
                .layer(CgVfxLayer.of(flow).on(slashSlot).mesh(slash).bind("_Color", CYAN).bind("_Hot", WHITE)
                        .set("_Scroll", 2.5f, 0f, 0f, 0f).set("_Tiling", 5f, 1f, 0f, 0f).set("_Erosion", 0.35f).build())
                .layer(CgVfxLayer.of(flow).on(swirlSlot).mesh(swirl).bind("_Color", VIOLET).bind("_Hot", WHITE)
                        .set("_Scroll", 0f, -1.4f, 0f, 0f).set("_Tiling", 1.5f, 5f, 0f, 0f).set("_Edges", 0.3f).build())
                .layer(CgVfxLayer.of(flow).on(innerSlot).mesh(inner).bind("_Color", MAGENTA).bind("_Hot", WHITE)
                        .set("_Scroll", 0f, 2f, 0f, 0f).set("_Tiling", 1.5f, 4f, 0f, 0f).set("_Edges", 0.3f).build())
                .layer(CgVfxLayer.of(flow).on(domeSlot).mesh(dome).bind("_Color", CYAN).bind("_Hot", ICE)
                        .set("_Scroll", 0.15f, 0.5f, 0f, 0f).set("_Tiling", 10f, 4f, 0f, 0f).set("_Erosion", 0.55f)
                        .set("_Edges", 0f).set("_Rim", 1.6f).build())
                .layer(CgVfxLayer.of(flow).on(shockSlot).mesh(shock).bind("_Color", ICE).bind("_Hot", WHITE)
                        .set("_Scroll", 0.6f, 0f, 0f, 0f).set("_Tiling", 12f, 1f, 0f, 0f).set("_Erosion", 0.3f).build())
                .layer(CgVfxLayer.of(flow).on(runeSlot).mesh(rune).bind("_Color", GOLD).bind("_Hot", FLAME)
                        .set("_Scroll", 0.08f, 0f, 0f, 0f).set("_Tiling", 24f, 1f, 0f, 0f).set("_Erosion", 0.5f)
                        .set("_Edges", 0.2f).build())
                .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/smoke/billow.shader").on(rocks).mesh(rock)
                        .bind("_Color", DEBRIS).bind("_Hot", EMBER).role(CgVfxLayer.Role.SMOKE).build())
                .build();

        float slashX = -5f, swirlX = 0f, domeX = 5f;
        // The slash combo: one every 0.7 s, each its own tilt and turn, swept in over 0.15 s and gone by 0.6 s.
        float[] tilts = {25f, -35f, 70f, -10f, 45f, -60f};
        MeshMotion slashes = (age, m, fx) -> {
            int k = (int) (age / 0.7f);
            float t = age - k * 0.7f;
            if (k >= tilts.length || t > 0.6f) return false;
            m.identity().translate(0f, 1.6f, 0f).rotateY((float) Math.toRadians(30f + 50f * k))
                    .rotateX((float) Math.toRadians(tilts[k])).scale(1f + 0.25f * t);
            fx[0] = t / 0.15f;
            fx[1] = 1f - smooth(0.2f, 0.6f, t);
            fx[2] = 0.8f;
            return true;
        };
        MeshMotion turning = (age, m, fx) -> {
            m.identity().rotateY(age * 3f);
            fx[0] = 2f;
            fx[1] = fade(age);
            fx[2] = 0f;
            return true;
        };
        MeshMotion counter = (age, m, fx) -> {
            m.identity().rotateY(-age * 4.5f);
            fx[0] = 2f;
            fx[1] = fade(age);
            fx[2] = 0f;
            return true;
        };
        MeshMotion shield = (age, m, fx) -> {
            m.identity();
            fx[0] = age / 0.6f;   // swept in round it
            fx[1] = fade(age) * (0.75f + 0.25f * (float) Math.sin(age * 5f));
            fx[2] = 0f;
            return true;
        };
        MeshMotion runeTurn = (age, m, fx) -> {
            m.identity().translate(0f, 0.02f, 0f).rotateY(age * 0.5f);
            fx[0] = age / 0.8f;
            fx[1] = fade(age);
            fx[2] = 0f;
            return true;
        };
        // Shock rings: one every 1.2 s, running from the dome's foot out to three times its radius.
        Show show = new Show() {
            private final Matrix4f m = new Matrix4f();
            private final float[] fx = new float[3];

            @Override
            public void draw(CgVfxEffect effect, CgVfxFrame frame, float age) {
                mesh(effect, frame, slashSlot, slashX, slashes, age);
                mesh(effect, frame, swirlSlot, swirlX, turning, age);
                mesh(effect, frame, innerSlot, swirlX, counter, age);
                mesh(effect, frame, domeSlot, domeX, shield, age);
                mesh(effect, frame, runeSlot, domeX, runeTurn, age);
                for (int k = 0; k < 4; k++) {
                    float t = age - 0.4f - k * 1.2f;
                    if (t < 0f || t > 1.1f || age > PERIOD - 1.5f) continue;
                    m.identity().scale(1.6f + 3.2f * t, 1f, 1.6f + 3.2f * t);
                    frame.mesh(effect, shockSlot, domeX, 0.05f, 0f, m, 2f, 1f - t / 1.1f, 0f, 0f);
                }
            }

            private void mesh(CgVfxEffect effect, CgVfxFrame frame, CgVfxSlot slot, float at, MeshMotion motion, float age) {
                if (!motion.at(age, m, fx)) return;
                frame.mesh(effect, slot, at, 0f, 0f, m, fx[0], fx[1], fx[2], 0f);
            }
        };
        stations.add(new Station("meshes: procedural meshes on mesh slots", x, z, look, List.of(onDome, onSwirl, rocks),
                new float[][]{{domeX, 0f, 0f}, {swirlX, 0f, 0f}, {swirlX, 0.3f, 2.5f}}, p -> {
                    p.label(slashX, 5.2f, 0f, 0.42f, "slashes", 0xFFFFFFFF);
                    p.label(slashX, 4.7f, 0f, 0.26f, "a revolved band, swept in", 0xFFB8D8FF);
                    p.label(swirlX, 5.2f, 0f, 0.42f, "swirls", 0xFFFFFFFF);
                    p.label(swirlX, 4.7f, 0f, 0.26f, "ribbons, sparks at vertices", 0xFFB8D8FF);
                    p.label(domeX, 5.2f, 0f, 0.42f, "shield", 0xFFFFFFFF);
                    p.label(domeX, 4.7f, 0f, 0.26f, "dome, shocks, rune ring", 0xFFB8D8FF);
                }, null, () -> show, null));
    }

    // ── trails ────────────────────────────────────────────────────────────────

    private static final String TRAIL = "crystalgraphics:shaders/vfx/trail/", FLOW = "crystalgraphics:shaders/vfx/mesh/flow.shader";
    /** An effect's tick: what a station's frame lags its last tick by, at most. */
    private static final float TICK = 1f / 120f;
    /** How long a trail station's things move each play, leaving the rest for their trails to run out. */
    private static final float MOTION = PERIOD - 1.8f;

    private static CgVfxLayer.Builder trail(String shader, CgVfxSlot slot, float size) {
        return CgVfxLayer.of(TRAIL + shader + ".shader").on(slot).size(size);
    }

    /** Where a moving thing is at {@code t} seconds, from its station, into {@code p}. */
    private interface Path {
        void at(float t, float[] p);
    }

    /** A moving thing's place and axes: forward along its motion, right level across it, up from those. */
    private static final class Rig {
        final float[] p = new float[3], f = {1f, 0f, 0f}, r = {0f, 0f, 1f}, u = {0f, 1f, 0f};
        private final float[] q = new float[3];

        Rig at(Path path, float t) {
            path.at(t, p);
            path.at(t + 0.01f, q);
            float fx = q[0] - p[0], fy = q[1] - p[1], fz = q[2] - p[2];
            float l = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
            if (l < 1e-6f) return this;
            f[0] = fx / l; f[1] = fy / l; f[2] = fz / l;
            float rl = (float) Math.sqrt(f[2] * f[2] + f[0] * f[0]);
            if (rl > 1e-4f) {
                r[0] = -f[2] / rl; r[1] = 0f; r[2] = f[0] / rl;
            }
            u[0] = r[1] * f[2] - r[2] * f[1];
            u[1] = r[2] * f[0] - r[0] * f[2];
            u[2] = r[0] * f[1] - r[1] * f[0];
            return this;
        }

        /** Component {@code axis} of its place moved {@code forward}, {@code right} and {@code up} along its axes. */
        float off(int axis, float forward, float right, float up) {
            return p[axis] + f[axis] * forward + r[axis] * right + u[axis] * up;
        }

        /** Its axes as a transform's columns, right, up and forward, scaled by each. */
        Matrix4f basis(Matrix4f m, float sr, float su, float sf) {
            return m.set(r[0] * sr, r[1] * sr, r[2] * sr, 0f, u[0] * su, u[1] * su, u[2] * su, 0f,
                    f[0] * sf, f[1] * sf, f[2] * sf, 0f, 0f, 0f, 0f, 1f);
        }
    }

    /** {@code trail} moved to {@code (x, y, z)} from {@code effect}'s origin. */
    private static void move(CgVfxTrail trail, CgVfxEffect effect, float dt, float x, float y, float z) {
        trail.tick(dt, effect.originX() + x, effect.originY() + y, effect.originZ() + z);
    }

    /** The dash station's flight, from its middle: a figure eight two and a half seconds round, skimming the ground at its middle. */
    private static void dashPath(float t, float[] p) {
        float w = (float) (2.0 * Math.PI / 2.4);
        p[0] = 6f * (float) Math.sin(w * t);
        p[1] = 3f + 2.6f * (float) Math.cos(w * t);
        p[2] = 3.2f * (float) Math.sin(2f * w * t);
    }

    /**
     * A fighter in flight, as Dragon Ball games draw one: an aura of ki round a body tearing through a figure eight at up
     * to 23 blocks a second, a white-hot wake behind it with the air bent round it, a thin streak off each hand and foot,
     * sparks shed as it flies; where the loop skims the ground, a scorch torn into it and dust thrown up behind.
     */
    private void dash(float x, float z, CgTexture smokeSheet) {
        CgVfxSlot wakeSlot = CgVfxSlot.trail("crystalgraphics:demo/modules/dash_wake");
        CgVfxSlot limbSlot = CgVfxSlot.trail("crystalgraphics:demo/modules/dash_limbs");
        CgVfxSlot scorchSlot = CgVfxSlot.trail("crystalgraphics:demo/modules/dash_scorch");
        CgVfxSlot auraSlot = CgVfxSlot.mesh("crystalgraphics:demo/modules/dash_aura");
        CgMesh shell = CgProceduralMesh.revolve(CgProceduralMesh.sphere(1f, -1f, 1f)).divisions(32, 16)
                .curl(1.4f, 0.12f).noiseSeed(5).build();
        CgVfxEmitter sparks = CgVfxEmitter.builder("dashSparks").capacity(1600).ratePerBlock(12f, 0f, MOTION).maxStep(4f)
                .inherit(0.2f).shape(CgSphere.of(0.4)).speed(0.5f, 2f).life(0.35f, 0.8f).size(0.03f, 0.06f, 1.4f).heat(1f)
                .module(new CgVfxModule.Turbulence(3f, 0.8f, 1f)).module(new CgVfxModule.Drag(2.5f, 0f)).opacity(FADE).build();
        CgVfxEmitter dust = CgVfxEmitter.builder("dashDust").capacity(400).ratePerBlock(5f, 0f, MOTION).maxStep(4f)
                .shape(CgRing.of(0.2, 0.7, 0)).launch(0.35f, 0.9f, 1f).speed(1.5f, 4f).life(1.4f, 2.2f)
                .size(0.4f, 0.7f, 1f).spin(0.1f, 0.5f).module(new CgVfxModule.Drag(1.6f, 0f))
                .module(new CgVfxModule.Gravity(0.8f))
                .size(CgKeyframes.start(0f, 0.5f).to(1f, 2.6f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 0.8f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(sparks).emitter(dust)
                .layer(layerOf("sprite", dust, DUST, DUST, smokeOf(smokeSheet)))
                .layer(trail("scorch", scorchSlot, 0.6f).role(CgVfxLayer.Role.SMOKE).build())
                .layer(trail("haze", wakeSlot, 1.1f).role(CgVfxLayer.Role.DISTORTION).from(CgQuality.MEDIUM).build())
                .layer(trail("glow", wakeSlot, 0.45f).bind("_Color", CYAN).bind("_Core", WHITE).set("_Tail", 0.04f)
                        .set("_Flow", 10f).set("_Erode", 0.85f).build())
                .layer(trail("glow", limbSlot, 0.05f).bind("_Color", ICE).bind("_Core", WHITE).set("_CoreWidth", 0.5f)
                        .set("_Erode", 0.25f).set("_Tail", 0.4f).set("_Fade", 1f).build())
                .layer(CgVfxLayer.of(FLOW).on(auraSlot).mesh(shell).bind("_Color", CYAN).bind("_Hot", WHITE)
                        .set("_Scroll", 0f, -3.2f, 0f, 0f).set("_Tiling", 6f, 3f, 0f, 0f).set("_Erosion", 0.48f)
                        .set("_Edges", 0f).set("_Rim", 2.4f).build())
                .layer(sparkLayer(sparks, CYAN, WHITE))
                .build();
        float w = (float) (2.0 * Math.PI / 2.4);
        Mover follow = (i, age, at) -> {
            if (i == 0) {
                dashPath(age, at);
                return;
            }
            // The dust: under the fighter while it skims the ground, held where it last did, so none rises aloft.
            float t = age;
            for (int k = 0; k < 240 && 3f + 2.6f * (float) Math.cos(w * t) > 1.2f; k++) t -= 0.01f;
            dashPath(t, at);
            at[1] = 0.1f;
        };
        float[][] offsets = {{-0.1f, 0.45f, 0.15f}, {-0.1f, -0.45f, 0.15f}, {-0.35f, 0.18f, -0.85f}, {-0.35f, -0.18f, -0.85f}};
        Supplier<Show> show = () -> new Show() {
            private final CgVfxTrail wake = CgVfxTrail.ribbon(0.5f).spacing(0.2f).subdivisions(3);
            private final CgVfxTrail scorch = CgVfxTrail.flat(4f).spacing(0.25f).subdivisions(2);
            private final CgVfxTrail[] limbs = new CgVfxTrail[4];
            private final Rig rig = new Rig();
            private final Matrix4f m = new Matrix4f();

            {
                for (int k = 0; k < limbs.length; k++) limbs[k] = CgVfxTrail.ribbon(0.3f).spacing(0.15f).subdivisions(3);
            }

            @Override
            public void tick(CgVfxEffect effect, float dt, float age) {
                boolean flying = age < MOTION;
                wake.emitting(flying);
                for (CgVfxTrail limb : limbs) limb.emitting(flying);
                if (!flying) {
                    scorch.emitting(false);
                    wake.tick(dt);
                    scorch.tick(dt);
                    for (CgVfxTrail limb : limbs) limb.tick(dt);
                    return;
                }
                rig.at(CgVfxModules::dashPath, age);
                move(wake, effect, dt, rig.p[0], rig.p[1], rig.p[2]);
                for (int k = 0; k < limbs.length; k++) {
                    float[] o = offsets[k];
                    move(limbs[k], effect, dt, rig.off(0, o[0], o[1], o[2]), rig.off(1, o[0], o[1], o[2]), rig.off(2, o[0], o[1], o[2]));
                }
                boolean low = rig.p[1] < 1.1f;
                scorch.emitting(low);
                if (low) move(scorch, effect, dt, rig.p[0], 0.02f, rig.p[2]);
                else scorch.tick(dt);
            }

            @Override
            public void draw(CgVfxEffect effect, CgVfxFrame frame, float age) {
                frame.trail(effect, scorchSlot, scorch);
                frame.trail(effect, wakeSlot, wake);
                for (CgVfxTrail limb : limbs) frame.trail(effect, limbSlot, limb);
                if (age >= MOTION) return;
                rig.at(CgVfxModules::dashPath, age - (1f - frame.alpha()) * TICK);
                frame.mesh(effect, auraSlot, rig.p[0], rig.p[1], rig.p[2], rig.basis(m, 0.55f, 0.6f, 1.05f), 2f,
                        fade(age), 0f, 0f);
            }

            @Override
            public boolean done(float age) {
                boolean done = wake.done() && scorch.done();
                for (CgVfxTrail limb : limbs) done &= limb.done();
                return done;
            }
        };
        float[] start = new float[3];
        dashPath(0f, start);
        stations.add(new Station("dash: a fighter in flight", x, z, look, List.of(sparks, dust),
                new float[][]{start.clone(), {start[0], 0.1f, start[2]}}, p -> {
                    p.label(0f, 7.6f, 0f, 0.42f, "glow + haze", 0xFFFFFFFF);
                    p.label(0f, 7.1f, 0f, 0.26f, "a white-hot wake, the air bent behind it", 0xFFB8D8FF);
                    p.label(-6f, 7.6f, 0f, 0.42f, "four thin ribbons", 0xFFFFFFFF);
                    p.label(-6f, 7.1f, 0f, 0.26f, "a streak off each hand and foot", 0xFFB8D8FF);
                    p.label(6f, 7.6f, 0f, 0.42f, "flat + scorch", 0xFFFFFFFF);
                    p.label(6f, 7.1f, 0f, 0.26f, "torn into the ground it skims", 0xFFB8D8FF);
                }, follow, show, null));
    }

    /** One swing of the rush station's combo: when, round where, sweeping from s toward v, out to its reach. */
    private record Swing(float start, float cx, float cy, float cz, float sx, float sy, float sz, float vx, float vy,
                         float vz, float nx, float ny, float nz, float reach) {
        static final float LENGTH = 0.15f, ARC = 2.5f;

        static Swing of(float start, float cx, float cy, float cz, float sx, float sy, float sz, float nx, float ny,
                        float nz, float reach) {
            float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz), nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            sx /= sl; sy /= sl; sz /= sl;
            nx /= nl; ny /= nl; nz /= nl;
            return new Swing(start, cx, cy, cz, sx, sy, sz, ny * sz - nz * sy, nz * sx - nx * sz, nx * sy - ny * sx,
                    nx, ny, nz, reach);
        }

        float end() {
            return start + LENGTH;
        }

        /** How far round its arc it is at {@code age}, 0 to ARC: fast, then easing into the hit. */
        float angle(float age) {
            float u = Math.min(Math.max((age - start) / LENGTH, 0f), 1f);
            return ARC * (1f - (1f - u) * (1f - u) * (1f - u));
        }

        /** The point {@code out} of the way out along its arm at angle {@code a}, from the station: component {@code axis}. */
        float at(int axis, float a, float out) {
            float c = (float) Math.cos(a) * out, s = (float) Math.sin(a) * out;
            return switch (axis) {
                case 0 -> cx + sx * c + vx * s;
                case 1 -> cy + sy * c + vy * s;
                default -> cz + sz * c + vz * s;
            };
        }
    }

    /**
     * A rush, as a fighting game draws one: a combo of six punches and kicks round a mannequin, each a blade's swoosh from
     * the forearm out to the fist, white-hot on its leading edge and gone a moment after; each landing in a spray of
     * sparks, a flash and a shock ring in the swing's own plane; then a dragon fist driving nine blocks out in a golden
     * streak with two anime ribbons corkscrewing round it, bursting at its end.
     */
    private void rush(float x, float z) {
        CgVfxSlot swooshSlot = CgVfxSlot.trail("crystalgraphics:demo/modules/rush_swoosh");
        CgVfxSlot fistSlot = CgVfxSlot.trail("crystalgraphics:demo/modules/rush_fist");
        CgVfxSlot coilSlot = CgVfxSlot.trail("crystalgraphics:demo/modules/rush_coil");
        CgVfxSlot ringSlot = CgVfxSlot.mesh("crystalgraphics:demo/modules/rush_ring");
        CgMesh ring = CgProceduralMesh.revolve(CgProceduralMesh.cylinder(1f, 1f, 0.12f)).divisions(64, 2).build();
        Swing[] combo = {
                Swing.of(0.30f, 0.3f, 1.3f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 1.6f),
                Swing.of(0.72f, 0.4f, 1.1f, 0f, 0f, -1f, 0f, 0f, 0f, 1f, 1.5f),
                Swing.of(1.14f, 0.5f, 1.4f, 0f, 0f, 1f, -1f, -1f, -1f, -1f, 1.7f),
                Swing.of(1.56f, 0.3f, 0.55f, 0f, 0f, 0f, -1f, 0f, -1f, 0f, 1.9f),
                Swing.of(1.98f, 0.4f, 1.5f, 0f, 0f, 0.4f, -1f, 0f, -1f, -0.4f, 1.7f),
                Swing.of(2.40f, 0.6f, 1.6f, 0f, 0f, 1f, 0f, 0f, 0f, -1f, 2f)};
        float fistFrom = 3.2f, fistTo = 3.75f, fistReach = 9f;
        CgVfxEmitter.Builder hits = CgVfxEmitter.builder("rushHits").capacity(900).shape(CgSphere.of(0.15))
                .launch(-0.3f, 1f, 1f).speed(3f, 10f).life(0.25f, 0.6f).size(0.025f, 0.05f, 1.5f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Ground(0.3f, 0.5f, 0.6f))
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build());
        CgVfxEmitter.Builder flashes = CgVfxEmitter.builder("rushFlash").capacity(16).speed(0f, 0f).life(0.06f, 0.08f)
                .size(0.8f, 1f, 1f).heat(1f)
                .size(CgKeyframes.start(0f, 0.4f).to(1f, 1.6f, CgEasings.OUT_QUAD).build())
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build());
        for (Swing s : combo) {
            hits.burst(s.end(), 45);
            flashes.burst(s.end(), 1);
        }
        hits.burst(fistTo, 260);
        flashes.burst(fistTo, 2);
        CgVfxEmitter hit = hits.build(), flash = flashes.build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(hit).emitter(flash)
                .layer(trail("swoosh", swooshSlot, 1f).bind("_Color", CYAN).bind("_Hot", WHITE).build())
                .layer(trail("haze", fistSlot, 0.8f).role(CgVfxLayer.Role.DISTORTION).from(CgQuality.MEDIUM).build())
                .layer(trail("glow", fistSlot, 0.3f).bind("_Color", GOLD).bind("_Core", WHITE).set("_Tail", 0.2f)
                        .set("_Flow", 14f).build())
                .layer(trail("bands", coilSlot, 0.11f).setArray("_Bands", 1.4f, 1.35f, 1.2f, 1f, 1.6f, 1.05f, 0.3f, 1f,
                        1.3f, 0.45f, 0.05f, 1f).set("_Ink", 0.12f, 0.03f, 0f, 1f).set("_Break", 0.45f).build())
                .layer(CgVfxLayer.of(FLOW).on(ringSlot).mesh(ring).bind("_Color", ICE).bind("_Hot", WHITE)
                        .set("_Scroll", 0.6f, 0f, 0f, 0f).set("_Tiling", 12f, 1f, 0f, 0f).set("_Erosion", 0.3f).build())
                .layer(sparkLayer(hit, GOLD, WHITE))
                .layer(sparkLayer(flash, WHITE, WHITE))
                .build();
        float[] fistStart = {0.6f, 1.3f, 0f};
        Mover at = (i, age, out) -> {
            // Each burst where its swing lands: the next swing's end until it is reached, then the fist's.
            for (Swing s : combo) {
                if (s.end() >= age - 1e-3f) {
                    for (int a = 0; a < 3; a++) out[a] = s.at(a, Swing.ARC, s.reach());
                    return;
                }
            }
            out[0] = fistStart[0] + fistReach;
            out[1] = fistStart[1];
            out[2] = fistStart[2];
        };
        Supplier<Show> show = () -> new Show() {
            private final CgVfxTrail[] swooshes = new CgVfxTrail[3];
            private final CgVfxTrail fist = CgVfxTrail.ribbon(0.45f).spacing(0.15f).subdivisions(2);
            private final CgVfxTrail[] coils = {CgVfxTrail.ribbon(0.5f).spacing(0.08f), CgVfxTrail.ribbon(0.5f).spacing(0.08f)};
            private final Matrix4f m = new Matrix4f();
            private final Quaternionf turn = new Quaternionf();

            {
                for (int k = 0; k < swooshes.length; k++) swooshes[k] = CgVfxTrail.blade(0.22f).spacing(0.04f).subdivisions(4);
            }

            @Override
            public void tick(CgVfxEffect effect, float dt, float age) {
                for (int k = 0; k < swooshes.length; k++) {
                    // The swing this trail sweeps now, or the last it swept, running out.
                    int swing = -1;
                    for (int s = k; s < combo.length; s += swooshes.length) if (combo[s].start() <= age) swing = s;
                    CgVfxTrail trail = swooshes[k];
                    if (swing < 0 || age > combo[swing].end() + 0.02f) {
                        trail.emitting(false);
                        trail.tick(dt);
                        continue;
                    }
                    Swing s = combo[swing];
                    if (age - dt < s.start()) trail.clear();
                    trail.emitting(true);
                    float a = s.angle(age);
                    trail.tick(dt, effect.originX() + s.at(0, a, 0.35f), effect.originY() + s.at(1, a, 0.35f),
                            effect.originZ() + s.at(2, a, 0.35f), effect.originX() + s.at(0, a, s.reach()),
                            effect.originY() + s.at(1, a, s.reach()), effect.originZ() + s.at(2, a, s.reach()));
                }
                boolean driving = age >= fistFrom && age <= fistTo + 0.02f;
                fist.emitting(driving);
                for (CgVfxTrail coil : coils) coil.emitting(driving);
                if (!driving) {
                    fist.tick(dt);
                    for (CgVfxTrail coil : coils) coil.tick(dt);
                    return;
                }
                float u = Math.min((age - fistFrom) / (fistTo - fistFrom), 1f), eased = u * u * (3f - 2f * u);
                float fx = fistStart[0] + fistReach * eased;
                move(fist, effect, dt, fx, fistStart[1], fistStart[2]);
                float radius = 0.75f * (1f - 0.45f * eased);
                for (int k = 0; k < coils.length; k++) {
                    float a = (age - fistFrom) * 22f + k * (float) Math.PI;
                    move(coils[k], effect, dt, fx - 0.3f, fistStart[1] + radius * (float) Math.cos(a),
                            fistStart[2] + radius * (float) Math.sin(a));
                }
            }

            @Override
            public void draw(CgVfxEffect effect, CgVfxFrame frame, float age) {
                for (CgVfxTrail swoosh : swooshes) frame.trail(effect, swooshSlot, swoosh);
                frame.trail(effect, fistSlot, fist);
                for (CgVfxTrail coil : coils) frame.trail(effect, coilSlot, coil);
                for (Swing s : combo) ring(effect, frame, age - s.end(), s.at(0, Swing.ARC, s.reach()),
                        s.at(1, Swing.ARC, s.reach()), s.at(2, Swing.ARC, s.reach()), s.nx(), s.ny(), s.nz(), 1f);
                ring(effect, frame, age - fistTo, fistStart[0] + fistReach, fistStart[1], fistStart[2], 1f, 0f, 0f, 2.2f);
            }

            /** A shock ring {@code t} seconds after a hit at {@code (x, y, z)}, in the plane across {@code n}. */
            private void ring(CgVfxEffect effect, CgVfxFrame frame, float t, float x, float y, float z, float nx, float ny,
                              float nz, float scale) {
                if (t < 0f || t > 0.3f) return;
                float r = (0.25f + 2f * t / 0.3f) * scale;
                m.identity().rotate(turn.rotationTo(0f, 1f, 0f, nx, ny, nz)).scale(r, 1f, r);
                frame.mesh(effect, ringSlot, x, y, z, m, 2f, 1f - t / 0.3f, 0f, 0f);
            }

            @Override
            public boolean done(float age) {
                boolean done = age > fistTo + 0.5f && fist.done();
                for (CgVfxTrail swoosh : swooshes) done &= swoosh.done();
                return done;
            }
        };
        Matrix4f body = new Matrix4f().scale(0.6f, 1.8f, 0.6f);
        stations.add(new Station("rush: a combo, then a dragon fist", x, z, look, List.of(hit, flash),
                new float[][]{{0f, 1.3f, 1.6f}, {0f, 1.3f, 1.6f}}, p -> {
                    p.box(0f, 0.9f, 0f, body);
                    p.label(0f, 5.2f, 0f, 0.42f, "blade + swoosh", 0xFFFFFFFF);
                    p.label(0f, 4.7f, 0f, 0.26f, "each swing from forearm to fist", 0xFFB8D8FF);
                    p.label(6f, 5.2f, 0f, 0.42f, "glow + bands", 0xFFFFFFFF);
                    p.label(6f, 4.7f, 0f, 0.26f, "a dragon fist, two coils round it", 0xFFB8D8FF);
                }, at, show, null));
    }

    /**
     * A volley of ki blasts: five fired from one hand a beat apart, each curving in on its own arc to land in a row ahead,
     * a blazing ball with a hot streak and a thin smoke trail behind it shedding sparks; each landing in a burst of sparks,
     * a puff of dust and a shock ring along the ground.
     */
    private void volley(float x, float z, CgTexture smokeSheet) {
        CgVfxSlot streakSlot = CgVfxSlot.trail("crystalgraphics:demo/modules/volley_streak");
        CgVfxSlot smokeSlot = CgVfxSlot.trail("crystalgraphics:demo/modules/volley_smoke");
        CgVfxSlot ringSlot = CgVfxSlot.mesh("crystalgraphics:demo/modules/volley_ring");
        CgMesh ring = CgProceduralMesh.revolve(CgProceduralMesh.cylinder(1f, 1f, 0.1f)).divisions(64, 2).build();
        int n = 5;
        float fly = 1.2f;
        float[] launch = new float[n];
        float[][] targets = new float[n][], controls = new float[n][];
        float[] from = {-5.5f, 1.6f, 0f};
        List<CgVfxEmitter> emitters = new ArrayList<>();
        CgVfxLook.Builder look = CgVfxLook.builder(SCHEMA);
        CgVfxEmitter[] balls = new CgVfxEmitter[n], crumbs = new CgVfxEmitter[n], sprays = new CgVfxEmitter[n],
                puffs = new CgVfxEmitter[n];
        for (int i = 0; i < n; i++) {
            launch[i] = 0.4f + 0.3f * i;
            targets[i] = new float[]{5f, 0.15f, (i - 2) * 1.7f};
            controls[i] = new float[]{-1.5f, 4.2f + 1.4f * (float) Math.sin(i * 2.1f), (i - 2) * 3.4f};
            float lands = launch[i] + fly;
            balls[i] = CgVfxEmitter.builder("volleyBall" + i).capacity(60).rate(320f, launch[i], lands).speed(0f, 0f)
                    .life(0.05f, 0.07f).size(0.3f, 0.34f, 1f).heat(1f).build();
            crumbs[i] = CgVfxEmitter.builder("volleyCrumbs" + i).capacity(300).ratePerBlock(10f, launch[i], lands)
                    .shape(CgSphere.of(0.15)).speed(0.2f, 0.8f).life(0.3f, 0.6f).size(0.02f, 0.04f, 1.4f).heat(1f)
                    .module(new CgVfxModule.Turbulence(2.5f, 0.6f, 0.7f)).module(new CgVfxModule.Drag(2f, 0f))
                    .opacity(FADE).build();
            sprays[i] = CgVfxEmitter.builder("volleySpray" + i).capacity(120).burst(lands, 80).shape(CgSphere.of(0.2))
                    .launch(0.15f, 1f, 1f).speed(3f, 8f).life(0.3f, 0.7f).size(0.025f, 0.05f, 1.5f).heat(1f)
                    .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Ground(0.3f, 0.5f, 0.6f))
                    .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
            puffs[i] = CgVfxEmitter.builder("volleyPuff" + i).capacity(16).burst(lands, 10).shape(CgRing.of(0.2, 0.8, 0))
                    .launch(0.1f, 0.6f, 1f).speed(1f, 3f).life(1.5f, 2.5f).size(0.6f, 0.9f, 1f).spin(0.1f, 0.4f)
                    .module(new CgVfxModule.Drag(1.8f, 0f))
                    .size(CgKeyframes.start(0f, 0.5f).to(1f, 2.4f, CgEasings.OUT_CUBIC).build())
                    .opacity(CgKeyframes.start(0f, 0f).to(0.12f, 0.85f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
                    .build();
            look.emitter(balls[i]).emitter(crumbs[i]).emitter(sprays[i]).emitter(puffs[i])
                    .layer(sparkLayer(balls[i], GOLD, WHITE)).layer(sparkLayer(crumbs[i], GOLD, WHITE))
                    .layer(sparkLayer(sprays[i], GOLD, WHITE)).layer(layerOf("sprite", puffs[i], DUST, DUST, smokeOf(smokeSheet)));
        }
        emitters.addAll(List.of(balls));
        emitters.addAll(List.of(crumbs));
        emitters.addAll(List.of(sprays));
        emitters.addAll(List.of(puffs));
        look.layer(trail("smoke", smokeSlot, 0.1f).set("_Spread", 4f).set("_Rise", 0.5f).build())
                .layer(trail("glow", streakSlot, 0.2f).bind("_Color", GOLD).bind("_Core", WHITE).set("_Tail", 0.1f).build())
                .layer(CgVfxLayer.of(FLOW).on(ringSlot).mesh(ring).bind("_Color", GOLD).bind("_Hot", WHITE)
                        .set("_Scroll", 0.7f, 0f, 0f, 0f).set("_Tiling", 14f, 1f, 0f, 0f).set("_Erosion", 0.3f).build());
        // Each blast on its arc: a quadratic Bezier, quicker as it closes in.
        Path[] arcs = new Path[n];
        for (int i = 0; i < n; i++) {
            float start = launch[i];
            float[] c = controls[i], t = targets[i];
            arcs[i] = (age, p) -> {
                float u = Math.min(Math.max((age - start) / fly, 0f), 1f);
                u = (float) Math.pow(u, 1.25);
                float a = (1f - u) * (1f - u), b = 2f * (1f - u) * u, d = u * u;
                for (int k = 0; k < 3; k++) p[k] = a * from[k] + b * c[k] + d * t[k];
            };
        }
        Mover along = (i, age, out) -> {
            if (i < 2 * n) {
                arcs[i % n].at(age, out);
                return;
            }
            float[] t = targets[i % n];
            out[0] = t[0];
            out[1] = t[1];
            out[2] = t[2];
        };
        float[][] sources = new float[4 * n][];
        for (int i = 0; i < 4 * n; i++) sources[i] = i < 2 * n ? from.clone() : targets[i % n].clone();
        Supplier<Show> show = () -> new Show() {
            private final CgVfxTrail[] streaks = new CgVfxTrail[n], smokes = new CgVfxTrail[n];
            private final float[] p = new float[3];
            private final Matrix4f m = new Matrix4f();

            {
                for (int i = 0; i < n; i++) {
                    streaks[i] = CgVfxTrail.ribbon(0.28f).spacing(0.12f).subdivisions(3);
                    smokes[i] = CgVfxTrail.ribbon(1.6f).spacing(0.2f).subdivisions(2);
                }
            }

            @Override
            public void tick(CgVfxEffect effect, float dt, float age) {
                for (int i = 0; i < n; i++) {
                    boolean flying = age >= launch[i] && age < launch[i] + fly;
                    streaks[i].emitting(flying);
                    smokes[i].emitting(flying);
                    if (!flying) {
                        streaks[i].tick(dt);
                        smokes[i].tick(dt);
                        continue;
                    }
                    arcs[i].at(age, p);
                    move(streaks[i], effect, dt, p[0], p[1], p[2]);
                    move(smokes[i], effect, dt, p[0], p[1], p[2]);
                }
            }

            @Override
            public void draw(CgVfxEffect effect, CgVfxFrame frame, float age) {
                for (int i = 0; i < n; i++) {
                    frame.trail(effect, smokeSlot, smokes[i]);
                    frame.trail(effect, streakSlot, streaks[i]);
                    float t = age - launch[i] - fly;
                    if (t < 0f || t > 0.45f) continue;
                    float r = 0.3f + 3.2f * t / 0.45f;
                    m.identity().scale(r, 1f, r);
                    frame.mesh(effect, ringSlot, targets[i][0], 0.05f, targets[i][2], m, 2f, 1f - t / 0.45f, 0f, 0f);
                }
            }

            @Override
            public boolean done(float age) {
                boolean done = age > launch[n - 1] + fly;
                for (int i = 0; i < n; i++) done &= streaks[i].done() && smokes[i].done();
                return done;
            }
        };
        stations.add(new Station("volley: ki blasts curving in", x, z, look.build(), emitters, sources, p -> {
            p.label(-5.5f, 5.2f, 0f, 0.42f, "glow + smoke", 0xFFFFFFFF);
            p.label(-5.5f, 4.7f, 0f, 0.26f, "a hot streak, a thin smoke trail", 0xFFB8D8FF);
            p.label(5f, 5.2f, 0f, 0.42f, "landing", 0xFFFFFFFF);
            p.label(5f, 4.7f, 0f, 0.26f, "sparks, dust, a shock ring", 0xFFB8D8FF);
        }, along, show, null));
    }

    /**
     * Anime streaks, in flat cel bands: a flash step zig-zagging through the air in straight dashes and hard turns, inked
     * and breaking into brushed strands at its tail; and a ring of power-up lines shooting up round a point, gold.
     */
    private void anime(float x, float z) {
        CgVfxSlot stepSlot = CgVfxSlot.trail("crystalgraphics:demo/modules/anime_step");
        CgVfxSlot lineSlot = CgVfxSlot.trail("crystalgraphics:demo/modules/anime_lines");
        CgVfxLook look = CgVfxLook.builder(SCHEMA)
                .layer(trail("bands", stepSlot, 0.28f).set("_Ink", 0.02f, 0.04f, 0.12f, 1f).build())
                .layer(trail("bands", lineSlot, 0.08f).setArray("_Bands", 1.4f, 1.35f, 1.2f, 1f, 1.6f, 1.1f, 0.3f, 1f,
                        1.4f, 0.55f, 0.05f, 1f).set("_Break", 0.4f).set("_Tail", 0.4f).build())
                .build();
        float[][] corners = {{-6f, 1.2f, -3f}, {-3f, 4.2f, -1f}, {-0.5f, 1.4f, -4f}, {2.5f, 5f, -1.5f}, {5.5f, 1.8f, -3.5f}};
        float speed = 18f;
        float[] lengths = new float[corners.length];
        for (int k = 1; k < corners.length; k++) {
            float dx = corners[k][0] - corners[k - 1][0], dy = corners[k][1] - corners[k - 1][1], dz = corners[k][2] - corners[k - 1][2];
            lengths[k] = lengths[k - 1] + (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        float lap = lengths[corners.length - 1] / speed + 0.5f;
        Path step = (age, p) -> {
            float d = Math.min((age % lap) * speed, lengths[corners.length - 1]);
            int k = 1;
            while (k < corners.length - 1 && lengths[k] < d) k++;
            float u = (d - lengths[k - 1]) / (lengths[k] - lengths[k - 1]);
            for (int a = 0; a < 3; a++) p[a] = corners[k - 1][a] + (corners[k][a] - corners[k - 1][a]) * u;
        };
        int lines = 12;
        float cycle = 0.55f, rise = 0.22f;
        Supplier<Show> show = () -> new Show() {
            private final CgVfxTrail flash = CgVfxTrail.ribbon(0.45f).spacing(0.1f).subdivisions(4);
            private final CgVfxTrail[] power = new CgVfxTrail[lines];
            private final float[] p = new float[3];

            {
                for (int j = 0; j < lines; j++) power[j] = CgVfxTrail.ribbon(0.16f).spacing(0.1f).subdivisions(2);
            }

            @Override
            public void tick(CgVfxEffect effect, float dt, float age) {
                boolean on = age < MOTION;
                flash.emitting(on);
                if (on) {
                    step.at(age, p);
                    move(flash, effect, dt, p[0], p[1], p[2]);
                } else {
                    flash.tick(dt);
                }
                for (int j = 0; j < lines; j++) {
                    float phase = (age + j * 0.137f * cycle) % cycle;
                    boolean rising = on && phase < rise;
                    if (rising && phase < dt) power[j].clear();
                    power[j].emitting(rising);
                    if (!rising) {
                        power[j].tick(dt);
                        continue;
                    }
                    float a = j * (float) (2.0 * Math.PI / lines) + 0.3f * (float) Math.sin(j * 7.1f);
                    float r = 1.3f + 0.3f * (float) Math.sin(j * 3.7f);
                    float u = phase / rise, y = 0.1f + 5.2f * (1f - (1f - u) * (1f - u));
                    move(power[j], effect, dt, r * (float) Math.cos(a), y, 3f + r * (float) Math.sin(a));
                }
            }

            @Override
            public void draw(CgVfxEffect effect, CgVfxFrame frame, float age) {
                frame.trail(effect, stepSlot, flash);
                for (CgVfxTrail line : power) frame.trail(effect, lineSlot, line);
            }

            @Override
            public boolean done(float age) {
                boolean done = flash.done();
                for (CgVfxTrail line : power) done &= line.done();
                return done;
            }
        };
        stations.add(new Station("anime: cel-banded streaks", x, z, look, List.of(), new float[0][], p -> {
            p.label(0f, 6.6f, -3f, 0.42f, "bands, inked", 0xFFFFFFFF);
            p.label(0f, 6.1f, -3f, 0.26f, "a flash step's hard turns", 0xFFB8D8FF);
            p.label(0f, 6.6f, 3f, 0.42f, "bands, gold", 0xFFFFFFFF);
            p.label(0f, 6.1f, 3f, 0.26f, "power-up lines shooting up", 0xFFB8D8FF);
        }, null, show, null));
    }

    /**
     * A second-stage Super Saiyan's storm: thin lightning trails crackling round a mannequin on erratic orbits, each
     * shaking across its own line and flickering on and off, white-blue through a gold aura's rising streaks and motes.
     */
    private void storm(float x, float z) {
        CgVfxSlot boltSlot = CgVfxSlot.trail("crystalgraphics:demo/modules/storm_bolts");
        CgVfxEmitter streaks = CgVfxEmitter.builder("stormStreaks").renderer(CgVfxEmitter.Renderer.ARCS).capacity(1000)
                .rate(200f, 0.2f, MOTION).entity().surface().direction(0f, 1f, 0f, 12f).speed(3f, 6f).life(0.25f, 0.45f)
                .size(0.05f, 0.09f, 1f).heat(1f).opacity(FADE).build();
        CgVfxEmitter motes = CgVfxEmitter.builder("stormMotes").capacity(600).rate(90f, 0.2f, MOTION)
                .entity().surface().speed(0.1f, 0.3f).life(0.8f, 1.4f).size(0.03f, 0.06f, 1.4f).heat(1f)
                .module(new CgVfxModule.Force(0f, 2.2f, 0f)).module(new CgVfxModule.Turbulence(2f, 0.5f, 0.6f))
                .opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(streaks).emitter(motes)
                .layer(layer("ray", streaks, GOLD, WHITE, 1f, null))
                .layer(sparkLayer(motes, FLAME, WHITE))
                .layer(trail("glow", boltSlot, 0.03f).bind("_Color", ICE).bind("_Core", WHITE).set("_Wobble", 1.4f)
                        .set("_WobbleRate", 45f).set("_Tail", 0.6f).set("_Erode", 0.15f).set("_Fade", 0.7f)
                        .set("_Flow", 20f).set("_GlowGain", 2.5f).build())
                .build();
        int bolts = 7;
        Supplier<Show> show = () -> new Show() {
            private final CgVfxTrail[] trails = new CgVfxTrail[bolts];

            {
                for (int j = 0; j < bolts; j++) trails[j] = CgVfxTrail.ribbon(0.11f).spacing(0.05f).subdivisions(2);
            }

            @Override
            public void tick(CgVfxEffect effect, float dt, float age) {
                for (int j = 0; j < bolts; j++) {
                    // On and off in flickers, each its own: a spark, not a light.
                    boolean on = age < MOTION && hash((int) (age * 9f), j * 17 + 3) > 0.35f;
                    trails[j].emitting(on);
                    if (!on) {
                        trails[j].tick(dt);
                        continue;
                    }
                    float a = 9f + j, b = 13f + 1.7f * j;
                    float dx = (float) Math.sin(a * age + j * 1.3f), dy = 0.8f * (float) Math.sin(b * age + j * 2.1f);
                    float dz = (float) Math.cos(a * age * 0.93f + j * 0.7f);
                    float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-4f;
                    float r = 0.75f + 0.25f * (float) Math.sin(5f * age + j);
                    move(trails[j], effect, dt, dx / l * r, 1f + 0.95f * dy / l, dz / l * r);
                }
            }

            @Override
            public void draw(CgVfxEffect effect, CgVfxFrame frame, float age) {
                for (CgVfxTrail bolt : trails) frame.trail(effect, boltSlot, bolt);
            }

            @Override
            public boolean done(float age) {
                boolean done = age >= MOTION;
                for (CgVfxTrail bolt : trails) done &= bolt.done();
                return done;
            }
        };
        float[] box = {0f, 0.9f, 0f, 0.3f, 0.9f, 0.3f};
        Matrix4f body = new Matrix4f().scale(0.6f, 1.8f, 0.6f);
        float[] middle = {0f, 0.9f, 0f};
        stations.add(new Station("storm: lightning round a Super Saiyan 2", x, z, look, List.of(streaks, motes),
                new float[][]{middle, middle}, p -> {
                    p.box(0f, 0.9f, 0f, body);
                    p.label(0f, 5.2f, 0f, 0.42f, "glow, _Wobble", 0xFFFFFFFF);
                    p.label(0f, 4.7f, 0f, 0.26f, "seven bolts shaking across their lines", 0xFFB8D8FF);
                }, null, show, box));
    }

    /** The meteor station's rock, from its middle: falling faster and faster, then skidding to a stop along the ground. */
    private static void meteorPath(float t, float[] p) {
        float[] s = {-7f, 13f, -3f}, i = {1f, 0.4f, 0f}, e = {4.5f, 0.35f, 1f};
        if (t < 1.1f) {
            float u = Math.max(t, 0f) / 1.1f;
            u = u * u * 0.6f + u * 0.4f;
            for (int k = 0; k < 3; k++) p[k] = s[k] + (i[k] - s[k]) * u;
        } else {
            float u = Math.min((t - 1.1f) / 0.6f, 1f);
            u = 1f - (1f - u) * (1f - u);
            for (int k = 0; k < 3; k++) p[k] = i[k] + (e[k] - i[k]) * u;
        }
    }

    /**
     * A meteor: a molten rock falling out of the sky, faster and faster, wrapped in fire and dragging a long smoke trail
     * that billows out and drifts up, the air shimmering behind it, embers falling off it; it strikes the ground in a
     * flash, a shock ring, thrown rocks and sparks and a wall of dust, skids to a stop gouging a glowing scorch, and lies
     * there cooling and smoking.
     */
    private void meteor(float x, float z, CgTexture smokeSheet) {
        CgVfxSlot fireSlot = CgVfxSlot.trail("crystalgraphics:demo/modules/meteor_fire");
        CgVfxSlot smokeSlot = CgVfxSlot.trail("crystalgraphics:demo/modules/meteor_smoke");
        CgVfxSlot scorchSlot = CgVfxSlot.trail("crystalgraphics:demo/modules/meteor_scorch");
        CgVfxSlot rockSlot = CgVfxSlot.mesh("crystalgraphics:demo/modules/meteor_rock");
        CgVfxSlot ringSlot = CgVfxSlot.mesh("crystalgraphics:demo/modules/meteor_ring");
        CgMesh rock = CgProceduralMesh.revolve(CgProceduralMesh.sphere(1f, -1f, 1f)).divisions(20, 12).curl(1.2f, 0.3f)
                .noiseSeed(11).build();
        CgMesh ring = CgProceduralMesh.revolve(CgProceduralMesh.cylinder(1f, 1f, 0.14f)).divisions(64, 2).build();
        CgMesh chunk = CgProceduralMesh.revolve(CgProceduralMesh.sphere(1f, -1f, 1f)).divisions(10, 6).curl(1.4f, 0.3f)
                .noiseSeed(4).build();
        float impact = 1.1f, rest = 1.7f;
        CgVfxEmitter embers = CgVfxEmitter.builder("meteorEmbers").capacity(700).ratePerBlock(5f, 0f, impact).maxStep(6f)
                .inherit(0.3f).shape(CgSphere.of(0.4)).speed(0.5f, 2f).life(0.6f, 1.3f).size(0.03f, 0.06f, 1.4f).heat(1f)
                .module(new CgVfxModule.Gravity(6f)).module(new CgVfxModule.Drag(0.8f, 0f))
                .module(new CgVfxModule.Ground(0.3f, 0.5f, 0.6f)).opacity(FADE).build();
        CgVfxEmitter debris = CgVfxEmitter.builder("meteorDebris").renderer(CgVfxEmitter.Renderer.MESHES).capacity(40)
                .burst(impact, 28).shape(CgSphere.of(0.5)).launch(0.35f, 1f, 1f).speed(4f, 9f).life(2.5f, 3.5f)
                .size(0.08f, 0.22f, 1.4f).spin(2f, 6f).module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Ground(0.25f, 0.4f, 0.6f)).opacity(FADE).build();
        CgVfxEmitter sparks = CgVfxEmitter.builder("meteorSparks").capacity(300).burst(impact, 220).shape(CgSphere.of(0.3))
                .launch(0.1f, 1f, 1f).speed(4f, 12f).life(0.4f, 0.9f).size(0.03f, 0.06f, 1.5f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Ground(0.3f, 0.4f, 0.6f))
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter flash = CgVfxEmitter.builder("meteorFlash").capacity(4).burst(impact, 2).speed(0f, 0f).life(0.1f, 0.12f)
                .size(2.4f, 2.6f, 1f).heat(1f).size(CgKeyframes.start(0f, 0.5f).to(1f, 1.4f, CgEasings.OUT_QUAD).build())
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter wall = CgVfxEmitter.builder("meteorDust").capacity(40).burst(impact, 22).shape(CgRing.of(0.3, 1.2, 0))
                .launch(0.15f, 0.7f, 1f).speed(2f, 6f).life(2f, 3.2f).size(0.8f, 1.3f, 1f).spin(0.1f, 0.4f)
                .module(new CgVfxModule.Drag(1.5f, 0f))
                .size(CgKeyframes.start(0f, 0.5f).to(1f, 2.8f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 0.9f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        CgVfxEmitter skid = CgVfxEmitter.builder("meteorSkid").capacity(120).ratePerBlock(5f, impact, rest).maxStep(6f)
                .shape(CgRing.of(0.2, 0.6, 0)).launch(0.3f, 0.8f, 1f).speed(1f, 2.5f).life(1.2f, 2f).size(0.5f, 0.8f, 1f)
                .spin(0.1f, 0.4f).module(new CgVfxModule.Drag(1.8f, 0f))
                .size(CgKeyframes.start(0f, 0.5f).to(1f, 2.2f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 0.8f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        CgVfxEmitter smoulder = CgVfxEmitter.builder("meteorSmoulder").capacity(60).rate(7f, rest, MOTION + 0.5f)
                .shape(CgSphere.of(0.3)).launch(0.85f, 1f, 1f).speed(0.4f, 0.8f).life(2.5f, 3.5f).size(0.5f, 0.8f, 1f)
                .spin(0.1f, 0.4f).heat(0.5f).module(new CgVfxModule.Buoyancy(2.5f, 2f))
                .module(new CgVfxModule.Turbulence(1.2f, 0.35f, 0.3f)).module(new CgVfxModule.Drag(0.6f, 0f))
                .size(CgKeyframes.start(0f, 0.6f).to(1f, 3.2f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.2f, 0.8f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(embers).emitter(debris).emitter(sparks).emitter(flash)
                .emitter(wall).emitter(skid).emitter(smoulder)
                .layer(layerOf("sprite", wall, DUST, DUST, smokeOf(smokeSheet)))
                .layer(layerOf("sprite", skid, DUST, DUST, smokeOf(smokeSheet)))
                .layer(layerOf("sprite", smoulder, SOOT, SOOT, smokeOf(smokeSheet)))
                .layer(trail("scorch", scorchSlot, 0.65f).role(CgVfxLayer.Role.SMOKE).build())
                .layer(trail("smoke", smokeSlot, 0.32f).set("_Spread", 5f).set("_Rise", 0.6f)
                        .set("_Glow", 1.6f, 0.55f, 0.12f, 1f).role(CgVfxLayer.Role.SMOKE).build())
                .layer(trail("haze", fireSlot, 1.1f).role(CgVfxLayer.Role.DISTORTION).from(CgQuality.MEDIUM).build())
                .layer(trail("glow", fireSlot, 0.42f).bind("_Color", EMBER).bind("_Core", FLAME).set("_Tail", 0.1f)
                        .set("_Flow", 6f).set("_Wobble", 0.15f).build())
                .layer(CgVfxLayer.of(FLOW).on(rockSlot).mesh(rock).bind("_Color", EMBER).bind("_Hot", FLAME)
                        .set("_Scroll", 0.4f, -1.2f, 0f, 0f).set("_Tiling", 4f, 3f, 0f, 0f).set("_Erosion", 0.1f)
                        .set("_Edges", 0f).set("_Rim", 1.5f).build())
                .layer(CgVfxLayer.of(FLOW).on(ringSlot).mesh(ring).bind("_Color", FLAME).bind("_Hot", WHITE)
                        .set("_Scroll", 0.6f, 0f, 0f, 0f).set("_Tiling", 14f, 1f, 0f, 0f).set("_Erosion", 0.3f).build())
                .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/smoke/billow.shader").on(debris).mesh(chunk)
                        .bind("_Color", DEBRIS).bind("_Hot", EMBER).role(CgVfxLayer.Role.SMOKE).build())
                .layer(sparkLayer(embers, EMBER, FLAME))
                .layer(sparkLayer(sparks, EMBER, FLAME))
                .layer(sparkLayer(flash, FLAME, WHITE))
                .build();
        float[] hit = new float[3], end = new float[3];
        meteorPath(impact, hit);
        meteorPath(rest, end);
        Mover follow = (i, age, at) -> {
            if (i == 0) {
                meteorPath(Math.min(age, impact), at);
            } else if (i == 5) {
                meteorPath(Math.max(Math.min(age, rest), impact), at);
                at[1] = 0.1f;
            } else if (i == 6) {
                System.arraycopy(end, 0, at, 0, 3);
            } else {
                System.arraycopy(hit, 0, at, 0, 3);
            }
        };
        Supplier<Show> show = () -> new Show() {
            private final CgVfxTrail fire = CgVfxTrail.ribbon(0.22f).spacing(0.15f).subdivisions(3);
            private final CgVfxTrail smoke = CgVfxTrail.ribbon(3.2f).spacing(0.25f).subdivisions(2);
            private final CgVfxTrail scorch = CgVfxTrail.flat(4.5f).spacing(0.15f).subdivisions(2);
            private final float[] p = new float[3];
            private final Matrix4f m = new Matrix4f();

            @Override
            public void tick(CgVfxEffect effect, float dt, float age) {
                meteorPath(age, p);
                boolean falling = age < rest, skidding = age >= impact && age < rest;
                fire.emitting(falling);
                smoke.emitting(falling);
                scorch.emitting(skidding);
                if (falling) {
                    move(fire, effect, dt, p[0], p[1], p[2]);
                    move(smoke, effect, dt, p[0], p[1], p[2]);
                } else {
                    fire.tick(dt);
                    smoke.tick(dt);
                }
                if (skidding) move(scorch, effect, dt, p[0], 0.02f, p[2]);
                else scorch.tick(dt);
            }

            @Override
            public void draw(CgVfxEffect effect, CgVfxFrame frame, float age) {
                frame.trail(effect, scorchSlot, scorch);
                frame.trail(effect, smokeSlot, smoke);
                frame.trail(effect, fireSlot, fire);
                float t = age - (1f - frame.alpha()) * TICK;
                meteorPath(t, p);
                float spin = Math.min(t, rest) * 7f;
                m.identity().rotateXYZ(spin, spin * 0.6f, spin * 0.3f).scale(0.55f);
                // Molten while it flies, cooling to embers where it lies.
                float heat = t < rest ? 1.3f : 1.3f * (float) Math.exp(-(t - rest) * 0.7f) * fade(age);
                frame.mesh(effect, rockSlot, p[0], p[1], p[2], m, 2f, heat, 0f, 0f);
                float s = age - impact;
                if (s >= 0f && s < 0.5f) {
                    float r = 0.4f + 5f * s / 0.5f;
                    m.identity().scale(r, 1f, r);
                    frame.mesh(effect, ringSlot, hit[0], 0.05f, hit[2], m, 2f, 1f - s / 0.5f, 0f, 0f);
                }
            }

            @Override
            public boolean done(float age) {
                return age > MOTION && fire.done() && smoke.done() && scorch.done();
            }
        };
        float[] start = new float[3];
        meteorPath(0f, start);
        stations.add(new Station("meteor: fire, smoke, a scorch", x, z, look,
                List.of(embers, debris, sparks, flash, wall, skid, smoulder),
                new float[][]{start, hit, hit, hit, hit, {hit[0], 0.1f, hit[2]}, end}, p -> {
                    p.label(-5f, 5.2f, 0f, 0.42f, "glow + smoke + haze", 0xFFFFFFFF);
                    p.label(-5f, 4.7f, 0f, 0.26f, "fire round it, smoke billowing behind", 0xFFB8D8FF);
                    p.label(4f, 3.6f, 1f, 0.42f, "flat + scorch", 0xFFFFFFFF);
                    p.label(4f, 3.1f, 1f, 0.26f, "gouged as it skids", 0xFFB8D8FF);
                }, follow, show, null));
    }

    private static final String STREAKS = "crystalgraphics:shaders/vfx/particle/streaks.shader",
            RIBBON = "crystalgraphics:shaders/vfx/particle/ribbon.shader";

    /**
     * A trail behind every particle, each through the places its pool kept, so it follows the curve its particle flew: a
     * grinder's fountain of sparks arcing out and skipping off the ground, embers spiralling up a vortex through curling
     * air, and a firework bursting overhead every two seconds into drooping streamers.
     */
    private void sparks(float x, float z) {
        CgVfxEmitter grinder = CgVfxEmitter.builder("sparksGrinder").capacity(1500).rate(320f, 0f, MOTION).trail(10)
                .shape(CgCone.of(0.05, Math.toRadians(22))).pointing(1f, 0.55f, 0f).speed(6f, 11f).life(0.7f, 1.5f)
                .size(0.03f, 0.05f, 1.4f).heat(1f).module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Drag(0.4f, 0f)).module(new CgVfxModule.Ground(0.45f, 0.3f, 0.5f))
                .opacity(CgKeyframes.start(0f, 1f).to(0.7f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        CgVfxEmitter embers = CgVfxEmitter.builder("sparksEmbers").capacity(500).rate(80f, 0f, MOTION).trail(30)
                .shape(CgRing.of(0.3, 1.4, 0)).speed(0.2f, 0.6f).life(2.4f, 3.4f).size(0.035f, 0.06f, 1.4f).heat(1f)
                .module(new CgVfxModule.Vortex(0f, 1f, 0f, 5f, -1.2f, 0f, 0f, 0f))
                .module(new CgVfxModule.Force(0f, 2.2f, 0f)).module(new CgVfxModule.Turbulence(2.2f, 0.7f, 0.9f))
                .module(new CgVfxModule.Drag(1f, 0f)).opacity(FADE).build();
        CgVfxEmitter firework = CgVfxEmitter.builder("sparksFirework").capacity(900).burst(0.5f, 280).burst(2.5f, 280)
                .burst(4.5f, 280).trail(24).shape(CgSphere.of(0.1)).speed(5f, 6.5f).life(1.6f, 2.3f)
                .size(0.03f, 0.05f, 1.2f).heat(1f).module(new CgVfxModule.Gravity(3.2f))
                .module(new CgVfxModule.Drag(1.3f, 0f))
                .opacity(CgKeyframes.start(0f, 1f).to(0.6f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(grinder).emitter(embers).emitter(firework)
                .layer(CgVfxLayer.of(STREAKS).on(grinder).trails().bind("_Color", FLAME).bind("_Core", WHITE).build())
                .layer(CgVfxLayer.of(STREAKS).on(embers).trails().size(0.8f).bind("_Color", EMBER).bind("_Core", FLAME)
                        .set("_Tail", 0.3f).set("_Fade", 0.8f).build())
                .layer(CgVfxLayer.of(STREAKS).on(firework).trails().size(1.2f).bind("_Color", MAGENTA)
                        .bind("_Core", WHITE).set("_Erode", 0.7f).build())
                .layer(sparkLayer(grinder, FLAME, WHITE))
                .layer(sparkLayer(embers, EMBER, FLAME))
                .layer(sparkLayer(firework, MAGENTA, WHITE))
                .build();
        float[][] places = {{-5f, 1.2f, -2f}, {3f, 0.2f, 1f}};
        float[][] bursts = {{-3f, 7.5f, 1f}, {2.5f, 8f, -1f}, {0f, 7f, 2.5f}};
        // The firework bursts somewhere new each time; the fountain and the embers stay.
        Mover burstAt = (i, age, at) -> {
            float[] b = i < 2 ? places[i] : bursts[age < 1.5f ? 0 : age < 3.5f ? 1 : 2];
            at[0] = b[0];
            at[1] = b[1];
            at[2] = b[2];
        };
        stations.add(new Station("sparks: a trail behind every particle", x, z, look, List.of(grinder, embers, firework),
                new float[][]{places[0], places[1], bursts[0]}, p -> {
                    p.sphere(-5f, 1.2f, -2f, 0.35f);
                    p.label(-5f, 5.2f, -2f, 0.42f, "trail(10) + trails()", 0xFFFFFFFF);
                    p.label(-5f, 4.7f, -2f, 0.26f, "sparks arcing, skipping off the ground", 0xFFB8D8FF);
                    p.label(3f, 5.2f, 1f, 0.42f, "trail(30)", 0xFFFFFFFF);
                    p.label(3f, 4.7f, 1f, 0.26f, "embers up a vortex through curling air", 0xFFB8D8FF);
                }, burstAt, null, null));
    }

    /**
     * One ribbon through each emitter instance's particles in spawn order, every particle moved by its own modules: a ki
     * stream spiralling up and down a column, writhing in curl noise and swelling then thinning along its life; and five
     * rivers of energy pulled in round a vortex to a swelling core, a spirit bomb gathering.
     */
    private void streams(float x, float z) {
        CgVfxEmitter helix = CgVfxEmitter.builder("streamHelix").capacity(700).ratePerBlock(16f, 0f, MOTION)
                .maxStep(4f).speed(0.1f, 0.3f).life(1.4f, 1.4f).size(1f, 1f, 1f).heat(1f)
                .module(new CgVfxModule.Turbulence(1.4f, 0.9f, 1.2f)).module(new CgVfxModule.Drag(1.2f, 0f))
                .size(CgKeyframes.start(0f, 0.15f).to(0.12f, 1f, CgEasings.OUT_QUAD).to(0.6f, 0.7f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build())
                .opacity(CgKeyframes.start(0f, 1f).to(0.75f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        CgVfxLook.Builder look = CgVfxLook.builder(SCHEMA).emitter(helix)
                .layer(CgVfxLayer.of(RIBBON).on(helix).ribbon().size(0.22f).subdivisions(2).bind("_Color", CYAN)
                        .bind("_Core", WHITE).build());
        List<CgVfxEmitter> emitters = new ArrayList<>(List.of(helix));
        List<float[]> sources = new ArrayList<>();
        sources.add(new float[]{-4f, 0.5f, 0f});
        float[] core = {4f, 3.5f, 0f};
        int rivers = 5;
        for (int k = 0; k < rivers; k++) {
            float a = k * (float) (2.0 * Math.PI / rivers);
            float[] at = {core[0] + 6.5f * (float) Math.cos(a), 0.6f + 1.8f * (k % 2), core[2] + 6.5f * (float) Math.sin(a)};
            float dx = core[0] - at[0], dy = core[1] - at[1], dz = core[2] - at[2];
            CgVfxEmitter river = CgVfxEmitter.builder("streamRiver" + k).capacity(500).rate(90f, 0.2f, MOTION - 0.5f)
                    .speed(0.2f, 0.5f).life(3f, 3f).size(1f, 1f, 1f).heat(1f)
                    .module(new CgVfxModule.Attract(CgSphere.of(8f), 26f).at(dx, dy, dz))
                    .module(new CgVfxModule.Vortex(0f, 1f, 0f, 6f, 0f, dx, dy, dz))
                    .module(new CgVfxModule.Turbulence(0.8f, 0.35f, 0.5f))
                    .module(new CgVfxModule.Drag(1.4f, 0f))
                    .module(new CgVfxModule.Kill(CgSphere.of(0.55f), true).at(dx, dy, dz))
                    .size(CgKeyframes.start(0f, 0.3f).to(0.2f, 1f, CgEasings.OUT_QUAD).to(1f, 0.4f, CgEasings.LINEAR).build())
                    .opacity(FADE).build();
            look.emitter(river).layer(CgVfxLayer.of(RIBBON).on(river).ribbon().size(0.12f).bind("_Color", GOLD)
                    .bind("_Core", WHITE).set("_Tiling", 0.15f, 1.3f, 0f, 0f).build());
            emitters.add(river);
            sources.add(at);
        }
        CgVfxEmitter ball = CgVfxEmitter.builder("streamCore").capacity(4).burst(0f, 1).speed(0f, 0f)
                .life(MOTION, MOTION).size(1.4f, 1.4f, 1f).heat(1f)
                .size(CgKeyframes.start(0f, 0.2f).to(0.9f, 1f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.08f, 1f, CgEasings.OUT_QUAD).to(1f, 1f, CgEasings.LINEAR).build())
                .build();
        look.emitter(ball).layer(sparkLayer(ball, GOLD, WHITE));
        emitters.add(ball);
        sources.add(core.clone());
        // The stream's source spirals up a column and back down, so it never jumps.
        Mover spiral = (i, age, at) -> {
            if (i != 0) {
                float[] s = sources.get(i);
                at[0] = s[0];
                at[1] = s[1];
                at[2] = s[2];
                return;
            }
            float a = age * 5f, rise = (float) (0.5 - 0.5 * Math.cos(age * Math.PI / 2.6));
            at[0] = -4f + 1.6f * (float) Math.cos(a);
            at[1] = 0.5f + 6f * rise;
            at[2] = 1.6f * (float) Math.sin(a);
        };
        stations.add(new Station("streams: ribbons through particles", x, z, look.build(), emitters,
                sources.toArray(new float[0][]), p -> {
                    p.label(-4f, 8f, 0f, 0.42f, "ribbon()", 0xFFFFFFFF);
                    p.label(-4f, 7.5f, 0f, 0.26f, "a ki stream writhing in curl noise", 0xFFB8D8FF);
                    p.label(4f, 7f, 0f, 0.42f, "five ribbons", 0xFFFFFFFF);
                    p.label(4f, 6.5f, 0f, 0.26f, "rivers pulled round a vortex into a core", 0xFFB8D8FF);
                }, spiral, null, null));
    }

    /** A flipbook smoke sheet's layer properties, drawn first in its effect. */
    private static Consumer<CgVfxLayer.Builder> smokeOf(CgTexture smokeSheet) {
        return b -> b.texture("_Sheet", smokeSheet).set("_Frames", 8f, 8f, 64f, 1f).set("_Flip", 0f, 0f, 1f, 0f)
                .role(CgVfxLayer.Role.SMOKE);
    }

    /** A play's strength: up over its first 0.3 s, down over the period's last 1.5. */
    private static float fade(float age) {
        return smooth(0f, 0.3f, age) * (1f - smooth(PERIOD - 2.5f, PERIOD - 1f, age));
    }

    /**
     * A Super Saiyan's aura round a mannequin, from its box as an entity's ({@code entity()} reads the effect's
     * {@code spawnBox}): flame tongues of a revolved teardrop licking up past its head, gold outside and white-hot
     * within, flickering as they pulse; hot streaks shooting up off its faces; lightning crackling round it; pebbles
     * lifting off the ground under it and drifting up; and a shock running out from its feet with each surge.
     */
    private void aura(float x, float z) {
        CgMesh flame = CgProceduralMesh.revolve(CgProceduralMesh.spline(0.35f, -0.05f, 0.95f, 0.9f, 0.7f, 2.2f, 0f, 3.4f))
                .divisions(48, 20).curl(1.1f, 0.12f).noiseSeed(3).build();
        CgMesh core = CgProceduralMesh.revolve(CgProceduralMesh.spline(0.25f, 0f, 0.6f, 0.8f, 0.45f, 1.9f, 0f, 2.7f))
                .divisions(40, 16).curl(1.6f, 0.08f).noiseSeed(9).build();
        CgMesh shock = CgProceduralMesh.revolve(CgProceduralMesh.cylinder(1f, 1f, 0.1f)).divisions(64, 2).build();
        CgMesh pebble = CgProceduralMesh.revolve(CgProceduralMesh.sphere(1f, -1f, 1f)).divisions(10, 6).curl(1.4f, 0.3f)
                .noiseSeed(4).build();
        CgVfxSlot flameSlot = CgVfxSlot.mesh("crystalgraphics:demo/modules/aura_flame");
        CgVfxSlot coreSlot = CgVfxSlot.mesh("crystalgraphics:demo/modules/aura_core");
        CgVfxSlot shockSlot = CgVfxSlot.mesh("crystalgraphics:demo/modules/aura_shock");

        CgVfxEmitter streaks = CgVfxEmitter.builder("auraStreaks").renderer(CgVfxEmitter.Renderer.ARCS).capacity(1200)
                .rate(260f, 0.2f, PERIOD - 2f).entity().surface().direction(0f, 1f, 0f, 12f).speed(3f, 6f).life(0.25f, 0.45f).size(0.05f, 0.09f, 1f)
                .heat(1f).opacity(FADE).build();
        CgVfxEmitter crackle = CgVfxEmitter.builder("auraCrackle").capacity(600).rate(140f, 0.4f, PERIOD - 2f)
                .entity().surface().speed(2f, 5f).life(0.06f, 0.16f).size(0.02f, 0.04f, 1.5f).heat(1f)
                .module(new CgVfxModule.Turbulence(22f, 2.5f, 3f)).module(new CgVfxModule.Drag(6f, 0f))
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter motes = CgVfxEmitter.builder("auraMotes").capacity(800).rate(120f, 0.2f, PERIOD - 2f)
                .entity().surface().speed(0.1f, 0.3f).life(0.8f, 1.4f).size(0.03f, 0.06f, 1.4f).heat(1f)
                .module(new CgVfxModule.Force(0f, 2.2f, 0f)).module(new CgVfxModule.Turbulence(2f, 0.5f, 0.6f))
                .opacity(FADE).build();
        CgVfxEmitter pebbles = CgVfxEmitter.builder("auraPebbles").renderer(CgVfxEmitter.Renderer.MESHES).capacity(60)
                .rate(9f, 0.3f, PERIOD - 3f).shape(CgRing.of(0.5, 1.8, 0)).speed(0f, 0.1f).life(2.5f, 3.5f)
                .size(0.04f, 0.1f, 1.5f).spin(0.5f, 2f).module(new CgVfxModule.Force(0f, 0.9f, 0f))
                .module(new CgVfxModule.Turbulence(1f, 0.15f, 0.2f)).opacity(FADE).build();

        String flow = "crystalgraphics:shaders/vfx/mesh/flow.shader";
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(streaks).emitter(crackle).emitter(motes).emitter(pebbles)
                .layer(CgVfxLayer.of(flow).on(flameSlot).mesh(flame).bind("_Color", GOLD).bind("_Hot", FLAME)
                        .set("_Scroll", 0.12f, -2.6f, 0f, 0f).set("_Tiling", 7f, 3f, 0f, 0f).set("_Erosion", 0.5f)
                        .set("_Edges", 1f).set("_Rim", 1.2f).build())
                .layer(CgVfxLayer.of(flow).on(coreSlot).mesh(core).bind("_Color", FLAME).bind("_Hot", WHITE)
                        .set("_Scroll", -0.2f, -3.4f, 0f, 0f).set("_Tiling", 6f, 3f, 0f, 0f).set("_Erosion", 0.45f)
                        .set("_Edges", 1f).set("_Rim", 0.8f).build())
                .layer(CgVfxLayer.of(flow).on(shockSlot).mesh(shock).bind("_Color", GOLD).bind("_Hot", WHITE)
                        .set("_Scroll", 0.8f, 0f, 0f, 0f).set("_Tiling", 14f, 1f, 0f, 0f).set("_Erosion", 0.3f).build())
                .layer(layer("ray", streaks, GOLD, WHITE, 1f, null))
                .layer(sparkLayer(crackle, CYAN, WHITE))
                .layer(sparkLayer(motes, FLAME, WHITE))
                .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/smoke/billow.shader").on(pebbles).mesh(pebble)
                        .bind("_Color", DEBRIS).bind("_Hot", GOLD).role(CgVfxLayer.Role.SMOKE).build())
                .build();
        Show show = new Show() {
            private final Matrix4f m = new Matrix4f();

            @Override
            public void draw(CgVfxEffect effect, CgVfxFrame frame, float age) {
                // Surges every 1.4 s: the flames swell and flare, then settle while flickering.
                float surge = (age % 1.4f) / 1.4f, swell = (float) Math.exp(-surge * 5f);
                float flicker = 0.85f + 0.15f * (float) Math.sin(age * 37f) * (float) Math.sin(age * 23f + 1f);
                float on = fade(age);
                m.identity().rotateY(age * 1.3f).scale(1f + 0.12f * swell, 1f + 0.22f * swell, 1f + 0.12f * swell);
                frame.mesh(effect, flameSlot, 0f, 0f, 0f, m, 2f, on * flicker * (0.8f + 0.5f * swell), 0f, 0f);
                m.identity().rotateY(-age * 2.1f).scale(1f + 0.08f * swell);
                frame.mesh(effect, coreSlot, 0f, 0f, 0f, m, 2f, on * flicker, 0f, 0f);
                if (surge < 0.45f && age < PERIOD - 1.5f) {
                    float t = surge / 0.45f, r = 0.8f + 3.5f * t;
                    m.identity().scale(r, 1f, r);
                    frame.mesh(effect, shockSlot, 0f, 0.04f, 0f, m, 2f, on * (1f - t), 0f, 0f);
                }
            }
        };
        float[] box = {0f, 0.9f, 0f, 0.3f, 0.9f, 0.3f};
        Matrix4f body = new Matrix4f().scale(0.6f, 1.8f, 0.6f);
        float[] middle = {0f, 0.9f, 0f};
        stations.add(new Station("aura: a Super Saiyan's, round a mannequin", x, z, look,
                List.of(streaks, crackle, motes, pebbles), new float[][]{middle, middle, middle, {0f, 0.05f, 0f}}, p -> {
                    p.box(0f, 0.9f, 0f, body);
                    p.label(0f, 5.2f, 0f, 0.42f, "entity()", 0xFFFFFFFF);
                    p.label(0f, 4.7f, 0f, 0.26f, "born on the box it follows", 0xFFB8D8FF);
                }, null, () -> show, box));
    }

    /**
     * Energy gathering in a hand: motes drawn in by a pull that strengthens toward the centre, whirled faster the closer
     * they come (a constant push round the axis against drag: a free vortex), stirred by curl noise and swallowed by the
     * core, which swells as it charges and crackles with sparks.
     */
    private void gather(float x, float z) {
        CgVfxEmitter motes = CgVfxEmitter.builder("gather").renderer(CgVfxEmitter.Renderer.QUADS).capacity(2500)
                .rate(380f, 0f, PERIOD - 3f).shape(CgSphere.of(6.5f)).launch(-0.4f, 0.4f, 1f).speed(0f, 0.3f).life(3f, 4f)
                .size(0.04f, 0.1f, 1.5f).heat(1f)
                .module(new CgVfxModule.Attract(CgSphere.of(9f), 30f).attenuation(1.6f))
                .module(new CgVfxModule.Vortex(0.15f, 1f, 0.1f, 7f, 0f, 0f, 0f, 0f))
                .module(new CgVfxModule.Turbulence(2.5f, 0.45f, 0.5f))
                .module(new CgVfxModule.Drag(1.1f, 0f))
                .module(new CgVfxModule.Kill(CgSphere.of(0.45f), true))
                .size(CgKeyframes.start(0f, 0.6f).to(0.15f, 1f, CgEasings.OUT_QUAD).to(1f, 0.5f, CgEasings.IN_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.12f, 1f, CgEasings.OUT_QUAD).to(0.85f, 1f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter core = CgVfxEmitter.builder("gatherCore").renderer(CgVfxEmitter.Renderer.QUADS).capacity(4)
                .burst(0f, 1).speed(0f, 0f).life(PERIOD - 1.5f, PERIOD - 1.5f).size(0.7f, 0.7f, 1f).heat(1f)
                .size(CgKeyframes.start(0f, 0.2f).to(0.85f, 1.6f, CgEasings.IN_QUAD).to(0.92f, 2.4f, CgEasings.OUT_QUAD)
                        .to(1f, 0f, CgEasings.IN_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD).to(1f, 1f, CgEasings.LINEAR).build())
                .build();
        CgVfxEmitter crackle = CgVfxEmitter.builder("gatherCrackle").renderer(CgVfxEmitter.Renderer.QUADS).capacity(600)
                .rate(90f, 0.8f, PERIOD - 2f).shape(CgSphere.of(0.4f)).launch(-1f, 1f, 1f).speed(2f, 5f).life(0.08f, 0.2f)
                .size(0.02f, 0.04f, 1.5f).heat(1f)
                .module(new CgVfxModule.Turbulence(20f, 2f, 3f))
                .module(new CgVfxModule.Drag(6f, 0f))
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(motes).emitter(core).emitter(crackle)
                .layer(sparkLayer(motes, GOLD, WHITE)).layer(sparkLayer(core, GOLD, WHITE))
                .layer(sparkLayer(crackle, GOLD, WHITE)).build();
        float[] centre = {0f, 3f, 0f};
        stations.add(new Station("gather: Attract + Vortex + Turbulence + Kill", x, z, look,
                List.of(motes, core, crackle), new float[][]{centre, centre, centre}, null));
    }

    /**
     * A tornado as the air it is: a funnel {@link CgVfxField} of a Rankine vortex round a wall that widens with height,
     * air drawn onto the wall, rising up it and spreading at the top. Drag holds each puff to the field's velocity, so
     * the smoke traces the funnel; turbulence makes it wander. Debris, held loosely, is flung round its foot and falls
     * back, under a skirt of dust.
     */
    private void tornado(float x, float z, CgTexture smokeSheet) {
        // A particle follows air turning at w radians a second only while w / drag stays well under 1: the funnel's
        // drag holds its smoke to the wall above the foot; the debris's lets it fly off.
        float drag = 8f;
        CgVfxField air = tornadoField();
        CgBox box = CgBox.of(TORNADO_HALF, TORNADO_TOP / 2f, TORNADO_HALF);
        float boxY = TORNADO_TOP / 2f - 0.3f;
        CgVfxEmitter funnel = CgVfxEmitter.builder("tornado").renderer(CgVfxEmitter.Renderer.QUADS).capacity(2000)
                .rate(320f, 0f, PERIOD - 2.5f).shape(CgSphere.of(0.6f)).launch(0f, 1f, 1f).speed(0f, 0.5f).life(3f, 4f)
                .size(0.25f, 0.4f, 1f).spin(0.5f, 1.5f)
                .module(new CgVfxModule.VectorField(air, box, drag, 1f).at(0f, boxY, 0f))
                .module(new CgVfxModule.Turbulence(10f, 0.6f, 0.8f))
                .module(new CgVfxModule.Drag(drag, 0f))
                .size(CgKeyframes.start(0f, 0.6f).to(1f, 4f, CgEasings.IN_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 0.9f, CgEasings.OUT_QUAD).to(0.75f, 0.8f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter debris = CgVfxEmitter.builder("tornadoDebris").renderer(CgVfxEmitter.Renderer.QUADS).capacity(800)
                .rate(120f, 0f, PERIOD - 2.5f).shape(CgSphere.of(2.5f)).launch(0f, 0.3f, 1f).speed(0.5f, 2f).life(2f, 3f)
                .size(0.05f, 0.12f, 1.5f).spin(2f, 8f)
                .module(new CgVfxModule.VectorField(air, box, 1.2f, 1f).at(0f, boxY, 0f))
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Drag(0.7f, 0f))
                .module(new CgVfxModule.Ground(0.2f, 0.05f, 0.3f))
                .opacity(FADE).build();
        CgVfxEmitter dust = CgVfxEmitter.builder("tornadoDust").renderer(CgVfxEmitter.Renderer.QUADS).capacity(300)
                .rate(45f, 0f, PERIOD - 2.5f).shape(CgSphere.of(2.5f)).launch(0f, 0.1f, 1f).speed(0.2f, 0.8f).life(1.6f, 2.4f)
                .size(0.9f, 1.4f, 1f).spin(0.2f, 0.8f)
                .module(new CgVfxModule.VectorField(air, box, 1.5f, 1f).at(0f, boxY, 0f))
                .module(new CgVfxModule.Force(0f, -2f, 0f))
                .module(new CgVfxModule.Drag(1.5f, 0f))
                .module(new CgVfxModule.Ground(0f, 0.05f, 0.1f))
                .size(CgKeyframes.start(0f, 0.6f).to(1f, 2.4f, CgEasings.OUT_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.2f, 0.55f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        Consumer<CgVfxLayer.Builder> sheet = b -> b.texture("_Sheet", smokeSheet).set("_Frames", 8f, 8f, 64f, 1f)
                .set("_Flip", 0.3f, 0f, 1f, 0f);
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(funnel).emitter(debris).emitter(dust)
                .layer(layerOf("sprite", dust, DUST, DUST, sheet)).layer(layerOf("sprite", funnel, FUNNEL, FUNNEL, sheet))
                .layer(layer("speck", debris, DEBRIS, DEBRIS, 1f, null)).build();
        float[] base = {0f, 0.3f, 0f};
        stations.add(new Station("tornado: a Rankine funnel VectorField, debris and dust", x, z, look,
                List.of(funnel, debris, dust), new float[][]{base, base, base}, null));
    }

    /** The tornado field's box: this many blocks either side of the axis, and up from the floor. */
    private static final float TORNADO_HALF = 5.5f, TORNADO_TOP = 14f;

    /**
     * A tornado's air, its velocity in blocks a second: round a funnel wall of radius 0.3 at the floor widening 0.28 a
     * block, a Rankine vortex (turning as a solid inside the wall, slowing as 1/r out of it), a pull onto the wall,
     * rising along it, and from 11 blocks up, slowing and spreading. A {@link CgVfxModule.VectorField} of strength
     * equal to a particle's drag settles it to this velocity.
     */
    private static CgVfxField tornadoField() {
        int nx = 24, ny = 32;
        return CgVfxField.of(nx, ny, nx, false, (i, j, k, out) -> {
            float x = ((i + 0.5f) / nx * 2f - 1f) * TORNADO_HALF, z = ((k + 0.5f) / nx * 2f - 1f) * TORNADO_HALF;
            float y = (j + 0.5f) / ny * TORNADO_TOP;
            float r = Math.max((float) Math.sqrt(x * x + z * z), 1e-3f), wall = 0.3f + 0.28f * y;
            float spin = 5f * (r < wall ? r / wall : wall / r);
            float top = smooth(11f, 14f, y);
            float in = Math.max(-4f, Math.min(4f, -2.5f * (r - wall))) * (1f - top) + 3f * top;
            float near = (r - wall) / (0.6f + 0.15f * y);
            float rise = (5f * (float) Math.exp(-near * near) + 0.4f) * (1f - top);
            // Tangent (-z, x) / r turns counter-clockwise seen from above; (x, z) / r points out.
            out[0] = -z / r * spin + x / r * in;
            out[1] = rise;
            out[2] = x / r * spin + z / r * in;
        });
    }

    /**
     * A bar magnet standing on end, its field drawn as iron filings draw it: comet streaks leave its north pole (top) at
     * four angles, each tracing one shell of field lines round to the south, nested and coloured inner to outer, the
     * whole field slowly twisting. Its poles pulse and crackle, and a pulse rings out from its middle. The air is a
     * {@link CgVfxField} of the two poles' field, and drag holds each streak to its line.
     */
    private void field(float x, float z, CgTexture dot, CgTexture ring) {
        float drag = 10f;
        // Source-relative, from the north pole: the field's box round the magnet, and the south pole under it.
        CgBox box = CgBox.of(MAGNET_HALF, MAGNET_HALF_Y, MAGNET_HALF);
        float boxY = -MAGNET_POLE;
        CgVfxField air = magnetField();
        // A line's launch angle picks its shell: leaving at these vertical shares, they loop about 1.5, 2, 2.6 and 3.2
        // blocks out. One leaving upward would fly off the field.
        float[] shares = {-0.65f, -0.45f, -0.25f, -0.08f};
        CgVfxParam.Color[][] colours = {{CYAN, WHITE}, {CYAN, VIOLET}, {VIOLET, MAGENTA}, {MAGENTA, GOLD}};
        float top = 2.4f + 2f * MAGNET_POLE, middle = top - MAGNET_POLE;
        CgVfxLook.Builder look = CgVfxLook.builder(SCHEMA);
        List<CgVfxEmitter> emitters = new ArrayList<>();
        List<float[]> sources = new ArrayList<>();
        for (int s = 0; s < shares.length; s++) {
            CgVfxEmitter shell = CgVfxEmitter.builder("fieldShell" + s).renderer(CgVfxEmitter.Renderer.QUADS)
                    .capacity(900).rate(110f, 0f, PERIOD - 3.5f).shape(CgSphere.of(0.35f)).launch(shares[s], shares[s] + 0.02f, 1f)
                    .speed(0.5f, 1f).life(4.5f, 5.5f).size(0.022f, 0.032f, 1.5f).heat(1f)
                    .module(new CgVfxModule.VectorField(air, box, drag, 1f).at(0f, boxY, 0f))
                    .module(new CgVfxModule.Vortex(0f, 1f, 0f, 6f, 0f, 0f, -MAGNET_POLE, 0f))
                    .module(new CgVfxModule.Drag(drag, 0f))
                    .module(new CgVfxModule.Buoyancy(0f, 2.2f))
                    .module(new CgVfxModule.Kill(CgSphere.of(0.3f), true).at(0f, -2f * MAGNET_POLE, 0f))
                    .module(new CgVfxModule.Kill(box, false).at(0f, boxY, 0f))
                    .opacity(CgKeyframes.start(0f, 0f).to(0.05f, 1f, CgEasings.OUT_QUAD).to(0.85f, 1f, CgEasings.LINEAR)
                            .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
            look.emitter(shell).layer(layerOf("sprite_glow", shell, colours[s][0], colours[s][1], b -> b
                    .texture("_Sheet", dot).set("_Frames", 1f, 1f, 1f, 1f).set("_Facing", 3f)
                    .set("_Stretch", 3f).set("_MaxStretch", 40f)));
            emitters.add(shell);
            sources.add(new float[]{0f, top, 0f});
        }
        CgVfxEmitter north = pole("fieldNorth"), south = pole("fieldSouth");
        CgVfxEmitter northCrackle = crackle("fieldNorthCrackle"), southCrackle = crackle("fieldSouthCrackle");
        CgVfxEmitter pulse = CgVfxEmitter.builder("fieldPulse").renderer(CgVfxEmitter.Renderer.QUADS).capacity(8)
                .rate(0.8f, 0.5f, PERIOD - 3f).speed(0f, 0f).life(1.6f, 1.6f).size(1f, 1f, 1f).heat(1f)
                .module(new CgVfxModule.Buoyancy(0f, 1f))
                .size(CgKeyframes.start(0f, 0.3f).to(1f, 3.6f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 0.8f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        look.emitter(north).emitter(south).emitter(northCrackle).emitter(southCrackle).emitter(pulse)
                .layer(sparkLayer(north, VIOLET, WHITE)).layer(sparkLayer(south, CYAN, WHITE))
                .layer(sparkLayer(northCrackle, VIOLET, WHITE)).layer(sparkLayer(southCrackle, CYAN, WHITE))
                .layer(facing(pulse, VIOLET, WHITE, ring, 4, null));
        emitters.addAll(List.of(north, south, northCrackle, southCrackle, pulse));
        sources.addAll(List.of(new float[]{0f, top, 0f}, new float[]{0f, 2.4f, 0f}, new float[]{0f, top, 0f},
                new float[]{0f, 2.4f, 0f}, new float[]{0f, middle, 0f}));
        stations.add(new Station("field: a bar magnet's VectorField", x, z, look.build(), emitters,
                sources.toArray(new float[0][]), p -> {
                    transform.identity().scale(0.36f, 2f * MAGNET_POLE - 0.2f, 0.36f);
                    p.box(0f, middle, 0f, transform);
                }));
    }

    /** Sparks crackling round a pole: short-lived, torn about by fast noise. */
    private static CgVfxEmitter crackle(String name) {
        return CgVfxEmitter.builder(name).renderer(CgVfxEmitter.Renderer.QUADS).capacity(300)
                .rate(70f, 0f, PERIOD - 2.5f).shape(CgSphere.of(0.25f)).speed(2f, 5f).life(0.08f, 0.22f).size(0.02f, 0.04f, 1.5f)
                .heat(1f)
                .module(new CgVfxModule.Turbulence(20f, 2f, 3f))
                .module(new CgVfxModule.Drag(6f, 0f))
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
    }

    /** The field magnet's box: this many blocks either side of its axis, and above and below its middle. */
    private static final float MAGNET_HALF = 4.2f, MAGNET_HALF_Y = 3.2f, MAGNET_POLE = 1.6f;

    /**
     * A bar magnet's field round its middle, poles {@link #MAGNET_POLE} above (north) and below: each pole's field
     * summed, followed at a speed easing with its strength, fastest near the poles.
     */
    private static CgVfxField magnetField() {
        int nx = 32, ny = 24;
        return CgVfxField.of(nx, ny, nx, false, (i, j, k, out) -> {
            float x = ((i + 0.5f) / nx * 2f - 1f) * MAGNET_HALF, z = ((k + 0.5f) / nx * 2f - 1f) * MAGNET_HALF;
            float y = ((j + 0.5f) / ny * 2f - 1f) * MAGNET_HALF_Y;
            float bx = 0f, by = 0f, bz = 0f;
            for (int pole = 0; pole < 2; pole++) {
                float sign = pole == 0 ? 1f : -1f, dy = y - sign * MAGNET_POLE;
                float r = (float) Math.sqrt(x * x + dy * dy + z * z) + 1e-4f, r3 = sign / (r * r * r);
                bx += x * r3;
                by += dy * r3;
                bz += z * r3;
            }
            float b = (float) Math.sqrt(bx * bx + by * by + bz * bz) + 1e-9f;
            float speed = Math.min(6f, 2.2f * (float) Math.pow(b, 0.25)) / b;
            out[0] = bx * speed;
            out[1] = by * speed;
            out[2] = bz * speed;
        });
    }

    /** A pole's glow, beating as it charges and fading as the station's period ends. */
    private static CgVfxEmitter pole(String name) {
        return CgVfxEmitter.builder(name).renderer(CgVfxEmitter.Renderer.QUADS).capacity(4)
                .burst(0f, 1).speed(0f, 0f).life(PERIOD - 2f, PERIOD - 2f).size(0.45f, 0.45f, 1f).heat(1f)
                .size(CgKeyframes.start(0f, 0.6f).to(0.15f, 1.25f, CgEasings.OUT_QUAD).to(0.3f, 0.9f, CgEasings.IN_OUT_QUAD)
                        .to(0.45f, 1.25f, CgEasings.IN_OUT_QUAD).to(0.6f, 0.9f, CgEasings.IN_OUT_QUAD)
                        .to(0.75f, 1.25f, CgEasings.IN_OUT_QUAD).to(1f, 0.4f, CgEasings.IN_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD).to(0.8f, 1f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
    }

    /**
     * bevy_hanabi's conform to sphere: a shield taking shape, its motes drawn onto the surface and carried over it by
     * curl-noise currents, which flow and part as one field, round a slow drift.
     */
    private void shield(float x, float z, CgTexture dot) {
        CgVfxEmitter motes = CgVfxEmitter.builder("shield").renderer(CgVfxEmitter.Renderer.QUADS).capacity(2500)
                .rate(520f, 0f, PERIOD - 3f).shape(CgSphere.of(5.5f)).launch(-0.5f, 0.5f, 1f).speed(0.3f, 1.5f).life(2.6f, 3.4f)
                .size(0.06f, 0.14f, 1.5f).heat(0.7f)
                .module(new CgVfxModule.Conform(CgSphere.of(2.6f), 4.5f, 40f, 6f))
                .module(new CgVfxModule.Turbulence(9f, 0.9f, 0.6f))
                .module(new CgVfxModule.Vortex(0.2f, 1f, 0f, 2.5f, 0f, 0f, 0f, 0f))
                .module(new CgVfxModule.Drag(0.9f, 0f))
                .opacity(CgKeyframes.start(0f, 0f).to(0.2f, 1f, CgEasings.OUT_QUAD).to(0.8f, 1f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        add("shield: Conform + Turbulence", x, z, List.of(motes), new float[][]{{0f, 3.2f, 0f}},
                layer("sprite_glow", motes, VIOLET, WHITE, 1f,
                        b -> b.texture("_Sheet", dot).set("_Frames", 1f, 1f, 1f, 1f)), null);
    }

    /**
     * Godot's sphere collider and rigid response; each bounce a collision event: a flash at the contact, popping and gone
     * within a tenth of a second, and a few hot chips glancing off the surface about its normal.
     */
    private void boulder(float x, float z) {
        CgVfxEmitter flash = CgVfxEmitter.builder("boulderFlash").renderer(CgVfxEmitter.Renderer.QUADS).capacity(64)
                .speed(0f, 0f).life(0.05f, 0.09f).size(0.3f, 0.5f, 1f).heat(1f)
                .size(CgKeyframes.start(0f, 0.4f).to(0.2f, 1f, CgEasings.OUT_QUAD).to(1f, 0.8f, CgEasings.LINEAR).build())
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.OUT_QUAD).build()).build();
        CgVfxEmitter chips = CgVfxEmitter.builder("boulderChips").renderer(CgVfxEmitter.Renderer.QUADS).capacity(256)
                .launch(0.15f, 0.9f, 1f).speed(2.5f, 6f).life(0.15f, 0.4f).size(0.025f, 0.05f, 1.5f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Drag(1.2f, 0f))
                .module(new CgVfxModule.CollideDepth(0.4f, 0.2f, 0.3f, DEPTH_THICKNESS))
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter sparks = CgVfxEmitter.builder("boulder").renderer(CgVfxEmitter.Renderer.QUADS).capacity(1200)
                .rate(120f, 0f, PERIOD - 3f).shape(CgSphere.of(1.2f)).launch(-1f, -0.7f, 1f).speed(0.5f, 2f).life(2.5f, 3f)
                .size(0.08f, 0.14f, 1f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Collide(CgSphere.of(1.8f), 0.6f, 0.05f, 0.3f).at(0f, -5.2f, 0f))
                .module(new CgVfxModule.CollideWorld(0.35f, 0.4f, 0.4f))
                // A mob or player under the boulder: what the voxel window does not hold, on screen.
                .module(new CgVfxModule.CollideDepth(0.35f, 0.4f, 0.4f, DEPTH_THICKNESS))
                .event(CgVfxEvent.onCollision().spawn(flash, 1))
                .event(CgVfxEvent.onCollision().spawn(chips, 4))
                .opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(sparks).emitter(flash).emitter(chips)
                .layer(sparkLayer(sparks, EMBER, FLAME)).layer(sparkLayer(flash, WHITE, FLAME))
                .layer(sparkLayer(chips, FLAME, EMBER)).build();
        stations.add(new Station("boulder: Collide sphere, onCollision spawns a flash and chips", x, z, look, List.of(sparks),
                new float[][]{{0f, 7f, 0f}}, p -> p.sphere(0f, 1.8f, 0f, 1.75f)));
    }

    /**
     * A container box drawn as a copper cage: balls burst inside it, lose a share of their speed at every bounce and
     * settle on its floor, glinting where they strike hard; a second burst halfway knocks the pile about again.
     */
    private void cage(float x, float z) {
        CgVfxEmitter glint = CgVfxEmitter.builder("cageGlint").renderer(CgVfxEmitter.Renderer.QUADS).capacity(256)
                .speed(0f, 0f).life(0.06f, 0.12f).size(0.18f, 0.3f, 1f).heat(1f)
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.OUT_QUAD).build()).build();
        CgVfxEmitter balls = CgVfxEmitter.builder("cage").renderer(CgVfxEmitter.Renderer.QUADS).capacity(500)
                .burst(0f, 300).burst(3.5f, 150).shape(CgSphere.of(1f)).launch(-1f, 1f, 1f).speed(4f, 9f)
                .life(PERIOD - 1f, PERIOD - 0.5f).size(0.1f, 0.2f, 1f).heat(0.9f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Collide(CgBox.of(2.5f, 2.5f, 2.5f), 0.78f, 0.03f, 0.4f).container())
                .event(CgVfxEvent.onCollision().spawn(glint, 1))
                .opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(balls).emitter(glint)
                .layer(sparkLayer(balls, CYAN, WHITE)).layer(sparkLayer(glint, WHITE, WHITE)).build();
        stations.add(new Station("cage: Collide container box, onCollision glints", x, z, look, List.of(balls),
                new float[][]{{0f, 2.6f, 0f}}, p -> {
                    // The box's twelve edges: along x, y and z in turn, at each corner of the other two axes.
                    float h = 2.5f, cy = 2.6f, bar = 0.08f, span = 2f * h + bar;
                    for (int a = 0; a < 2; a++) {
                        for (int b = 0; b < 2; b++) {
                            float s = a * 2f - 1f, t = b * 2f - 1f;
                            transform.identity().scale(span, bar, bar);
                            p.box(0f, cy + s * h, t * h, transform);
                            transform.identity().scale(bar, span, bar);
                            p.box(s * h, cy, t * h, transform);
                            transform.identity().scale(bar, bar, span);
                            p.box(s * h, cy + t * h, 0f, transform);
                        }
                    }
                }));
    }

    /**
     * An avalanche down a plane collider tilted into a ramp: glowing ice shards tumble onto it, cooling from white to
     * ice blue, each impact kicking up a puff of snow, and slide off onto the floor, killed past a box.
     */
    private void slope(float x, float z, CgTexture shard, CgTexture smokeSheet) {
        // The plane stands top blocks over the floor at the station's centre; ramp is its length, end to end, from as
        // high again down to the floor.
        float nx = 0.35f, ny = 1f, length = (float) Math.sqrt(nx * nx + ny * ny), top = 1.5f, source = 6.5f;
        float ramp = 2f * top * length / nx;
        CgVfxEmitter snow = CgVfxEmitter.builder("slopeSnow").renderer(CgVfxEmitter.Renderer.QUADS).capacity(900)
                .launch(0.3f, 1f, 1f).speed(0.3f, 0.9f).life(0.8f, 1.4f).size(0.25f, 0.4f, 1f).spin(0.2f, 0.8f)
                .module(new CgVfxModule.Force(0f, 0.3f, 0f))
                .module(new CgVfxModule.Drag(2f, 0f))
                .size(CgKeyframes.start(0f, 0.5f).to(1f, 2.2f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.15f, 0.6f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        CgVfxEmitter shards = CgVfxEmitter.builder("slope").renderer(CgVfxEmitter.Renderer.QUADS).capacity(1500)
                .rate(200f, 0f, PERIOD - 3f).shape(CgSphere.of(2.5f)).launch(-1f, -0.8f, 1f).speed(0.5f, 1.5f).life(3f, 4f)
                .size(0.08f, 0.2f, 1.5f).spin(2f, 8f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Buoyancy(0f, 1.5f))
                .module(new CgVfxModule.Collide(CgPlane.of(nx, ny, 0f, ramp / 2f), 0.3f, 0.02f, 0f)
                        .at(0f, top - source, 0f))
                // Friction takes its share of the sliding speed every step: 0.05 skids a piece off the ramp a block or two.
                .module(new CgVfxModule.CollideWorld(0.3f, 0.05f, 0.1f))
                .event(CgVfxEvent.onCollision().spawn(snow, 1))
                .opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(shards).emitter(snow)
                .layer(layerOf("sprite", snow, SNOW, SNOW, b -> b.texture("_Sheet", smokeSheet)
                        .set("_Frames", 8f, 8f, 64f, 1f).set("_Flip", 0.3f, 0f, 1f, 0f)))
                // Solid pieces: pulled toward the eye to rest on the ramp whole, never faded into it.
                .layer(facing(shards, ICE, WHITE, shard, 0, b -> b.set("_SoftDistance", 0f).set("_CameraOffset", 1f)))
                .build();
        stations.add(new Station("slope: Collide plane, an avalanche kicking up snow", x, z, look, List.of(shards),
                new float[][]{{0f, source, 0f}}, p -> {
                    // the plane's surface as a thin slab just under it, from its high end to the floor; rotateZ turns +y
                    // toward -x, so the normal's lean toward +x is a negative angle
                    float angle = -(float) Math.atan2(nx, ny);
                    transform.identity().rotateZ(angle).translate(0f, -0.05f, 0f).scale(ramp, 0.1f, 8f);
                    p.box(0f, top, 0f, transform);
                }));
    }

    /**
     * Neon rain on Godot's box collider: streaked drops pelt a block and the floor round it, each splashing into droplets
     * and a ripple ring flat on what it struck, and dying there.
     */
    private void block(float x, float z, CgTexture dot, CgTexture ring) {
        float source = 8f;
        CgVfxEmitter splash = CgVfxEmitter.builder("blockSplash").renderer(CgVfxEmitter.Renderer.QUADS).capacity(2000)
                .launch(0.5f, 1f, 1f).speed(1.5f, 3f).life(0.25f, 0.45f).size(0.02f, 0.035f, 1f).heat(0.5f)
                .module(new CgVfxModule.Gravity(9.8f))
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter ripple = CgVfxEmitter.builder("blockRipple").renderer(CgVfxEmitter.Renderer.QUADS).capacity(600)
                .speed(0f, 0f).life(0.45f, 0.45f).size(1f, 1f, 1f).heat(0.6f)
                .size(CgKeyframes.start(0f, 0.05f).to(1f, 0.4f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 0.9f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        // A ripple is flat and cannot bend over an edge, so each rain keeps to what it falls on: the block's starts within
        // 1.1 of its middle, a ripple's 0.35 radius short of its 1.5 edge; the floor's dies in the column over the block.
        CgVfxEmitter onBlock = rain("block", 180f, 1.1f, source, splash, ripple);
        CgVfxEmitter onFloor = rain("blockFloor", 420f, 3f, source, splash, ripple);
        Consumer<CgVfxLayer.Builder> streak = b -> b.set("_Stretch", 0.25f).set("_MaxStretch", 12f);
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(onBlock).emitter(onFloor).emitter(splash).emitter(ripple)
                .layer(facing(onBlock, CYAN, WHITE, dot, 3, streak)).layer(facing(onFloor, CYAN, WHITE, dot, 3, streak))
                .layer(facing(splash, CYAN, WHITE, dot, 3, streak)).layer(facing(ripple, CYAN, WHITE, ring, 4, null))
                .build();
        stations.add(new Station("block: Collide box, rain splashing and rippling", x, z, look, List.of(onBlock, onFloor),
                new float[][]{{0f, source, 0f}, {0f, source, 0f}}, p -> {
                    transform.identity().scale(3f, 2f, 3f);
                    p.box(0f, 1f, 0f, transform);
                }));
    }

    /**
     * Rain from {@code source} blocks over the floor, starting within {@code spread} of the middle, onto the 3x2x3 block
     * and the world's ground; each drop dies where it lands, splashing and rippling. Spread past the block's edge, it is
     * the ground's rain, and dies in the column over the block instead.
     */
    private static CgVfxEmitter rain(String name, float rate, float spread, float source, CgVfxEmitter splash,
                                     CgVfxEmitter ripple) {
        // Still at launch, so gravity alone sets them falling straight down; the launch only spreads where they start.
        // Long enough lived to fall 90 blocks, to the ground under a station standing in the air.
        CgVfxEmitter.Builder rain = CgVfxEmitter.builder(name).renderer(CgVfxEmitter.Renderer.QUADS).capacity(1000)
                .rate(rate, 0f, PERIOD - 3f).shape(CgSphere.of(spread)).launch(-1f, 0f, 1f).speed(0f, 0f).life(3f, 3f)
                .size(0.025f, 0.035f, 1f).heat(0.4f)
                .module(new CgVfxModule.Gravity(20f))
                .module(new CgVfxModule.Collide(CgBox.of(1.5f, 1f, 1.5f), 0f, 0f, 0f).at(0f, 1f - source, 0f))
                // Just past the block's surface: a drop is killed the step it lands, after its collision fired.
                .module(new CgVfxModule.Kill(CgBox.of(1.52f, 1.03f, 1.52f), true).at(0f, 1f - source, 0f))
                .module(new CgVfxModule.CollideWorld(0f, 0f, 0f).killing())
                .module(new CgVfxModule.CollideDepth(0f, 0f, 0f, DEPTH_THICKNESS).killing());
        if (spread > 1.5f) {
            float column = (source - 2f) / 2f;
            rain.module(new CgVfxModule.Kill(CgBox.of(1.55f, column, 1.55f), true).at(0f, -column, 0f));
        }
        return rain.event(CgVfxEvent.onCollision().spawn(splash, 3)).event(CgVfxEvent.onCollision().spawn(ripple, 1))
                .build();
    }

    /**
     * An obstacle course, colliders in a row: molten beads pour onto a sphere, glance off it onto a cube sitting in a
     * ramp, run down the ramp and skid across the floor, cooling from white-hot to red and throwing sparks at each
     * impact. The ramp is a plane ending where it is drawn, so beads run off its end onto whatever ground is below.
     */
    private void obstacles(float x, float z) {
        // World heights and offsets from the station's centre; every volume is from the source, so each is less it.
        // Poured 1.2 blocks over the sphere's shoulder: a higher drop glances off fast enough to clear the cube.
        float sx = -2.55f, sy = 7.6f;
        float sphereX = -3f, sphereY = 6.4f, sphereR = 0.9f;
        float nx = 0.45f, rampY = 2.25f, rampFrom = -5f, rampTo = rampY / nx;   // its height at 0, and where it meets the floor
        float cubeX = -1.5f, cubeHalf = 0.9f, cubeY = rampY - nx * (cubeX + cubeHalf) + cubeHalf;   // resting on the ramp downhill
        float rampLength = (rampTo - rampFrom) * (float) Math.sqrt(1f + nx * nx);
        CgVfxEmitter sparks = CgVfxEmitter.builder("obstacleSparks").renderer(CgVfxEmitter.Renderer.QUADS).capacity(1500)
                .launch(0.2f, 1f, 1f).speed(1.5f, 4f).life(0.15f, 0.4f).size(0.015f, 0.03f, 1.5f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Drag(1.5f, 0f))
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter beads = CgVfxEmitter.builder("obstacles").renderer(CgVfxEmitter.Renderer.QUADS).capacity(1500)
                .rate(220f, 0f, PERIOD - 3f).shape(CgSphere.of(0.4f)).launch(-1f, -0.9f, 1f).speed(0f, 0f).life(4.5f, 5.5f)
                .size(0.05f, 0.08f, 1.5f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Buoyancy(0f, 2.5f))
                .module(new CgVfxModule.Collide(CgSphere.of(sphereR), 0.5f, 0.05f, 0.3f).at(sphereX - sx, sphereY - sy, 0f))
                .module(new CgVfxModule.Collide(CgBox.of(cubeHalf, cubeHalf, cubeHalf), 0.35f, 0.05f, 0.3f)
                        .at(cubeX - sx, cubeY - sy, 0f))
                .module(new CgVfxModule.Collide(CgPlane.of(nx, 1f, 0f, rampLength / 2f), 0.3f, 0.02f, 0f)
                        .at(-sx, rampY - sy, 0f))
                // A grippy floor stops a bead within a block or so: it cools and fades where it rests.
                .module(new CgVfxModule.CollideWorld(0.3f, 0.12f, 0.1f))
                .event(CgVfxEvent.onCollision().spawn(sparks, 2))
                .opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(beads).emitter(sparks)
                .layer(sparkLayer(beads, EMBER, FLAME)).layer(sparkLayer(sparks, FLAME, WHITE)).build();
        float angle = -(float) Math.atan2(nx, 1f), rampMid = (rampFrom + rampTo) / 2f;
        stations.add(new Station("obstacles: a sphere, a cube in a ramp, the floor", x, z, look, List.of(beads),
                new float[][]{{sx, sy, 0f}}, p -> {
                    p.sphere(sphereX, sphereY, 0f, sphereR - 0.02f);
                    transform.identity().scale(2f * cubeHalf);
                    p.box(cubeX, cubeY, 0f, transform);
                    transform.identity().rotateZ(angle).translate(0f, -0.05f, 0f).scale(rampLength, 0.1f, 6f);
                    p.box(rampMid, rampY - nx * rampMid, 0f, transform);
                }));
    }

    /**
     * Godot's damping (left, gold) beside drag (right, cyan), as a graph: each row's columns fire pucks straight up at 1
     * to 7 blocks a second, with no gravity, so each stops where its brake alone stops it. Damping brakes at a constant
     * rate, so a puck stops at speed squared over twice it and the row's tops trace a parabola; drag brakes in proportion
     * to speed, so they stop at speed over it, a straight line. Both reach 3.5 blocks at 7.
     */
    private void dampingAndDrag(float x, float z) {
        CgVfxLook.Builder look = CgVfxLook.builder(SCHEMA);
        List<CgVfxEmitter> emitters = new ArrayList<>();
        List<float[]> sources = new ArrayList<>();
        for (int row = 0; row < 2; row++) {
            boolean damping = row == 0;
            for (int c = 0; c < BRAKE_COLUMNS; c++) {
                float speed = c + 1f;
                CgVfxEmitter puck = CgVfxEmitter.builder((damping ? "damping" : "drag") + c)
                        .renderer(CgVfxEmitter.Renderer.QUADS).capacity(8)
                        .burst(0f, 1).burst(1.3f, 1).burst(2.6f, 1).burst(3.9f, 1).launch(1f, 1f, 1f).speed(speed, speed)
                        .life(2.6f, 2.6f).size(0.12f, 0.12f, 1f).heat(1f)
                        .module(damping ? new CgVfxModule.Damping(7f) : new CgVfxModule.Drag(2f, 0f))
                        .opacity(CgKeyframes.start(0f, 0f).to(0.03f, 1f, CgEasings.OUT_QUAD).to(0.85f, 1f, CgEasings.LINEAR)
                                .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
                look.emitter(puck).layer(damping ? sparkLayer(puck, GOLD, WHITE) : sparkLayer(puck, CYAN, WHITE));
                emitters.add(puck);
                sources.add(new float[]{brakeColumn(row, c), 0.3f, 0f});
            }
        }
        stations.add(new Station("damping: Damping (gold) beside Drag (cyan)", x, z, look.build(), emitters,
                sources.toArray(new float[0][]), p -> {
                    for (int row = 0; row < 2; row++) {
                        float middle = (brakeColumn(row, 0) + brakeColumn(row, BRAKE_COLUMNS - 1)) / 2f;
                        transform.identity().scale(BRAKE_SPACING * BRAKE_COLUMNS, 0.06f, 0.3f);
                        p.box(middle, 0.27f, 0f, transform);
                        p.label(middle, 4.7f, 0f, 0.42f, row == 0 ? "damping: a steady brake" : "drag: brakes with speed",
                                0xFFFFFFFF);
                        p.label(middle, 4.2f, 0f, 0.26f, row == 0 ? "stops at speed squared: a curve"
                                : "stops at speed: a straight line", 0xFFB8D8FF);
                        p.label(middle, 0.55f, 0.5f, 0.22f, "1 to 7 blocks a second, left to right", 0xFFB8D8FF);
                    }
                }));
    }

    /** The damping station's columns a row, and how far apart. */
    private static final int BRAKE_COLUMNS = 7;
    private static final float BRAKE_SPACING = 0.75f;

    /** Where column {@code c} of {@code row} stands: damping's row left of the middle, drag's right. */
    private static float brakeColumn(int row, int c) {
        return (row == 0 ? -1.25f - (BRAKE_COLUMNS - 1) * BRAKE_SPACING : 1.25f) + c * BRAKE_SPACING;
    }

    /** Godot's velocity limit: the same fountain with (gold) and without (cyan) a 3 blocks a second ceiling. */
    private void limit(float x, float z) {
        CgVfxEmitter free = CgVfxEmitter.builder("free").renderer(CgVfxEmitter.Renderer.QUADS).capacity(1500)
                .rate(120f, 0f, PERIOD - 3f).launch(0.85f, 1f, 1f).speed(9f, 12f).life(3f, 3.5f)
                .size(0.08f, 0.12f, 1f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Ground(0.3f, 0.3f, 0.5f))
                .opacity(FADE).build();
        CgVfxEmitter limited = free.toBuilder().name("limited").module(new CgVfxModule.LimitSpeed(3f)).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(free).emitter(limited)
                .layer(sparkLayer(free, CYAN, WHITE)).layer(sparkLayer(limited, GOLD, WHITE)).build();
        stations.add(new Station("limit: LimitSpeed 3 (gold) beside none (cyan)", x, z, look, List.of(free, limited),
                new float[][]{{3f, 0.3f, 0f}, {-3f, 0.3f, 0f}}, null));
    }

    /**
     * Flipbooks: a smoke column (left), each puff the smoke sheet over its life, lifted by its heat as it cools and
     * rolled by slow curl noise as it spreads; and a fire (right), upright flames on a looping sheet, lofted by their
     * heat and reddening as they cool, with embers and its own smoke rising off it.
     */
    private void flipbooks(float x, float z, CgTexture smokeSheet, CgTexture fireSheet) {
        CgVfxEmitter puffs = CgVfxEmitter.builder("puffs").renderer(CgVfxEmitter.Renderer.QUADS).capacity(300)
                .rate(18f, 0f, PERIOD - 3f).shape(CgSphere.of(0.5f)).launch(0.8f, 1f, 1f).speed(0.8f, 1.5f).life(3f, 4f)
                .size(0.7f, 1.1f, 1f).spin(0.05f, 0.35f).heat(0.7f)
                .module(new CgVfxModule.Buoyancy(3f, 1.5f))
                .module(new CgVfxModule.Turbulence(1.4f, 0.3f, 0.35f))
                .module(new CgVfxModule.Drag(0.7f, 0f))
                .size(CgKeyframes.start(0f, 0.55f).to(1f, 3f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.15f, 0.95f, CgEasings.OUT_QUAD).to(0.6f, 0.75f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter fire = CgVfxEmitter.builder("fire").renderer(CgVfxEmitter.Renderer.QUADS).capacity(300)
                .rate(55f, 0f, PERIOD - 3f).shape(CgSphere.of(0.4f)).launch(0.85f, 1f, 1f).speed(0.4f, 1f).life(0.6f, 1f)
                .size(0.55f, 0.9f, 1.5f).heat(1f)
                .module(new CgVfxModule.Buoyancy(5f, 1.2f))
                .module(new CgVfxModule.Turbulence(2.5f, 0.9f, 1.4f))
                .module(new CgVfxModule.Drag(1.6f, 0f))
                .size(CgKeyframes.start(0f, 0.7f).to(0.3f, 1.1f, CgEasings.OUT_QUAD).to(1f, 0.55f, CgEasings.IN_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD).to(0.7f, 0.9f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter embers = CgVfxEmitter.builder("fireEmbers").renderer(CgVfxEmitter.Renderer.QUADS).capacity(200)
                .rate(18f, 0f, PERIOD - 3f).shape(CgSphere.of(0.3f)).launch(0.8f, 1f, 1f).speed(1f, 2.5f).life(1.2f, 2.2f)
                .size(0.025f, 0.05f, 1.5f).heat(1f)
                .module(new CgVfxModule.Buoyancy(7f, 0.9f))
                .module(new CgVfxModule.Turbulence(7f, 1.1f, 1.5f))
                .module(new CgVfxModule.Drag(0.8f, 0f))
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter soot = CgVfxEmitter.builder("fireSmoke").renderer(CgVfxEmitter.Renderer.QUADS).capacity(100)
                .rate(9f, 0.4f, PERIOD - 3f).shape(CgSphere.of(0.3f)).launch(0.9f, 1f, 1f).speed(0.4f, 0.8f).life(2.5f, 3.5f)
                .size(0.6f, 0.9f, 1f).spin(0.1f, 0.4f).heat(0.5f)
                .module(new CgVfxModule.Buoyancy(2.5f, 2f))
                .module(new CgVfxModule.Turbulence(1.2f, 0.35f, 0.3f))
                .module(new CgVfxModule.Drag(0.6f, 0f))
                .size(CgKeyframes.start(0f, 0.6f).to(1f, 3.2f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.2f, 1f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        Consumer<CgVfxLayer.Builder> smoke = b -> b.texture("_Sheet", smokeSheet).set("_Frames", 8f, 8f, 64f, 1f)
                .set("_Flip", 0f, 0f, 1f, 0f);
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(puffs).emitter(fire).emitter(embers).emitter(soot)
                .layer(layerOf("sprite", puffs, SMOKE, SMOKE, smoke))
                .layer(layerOf("sprite", soot, SOOT, SOOT, smoke))
                .layer(layerOf("sprite_glow", fire, FIRE_COOL, FIRE_HOT,
                        b -> b.texture("_Sheet", fireSheet).set("_Frames", 4f, 4f, 16f, 2f).set("_Flip", 1f, 1f, 1f, 0f)
                                .set("_Facing", 2f)))
                .layer(sparkLayer(embers, EMBER, FLAME))
                .build();
        stations.add(new Station("flipbooks: a smoke column (left), a fire with embers and smoke (right)", x, z, look,
                List.of(puffs, fire, embers, soot),
                new float[][]{{-2.5f, 0.5f, 0f}, {2.5f, 0.2f, 0f}, {2.5f, 0.3f, 0f}, {2.5f, 1.6f, 0f}}, null));
    }

    /**
     * The five facing modes, left to right, each on the effect it is for: twinkling glints (camera), runes spiralling up
     * (camera position), spirit flames that stay standing seen from above (upright), a fountain of sparks streaked along
     * their arcs (velocity), and shockwaves flat on the floor (axis).
     */
    private void facings(float x, float z, CgTexture star, CgTexture rune, CgTexture wisp, CgTexture dot,
                         CgTexture ring) {
        CgVfxEmitter glints = CgVfxEmitter.builder("faceCamera").renderer(CgVfxEmitter.Renderer.QUADS).capacity(120)
                .rate(16f, 0f, PERIOD - 3f).shape(CgSphere.of(1f)).speed(0.05f, 0.2f).life(1.5f, 2.5f).size(0.2f, 0.45f, 1.5f)
                .spin(0.3f, 1.2f).heat(1f)
                .module(new CgVfxModule.Buoyancy(0.4f, 1.5f))
                .module(new CgVfxModule.Drag(1f, 0f))
                .opacity(CgKeyframes.start(0f, 0f).to(0.2f, 1f, CgEasings.OUT_QUAD).to(0.45f, 0.35f, CgEasings.IN_OUT_QUAD)
                        .to(0.7f, 1f, CgEasings.IN_OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter runes = CgVfxEmitter.builder("facePosition").renderer(CgVfxEmitter.Renderer.QUADS).capacity(40)
                .rate(4f, 0f, PERIOD - 3.5f).shape(CgSphere.of(0.9f)).launch(-0.2f, 0.2f, 1f).speed(0f, 0f).life(3f, 3.5f)
                .size(0.4f, 0.55f, 1f).spin(0.2f, 0.5f).heat(1f)
                .module(new CgVfxModule.Vortex(0f, 1f, 0f, 1.2f, -0.8f, 0f, 0f, 0f))
                .module(new CgVfxModule.Force(0f, 0.35f, 0f))
                .module(new CgVfxModule.Buoyancy(0f, 2f))
                .module(new CgVfxModule.Drag(1f, 0f))
                .opacity(FADE).build();
        CgVfxEmitter flames = CgVfxEmitter.builder("faceUpright").renderer(CgVfxEmitter.Renderer.QUADS).capacity(150)
                .rate(30f, 0f, PERIOD - 3f).shape(CgSphere.of(0.3f)).launch(0.85f, 1f, 1f).speed(0.3f, 0.8f).life(0.6f, 1f)
                .size(0.55f, 0.85f, 1.5f).heat(1f)
                .module(new CgVfxModule.Buoyancy(4f, 1f))
                .module(new CgVfxModule.Turbulence(2.5f, 0.6f, 1.2f))
                .module(new CgVfxModule.Drag(1.6f, 0f))
                .size(CgKeyframes.start(0f, 0.7f).to(0.3f, 1.1f, CgEasings.OUT_QUAD).to(1f, 0.5f, CgEasings.IN_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        // Near upright, so the fountain stays in its lane: 1.5 blocks out at most, bounces included.
        CgVfxEmitter sparks = CgVfxEmitter.builder("faceVelocity").renderer(CgVfxEmitter.Renderer.QUADS).capacity(250)
                .rate(90f, 0f, PERIOD - 3f).shape(CgSphere.of(0.05f)).launch(0.99f, 1f, 1f).speed(5f, 7f).life(1.2f, 1.8f)
                .size(0.045f, 0.07f, 1.5f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Buoyancy(0f, 0.6f))
                .module(new CgVfxModule.Drag(0.3f, 0f))
                .module(new CgVfxModule.Ground(0.35f, 0.1f, 0.2f))
                .opacity(CgKeyframes.start(0f, 1f).to(0.8f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        CgVfxEmitter waves = CgVfxEmitter.builder("faceAxis").renderer(CgVfxEmitter.Renderer.QUADS).capacity(8)
                .rate(1.6f, 0f, PERIOD - 3f).speed(0f, 0f).life(1.4f, 1.4f).size(1f, 1f, 1f).heat(1f)
                .module(new CgVfxModule.Buoyancy(0f, 0.8f))
                .size(CgKeyframes.start(0f, 0.15f).to(1f, 1.5f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(glints).emitter(runes).emitter(flames).emitter(sparks)
                .emitter(waves)
                .layer(facing(glints, GOLD, WHITE, star, 0, null))
                .layer(facing(runes, VIOLET, WHITE, rune, 1, null))
                .layer(facing(flames, SPIRIT_COOL, SPIRIT_HOT, wisp, 2, b -> b.set("_Frames", 4f, 4f, 16f, 2f)
                        .set("_Flip", 1f, 1f, 1f, 0f)))
                .layer(facing(sparks, GOLD, WHITE, dot, 3, b -> b.set("_Stretch", 0.6f).set("_MaxStretch", 10f)))
                .layer(facing(waves, CYAN, WHITE, ring, 4, null)).build();
        stations.add(new Station("facing: camera, camera position, upright, velocity, axis", x, z, look,
                List.of(glints, runes, flames, sparks, waves),
                new float[][]{{-7f, 2.5f, 0f}, {-3.5f, 1.8f, 0f}, {0f, 0.2f, 0f}, {3.5f, 0.4f, 0f}, {7f, 0.04f, 0f}}, p -> {
                    for (int lane = 0; lane < 5; lane++) {
                        float lx = -7f + lane * 3.5f;
                        p.label(lx, 5.2f, 0f, 0.42f, FACING_NAMES[lane], 0xFFFFFFFF);
                        p.label(lx, 4.7f, 0f, 0.26f, FACING_HINTS[lane], 0xFFB8D8FF);
                    }
                }));
    }

    /** Each facing lane's mode, and how it turns: what to look for, flying round it. */
    private static final String[] FACING_NAMES = {"camera", "camera position", "upright", "velocity", "axis"};
    private static final String[] FACING_HINTS = {"lies in the screen", "turns to your eye", "stands up, turns round y",
            "lies along its motion", "flat across an axis"};

    /** A glowing sprite of {@code sheet}, a single frame unless {@code more} says otherwise, facing as {@code mode}. */
    private static CgVfxLayer facing(CgVfxEmitter e, CgVfxParam.Color cool, CgVfxParam.Color hot, CgTexture sheet, int mode,
                                     Consumer<CgVfxLayer.Builder> more) {
        return layerOf("sprite_glow", e, cool, hot, b -> {
            b.texture("_Sheet", sheet).set("_Frames", 1f, 1f, 1f, 1f).set("_Facing", mode)
                    .set("_Axis", 0f, 1f, 0f, 0f);
            // Lying on a surface on purpose: a soft fade would fade it out.
            if (mode == 4) b.set("_SoftDistance", 0f);
            if (more != null) more.accept(b);
        });
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void add(String name, float x, float z, List<CgVfxEmitter> emitters, float[][] sources, CgVfxLayer layer,
                     Consumer<Props> props) {
        CgVfxLook.Builder look = CgVfxLook.builder(SCHEMA);
        for (CgVfxEmitter e : emitters) look.emitter(e);
        stations.add(new Station(name, x, z, look.layer(layer).build(), emitters, sources, props));
    }

    private static CgVfxLayer layer(String shader, CgVfxEmitter e, CgVfxParam.Color a, CgVfxParam.Color b, float radius,
                                    Consumer<CgVfxLayer.Builder> properties) {
        CgVfxLayer.Builder l = CgVfxLayer.of(PARTICLE + shader + ".shader").on(e).bind("_Color", a).size(radius);
        // sprite and speck tint by one colour
        if (!shader.equals("sprite") && !shader.equals("speck")) l.bind("_Hot", b);
        if (properties != null) properties.accept(l);
        return l.build();
    }

    private static CgVfxLayer layerOf(String shader, CgVfxEmitter e, CgVfxParam.Color a, CgVfxParam.Color b,
                                      Consumer<CgVfxLayer.Builder> properties) {
        return layer(shader, e, a, b, 1f, properties);
    }

    private static CgVfxLayer sparkLayer(CgVfxEmitter e, CgVfxParam.Color a, CgVfxParam.Color b) {
        return layer("spark", e, a, b, 1f, null);
    }

    /** Fills one texel of a flipbook frame: {@code (u, v)} 0..1 across the frame, v up; straight-alpha RGBA. */
    private interface Texel {
        void at(int frame, float u, float v, float[] out);
    }

    /**
     * A {@code columns} x {@code rows} sheet of {@code cell}-texel frames, frame 0 at the top left: made empty here, its
     * texels computed by {@link #fillSheets()}.
     */
    private CgTexture sheet(int columns, int rows, int cell, Texel texel) {
        int w = columns * cell, h = rows * cell;
        CgTexture2D texture = CgTexture2D.createEmpty(w, h, CgTextureSpec.RGBA8_LINEAR);
        sheets.add(texture);
        fills.add(() -> {
            ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            float[] out = new float[4];
            for (int y = 0; y < h; y++) {
                int row = rows - 1 - y / cell;
                for (int x = 0; x < w; x++) {
                    int frame = row * columns + x / cell;
                    texel.at(frame, (x % cell + 0.5f) / cell, (y % cell + 0.5f) / cell, out);
                    for (int c = 0; c < 4; c++) pixels.put((byte) Math.round(Math.max(0f, Math.min(1f, out[c])) * 255f));
                }
            }
            pixels.flip();
            synchronized (sheets) {
                if (!deleted) texture.uploadRegion(0, 0, 0, w, h, pixels, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE);
            }
        });
        return texture;
    }

    /**
     * Computes every sheet's texels on a worker, each uploaded as it finishes (a texture lands what a worker queued before
     * the next frame): on the render thread they cost the first frame 1.6 s in Minecraft. A sheet draws empty until then.
     */
    private void fillSheets() {
        List<Runnable> work = new ArrayList<>(fills);
        fills.clear();
        Thread worker = new Thread(() -> {
            try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, SHEETS_ZONE)) {
                for (Runnable fill : work) {
                    if (deleted) return;
                    fill.run();
                }
            } catch (RuntimeException e) {
                LOG.warn("[vfx-modules] flipbook sheets failed", e);
            }
        }, "CrystalGraphics vfx-modules sheets");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * A smoke puff over 64 frames: a lumpy, domain-warped billow swelling out of a tight core, churning as it goes and
     * thinning to wisps, lit from above by how much of itself lies between each point and the light.
     */
    private static void smokeFrame(int frame, float u, float v, float[] out) {
        float t = frame / 63f;
        float grow = 0.5f + 0.5f * (1f - (1f - t) * (1f - t));
        float px = (u - 0.5f) / grow, py = (v - 0.5f) / grow;
        float density = smokeDensity(px, py, t);
        // Self-shadowing: the density a short step toward the light (up and a little left) dims what is under it.
        float shadow = smokeDensity(px - 0.05f, py + 0.12f, t) + 0.5f * smokeDensity(px - 0.1f, py + 0.24f, t);
        float shade = 0.38f + 0.62f * (float) Math.exp(-1.8f * shadow);
        out[0] = shade;
        out[1] = shade;
        out[2] = shade * 1.03f;
        out[3] = density * (1f - 0.8f * t * t);
    }

    /** The puff's density at {@code (x, y)} in its own units (radius about 0.5) at {@code t}, 0..1. */
    private static float smokeDensity(float x, float y, float t) {
        float wx = fbm(x * 2.2f + 3.1f, y * 2.2f - t * 0.6f, 3) - 0.5f;
        float wy = fbm(x * 2.2f - 1.7f + t * 0.4f, y * 2.2f + 5.3f, 3) - 0.5f;
        float d = fbm(x * 3f + wx * 1.6f, y * 3f + wy * 1.6f - t * 0.8f, 5);
        float r = (float) Math.sqrt(x * x + y * y) * 2f;
        // Billows: dense in the middle, a ragged edge that grows wispier as the puff spreads.
        float body = 1f - smooth(0.3f - 0.1f * t, 1.05f, r + (d - 0.5f) * (0.8f + 0.6f * t));
        return clamp01(body * (0.5f + 1.1f * (d - 0.3f)));
    }

    /**
     * A flame over 16 frames that loop: a teardrop wide at its base, its tongues torn upward by rising noise, coloured
     * by temperature: white-yellow at the root through orange to a dark red edge.
     */
    private static void fireFrame(int frame, float u, float v, float[] out) {
        float t = frame / 16f;
        // Two scrolls a loop apart, crossfaded, so frame 16 is frame 0.
        float a = flameNoise(u, v, t), b = flameNoise(u, v, t - 1f);
        float n = a + (b - a) * t;
        float dx = u - 0.5f + (n - 0.5f) * 0.3f * v;
        float width = 0.34f * (float) Math.pow(1f - v, 0.6f) + 0.02f;
        // Soft sides, so overlapping flames blend rather than showing each one's outline.
        float shape = 1f - smooth(0.15f, 1f, Math.abs(dx) / width);
        float heat = clamp01((shape * (1.05f - v * 1.1f) + (n - 0.5f) * 0.8f * v) * smooth(0f, 0.08f, v));
        flameColour(heat, out);
        out[3] = smooth(0.03f, 0.6f, heat) * (0.55f + 0.45f * shape);
    }

    /** Fine flame noise rising {@code t} loops: 4 lattice cells a loop, stretched upward. */
    private static float flameNoise(float u, float v, float t) {
        return fbm(u * 5f + 7.3f, v * 3f - t * 4f, 4);
    }

    /** A blackbody-like ramp, by heat: dark red, orange, yellow, pale yellow. Each stop is a heat, then RGB. */
    private static final float[][] FLAME_STOPS = {{0f, 0.25f, 0.02f, 0f}, {0.3f, 0.85f, 0.18f, 0.02f},
            {0.55f, 1f, 0.45f, 0.05f}, {0.8f, 1f, 0.75f, 0.25f}, {1f, 1f, 0.92f, 0.6f}};

    private static void flameColour(float heat, float[] out) {
        float[][] s = FLAME_STOPS;
        int i = 1;
        while (i < s.length - 1 && heat > s[i][0]) i++;
        float k = clamp01((heat - s[i - 1][0]) / (s[i][0] - s[i - 1][0]));
        for (int c = 0; c < 3; c++) out[c] = s[i - 1][c + 1] + (s[i][c + 1] - s[i - 1][c + 1]) * k;
    }

    private static void dot(float u, float v, float[] out) {
        float dx = u - 0.5f, dy = v - 0.5f, r = (float) Math.sqrt(dx * dx + dy * dy) * 2f;
        out[0] = out[1] = out[2] = 1f;
        out[3] = (float) Math.exp(-r * r * 5f) * smooth(1f, 0.85f, r);
    }

    /** A four-pointed glint: thin rays along u and v, fainter diagonals, round a bright core. */
    private static void star(float u, float v, float[] out) {
        float dx = Math.abs(u - 0.5f) * 2f, dy = Math.abs(v - 0.5f) * 2f, r2 = dx * dx + dy * dy;
        float cross = (float) (Math.exp(-dx * 30f) * Math.max(0f, 1f - dy) + Math.exp(-dy * 30f) * Math.max(0f, 1f - dx));
        float ex = (dx + dy) * 0.7071f, ey = Math.abs(dx - dy) * 0.7071f;
        float diagonal = 0.4f * (float) Math.exp(-ey * 40f) * Math.max(0f, 1f - ex * 1.6f);
        out[0] = out[1] = out[2] = 1f;
        out[3] = clamp01(cross + diagonal + (float) Math.exp(-r2 * 25f));
    }

    /** A rune circle: two rings, ticks between them, and a triangle inside. */
    private static void rune(float u, float v, float[] out) {
        float x = (u - 0.5f) * 2f, y = (v - 0.5f) * 2f, r = (float) Math.sqrt(x * x + y * y);
        float ink = Math.max(line(Math.abs(r - 0.9f), 0.035f), line(Math.abs(r - 0.66f), 0.02f));
        double angle = Math.atan2(y, x);
        float tick = (float) Math.abs(Math.IEEEremainder(angle, Math.PI / 8)) * r;
        if (r > 0.7f && r < 0.86f) ink = Math.max(ink, line(tick, 0.018f));
        for (int k = 0; k < 3; k++) {
            double a0 = Math.PI / 2 + k * 2 * Math.PI / 3, a1 = a0 + 2 * Math.PI / 3;
            ink = Math.max(ink, line(segment(x, y, 0.62f * (float) Math.cos(a0), 0.62f * (float) Math.sin(a0),
                    0.62f * (float) Math.cos(a1), 0.62f * (float) Math.sin(a1)), 0.022f));
        }
        out[0] = out[1] = out[2] = 1f;
        out[3] = Math.max(ink, 0.12f * (1f - smooth(0.6f, 0.95f, r)));
    }

    /** An ice shard: a long diamond, one facet lit and one in shade, a bright ridge between and a bright rim. */
    private static void shard(float u, float v, float[] out) {
        float dx = (u - 0.5f) / 0.22f, dy = (v - 0.5f) / 0.47f, d = Math.abs(dx) + Math.abs(dy);
        float shade = dx > 0f ? 1f : 0.55f, ridge = (float) Math.exp(-dx * dx * 60f), rim = smooth(0.7f, 0.95f, d);
        out[0] = out[1] = out[2] = clamp01(shade * (0.6f + 0.4f * rim) + ridge * 0.5f);
        out[3] = (1f - smooth(0.9f, 1f, d)) * (0.55f + 0.45f * Math.max(rim, ridge));
    }

    /** A shockwave: a bright thin edge trailing a soft glow inward. */
    private static void ring(float u, float v, float[] out) {
        float dx = u - 0.5f, dy = v - 0.5f, r = (float) Math.sqrt(dx * dx + dy * dy) * 2f;
        float edge = (r - 0.88f) / 0.04f, trail = (r - 0.72f) / 0.16f;
        out[0] = out[1] = out[2] = 1f;
        out[3] = clamp01((float) (Math.exp(-edge * edge) + 0.35f * Math.exp(-trail * trail)) * (r < 0.98f ? 1f : 0f));
    }

    /** {@link #fireFrame} in grey, brightest where hottest, for a layer's colours to tint. */
    private static void wispFrame(int frame, float u, float v, float[] out) {
        fireFrame(frame, u, v, out);
        out[0] = out[1] = out[2] = clamp01(0.2f + (out[0] + out[1] + out[2]) / 2.5f);
    }

    /** Ink of a stroke {@code half} wide at {@code distance} from its middle, antialiased over a texel or so. */
    private static float line(float distance, float half) {
        return 1f - smooth(half - 0.012f, half + 0.012f, distance);
    }

    /** The distance from {@code (x, y)} to the segment from {@code (ax, ay)} to {@code (bx, by)}. */
    private static float segment(float x, float y, float ax, float ay, float bx, float by) {
        float ex = bx - ax, ey = by - ay;
        float t = clamp01(((x - ax) * ex + (y - ay) * ey) / (ex * ex + ey * ey));
        float px = x - ax - ex * t, py = y - ay - ey * t;
        return (float) Math.sqrt(px * px + py * py);
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** {@link #noise}'s fractal, 0..1: octaves doubling in frequency, halving in weight, turned so they never line up. */
    private static float fbm(float x, float y, int octaves) {
        float sum = 0f, amplitude = 0.5f, norm = 0f;
        for (int k = 0; k < octaves; k++) {
            sum += amplitude * noise(x, y);
            norm += amplitude;
            float rx = 0.8f * x - 0.6f * y, ry = 0.6f * x + 0.8f * y;
            x = rx * 2.03f + 1.7f;
            y = ry * 2.03f - 0.9f;
            amplitude *= 0.5f;
        }
        return sum / norm;
    }

    /** Smooth value noise, 0..1. */
    private static float noise(float x, float y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        fx = fx * fx * (3f - 2f * fx);
        fy = fy * fy * (3f - 2f * fy);
        float a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
    }

    private static float hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0xFFFFFF;
    }

    // ── frame ─────────────────────────────────────────────────────────────────

    private final Props props = new Props() {
        private final Matrix4f scaled = new Matrix4f();

        @Override
        public void sphere(float x, float y, float z, float radius) {
            scaled.identity().scale(radius);
            world.draw(sphere, mercury).at(currentX + x, currentY + y, currentZ + z).transform(scaled).submit();
        }

        @Override
        public void box(float x, float y, float z, Matrix4f transform) {
            world.draw(cube, copper).at(currentX + x, currentY + y, currentZ + z).transform(transform).submit();
        }

        @Override
        public void label(float x, float y, float z, float height, String text, int color) {
            if (font == null) return;
            world.text(text).at(currentX + x, currentY + y, currentZ + z).height(height).font(font).color(color)
                    .stroke(0.08f, 0xFF000000).submit();
        }
    };
    private double currentX, currentY, currentZ;

    /**
     * Plays every station round {@code (x, y, z)} each {@link #PERIOD} seconds and submits this frame's draws: once a
     * frame, before the world stages record.
     */
    public void submit(CgWorldRenderer world, double x, double y, double z, float seconds) {
        this.world = world;
        if (seconds >= nextPlay) {
            for (int i = 0; i < stations.size(); i++) {
                Station s = stations.get(i);
                double floor = CgWorldQueries.groundBelow(x + s.x(), y + 16.0, z + s.z(), 48);
                floors[i] = (float) (Double.isNaN(floor) ? y : floor);
                vfx.play(new Play(s, x, floors[i], z));
            }
            nextPlay = seconds + PERIOD;
        }
        if (drives) {
            vfx.system().update(seconds);
            vfx.system().submit(world);
        }
        for (int i = 0; i < stations.size(); i++) {
            Station s = stations.get(i);
            currentX = x + s.x();
            currentY = floors[i];
            currentZ = z + s.z();
            if (font != null) {
                // Its name over it, and what it shows under that.
                int split = s.name().indexOf(": ");
                String title = split < 0 ? s.name() : s.name().substring(0, split);
                world.text(title).at(currentX, currentY + 10.2, currentZ).height(1.1f).font(font)
                        .stroke(0.08f, 0xFF000000).submit();
                if (split >= 0) {
                    world.text(s.name().substring(split + 2)).at(currentX, currentY + 9.1, currentZ).height(0.6f)
                            .font(font).color(0xFFB8D8FF).stroke(0.08f, 0xFF000000).submit();
                }
            }
            if (s.props() != null) s.props().accept(props);
        }
    }

    /** Warms every station's kernels and programs, so its first play stalls no frame. On the render thread. */
    public void prepare() {
        for (Station s : stations) vfx.prepare(s.look());
        CgWorldRenderer world = CgWorldRenderer.get();
        world.prepare(mercury);
        world.prepare(copper);
    }

    /** Whether everything {@link #prepare} started compiling is built. */
    public boolean warmed() {
        CgWorldRenderer world = CgWorldRenderer.get();
        return vfx.warmed() & world.prepare(mercury) & world.prepare(copper);
    }

    /** Ends every station's play at once; the next {@link #submit} plays them all again. */
    public void clear() {
        clearPlays();
        nextPlay = 0f;
    }

    /** The system the stations play in. */
    public CgVfxSystem vfx() {
        return vfx.system();
    }

    /** Its own system cleared whole, its clock with it; on the world's, its plays alone. */
    private void clearPlays() {
        if (drives) {
            vfx.system().clear();
        } else {
            vfx.clear();
        }
    }

    /** Stops every station and frees what it made: its own system, or its plays on the world's. The font is the caller's. */
    public void delete() {
        if (drives) {
            vfx.system().delete();
        } else {
            vfx.clear();
        }
        synchronized (sheets) {
            deleted = true;
            for (CgTexture sheet : sheets) sheet.delete();
            sheets.clear();
        }
    }
}
