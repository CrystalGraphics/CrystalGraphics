package com.crystalgraphics.vfx.fluid;

import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxElement;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;

/**
 * Smoke and fire as a fluid an effect owns: NVIDIA Flow's grid simulation (BSD-3-Clause, NVIDIA-Omniverse/PhysX
 * {@code flow/}) on the GPU, in {@link CgVfxFluidAtlas}'s sparse tiles about where it was placed. Its emitters couple
 * temperature, fuel, smoke and velocity toward their targets; fuel above its ignition temperature burns into heat and
 * smoke; heat rises; confinement keeps its curl; it flows round the world's blocks, and the sun and the sky light it
 * through itself. Its parameters are Flow's, by Flow's names, its lengths in blocks where Flow's are centimetres.
 *
 * <pre>{@code
 * public final class Campfire extends CgVfxEffect<Campfire> {
 *     private final CgVfxFluid fire = new CgVfxFluid(this).place(0f, 2f, 0f);       // its middle, from the origin
 *     private final CgVfxFluid.Emitter logs = fire.emitter(0f, 0.2f, 0f, 0.4f);    // Flow's sphere: hot fuel, rising
 *
 *     public Campfire(CgVfxLook look, double x, double y, double z) {
 *         super(look, x, y, z);
 *     }
 *
 *     @Override protected void tick(float dt) {
 *         fire.tick(dt);
 *         if (age > 30f) logs.off();
 *         if (fire.faded()) die();
 *     }
 *
 *     @Override protected void submit(CgVfxFrame frame) {
 *         fire.submit(frame);
 *     }
 * }
 *
 * static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).with(CgVfxFluid.FIRE).set(CgVfxFluid.CELL, 0.1f).build();
 * }</pre>
 *
 * <pre>{@code
 * fire.emitter(0f, 0f, 0f, 1f).temperature(0.8f, 4f).fuel(1f, 4f).noise(0.75f);   // Mantaflow's noisy inflow
 * smoke.emitter(0f, 0f, 0f, 0.5f).combustible(false).smoke(1f, 3f).temperature(0.3f, 2f);   // a smoke plume
 * fluid.blast(x, y, z, 4f, 25f, 1f);       // thrown out at 25 blocks a second, cleared within 4
 * CgVfxLook.builder(SCHEMA).with(CgVfxFluid.SMOKE).set(CgVfxFluid.COMBUSTION, 0f)...   // smoke that never burns
 * }</pre>
 *
 * <ul>
 *   <li>Positions are relative to the effect's origin; the fluid stays where it took its place, so a moving emitter
 *       trails through it.</li>
 *   <li>It reaches {@code CgVfxFluidAtlas.SPAN / 2} cells from where it was placed each way.</li>
 *   <li>Temperature is Flow's, normalized: 0 is the air's (no lift), 1 the hottest, burning fastest. The fire colour map
 *       is read over 0 to 1.</li>
 *   <li>Call {@link #tick} every effect tick: it steps on the effect's clock (Flow's 60 steps a second), and draws the
 *       time past its last step by carrying its density on (Flow's {@code interpolateTimeSteps}), so slow motion moves
 *       every frame.</li>
 *   <li>At most {@link CgVfxFluidAtlas#CAPACITY} fluids at once; one waiting for a place draws nothing.</li>
 * </ul>
 */
public final class CgVfxFluid extends CgVfxElement {

    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:fluid");
    /** A cell's side in blocks at High and Ultra, 1.5 times it at Medium, twice at Low: Flow's {@code densityCellSize}. */
    public static final CgVfxParam.Scalar CELL = SCHEMA.length("cell", 0.25f);

