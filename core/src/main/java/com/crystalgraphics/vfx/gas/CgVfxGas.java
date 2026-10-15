package com.crystalgraphics.vfx.gas;

import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxElement;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;

/**
 * A volume of gas an effect owns: a smoke grenade's cloud, steam, a fireball's burning air. It is simulated on the GPU
 * in a brick of {@link CgVfxGasAtlas}, a box {@link #SIZE} blocks a side fixed in the world where it was placed: its
 * sources pour smoke, heat and fuel into it, heat rises, fuel burns into smoke and heat, it curls, flows round the
 * world's blocks and out of its box, and the sun lights it through itself. Its layers draw it on its slot.
 *
 * <pre>{@code
 * public final class Grenade extends CgVfxEffect<Grenade> {
 *     private final CgVfxGas smoke = new CgVfxGas(this).place(0f, 5f, 0f);       // its middle, from the effect's origin
 *     private final CgVfxGas.Source vent = smoke.source(0f, 0.3f, 0f, 1.2f)
 *             .density(5f).heat(1.5f).velocity(0f, 2f, 0f);
 *
 *     public Grenade(CgVfxLook look, double x, double y, double z) {
 *         super(look, x, y, z);
 *     }
 *
 *     @Override protected void tick(float dt) {
 *         smoke.tick(dt);
 *         if (age > 15f) vent.off();                 // it stops pouring, and thins out
 *         if (smoke.faded()) die();
 *     }
 *
 *     @Override protected void submit(CgVfxFrame frame) {
 *         smoke.submit(frame);
 *     }
 * }
 *
 * // Its look takes the gas's kit: its schema, and the engine's smoke on its slot
 * static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).with(CgVfxGas.SMOKE).set(CgVfxGas.SIZE, 16f).build();
 * }</pre>
 *
 * <pre>{@code
 * smoke.blast(x, y, z, 4f, 25f, 1f);               // a blast through it: thrown out at 25 blocks a second, cleared within 4
 * CgVfxGas.Source flame = fire.source(0f, 0f, 0f, 0.8f).fuel(3f).heat(2f);   // fuel burns into heat and smoke (BURN, HEAT)
 * new CgVfxGas(this, STEAM);                       // a gas drawn on a volume slot of the effect's own
 * }</pre>
 *
 * <ul>
 *   <li>Positions are relative to the effect's origin, as everything an element draws is; the gas itself stays where
 *       it was placed when it took its brick, so a moving source trails through it.</li>
 *   <li>It flows round the voxel window's blocks, and stands on its effect's {@code ground} where it has one: a host
 *       with no world.</li>
 *   <li>Its look carries {@link #SCHEMA}: through {@link #SMOKE}, or a kit of its own that takes the schema.</li>
 *   <li>Call {@link #tick} every effect tick: it steps on the effect's clock, and one left unticked gives its brick back.</li>
 *   <li>At most {@link CgVfxGasAtlas#CAPACITY} gases hold bricks at once; one waiting for a brick draws nothing.</li>
 * </ul>
 */
