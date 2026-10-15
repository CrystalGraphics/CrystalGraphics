package com.crystalgraphics.vfx.gas;

import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxElement;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;

import java.nio.ByteBuffer;

/**
 * A smoke grenade's cloud as Counter-Strike 2 makes one: the cells a {@link CgVfxSmokeFill} floods from where it lands,
 * revealed outward over {@link #SPREAD}, held for {@link #DURATION}, then thinning away over {@link #FADE}; shots and
 * blasts carve it open and it heals. A gas fills the cells and gives them motion: its density is held to the cells
 * revealed, drained outside them, and churned by the gas's look. What it hides is gameplay, so {@link #blocksSight}
 * answers the same on the server and on every client.
 *
 * <pre>{@code
 * private final CgVfxSmoke smoke = new CgVfxSmoke(this);
 *
 * @Override protected void tick(float dt) {
 *     if (smoke.fill() == null) smoke.flood();      // the effect's world, from its origin; or fill(cells) from a cue
 *     smoke.tick(dt);
 *     if (smoke.done()) die();
 * }
 *
 * @Override protected void submit(CgVfxFrame frame) {
 *     smoke.submit(frame);
 * }
 * }</pre>
 *
 * <pre>{@code
 * smoke.shot(eyeX, eyeY, eyeZ, hitX, hitY, hitZ);    // a bullet's hole, healed in a second and a half
 * smoke.blast(x, y, z, 4f);                         // a grenade's: cleared and thrown out, healed over five
 * if (smoke.blocksSight(eyeX, eyeY, eyeZ, targetX, targetY, targetZ)) hidden();
 * }</pre>
 *
 * <ul>
 *   <li>Its look carries {@link #SCHEMA} and {@link CgVfxGas#SCHEMA}: the gas's {@code CELL} at {@link CgVfxSmokeFill#CELL}
 *       draws the cells as they are; {@code DISSIPATION} 0, since the cells hold its density.</li>
 *   <li>Positions here are absolute world coordinates, as gameplay holds them.</li>
 *   <li>A server's copy (an authoritative cue's) runs no gas: it floods, carves and answers sight alone.</li>
 *   <li>At most {@link #MAX_CARVES} carves at once: a new one takes the oldest's place.</li>
 * </ul>
 */
public final class CgVfxSmoke extends CgVfxElement {

    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:smoke");
    /** Seconds it takes to spread to its last cell, fast at first, as a grenade's burst slows. */
    public static final CgVfxParam.Scalar SPREAD = SCHEMA.time("spread", 1.6f);
    /** Seconds it holds once spread, then the seconds it thins away over. */
    public static final CgVfxParam.Scalar DURATION = SCHEMA.time("duration", 18f);
    public static final CgVfxParam.Scalar FADE = SCHEMA.time("fade", 2.5f);
    /** The most cells it fills, and the farthest it spreads across, blocks. */
    public static final CgVfxParam.Scalar CELLS = SCHEMA.scalar("cells", 2600f);
    public static final CgVfxParam.Scalar REACH = SCHEMA.length("reach", 8f);
    /** The density its gas is held at in its cells, and how fast it is pulled there, a second. */
    public static final CgVfxParam.Scalar DENSITY = SCHEMA.scalar("density", 2.5f);
    public static final CgVfxParam.Scalar HOLD = SCHEMA.rate("hold", 3f);
    /** Blocks of whole smoke a line of sight crosses before it is hidden. */
    public static final CgVfxParam.Scalar SIGHT = SCHEMA.length("sight", 1.5f);

    /** The most carves at once. */
    public static final int MAX_CARVES = 16;
    /** A shot's hole: its radius in blocks and the seconds it heals over. */
    public static final float SHOT_RADIUS = 0.35f, SHOT_HEAL = 1.5f;
    /** A blast's healing: CS2's {@code pow(smoothstep(0.5, 5, age), 1.8)}. */
    private static final float BLAST_HEAL_FROM = 0.5f, BLAST_HEAL_TO = 5f, BLAST_HEAL_POWER = 1.8f;
    /** Floats a carve: its ends from the effect's origin, its radius, when, and whether a blast. */
    private static final int CARVE = 9;
    /** The cost over which it thickens inward from the reveal's front: three cells, as gas.compute's G_FILL_EDGE. */
    static final float EDGE = 3 * CgVfxSmokeFill.ACROSS;