    // Flow's NvFlowAdvectionCombustionParams. Its world unit is a centimetre: a length, a speed or an acceleration here
    // is its default over 100.
    /** 1 to burn, 0 for smoke alone: Flow's {@code combustionEnabled}. */
    public static final CgVfxParam.Scalar COMBUSTION = SCHEMA.scalar("combustion", 1f);
    /** The least temperature fuel burns at. */
    public static final CgVfxParam.Scalar IGNITION_TEMP = SCHEMA.scalar("ignitionTemp", 0.05f);
    /** Burn a second per unit of temperature above ignition. */
    public static final CgVfxParam.Scalar BURN_PER_TEMP = SCHEMA.scalar("burnPerTemp", 4f);
    /** Fuel consumed per unit of burn: lower, a fuel lasts longer. */
    public static final CgVfxParam.Scalar FUEL_PER_BURN = SCHEMA.scalar("fuelPerBurn", 0.25f);
    /** Temperature gained per unit of burn. */
    public static final CgVfxParam.Scalar TEMP_PER_BURN = SCHEMA.scalar("tempPerBurn", 5f);
    /** Smoke made per unit of burn. */
    public static final CgVfxParam.Scalar SMOKE_PER_BURN = SCHEMA.scalar("smokePerBurn", 3f);
    /** Expansion per unit of burn, blocks a second: a fireball's burst. Flow's {@code divergencePerBurn}. */
    public static final CgVfxParam.Scalar DIVERGENCE_PER_BURN = SCHEMA.speed("divergencePerBurn", 0f);
    /** Lift per unit of temperature, and per unit of smoke up to its clamp, times {@link #GRAVITY}. */
    public static final CgVfxParam.Scalar BUOYANCY_PER_TEMP = SCHEMA.scalar("buoyancyPerTemp", 2f);
    public static final CgVfxParam.Scalar BUOYANCY_PER_SMOKE = SCHEMA.scalar("buoyancyPerSmoke", 0f);
    public static final CgVfxParam.Scalar BUOYANCY_MAX_SMOKE = SCHEMA.scalar("buoyancyMaxSmoke", 1f);
    /** The share of its temperature lost a second, applied per cell (not conduction). */
    public static final CgVfxParam.Scalar COOLING_RATE = SCHEMA.rate("coolingRate", 1.5f);
    /** Gravity's pull, blocks a second a second, down: Flow's 100 cm a second a second. */
    public static final CgVfxParam.Scalar GRAVITY = SCHEMA.scalar("gravity", 1f);

    // Flow's NvFlowAdvectionChannelParams, per channel: the share lost a second (damping) and the amount lost a second
    // (fade), the larger of the two each step.
    public static final CgVfxParam.Scalar VELOCITY_DAMPING = SCHEMA.rate("velocityDamping", 0.01f);
    /** Blocks a second lost a second: Flow's 1 cm. */
    public static final CgVfxParam.Scalar VELOCITY_FADE = SCHEMA.scalar("velocityFade", 0.01f);
    public static final CgVfxParam.Scalar TEMPERATURE_DAMPING = SCHEMA.rate("temperatureDamping", 0f);
    public static final CgVfxParam.Scalar TEMPERATURE_FADE = SCHEMA.scalar("temperatureFade", 0f);
    public static final CgVfxParam.Scalar FUEL_DAMPING = SCHEMA.rate("fuelDamping", 0f);
    public static final CgVfxParam.Scalar FUEL_FADE = SCHEMA.scalar("fuelFade", 0f);
    public static final CgVfxParam.Scalar SMOKE_DAMPING = SCHEMA.rate("smokeDamping", 0.3f);
    public static final CgVfxParam.Scalar SMOKE_FADE = SCHEMA.scalar("smokeFade", 0.65f);

    // Flow's NvFlowVorticityParams: the confinement's strength, and the masks it is weighted by.
    public static final CgVfxParam.Scalar VORTICITY = SCHEMA.scalar("vorticity", 0.6f);
    public static final CgVfxParam.Scalar VORTICITY_VELOCITY_MASK = SCHEMA.scalar("vorticityVelocityMask", 1f);
    public static final CgVfxParam.Scalar VORTICITY_VELOCITY_LOG_SCALE = SCHEMA.scalar("vorticityVelocityLogScale", 1f);
    public static final CgVfxParam.Scalar VORTICITY_VELOCITY_LINEAR_MASK = SCHEMA.scalar("vorticityVelocityLinearMask", 0f);
    public static final CgVfxParam.Scalar VORTICITY_CONSTANT_MASK = SCHEMA.scalar("vorticityConstantMask", 0f);
    public static final CgVfxParam.Scalar VORTICITY_TEMPERATURE_MASK = SCHEMA.scalar("vorticityTemperatureMask", 0f);
    public static final CgVfxParam.Scalar VORTICITY_FUEL_MASK = SCHEMA.scalar("vorticityFuelMask", 0f);
    public static final CgVfxParam.Scalar VORTICITY_BURN_MASK = SCHEMA.scalar("vorticityBurnMask", 0f);
    public static final CgVfxParam.Scalar VORTICITY_SMOKE_MASK = SCHEMA.scalar("vorticitySmokeMask", 0f);