public final class CgVfxGas extends CgVfxElement {

    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:gas");
    /** Its box's side, in blocks: a brick's cells span it, so the same gas is coarser at a lower tier. */
    public static final CgVfxParam.Scalar SIZE = SCHEMA.length("size", 12f);
    /** Upward acceleration a unit of temperature gives, blocks a second a second. */
    public static final CgVfxParam.Scalar BUOYANCY = SCHEMA.scalar("buoyancy", 2f);
    /** Downward acceleration a unit of density gives: heavy smoke sinks and spreads along the ground. */
    public static final CgVfxParam.Scalar WEIGHT = SCHEMA.scalar("weight", 0.3f);
    /** How hard small swirls are kept from smoothing out: the curl in the smoke. */
    public static final CgVfxParam.Scalar VORTICITY = SCHEMA.scalar("vorticity", 1.5f);
    /** The share of its density, temperature and velocity lost a second. */
    public static final CgVfxParam.Scalar DISSIPATION = SCHEMA.rate("dissipation", 0.08f);
    public static final CgVfxParam.Scalar COOLING = SCHEMA.rate("cooling", 0.8f);
    public static final CgVfxParam.Scalar DAMPING = SCHEMA.rate("damping", 0.3f);
    /** Fuel burnt a second, at most, and the temperature a unit burnt gives (a unit of smoke with it). */
    public static final CgVfxParam.Scalar BURN = SCHEMA.rate("burn", 1.5f);
    public static final CgVfxParam.Scalar HEAT = SCHEMA.scalar("heat", 3f);
    /** Light lost a block through a unit of density: how thick it looks, and how deep its shadows. */
    public static final CgVfxParam.Scalar EXTINCTION = SCHEMA.scalar("extinction", 2.5f);
    /** The smoke's colour in full light, and the light of what burns in it, past white where it should bloom. */
    public static final CgVfxParam.Color SMOKE_COLOR = SCHEMA.color("smoke", 0xFFB4B2AE);
    public static final CgVfxParam.Color FIRE = SCHEMA.color("fire", 0xFFFFFFFF);

    /** Where a gas draws unless given a slot of its own. */
    public static final CgVfxSlot SLOT = CgVfxSlot.volume("crystalgraphics:gas");
    /** The engine's smoke on {@link #SLOT}: lit by the sun through itself and by the sky, glowing where it burns. */
    public static final CgVfxKit SMOKE = CgVfxKit.of("crystalgraphics:gas/smoke").params(SCHEMA)
            .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/gas/smoke.shader").on(SLOT).role(CgVfxLayer.Role.SMOKE).build())
            .build();

    /** The most sources a gas pours from, and blasts it takes between two steps. */
    public static final int MAX_SOURCES = 8, MAX_BLASTS = 8;
    /** The density it thins to before {@link #faded()}: a share of a unit. */
    private static final float FADED = 0.02f;

    private final CgVfxSlot slot;
    private final Source[] sources = new Source[MAX_SOURCES];
    private int sourceCount;
    /** Blasts since the last step, five floats each: where from the origin, radius, speed, carve. */
    private final float[] blasts = new float[MAX_BLASTS * 6];
    private int blastCount;
    private float placeX, placeY, placeZ;
    /** Its middle in the world once it holds a brick. */
    double worldX, worldY, worldZ;
    private int brick = -1;
    /** Seconds its effect has ticked that no step has taken yet; the atlas frame of its last tick. */
    float owed;
    long ticked;
    /** Seconds since a source was on or a blast went off. */
    private float quiet;

    /** A gas drawn on {@link #SLOT}. */
    public CgVfxGas(CgVfxEffect<?> effect) {
        this(effect, SLOT);
    }

    public CgVfxGas(CgVfxEffect<?> effect, CgVfxSlot slot) {
        super(effect);
        if (slot.kind() != CgVfxSlot.Kind.VOLUME) throw new IllegalArgumentException(slot + " is not a volume slot");
        this.slot = slot;
    }

    /** Where its middle goes, from the effect's origin: taken when it first gets a brick, fixed after. */
    public CgVfxGas place(float x, float y, float z) {
        placeX = x;
        placeY = y;
        placeZ = z;
        return this;
    }

    /**
     * A sphere it pours from, {@code radius} blocks round {@code (x, y, z)} from the effect's origin: on, pouring nothing
     * until given amounts. Most of {@link #MAX_SOURCES}.
     */
    public Source source(float x, float y, float z, float radius) {
        if (sourceCount == MAX_SOURCES) throw new IllegalStateException("a gas pours from at most " + MAX_SOURCES + " sources");
        Source s = new Source();
        s.at(x, y, z).radius(radius);
        sources[sourceCount++] = s;
        return s;
    }