    private final CgVfxGas gas;
    private CgVfxSmokeFill fill;
    private float time;
    private final float[] carves = new float[MAX_CARVES * CARVE];
    private int carveCount, carveNext, carveRevision;

    /** A smoke drawn on {@link CgVfxGas#SLOT}. */
    public CgVfxSmoke(CgVfxEffect<?> effect) {
        this(effect, CgVfxGas.SLOT);
    }

    public CgVfxSmoke(CgVfxEffect<?> effect, CgVfxSlot slot) {
        super(effect);
        gas = new CgVfxGas(effect, slot);
        gas.smoke = this;
    }

    /** Floods its cells from the effect's origin through the effect's world, standing on its ground where it has one. */
    public CgVfxSmoke flood() {
        float ground = groundHeight();
        double floor = Float.isNaN(ground) ? Double.NaN : ground + originY();
        CgVfxSmokeFill.Solids solids = CgVfxSmokeFill.blocks(world().blocks(), (float) floor);
        return fill(CgVfxSmokeFill.flood(solids, originX(), originY() + CgVfxSmokeFill.CELL * 0.5, originZ(),
                Math.round(get(CELLS)), get(REACH)));
    }

    /** Takes {@code cells} as its own: a server's, from a cue. */
    public CgVfxSmoke fill(CgVfxSmokeFill cells) {
        fill = cells;
        return this;
    }

    /** Its cells; null before {@link #flood} or {@link #fill}. */
    public CgVfxSmokeFill fill() {
        return fill;
    }

    /** The gas that moves in its cells. */
    public CgVfxGas gas() {
        return gas;
    }

    /** Its tick: from the effect's, every tick, once it has cells. */
    public void tick(float dt) {
        if (fill == null) return;
        if (time == 0f && !authoritative()) gas.blast(0f, 0.5f, 0f, 2.5f, 5f, 0f);   // the burst it lands with
        time += dt;
        if (!authoritative()) gas.tick(dt);
    }

    /** Draws it: from the effect's {@code submit}. */
    public void submit(CgVfxFrame frame) {
        if (fill != null) gas.submit(frame);
    }

    /** Whether it has spread, held and thinned away: what its effect dies after. */
    public boolean done() {
        return fill != null && time > get(DURATION) + get(FADE);
    }

    /** Seconds since it landed. */
    public float time() {
        return time;
    }

    /** The cost its cells are revealed to now: the fill's dearest once {@link #SPREAD} has passed. */
    public float revealed() {
        if (fill == null) return -1f;
        float t = Math.min(1f, time / Math.max(get(SPREAD), 1e-3f));
        return fill.mostCost() * (float) Math.sqrt(t) + 1f;
    }

    /** How much of it is left, 1 until {@link #DURATION}, then thinning to 0 over {@link #FADE}. */
    public float presence() {
        float past = time - get(DURATION);
        return past <= 0f ? 1f : Math.max(0f, 1f - past / Math.max(get(FADE), 1e-3f));
    }

    /**
     * A shot through it from {@code (ax, ay, az)} to {@code (bx, by, bz)}: a hole {@link #SHOT_RADIUS} blocks round the
     * line, healing over {@link #SHOT_HEAL} seconds.
     */
    public void shot(double ax, double ay, double az, double bx, double by, double bz) {
        carve(ax, ay, az, bx, by, bz, SHOT_RADIUS, false);
    }

    /** A blast at {@code (x, y, z)}: its cells within {@code radius} cleared and healing over five seconds, its gas thrown out. */
    public void blast(double x, double y, double z, float radius) {
        carve(x, y, z, x, y, z, radius, true);
        if (!authoritative()) {
            gas.blast((float) (x - originX()), (float) (y - originY()), (float) (z - originZ()), radius * 1.3f, 30f, 1f);
        }
    }