    /**
     * Curl noise churning it where there is smoke, blocks a second a second, and its swirls' size: the engine's, not
     * Flow's, which has confinement alone.
     */
    public static final CgVfxParam.Scalar TURBULENCE = SCHEMA.scalar("turbulence", 0f);
    public static final CgVfxParam.Scalar TURBULENCE_SIZE = SCHEMA.length("turbulenceSize", 2f);

    // Flow's NvFlowShadowParams: the light through it, toward the sun.
    /** Light lost a block through whole smoke: Flow's 0.045 a centimetre. */
    public static final CgVfxParam.Scalar SHADOW_ATTENUATION = SCHEMA.scalar("shadowAttenuation", 4.5f);
    /** The least light a cell keeps however deep in smoke. */
    public static final CgVfxParam.Scalar SHADOW_MIN_INTENSITY = SCHEMA.scalar("shadowMinIntensity", 0.125f);

    // Flow's NvFlowRayMarchParams and the engine's light, read by the fluid layer.
    /** Opacity a block through whole smoke: Flow's ray march attenuation, 0.05 a centimetre. */
    public static final CgVfxParam.Scalar ATTENUATION = SCHEMA.scalar("attenuation", 5f);
    /** How much the light reaching a cell colours it, 0 to 1: Flow's {@code shadowFactor}. */
    public static final CgVfxParam.Scalar SHADOW_FACTOR = SCHEMA.scalar("shadowFactor", 1f);
    /** The temperatures the colour map spans: Flow's {@code colormapXMin} and {@code colormapXMax}. */
    public static final CgVfxParam.Scalar COLORMAP_MIN = SCHEMA.scalar("colormapMin", 0f);
    public static final CgVfxParam.Scalar COLORMAP_MAX = SCHEMA.scalar("colormapMax", 1f);
    /** Forward scattering, 0 to 0.9: the engine's phase, which Flow's colour map does not have. */
    public static final CgVfxParam.Scalar SCATTERING = SCHEMA.scalar("scattering", 0.6f);
    /**
     * Detail finer than its cells, 0 to 1, and its size: wavelet noise at Mantaflow's UV grids, carried with the flow
     * and reset every {@link #DETAIL_PERIOD} seconds, two grids half a period apart.
     */
    public static final CgVfxParam.Scalar DETAIL = SCHEMA.scalar("detail", 0.6f);
    public static final CgVfxParam.Scalar DETAIL_SIZE = SCHEMA.length("detailSize", 0.5f);
    public static final CgVfxParam.Scalar DETAIL_PERIOD = SCHEMA.time("detailPeriod", 2f);

    /** Where a fluid draws unless given a slot of its own. */
    public static final CgVfxSlot SLOT = CgVfxSlot.fluid("crystalgraphics:fluid");
    private static final String SHADER = "crystalgraphics:shaders/vfx/fluid/fluid.shader";
    /** Flow's smoke: its grey colour map, lit by the sun through itself and by the sky. */
    public static final CgVfxKit SMOKE = kit("crystalgraphics:fluid/smoke", CgVfxColormap.SMOKE);
    /** Flow's fire: its colour map from soot through orange to white-hot, glowing. */
    public static final CgVfxKit FIRE = kit("crystalgraphics:fluid/fire", CgVfxColormap.FIRE);

    /** The most emitters a fluid holds, and blasts at once. */
    public static final int MAX_EMITTERS = 8, MAX_BLASTS = 8;
    /** The most spheres {@link #wantTiles} keeps between two frames: the latest. */
    public static final int MAX_WANTED = 8;
    /** Seconds a blast's expansion lasts, then the step that stills it. */
    static final float BLAST_SECONDS = 1f / 30f, BLAST_SETTLE = 1f / 60f;
    /** The smoke it thins to before {@link #faded()}. */
    private static final float FADED = 0.02f;

