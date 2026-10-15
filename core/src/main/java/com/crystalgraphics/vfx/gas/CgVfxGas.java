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
 * in {@link CgVfxGasAtlas}'s sparse tiles, cells {@link #CELL} blocks a side about where it was placed, held only where
 * there is gas, so it flows as far as it goes: its sources pour smoke, heat and fuel into it, heat rises, fuel burns
 * into flame, heat and smoke and pushes outward as it burns, it curls and churns, flows round the world's blocks, and
 * the sun and the sky light it through itself. Its layers draw it on its slot. Its parameters are Houdini Pyro's, so a Pyro look carries over:
 * buoyancy, cooling, dissipation, vorticity, turbulence, disturbance, expansion, burn rate and flame temperature.
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
 * static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).with(CgVfxGas.SMOKE).set(CgVfxGas.CELL, 0.3f).build();
 * }</pre>
 *
 * <pre>{@code
 * smoke.blast(x, y, z, 4f, 25f, 1f);               // a blast through it: thrown out at 25 blocks a second, cleared within 4
 * CgVfxGas.Source flame = fire.source(0f, 0f, 0f, 0.8f).fuel(3f).heat(2f);   // fuel burns into flame and smoke (BURN, HEAT)
 * new CgVfxGas(this, STEAM);                       // a gas drawn on a volume slot of the effect's own
 * }</pre>
 *
 * <p>A look sets how it moves and how it shades:
 *
 * <pre>{@code
 * CgVfxLook.builder(SCHEMA).with(CgVfxGas.SMOKE)
 *         .set(CgVfxGas.CELL, 0.6f)                                         // big: coarser cells, fewer tiles
 *         .set(CgVfxGas.BURN, 2f).set(CgVfxGas.HEAT, 4f)                    // fuel burns fast and white-hot
 *         .set(CgVfxGas.EXPANSION, 0.25f)                                   // and bursts as it burns: a fireball
 *         .set(CgVfxGas.TURBULENCE, 4f).set(CgVfxGas.TURBULENCE_SIZE, 3f)   // churned at three blocks
 *         .set(CgVfxGas.DISTURBANCE, 3f).set(CgVfxGas.DISTURBANCE_SIZE, 1.5f) // broken up finer, faster
 *         .set(CgVfxGas.DETAIL, 0.7f).set(CgVfxGas.SOOT, 0.4f)              // wispy edges; hot smoke glowing dull red
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>Positions are relative to the effect's origin, as everything an element draws is; the gas itself stays where
 *       it was placed when it took its place in the atlas, so a moving source trails through it.</li>
 *   <li>It reaches {@code CgVfxGasAtlas.SPAN / 2} cells from where it was placed each way: 256 cells, about 100 blocks
 *       at a {@link #CELL} of 0.4.</li>
 *   <li>It flows round the voxel window's blocks, and stands on its effect's {@code ground} where it has one: a host
 *       with no world.</li>
 *   <li>Its look carries {@link #SCHEMA}: through {@link #SMOKE}, or a kit of its own that takes the schema.</li>
 *   <li>Call {@link #tick} every effect tick: it steps on the effect's clock, and one left unticked gives its tiles back.</li>
 *   <li>At most {@link CgVfxGasAtlas#CAPACITY} gases at once; one waiting for a place draws nothing. Every gas shares
 *       the pool's tiles: a finer {@link #CELL} spends more of them on the same smoke.</li>
 *   <li>Temperature is over the air's, in the solver's units: the engine's smoke shows a unit as {@code 600} kelvin, so
 *       {@link #HEAT} 3 burns orange and 4 yellow-white.</li>
 *   <li>A {@link #DISTURBANCE_SIZE} or {@link #TURBULENCE_SIZE} under two cells is lost in the grid; a cell is
 *       {@link #CELL} at High, half as fine again at Low.</li>
 * </ul>
 */
public final class CgVfxGas extends CgVfxElement {

    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:gas");
    /**
     * A cell's side in blocks at High and Ultra, 1.5 times it at Medium, twice at Low: the finest detail it holds, and how
     * many of the pool's tiles its smoke takes. Taken when it takes its place in the atlas.
     */
    public static final CgVfxParam.Scalar CELL = SCHEMA.length("cell", 0.4f);
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
    /**
     * The share of its fuel burnt a second, and the temperature it burns at: where it burns fast it is flame, which
     * lingers a moment as the flow licks it off, and it leaves {@link #SMOKE_AMOUNT} of smoke.
     */
    public static final CgVfxParam.Scalar BURN = SCHEMA.rate("burn", 1.5f);
    public static final CgVfxParam.Scalar HEAT = SCHEMA.scalar("heat", 3f);
    /** The smoke a unit of fuel leaves as it burns (Pyro's smoke amount): a clean flame's 0.1, a sooty fire's 1. */
    public static final CgVfxParam.Scalar SMOKE_AMOUNT = SCHEMA.scalar("smokeAmount", 0.5f);
    /**
     * The hot gas a unit of fuel releases as it burns (Pyro's expansion), as the log of the volume it swells by: a cell
     * holding {@code f} of fuel grows {@code e^(EXPANSION * f)} times as it burns, at {@link #BURN}'s pace, at most 24
     * a second. So a charge bursts by what it holds and stops, however big it is: a fireball of fuel 10 at 0.25 swells
     * twelvefold.
     */
    public static final CgVfxParam.Scalar EXPANSION = SCHEMA.scalar("expansion", 0f);
    /** Curl noise churning it, blocks a second a second, and the size of its swirls: billows on a plume. */
    public static final CgVfxParam.Scalar TURBULENCE = SCHEMA.scalar("turbulence", 0f);
    public static final CgVfxParam.Scalar TURBULENCE_SIZE = SCHEMA.length("turbulenceSize", 3f);
    /**
     * Fine, fast curl noise churning it (Pyro's disturbance), blocks a second a second, and its swirls' size: what
     * breaks a smooth body of gas into small detail and tears flames.
     */
    public static final CgVfxParam.Scalar DISTURBANCE = SCHEMA.scalar("disturbance", 0f);
    public static final CgVfxParam.Scalar DISTURBANCE_SIZE = SCHEMA.length("disturbanceSize", 0.75f);
    /** Light lost a block through a unit of density: how thick it looks, and how deep its shadows. */
    public static final CgVfxParam.Scalar EXTINCTION = SCHEMA.scalar("extinction", 2.5f);
    /** How strongly it scatters light forward, 0 to 0.9: steam's silver edge toward the sun, from 0.7 up. */
    public static final CgVfxParam.Scalar SCATTERING = SCHEMA.scalar("scattering", 0.6f);
    /**
     * Its edges worn away by noise finer than its cells, 0 to 1, carried by its flow, and that noise's size: wisps where
     * it thins, which its cells are too coarse to hold.
     */
    public static final CgVfxParam.Scalar DETAIL = SCHEMA.scalar("detail", 0.5f);
    public static final CgVfxParam.Scalar DETAIL_SIZE = SCHEMA.length("detailSize", 1.2f);
    /** How its fire shines: hot smoke's glow beside its flame's, and both together, 1 as the engine's smoke shows them. */
    public static final CgVfxParam.Scalar SOOT = SCHEMA.scalar("soot", 0.35f);
    public static final CgVfxParam.Scalar GLOW = SCHEMA.scalar("glow", 1f);
    /** The smoke's colour in full light, and the light of what burns in it, past white where it should bloom. */
    public static final CgVfxParam.Color SMOKE_COLOR = SCHEMA.color("smoke", 0xFFB4B2AE);
    public static final CgVfxParam.Color FIRE = SCHEMA.color("fire", 0xFFFFFFFF);

    /** Where a gas draws unless given a slot of its own. */
    public static final CgVfxSlot SLOT = CgVfxSlot.volume("crystalgraphics:gas");
    /** The engine's smoke on {@link #SLOT}: lit by the sun through itself and by the sky, glowing where it burns. */
    public static final CgVfxKit SMOKE = CgVfxKit.of("crystalgraphics:gas/smoke").params(SCHEMA)
            .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/gas/smoke.shader").on(SLOT).role(CgVfxLayer.Role.SMOKE)
                    .bind("_Scattering", SCATTERING).bind("_Detail", DETAIL).bind("_DetailSize", DETAIL_SIZE)
                    .bind("_Soot", SOOT).bind("_Glow", GLOW).build())
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
    /** Its middle in the world once it holds a place in the atlas, and its cell in blocks there. */
    double worldX, worldY, worldZ;
    float cell;
    private int index = -1;
    /** The box its tiles cover, from its middle in blocks: lo xyz then hi xyz; whether it has any. */
    private final float[] box = new float[6];
    private boolean boxed;
    /** Seconds its effect has ticked that no step has taken yet; the atlas frame of its last tick; seconds stepped. */
    float owed;
    long ticked;
    float clock;
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

    /** Where its middle goes, from the effect's origin: taken when it first gets a place in the atlas, fixed after. */
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

    /** Its tick: from the effect's {@code tick}, every tick. Takes a place in the atlas the first time one is free. */
    public void tick(float dt) {
        CgVfxGasAtlas atlas = CgVfxGasAtlas.get();
        if (index < 0) {
            index = atlas.acquire(this);
            if (index >= 0) {
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
        if (index >= 0 && boxed && frame.draws(effect, slot)) frame.volume(effect, slot, this);
    }

    /**
     * Whether it has thinned out: nothing poured for as long as {@link #DISSIPATION} takes a unit of smoke down to a
     * fiftieth, at most a minute. What an effect waits for before it dies.
     */
    public boolean faded() {
        float rate = Math.max(get(DISSIPATION), 0.01f);
        return quiet > Math.min(60f, (float) Math.log(1f / FADED) / rate);
    }

    /** Gives its place and tiles back now: it draws nothing from here, and a later tick takes a place again, empty. */
    public void release() {
        CgVfxGasAtlas.get().release(index);
        index = -1;
        boxed = false;
    }

    /** Its place among the atlas's gases: its page table's, -1 while it has none. */
    public int index() {
        return index;
    }

    public CgVfxSlot slot() {
        return slot;
    }

    /** Its middle in the world: valid while it holds a place in the atlas. */
    public double x() {
        return worldX;
    }

    public double y() {
        return worldY;
    }

    public double z() {
        return worldZ;
    }

    /** A cell's side in blocks in the atlas now: {@link #CELL} at the quality tier; 0 before its first place. */
    public float cellSize() {
        return cell;
    }

    /**
     * The box its tiles cover, from its middle in blocks, into {@code out}: lo xyz then hi xyz. False while it holds no
     * tile, when {@code out} is left alone.
     */
    public boolean bounds(float[] out) {
        if (!boxed) return false;
        System.arraycopy(box, 0, out, 0, 6);
        return true;
    }

    float cellSetting() {
        return get(CELL);
    }

    /** The atlas set its box: null for no tile. */
    void box(float[] lohi) {
        boxed = lohi != null;
        if (boxed) System.arraycopy(lohi, 0, box, 0, 6);
    }

    /** Asks the atlas for the tiles round each source pouring and each blast this frame, its place {@code b}. */
    void wantTiles(CgVfxGasAtlas atlas, int b, int now) {
        float ox = (float) (originX() - worldX), oy = (float) (originY() - worldY), oz = (float) (originZ() - worldZ);
        for (int i = 0; i < sourceCount; i++) {
            Source s = sources[i];
            if (s.pours()) atlas.wantSphere(b, ox + s.x, oy + s.y, oz + s.z, s.radius, now);
        }
        for (int i = 0; i < blastCount; i++) {
            int k = i * 6;
            atlas.wantSphere(b, ox + blasts[k], oy + blasts[k + 1], oz + blasts[k + 2], blasts[k + 3] * 1.5f, now);
        }
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

    float expansion() {
        return get(EXPANSION);
    }

    float smokeAmount() {
        return get(SMOKE_AMOUNT);
    }

    float turbulence() {
        return get(TURBULENCE);
    }

    float turbulenceSize() {
        return get(TURBULENCE_SIZE);
    }

    float disturbance() {
        return get(DISTURBANCE);
    }

    float disturbanceSize() {
        return get(DISTURBANCE_SIZE);
    }

    /** The atlas took its place back: its effect died or stopped ticking. */
    void lost() {
        index = -1;
        boxed = false;
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
     * Its sources pouring, or its blasts, into {@code out} from float {@code at}, three vec4s each, positions in its
     * grid's cells: {@code (place, centre)}, {@code (density, temperature, fuel, radius)} (a blast's carve in x) and
     * {@code (velocity, kind)}, kind 1 a source and 2 a blast (its speed in x). Answers the float after.
     */
    int writeSources(float[] out, int at, int b, boolean blastRows) {
        float half = CgVfxGasAtlas.SPAN * 0.5f;
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
