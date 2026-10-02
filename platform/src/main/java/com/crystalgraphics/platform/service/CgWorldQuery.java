package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgService;

/**
 * Answers small questions about the host's world at a block or a column: its collision, its fluid, its light, what it
 * is made of, its colours and its biome. What an effect needs to land debris on the ground, bounce it off a wall, stop a
 * beam, raise sand rather than snow, and tint dust like the ground it rose from. A host answers per block; {@code com.crystalgraphics.world.CgWorldQueries} composes the scans and the
 * raycast from these, once, for every host.
 *
 * <pre>{@code
 * CgWorldQuery world = CgPlatform.get(CgWorldQuery.SERVICE);
 * if (world.levelEpoch() != 0) {
 *     float top = world.collisionTop(x, y, z);        // NaN: nothing to stand on in this block
 *     if (!Float.isNaN(top)) floor = y + top;
 *     if (world.surface(x, y, z) == CgWorldQuery.SURFACE_SAND) puffSand();
 * }
 *
 * // a block's whole collision shape, as boxes within it: stairs, fences, walls
 * float[] boxes = new float[6 * 8];                    // once, and reused
 * int n = Math.min(world.collisionBoxes(x, y, z, boxes), 8);
 *
 * // a host, once, on a client only
 * CgPlatform.provide(CgWorldQuery.SERVICE, new WorldQueryModern());
 * }</pre>
 *
 * <ul>
 *   <li>Coordinates are absolute block coordinates: {@code Math.floor} of a world position, never camera-relative.</li>
 *   <li>Render thread only, where every host keeps its client level. A simulation on a worker reads a snapshot.</li>
 *   <li>{@link #NONE} answers NaN, false or 0: the harness, a dedicated server, a client with no level. An unloaded
 *       chunk answers the same as no level, so check {@link #loaded} before reading a missing floor as open air.</li>
 *   <li>The int answers ({@link #minY}, {@link #light}, ...) mean something only while {@link #levelEpoch} is not 0.</li>
 *   <li>Implementations allocate nothing and never throw: a block they cannot answer for answers as {@link #NONE}.</li>
 * </ul>
 */
public interface CgWorldQuery {

    int FLUID_NONE = 0, FLUID_WATER = 1, FLUID_LAVA = 2;

    /** What a block is made of, as its footstep sound says: what kind of dust, spray or sparks a hit on it raises. */
    int SURFACE_NONE = 0, SURFACE_STONE = 1, SURFACE_DIRT = 2, SURFACE_GRAVEL = 3, SURFACE_SAND = 4, SURFACE_SNOW = 5,
            SURFACE_ICE = 6, SURFACE_WOOD = 7, SURFACE_PLANT = 8, SURFACE_WOOL = 9, SURFACE_METAL = 10,
            SURFACE_GLASS = 11, SURFACE_OTHER = 12;

    /** A biome colour {@link #biomeColor} answers. */
    int BIOME_GRASS = 0, BIOME_FOLIAGE = 1, BIOME_WATER = 2, BIOME_FOG = 3, BIOME_SKY = 4, BIOME_WATER_FOG = 5;

    /** A column height {@link #surfaceY} answers: the top blocking movement (leaves too), without leaves, the sea floor. */
    int HEIGHT_TOP = 0, HEIGHT_NO_LEAVES = 1, HEIGHT_OCEAN_FLOOR = 2;

    /** What falls from the sky at a point when it rains there: {@link #precipitation}. */
    int PRECIPITATION_NONE = 0, PRECIPITATION_RAIN = 1, PRECIPITATION_SNOW = 2;

    /** No world: what a server, the harness or a client between levels reads. */
    CgWorldQuery NONE = new CgWorldQuery() {
        @Override public float collisionTop(int x, int y, int z) { return Float.NaN; }
        @Override public float collisionBottom(int x, int y, int z) { return Float.NaN; }
        @Override public int collisionBoxes(int x, int y, int z, float[] out) { return 0; }
        @Override public int surface(int x, int y, int z) { return SURFACE_NONE; }
        @Override public float hardness(int x, int y, int z) { return Float.NaN; }
        @Override public int lightEmission(int x, int y, int z) { return 0; }
        @Override public int tint(int x, int y, int z) { return 0; }
        @Override public int biomeColor(int x, int y, int z, int kind) { return 0; }
        @Override public int precipitation(int x, int y, int z) { return PRECIPITATION_NONE; }
        @Override public int surfaceY(int x, int z, int kind) { return Integer.MIN_VALUE; }
        @Override public boolean spriteRect(int x, int y, int z, float[] out) { return false; }
        @Override public float fluidHeight(int x, int y, int z) { return Float.NaN; }
        @Override public int fluidKind(int x, int y, int z) { return FLUID_NONE; }
        @Override public int light(int x, int y, int z) { return 0; }
        @Override public int mapColor(int x, int y, int z) { return 0; }
        @Override public boolean loaded(int x, int z) { return false; }
        @Override public int minY() { return 0; }
        @Override public int maxY() { return 0; }
        @Override public int seaLevel() { return 0; }
        @Override public int levelEpoch() { return 0; }
    };