    private final CgVfxSlot slot;
    private final Emitter[] emitters = new Emitter[MAX_EMITTERS];
    private int emitterCount;
    /** Blasts: where from the origin, radius, speed, carve, age; seven floats each. */
    private final float[] blasts = new float[MAX_BLASTS * BLAST];
    private int blastCount;
    private static final int BLAST = 7;
    private float placeX, placeY, placeZ;
    /** Its middle in the world once it holds a place, and its cell in blocks there. */
    double worldX, worldY, worldZ;
    float cell;
    private int index = -1;
    private final float[] box = new float[6];
    private boolean boxed;
    /** Its steps on its effect's clock, Flow's {@code TimeStepper}; the atlas frame of its last tick; seconds simulated. */
    final CgVfxTimeStepper stepper = new CgVfxTimeStepper().configure(60f, 2);
    float owed;
    long ticked;
    float clock;
    private float quiet;
    private final double[] wanted = new double[MAX_WANTED * 4];
    private int wantedCount, wantedNext;
    /** The smoke whose cells hold its smoke ({@link CgVfxSmoke}), else null. */
    CgVfxSmoke smoke;

    /** A fluid drawn on {@link #SLOT}. */
    public CgVfxFluid(CgVfxEffect<?> effect) {
        this(effect, SLOT);
    }

    public CgVfxFluid(CgVfxEffect<?> effect, CgVfxSlot slot) {
        super(effect);
        if (slot.kind() != CgVfxSlot.Kind.FLUID) throw new IllegalArgumentException(slot + " is not a fluid slot");
        this.slot = slot;
    }

    /**
     * The fluid layer on {@link #SLOT} under a colour map of its own: what {@link #SMOKE} and {@link #FIRE} are, over
     * Flow's two maps.
     *
     * <pre>{@code
     * static final CgVfxKit EMBERS = CgVfxFluid.kit("mymod:fluid/embers", MY_COLORMAP);
     * static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).with(EMBERS).build();
     * }</pre>
     */
    public static CgVfxKit kit(String name, CgVfxColormap colormap) {
        return CgVfxKit.of(name).params(SCHEMA)
                .layer(CgVfxLayer.of(SHADER).on(SLOT).role(CgVfxLayer.Role.SMOKE).texture("_Colormap", colormap::texture)
                        .bind("_Attenuation", ATTENUATION).bind("_ShadowFactor", SHADOW_FACTOR)
                        .bind("_ColormapMin", COLORMAP_MIN).bind("_ColormapMax", COLORMAP_MAX)
                        .bind("_Scattering", SCATTERING).bind("_Detail", DETAIL).bind("_DetailSize", DETAIL_SIZE).build())
                .build();
    }

    /** Where its middle goes, from the effect's origin: taken when it first gets a place, fixed after. */
    public CgVfxFluid place(float x, float y, float z) {
        placeX = x;
        placeY = y;
        placeZ = z;
        return this;
    }

    /**
     * A sphere of {@code radius} blocks round {@code (x, y, z)} from the effect's origin, coupling the fluid inside toward
     * Flow's sphere emitter's defaults: rising at 4 blocks a second, temperature 0.5, fuel 0.8, each at 2 a second; no
     * smoke, no burn. At most {@link #MAX_EMITTERS}.
     */
    public Emitter emitter(float x, float y, float z, float radius) {
        if (emitterCount == MAX_EMITTERS) throw new IllegalStateException("a fluid holds at most " + MAX_EMITTERS + " emitters");
        Emitter e = new Emitter().at(x, y, z).radius(radius);
        emitters[emitterCount++] = e;
        return e;
    }

    /**
     * A blast at {@code (x, y, z)} from the effect's origin: everything within {@code radius} blocks thrown out at
     * about {@code speed} blocks a second, through Flow's divergence channel, and {@code carve} (0 to 1) of its smoke and
     * fuel cleared there. At most {@link #MAX_BLASTS} at once; past that, dropped.
     */
    public void blast(float x, float y, float z, float radius, float speed, float carve) {
        quiet = 0f;
        if (blastCount == MAX_BLASTS) return;
        int at = blastCount++ * BLAST;
        blasts[at] = x;
        blasts[at + 1] = y;
        blasts[at + 2] = z;
        blasts[at + 3] = radius;
        blasts[at + 4] = speed;
        blasts[at + 5] = Math.max(0f, Math.min(1f, carve));
        blasts[at + 6] = 0f;
    }