    private void carve(double ax, double ay, double az, double bx, double by, double bz, float radius, boolean blast) {
        int at = carveNext * CARVE;
        carves[at] = (float) (ax - originX());
        carves[at + 1] = (float) (ay - originY());
        carves[at + 2] = (float) (az - originZ());
        carves[at + 3] = (float) (bx - originX());
        carves[at + 4] = (float) (by - originY());
        carves[at + 5] = (float) (bz - originZ());
        carves[at + 6] = radius;
        carves[at + 7] = time;
        carves[at + 8] = blast ? 1f : 0f;
        carveNext = (carveNext + 1) % MAX_CARVES;
        carveCount = Math.min(MAX_CARVES, carveCount + 1);
        carveRevision++;
    }

    /** How much of carve {@code k} is still open, 1 when made, 0 healed. */
    private float open(int k) {
        int at = k * CARVE;
        float age = time - carves[at + 7];
        if (carves[at + 8] == 0f) return Math.max(0f, 1f - age / SHOT_HEAL);
        float t = Math.max(0f, Math.min(1f, (age - BLAST_HEAL_FROM) / (BLAST_HEAL_TO - BLAST_HEAL_FROM)));
        return 1f - (float) Math.pow(t * t * (3f - 2f * t), BLAST_HEAL_POWER);
    }

    /** How much of it the carves leave at {@code (x, y, z)} from the effect's origin, 0 to 1. */
    private float uncarved(float x, float y, float z) {
        float left = 1f;
        for (int k = 0; k < carveCount; k++) {
            float open = open(k);
            if (open <= 0f) continue;
            int at = k * CARVE;
            float r = carves[at + 6];
            float d = segmentDistance(x, y, z, carves[at], carves[at + 1], carves[at + 2], carves[at + 3], carves[at + 4], carves[at + 5]);
            float t = Math.max(0f, Math.min(1f, (d - r * 0.6f) / (r * 0.4f)));
            left *= 1f - open * (1f - t * t * (3f - 2f * t));
        }
        return left;
    }