    /**
     * A blast through it at {@code (x, y, z)} from the effect's origin: everything within {@code radius} blocks thrown out
     * at up to {@code speed} blocks a second, and {@code carve} (0 to 1) of its smoke and fuel cleared at the middle,
     * less toward the edge. Taken by the next step; past {@link #MAX_BLASTS} before it, dropped.
     */
    public void blast(float x, float y, float z, float radius, float speed, float carve) {
        quiet = 0f;
        if (blastCount == MAX_BLASTS) return;
        int at = blastCount++ * 6;
        blasts[at] = x;
        blasts[at + 1] = y;
        blasts[at + 2] = z;
        blasts[at + 3] = radius;
        blasts[at + 4] = speed;
        blasts[at + 5] = Math.max(0f, Math.min(1f, carve));
    }

    /** Its tick: from the effect's {@code tick}, every tick. Takes a brick the first time one is free. */
    public void tick(float dt) {
        CgVfxGasAtlas atlas = CgVfxGasAtlas.get();
        if (brick < 0) {
            brick = atlas.acquire(this);
            if (brick >= 0) {
                worldX = originX() + placeX;
                worldY = originY() + placeY;
                worldZ = originZ() + placeZ;
            }
        }
        ticked = atlas.frame();
        owed += dt;
        boolean pouring = blastCount > 0;
        for (int i = 0; i < sourceCount; i++) pouring |= sources[i].pours();
        quiet = pouring ? 0f : quiet + dt;
    }

    /** Draws it on its slot: from the effect's {@code submit}. */
    public void submit(CgVfxFrame frame) {
        if (brick >= 0 && frame.draws(effect, slot)) frame.volume(effect, slot, this);
    }

    /**
     * Whether it has thinned out: nothing poured for as long as {@link #DISSIPATION} takes a unit of smoke down to a
     * fiftieth, at most a minute. What an effect waits for before it dies.
     */
    public boolean faded() {
        float rate = Math.max(get(DISSIPATION), 0.01f);
        return quiet > Math.min(60f, (float) Math.log(1f / FADED) / rate);
    }

    /** Gives its brick back now: it draws nothing from here, and a later tick takes one again, empty. */
    public void release() {
        CgVfxGasAtlas.get().release(brick);
        brick = -1;
    }

    /** Its brick in the atlas, -1 while it has none. */
    public int brick() {
        return brick;
    }

    public CgVfxSlot slot() {
        return slot;
    }

    /** Its middle in the world: valid while it holds a brick. */
    public double x() {
        return worldX;
    }

    public double y() {
        return worldY;
    }

    public double z() {
        return worldZ;
    }

    /** Its box's side in blocks: {@link #SIZE}'s value. */
    public float extent() {
        return get(SIZE);
    }

    public float extinction() {
        return get(EXTINCTION);
    }

    CgVfxEffect<?> effect() {
        return effect;
    }

    /** Its floor's height from its middle: the effect's ground, where it has one (a host with no world); far below, none. */
    float floor() {
        float ground = groundHeight();
        return Float.isNaN(ground) ? -1.0e9f : (float) (ground + originY() - worldY);
    }

    float buoyancy() {
        return get(BUOYANCY);
    }

    float weight() {
        return get(WEIGHT);
    }

    float vorticity() {
        return get(VORTICITY);
    }

    float dissipation() {
        return get(DISSIPATION);
    }

    float cooling() {
        return get(COOLING);
    }

    float damping() {
        return get(DAMPING);
    }

    float burn() {
        return get(BURN);
    }

    float heat() {
        return get(HEAT);
    }

    /** The atlas took its brick back: its effect died or stopped ticking. */
    void lost() {
        brick = -1;
    }

    /** A step took its blasts. */
    void stepped() {
        blastCount = 0;
    }