    /** Its tick: from the effect's {@code tick}, every tick. Takes a place in the atlas the first time one is free. */
    public void tick(float dt) {
        CgVfxFluidAtlas atlas = CgVfxFluidAtlas.get();
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
        for (int i = 0; i < blastCount; i++) blasts[i * BLAST + 6] += dt;
        for (int i = blastCount - 1; i >= 0; i--) {
            if (blasts[i * BLAST + 6] < BLAST_SECONDS + BLAST_SETTLE) continue;
            System.arraycopy(blasts, (blastCount - 1) * BLAST, blasts, i * BLAST, BLAST);
            blastCount--;
        }
        boolean pouring = blastCount > 0;
        for (int i = 0; i < emitterCount; i++) pouring |= emitters[i].on;
        quiet = pouring ? 0f : quiet + dt;
    }

    /** Draws it on its slot: from the effect's {@code submit}. */
    public void submit(CgVfxFrame frame) {
        if (index >= 0 && boxed && frame.draws(effect, slot)) frame.fluid(effect, slot, this);
    }

    /**
     * Whether it has thinned out: nothing emitted for as long as its smoke's fade and damping take whole smoke to a
     * fiftieth, at most a minute. What an effect waits for before it dies.
     */
    public boolean faded() {
        float fade = get(SMOKE_FADE), damping = get(SMOKE_DAMPING);
        float byFade = fade > 0f ? 1f / fade : Float.MAX_VALUE;
        float byDamping = damping > 0f ? (float) Math.log(1f / FADED) / damping : Float.MAX_VALUE;
        return quiet > Math.min(60f, Math.min(byFade, byDamping));
    }

    /**
     * Asks for its tiles round {@code radius} blocks of the absolute point {@code (x, y, z)} this frame: where something
     * will pour into it. The engine asks round each emitter and blast, and round each particle instance feeding it.
     */
    public void wantTiles(double x, double y, double z, float radius) {
        int at = wantedNext * 4;
        wanted[at] = x;
        wanted[at + 1] = y;
        wanted[at + 2] = z;
        wanted[at + 3] = radius;
        wantedNext = (wantedNext + 1) % MAX_WANTED;
        wantedCount = Math.min(MAX_WANTED, wantedCount + 1);
    }

    /** Gives its place and tiles back now: it draws nothing from here, and a later tick takes a place again, empty. */
    public void release() {
        CgVfxFluidAtlas.get().release(index);
        lost();
    }

    /** Its place among the atlas's fluids, -1 while it has none. */
    public int index() {
        return index;
    }

    public CgVfxSlot slot() {
        return slot;
    }

    /** Its middle in the world: valid while it holds a place. */
    public double x() {
        return worldX;
    }

    public double y() {
        return worldY;
    }

    public double z() {
        return worldZ;
    }

    /** A cell's side in blocks in the atlas now; 0 before its first place. */
    public float cellSize() {
        return cell;
    }

    /** The box its tiles cover, from its middle in blocks, into {@code out}: lo xyz then hi xyz. False while it holds none. */
    public boolean bounds(float[] out) {
        if (!boxed) return false;
        System.arraycopy(box, 0, out, 0, 6);
        return true;
    }

    /**
     * Its first UV grid's share of the detail now, 0 to 1, the second's the rest: Mantaflow's {@code updateUvWeight}, two
     * grids half a period apart, at its clock carried on by the time past its last step.
     */
    public float detailWeight() {
        return detailWeight(clock + stepper.timeError(), get(DETAIL_PERIOD));
    }

    static float detailWeight(float time, float period) {
        float a = ramp(phase(time, period)), b = ramp(phase(time + period * 0.5f, period));
        return a + b <= 1e-6f ? 1f : a / (a + b);
    }

    /** Mantaflow's {@code computeUvGridTime}: where in its period a grid is. */
    static float phase(float time, float period) {
        float t = time / Math.max(period, 1e-3f);
        return t - (float) Math.floor(t);
    }

    /** Mantaflow's {@code computeUvRamp}: 0 at a reset, 1 half a period on. */
    private static float ramp(float phase) {
        float w = 2f * phase;
        return w > 1f ? 2f - w : w;
    }

    // ── What the atlas reads ─────────────────────────────────────────────────────────────────────────────────────────

    float cellSetting() {
        return get(CELL);
    }

    /** {@code param}'s value now: what the atlas writes into the fluid's rows. */
    float value(CgVfxParam.Scalar param) {
        return get(param);
    }

    void box(float[] lohi) {
        boxed = lohi != null;
        if (boxed) System.arraycopy(lohi, 0, box, 0, 6);
    }

    void lost() {
        index = -1;
        boxed = false;
    }

    CgVfxEffect<?> effect() {
        return effect;
    }