    /**
     * Blocks of whole smoke between two points, through its cells as revealed now, less its carves and its thinning:
     * what {@link #blocksSight} compares with {@link #SIGHT}. Absolute world coordinates.
     */
    public float smokeBetween(double ax, double ay, double az, double bx, double by, double bz) {
        if (fill == null || fill.count() == 0) return 0f;
        float presence = presence(), reveal = revealed();
        if (presence <= 0f) return 0f;
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-6) return 0f;
        // Amanatides and Woo's traversal of the cells the segment crosses.
        double c = CgVfxSmokeFill.CELL;
        int x = (int) Math.floor(ax / c), y = (int) Math.floor(ay / c), z = (int) Math.floor(az / c);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double ux = dx / length, uy = dy / length, uz = dz / length;
        double tx = ux == 0 ? Double.POSITIVE_INFINITY : ((sx > 0 ? x + 1 : x) * c - ax) / ux;
        double ty = uy == 0 ? Double.POSITIVE_INFINITY : ((sy > 0 ? y + 1 : y) * c - ay) / uy;
        double tz = uz == 0 ? Double.POSITIVE_INFINITY : ((sz > 0 ? z + 1 : z) * c - az) / uz;
        double stepX = ux == 0 ? Double.POSITIVE_INFINITY : c / Math.abs(ux);
        double stepY = uy == 0 ? Double.POSITIVE_INFINITY : c / Math.abs(uy);
        double stepZ = uz == 0 ? Double.POSITIVE_INFINITY : c / Math.abs(uz);
        double at = 0, smoke = 0;
        while (at < length) {
            double next = Math.min(Math.min(tx, ty), Math.min(tz, length));
            int cost = fill.cost(x, y, z);
            float thick = cost < 0 ? 0f : Math.max(0f, Math.min(1f, (reveal - cost) / EDGE));
            if (thick > 0f) {
                double mid = (at + next) * 0.5;
                float w = uncarved((float) (ax + ux * mid - originX()), (float) (ay + uy * mid - originY()),
                        (float) (az + uz * mid - originZ()));
                smoke += (next - at) * w * thick * thick * (3f - 2f * thick);
            }
            at = next;
            if (tx <= ty && tx <= tz) {
                x += sx;
                tx += stepX;
            } else if (ty <= tz) {
                y += sy;
                ty += stepY;
            } else {
                z += sz;
                tz += stepZ;
            }
        }
        return (float) (smoke * presence);
    }

    /** Whether it hides {@code (bx, by, bz)} from {@code (ax, ay, az)}: {@link #SIGHT} blocks of smoke or more between. */
    public boolean blocksSight(double ax, double ay, double az, double bx, double by, double bz) {
        return smokeBetween(ax, ay, az, bx, by, bz) >= get(SIGHT);
    }

    /** Changes each time a carve is made: what a server publishes its carves by. */
    public int carveRevision() {
        return carveRevision;
    }

    /** Its carves as bytes for the wire. */
    public byte[] encodeCarves() {
        ByteBuffer out = ByteBuffer.allocate(8 + carveCount * CARVE * 4);
        out.putInt(carveRevision).putInt(carveCount);
        for (int k = 0; k < carveCount; k++) for (int f = 0; f < CARVE; f++) out.putFloat(carves[k * CARVE + f]);
        return out.array();
    }

    /** Takes the carves {@link #encodeCarves} wrote: a server's, from a cue. */
    public void carves(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return;
        ByteBuffer in = ByteBuffer.wrap(bytes);
        int revision = in.getInt(), n = in.getInt();
        if (n < 0 || n > MAX_CARVES || bytes.length != 8 + n * CARVE * 4 || revision == carveRevision) return;
        for (int i = 0; i < n * CARVE; i++) carves[i] = in.getFloat();
        carveCount = n;
        carveNext = n % MAX_CARVES;
        carveRevision = revision;
    }

    private static float segmentDistance(float px, float py, float pz, float ax, float ay, float az, float bx, float by, float bz) {
        float abx = bx - ax, aby = by - ay, abz = bz - az;
        float apx = px - ax, apy = py - ay, apz = pz - az;
        float ab = abx * abx + aby * aby + abz * abz;
        float t = ab > 0f ? Math.max(0f, Math.min(1f, (apx * abx + apy * aby + apz * abz) / ab)) : 0f;
        float qx = apx - abx * t, qy = apy - aby * t, qz = apz - abz * t;
        return (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
    }

    // ── What its gas reads, through the atlas ────────────────────────────────────────────────────────────────────────

    /** Carves still open: rows of the gas's sources. */
    int carveRows() {
        int n = 0;
        for (int k = 0; k < carveCount; k++) if (open(k) > 0f) n++;
        return n;
    }

    /**
     * Its open carves into {@code out} from float {@code at}, three vec4s each in gas {@code b}'s cells: (place, one
     * end), (how open, 0, 0, radius), (the other end, kind 3). Answers the float after.
     */
    int writeCarves(float[] out, int at, int b, float ox, float oy, float oz, float cell) {
        float half = CgVfxGasAtlas.SPAN * 0.5f;
        for (int k = 0; k < carveCount; k++) {
            float open = open(k);
            if (open <= 0f) continue;
            int c = k * CARVE;
            out[at] = b;
            out[at + 1] = (ox + carves[c]) / cell + half;
            out[at + 2] = (oy + carves[c + 1]) / cell + half;
            out[at + 3] = (oz + carves[c + 2]) / cell + half;
            out[at + 4] = open;
            out[at + 5] = 0f;
            out[at + 6] = 0f;
            out[at + 7] = carves[c + 6] / cell;
            out[at + 8] = (ox + carves[c + 3]) / cell + half;
            out[at + 9] = (oy + carves[c + 4]) / cell + half;
            out[at + 10] = (oz + carves[c + 5]) / cell + half;
            out[at + 11] = 3f;
            at += 12;
        }
        return at;
    }

    /** Blocks from its first cell's middle out to the farthest cell revealed now: the tiles its gas asks for. */
    float revealedRadius() {
        if (fill == null) return 0f;
        float reveal = revealed(), most = 0f;
        for (int i = 0; i < fill.count(); i++) {
            if (fill.cost(i) > reveal) break;
            int x = fill.offsetX(i), y = fill.offsetY(i), z = fill.offsetZ(i);
            most = Math.max(most, x * x + y * y + z * z);
        }
        return ((float) Math.sqrt(most) + 1f) * CgVfxSmokeFill.CELL;
    }

    float density() {
        return get(DENSITY);
    }

    float hold() {
        return get(HOLD);
    }
}