    /** Rows it writes into {@code SOURCES}: its sources pouring, and its blasts. */
    int sourceRows() {
        int n = blastCount;
        for (int i = 0; i < sourceCount; i++) if (sources[i].pours()) n++;
        return n;
    }

    /**
     * Its sources pouring, or its blasts, into {@code out} from float {@code at}, three vec4s each in {@code cells}
     * cells a brick: {@code (brick, centre)}, {@code (density, temperature, fuel, radius)} (a blast's carve in x) and
     * {@code (velocity, kind)}, kind 1 a source and 2 a blast (its speed in x). Answers the float after.
     */
    int writeSources(float[] out, int at, int b, int cells, boolean blastRows) {
        float cell = extent() / cells, half = cells * 0.5f;
        float ox = (float) (originX() - worldX), oy = (float) (originY() - worldY), oz = (float) (originZ() - worldZ);
        if (blastRows) {
            for (int i = 0; i < blastCount; i++) {
                int k = i * 6;
                at = row(out, at, b, (ox + blasts[k]) / cell + half, (oy + blasts[k + 1]) / cell + half,
                        (oz + blasts[k + 2]) / cell + half, blasts[k + 5], 0f, 0f, blasts[k + 3] / cell, blasts[k + 4], 0f, 0f, 2f);
            }
            return at;
        }
        for (int i = 0; i < sourceCount; i++) {
            Source s = sources[i];
            if (!s.pours()) continue;
            at = row(out, at, b, (ox + s.x) / cell + half, (oy + s.y) / cell + half, (oz + s.z) / cell + half,
                    s.density, s.heat, s.fuel, s.radius / cell, s.vx, s.vy, s.vz, 1f);
        }
        return at;
    }

    private static int row(float[] out, int at, int b, float cx, float cy, float cz, float density, float heat, float fuel,
                           float radius, float vx, float vy, float vz, float kind) {
        out[at] = b;
        out[at + 1] = cx;
        out[at + 2] = cy;
        out[at + 3] = cz;
        out[at + 4] = density;
        out[at + 5] = heat;
        out[at + 6] = fuel;
        out[at + 7] = radius;
        out[at + 8] = vx;
        out[at + 9] = vy;
        out[at + 10] = vz;
        out[at + 11] = kind;
        return at + 12;
    }

    /**
     * Where a gas pours from: a sphere that adds density, temperature and fuel a second, weighted toward its middle, and
     * steers what is inside it toward its velocity. Move and change it from the effect's tick.
     *
     * <pre>{@code
     * vent.at(0f, 0.5f, 0f).density(4f).heat(1f).velocity(0f, 3f, 0f);
     * vent.off();                       // and on() again
     * }</pre>
     */
    public static final class Source {
        float x, y, z, radius = 1f, density, heat, fuel, vx, vy, vz;
        boolean on = true;

        private Source() {
        }

        /** Its middle, from the effect's origin. */
        public Source at(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        public Source radius(float blocks) {
            this.radius = Math.max(blocks, 0.05f);
            return this;
        }

        /** Density added a second at its middle. */
        public Source density(float perSecond) {
            this.density = perSecond;
            return this;
        }

        /** Temperature over the air's added a second: what rises. */
        public Source heat(float perSecond) {
            this.heat = perSecond;
            return this;
        }

        /** Fuel added a second, which burns into heat and smoke. */
        public Source fuel(float perSecond) {
            this.fuel = perSecond;
            return this;
        }

        /** What it steers the gas inside it toward, blocks a second. */
        public Source velocity(float x, float y, float z) {
            vx = x;
            vy = y;
            vz = z;
            return this;
        }

        public Source on() {
            on = true;
            return this;
        }

        public Source off() {
            on = false;
            return this;
        }

        public boolean isOn() {
            return on;
        }

        boolean pours() {
            return on && (density > 0f || heat > 0f || fuel > 0f || vx != 0f || vy != 0f || vz != 0f);
        }
    }
}