    /** Its floor's height from its middle: the effect's ground where it has one; far below, none. */
    float floor() {
        float ground = groundHeight();
        return Float.isNaN(ground) ? -1.0e9f : (float) (ground + originY() - worldY);
    }

    /** Asks the atlas for the tiles round each emitter on and each blast, its place {@code b}. */
    void wantTiles(CgVfxFluidAtlas atlas, int b, int now) {
        float ox = (float) (originX() - worldX), oy = (float) (originY() - worldY), oz = (float) (originZ() - worldZ);
        for (int i = 0; i < emitterCount; i++) {
            Emitter e = emitters[i];
            if (e.on) atlas.wantSphere(b, ox + e.x, oy + e.y, oz + e.z, e.reach(), now);
        }
        for (int i = 0; i < blastCount; i++) {
            int k = i * BLAST;
            atlas.wantSphere(b, ox + blasts[k], oy + blasts[k + 1], oz + blasts[k + 2], blasts[k + 3] * 1.5f, now);
        }
        for (int i = 0; i < wantedCount; i++) {
            int k = i * 4;
            atlas.wantSphere(b, (float) (wanted[k] - worldX), (float) (wanted[k + 1] - worldY), (float) (wanted[k + 2] - worldZ),
                    (float) wanted[k + 3], now);
        }
        wantedCount = 0;
        if (smoke != null && smoke.fill() != null) {
            CgVfxSmokeFill fill = smoke.fill();
            float c = CgVfxSmokeFill.CELL;
            atlas.wantSphere(b, (float) ((fill.originX() + 0.5) * c - worldX), (float) ((fill.originY() + 0.5) * c - worldY),
                    (float) ((fill.originZ() + 0.5) * c - worldZ), smoke.revealedRadius(), now);
        }
    }

    /** Its smoke's open carves into {@code out} from float {@code at}, as {@link CgVfxSmoke#writeCarves}; none without one. */
    int writeCarves(float[] out, int at, int b) {
        if (smoke == null) return at;
        return smoke.writeCarves(out, at, b, (float) (originX() - worldX), (float) (originY() - worldY),
                (float) (originZ() - worldZ), cell);
    }

    /** The emitter rows it writes: its emitters on and its blasts. */
    int emitterRows() {
        int n = blastCount;
        for (int i = 0; i < emitterCount; i++) if (emitters[i].on) n++;
        return n;
    }

    /**
     * Its emitters and blasts into {@code out} from float {@code at}, {@link CgVfxFluidAtlas#EMITTER} floats each, in its
     * grid's cells ({@code fluid.compute}'s EMITTERS). Answers the float after.
     */
    int writeEmitters(float[] out, int at, int b) {
        float half = CgVfxFluidAtlas.SPAN * 0.5f;
        float ox = (float) (originX() - worldX), oy = (float) (originY() - worldY), oz = (float) (originZ() - worldZ);
        for (int i = 0; i < emitterCount; i++) {
            Emitter e = emitters[i];
            if (!e.on) continue;
            at = e.write(out, at, b, (ox + e.x) / cell + half, (oy + e.y) / cell + half, (oz + e.z) / cell + half, cell);
        }
        for (int i = 0; i < blastCount; i++) {
            int k = i * BLAST;
            float r = blasts[k + 3] / cell, speed = blasts[k + 4], carve = blasts[k + 5];
            boolean settling = blasts[k + 6] >= BLAST_SECONDS;
            // Uniform divergence d through a sphere of r cells throws its surface out at d r / 3 a step's cell: Flow's
            // divergence is half the central differences, a cell's worth of the velocity's.
            float divergence = settling ? 0f : 3f * speed / Math.max(r, 1e-3f);
            at = CgVfxFluidAtlas.emitterRow(out, at, b, (ox + blasts[k]) / cell + half, (oy + blasts[k + 1]) / cell + half,
                    (oz + blasts[k + 2]) / cell + half, r, r, r, 0f,
                    0f, 0f, 0f, divergence, 0f, 0f, 0f, 0f,
                    0f, CgVfxFluidAtlas.INSTANT, 0f, settling ? 0f : carve * CgVfxFluidAtlas.INSTANT,
                    0f, settling ? 0f : carve * CgVfxFluidAtlas.INSTANT, -1f, 0f);
        }
        return at;
    }