    CgService<CgWorldQuery> SERVICE = CgService.of("crystalgraphics:world_query", NONE);

    /**
     * The top of the block's collision shape, 0 to 1 within the block: 1 for a full block, 0.5 for a bottom slab,
     * 0.125 for a layer of snow. NaN when nothing in the block stops movement.
     */
    float collisionTop(int x, int y, int z);

    /** The bottom of the block's collision shape, 0 to 1 within the block: 0.5 for a top slab. NaN as above. */
    float collisionBottom(int x, int y, int z);

    /**
     * The block's collision shape as boxes within it, 0 to 1 on each axis, into {@code out} as min x, y, z then max
     * x, y, z, six floats a box, as many as fit. Answers how many boxes there are, which may be more than fit; 0 for none.
     */
    int collisionBoxes(int x, int y, int z, float[] out);

    /** What the block is made of: a {@code SURFACE_} constant, {@link #SURFACE_NONE} for air. */
    int surface(int x, int y, int z);

    /** How long the block takes to break, as Minecraft rates it: 0 for instant, negative for unbreakable, NaN for air. */
    float hardness(int x, int y, int z);

    /** The light the block itself gives off, 0 to 15: 15 for glowstone and lava. */
    int lightEmission(int x, int y, int z);

    /** The colour the block's texture is tinted with here, ARGB: green on grass and leaves, blue on water; 0 for none. */
    int tint(int x, int y, int z);

    /** The biome's colour at the block, ARGB with alpha 255: a {@code BIOME_} constant names which. */
    int biomeColor(int x, int y, int z, int kind);

    /** What falls at the block when it rains: a {@code PRECIPITATION_} constant, from the biome and the height. */
    int precipitation(int x, int y, int z);

    /**
     * One past the top block of the column at block {@code (x, z)} by a {@code HEIGHT_} rule, from the host's own
     * heightmap; {@link Integer#MIN_VALUE} when the column is not loaded. O(1), for effects too large to scan.
     */
    int surfaceY(int x, int z, int kind);

    /**
     * The block's particle sprite as a rect in the host's block atlas ({@code CgHostFrame.textures().blockAtlas()}),
     * into {@code out} as u0, v0, u1, v1: what Minecraft's own break particles are cut from, the current frame of an
     * animated one. False, with {@code out} untouched, when the block has none.
     */
    boolean spriteRect(int x, int y, int z, float[] out);

    /** The fluid's surface within the block, 0 to 1; NaN when it holds none. */
    float fluidHeight(int x, int y, int z);

    /** {@link #FLUID_WATER}, {@link #FLUID_LAVA} or {@link #FLUID_NONE}. */
    int fluidKind(int x, int y, int z);

    /** The block's light, {@code block | sky << 4}, each 0 to 15: read with {@link #blockLight} and {@link #skyLight}. */
    int light(int x, int y, int z);

    /** The block's map colour as ARGB, alpha 255; 0 for air. What dust rising off it takes. */
    int mapColor(int x, int y, int z);

    /** Whether the column at block {@code (x, z)} is loaded on the client. */
    boolean loaded(int x, int z);

    /** The lowest block y the level holds. */
    int minY();

    /** One past the highest block y the level holds. */
    int maxY();

    int seaLevel();

    /**
     * Which client level this is: changes whenever the level object does (a dimension switch, a rejoin), and 0 when
     * there is none. Compare it to drop anything cached from another level.
     */
    int levelEpoch();

    static int blockLight(int light) {
        return light & 0xF;
    }

    static int skyLight(int light) {
        return light >> 4 & 0xF;
    }
}