    /**
     * Where a fluid is fed: a sphere or box coupling what is inside it toward its targets, each at its rate a second
     * (Flow's {@code coupleRate}: the share of the way there a second, so it holds a region at a value and never
     * overfills it). Move and change it from the effect's tick.
     *
     * <pre>{@code
     * vent.at(0f, 0.5f, 0f).velocity(0f, 6f, 0f, 3f).smoke(1f, 2f);
     * vent.off();                       // and on() again
     * }</pre>
     */
    public static final class Emitter {
        private static final int SPHERE = 0, BOX = 1, CYLINDER = 2;
        float x, y, z, radius = 0.1f, hx, hy, hz;
        int shape = SPHERE;
        boolean on = true;
        float vx, vy = 4f, vz, divergence, temperature = 0.5f, fuel = 0.8f, burn, smoke;
        float coupleVelocity = 2f, coupleDivergence, coupleTemperature = 2f, coupleFuel = 2f, coupleBurn, coupleSmoke;
        float noise = -1f;

        private Emitter() {
        }

        /** Its middle, from the effect's origin. */
        public Emitter at(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        /** A sphere this many blocks round. */
        public Emitter radius(float blocks) {
            radius = Math.max(blocks, 0.01f);
            shape = SPHERE;
            return this;
        }

        /** A box this many blocks from its middle each way: Flow's box emitter. */
        public Emitter box(float halfX, float halfY, float halfZ) {
            hx = halfX;
            hy = halfY;
            hz = halfZ;
            shape = BOX;
            return this;
        }

        /** An upright cylinder, {@code radius} blocks round and {@code halfHeight} up and down: Mantaflow's Cylinder. */
        public Emitter cylinder(float radius, float halfHeight) {
            this.radius = Math.max(radius, 0.01f);
            hy = Math.max(halfHeight, 0.01f);
            shape = CYLINDER;
            return this;
        }

        /** The velocity it pulls toward, blocks a second, and how fast. */
        public Emitter velocity(float vx, float vy, float vz, float couple) {
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            coupleVelocity = couple;
            return this;
        }

        /** The expansion it pulls toward, blocks a second, and how fast: a burst without fire. */
        public Emitter divergence(float target, float couple) {
            divergence = target;
            coupleDivergence = couple;
            return this;
        }

        public Emitter temperature(float target, float couple) {
            temperature = target;
            coupleTemperature = couple;
            return this;
        }

        public Emitter fuel(float target, float couple) {
            fuel = target;
            coupleFuel = couple;
            return this;
        }

        public Emitter burn(float target, float couple) {
            burn = target;
            coupleBurn = couple;
            return this;
        }

        public Emitter smoke(float target, float couple) {
            smoke = target;
            coupleSmoke = couple;
            return this;
        }

        /** No fuel: what it emits only rises and thins. */
        public Emitter combustible(boolean fuels) {
            if (!fuels) coupleFuel = 0f;
            return this;
        }

        /**
         * Mantaflow's {@code densityInflow}: each of temperature, fuel, burn and smoke it couples raised to its target
         * times wavelet noise plus {@code offset} (held to 0..1) where below it, never lowered, the couple rates unused
         * but for which channels it touches. Its scenes use 0.75.
         */
        public Emitter noise(float offset) {
            noise = Math.max(0f, offset);
            return this;
        }

        public Emitter on() {
            on = true;
            return this;
        }

        public Emitter off() {
            on = false;
            return this;
        }

        public boolean isOn() {
            return on;
        }

        /** Blocks from its middle to its farthest point. */
        float reach() {
            switch (shape) {
                case BOX: return (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                case CYLINDER: return (float) Math.sqrt(radius * radius + hy * hy);
                default: return radius;
            }
        }

        int write(float[] out, int at, int b, float cx, float cy, float cz, float cell) {
            float ex = shape == BOX ? hx / cell : shape == SPHERE ? radius / cell : 0f;
            float ey = shape == SPHERE ? 0f : hy / cell, ez = shape == BOX ? hz / cell : 0f;
            return CgVfxFluidAtlas.emitterRow(out, at, b, cx, cy, cz, ex, ey, ez, shape,
                    vx, vy, vz, divergence, temperature, fuel, burn, smoke,
                    coupleVelocity, coupleDivergence, coupleTemperature, coupleFuel, coupleBurn, coupleSmoke, noise,
                    shape == CYLINDER ? radius / cell : 0f);
        }
    }
}
