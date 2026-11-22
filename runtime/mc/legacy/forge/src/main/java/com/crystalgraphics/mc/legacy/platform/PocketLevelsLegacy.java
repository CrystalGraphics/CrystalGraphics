package com.crystalgraphics.mc.legacy.platform;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.block.CgBlocks;
import com.crystalgraphics.mc.legacy.platform.world.ClientLegacy;
import com.crystalgraphics.mc.level.CgBlockPos;
import com.crystalgraphics.mc.level.CgDirection;
import com.crystalgraphics.mc.render.CgHostPictures;
import com.crystalgraphics.mc.render.world.CgPocketLevel;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.render.host.CgHostLevel;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;

import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.ForgeHooksClient;
//? if <1.9 {
/*import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumWorldBlockLayer;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.biome.BiomeGenBase;
*///?} else {
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Biomes;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;
//?}
//? if >=1.12 {
import net.minecraft.client.renderer.BufferBuilder;
//?} elif <1.9 {
/*import net.minecraft.client.renderer.WorldRenderer;
*///?}

/**
 * {@link CgPocketLevel} on legacy Forge (1.8.9-1.12.2), client: the blocks behind an {@code IBlockAccess} of their
 * own, meshed as {@code RenderChunk.rebuildChunk} meshes them (each layer a block renders in, world positions
 * translated to the section) and drawn as {@code VboRenderList} draws a layer, in {@code renderWorldPass}'s layer state.
 * A pick hits each block's selection box, the box legacy's crosshair outlines; the block's own ray trace needs a world.
 */
public final class PocketLevelsLegacy implements CgPocketLevel.Host {

    // The layers in draw order, as their enum orders them: solid, cutout mipped, cutout, translucent
    private static final int TRANSLUCENT = 3;
    // The block format as VboRenderList points at it: position, colour, texture, lightmap
    private static final int STRIDE = 28;
    private static final int LAYER_BYTES = 262144;
    private static final float FULL_LIGHT = 240f;

    private final Matrix4f matrix = new Matrix4f();
    private final FloatBuffer buffer = BufferUtils.createFloatBuffer(16);

    /** The blocks, by an access of their own: full light and plains. */
    static final class Pocket implements IBlockAccess {
        final Map<Long, IBlockState> states = new HashMap<>();
        final Map<Long, TileEntity> tiles = new HashMap<>();
        final Map<Long, Section> sections = new HashMap<>();
        final Map<Object, View> views = new IdentityHashMap<>();
        final WorldClient world;
        int minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE;

        Pocket(WorldClient world) {
            this.world = world;
        }

        boolean holds(BlockPos pos) {
            return pos.getY() >= minY && pos.getY() <= maxY && states.containsKey(pos.toLong());
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            IBlockState s = pos.getY() < minY || pos.getY() > maxY ? null : states.get(pos.toLong());
            //? if <1.9 {
            /*return s == null ? Blocks.air.getDefaultState() : s;
            *///?} else {
            return s == null ? Blocks.AIR.getDefaultState() : s;
            //?}
        }

        @Nullable
        @Override
        public TileEntity getTileEntity(BlockPos pos) {
            return pos.getY() < minY || pos.getY() > maxY ? null : tiles.get(pos.toLong());
        }

        @Override
        public int getCombinedLight(BlockPos pos, int lightValue) {
            return 15 << 20 | 15 << 4;
        }

        @Override
        public boolean isAirBlock(BlockPos pos) {
            return !holds(pos);
        }

        //? if <1.9 {
        /*@Override
        public BiomeGenBase getBiomeGenForCoords(BlockPos pos) {
            return BiomeGenBase.plains;
        }

        @Override
        public boolean extendedLevelsInChunkCache() {
            return false;
        }
        *///?} else {
        @Override
        public Biome getBiome(BlockPos pos) {
            return Biomes.PLAINS;
        }
        //?}

        @Override
        public int getStrongPower(BlockPos pos, EnumFacing direction) {
            return 0;
        }

        @Override
        public WorldType getWorldType() {
            return WorldType.DEFAULT;
        }

        @Override
        public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
            if (!holds(pos)) return _default;
            //? if <1.9 {
            /*return getBlockState(pos).getBlock().isSideSolid(this, pos, side);
            *///?} else {
            return getBlockState(pos).isSideSolid(this, pos, side);
            //?}
        }
    }

    /** A 16³ section's buffers, one per layer it holds; rebuilt where a block in it changed. */
    static final class Section {
        final VertexBuffer[] layers = new VertexBuffer[TRANSLUCENT + 1];
        final List<TileEntity> tiles = new ArrayList<>();
        boolean dirty = true;

        void release() {
            for (int i = 0; i < layers.length; i++) {
                if (layers[i] != null) layers[i].deleteGlBuffers();
                layers[i] = null;
            }
        }
    }

    /** A view's framebuffer, remade on a new size. */
    static final class View {
        Framebuffer target;
        CgTexture2D colour;
        CgHostLevel.Drawn drawn;

        void release() {
            if (colour != null) CgHostPictures.forget(colour);
            if (target != null) {
                CgExternalTarget.forgetHost(target.framebufferObject);
                target.deleteFramebuffer();
            }
            target = null;
            colour = null;
            drawn = null;
        }
    }

    /** A layer's builder: Minecraft's, named for each era (1.10.2's shares its name with the VBO). */
    //? if >=1.12 {
    private static final class Builder extends BufferBuilder {
        Builder() {
            super(LAYER_BYTES);
        }
    }
    //?} elif >=1.9 {
    /*private static final class Builder extends net.minecraft.client.renderer.VertexBuffer {
        Builder() {
            super(LAYER_BYTES);
        }
    }
    *///?} else {
    /*private static final class Builder extends WorldRenderer {
        Builder() {
            super(LAYER_BYTES);
        }
    }
    *///?}

    @Override
    public String absent() {
        return OpenGlHelper.isFramebufferEnabled() ? null : "framebuffers are off (Fast Render or an old driver)";
    }

    @Override
    public Object create() {
        WorldClient world = ClientLegacy.world();
        if (world == null) throw new IllegalStateException("a pocket level needs a client world open: its tile entities live in it");
        return new Pocket(world);
    }

    @Override
    public void setBlock(Object level, int x, int y, int z, @Nullable CgBlockState state) {
        Pocket p = (Pocket) level;
        BlockPos pos = new BlockPos(x, y, z);
        long key = pos.toLong();
        p.tiles.remove(key);
        if (state == null) {
            p.states.remove(key);
        } else {
            IBlockState s = CgBlocks.toHost(state);
            p.states.put(key, s);
            if (s.getBlock() instanceof ITileEntityProvider) {
                // As HostWorldLegacy makes one: from the state and its data, then placed
                TileEntity tile = ((ITileEntityProvider) s.getBlock()).createNewTileEntity(p.world, s.getBlock().getMetaFromState(s));
                if (tile != null) {
                    if (state.hasBlockEntity()) tile.readFromNBT(CgNbt.toHost(state.blockEntity()));
                    //? if <1.10 {
                    /*tile.setWorldObj(p.world);
                    *///?} else {
                    tile.setWorld(p.world);
                    //?}
                    tile.setPos(pos);
                    p.tiles.put(key, tile);
                }
            }
        }
        // Its section and those whose faces and light it touches
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Section s = p.sections.get(sectionKey((x + dx) >> 4, (y + dy) >> 4, (z + dz) >> 4));
                    if (s == null) {
                        s = new Section();
                        p.sections.put(sectionKey((x + dx) >> 4, (y + dy) >> 4, (z + dz) >> 4), s);
                    }
                    s.dirty = true;
                }
            }
        }
    }

    @Override
    public CgHostLevel.Drawn draw(Object level, Object key, int width, int height, Matrix4fc view, double ox, double oy,
                                  double oz, Matrix4fc projection, float partialTick, Runnable components) {
        Pocket p = (Pocket) level;
        Minecraft mc = Minecraft.getMinecraft();
        View v = p.views.get(key);
        if (v == null) {
            v = new View();
            p.views.put(key, v);
        }
        if (v.target == null || v.target.framebufferWidth != width || v.target.framebufferHeight != height) {
            v.release();
            v.target = new Framebuffer(width, height, true);
            v.target.setFramebufferColor(0f, 0f, 0f, 0f);
            v.colour = (CgTexture2D) CgHostPictures.imported(v.target.framebufferTexture);
            v.drawn = new CgHostLevel.Drawn(CgExternalTarget.host(v.target.framebufferObject, width, height), v.colour);
        }
        mesh(p);
        v.target.framebufferClear();
        v.target.bindFramebuffer(true);
        // GL's own matrices: the projection loaded, the model-view each section's
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.multMatrix(load(matrix.set(projection)));
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        try {
            // renderWorldPass's terrain: smooth shading, the block atlas, the lightmap, alpha-tested cutouts
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.enableCull();
            GlStateManager.disableBlend();
            GlStateManager.shadeModel(GL11.GL_SMOOTH);
            RenderHelper.disableStandardItemLighting();
            //? if <1.9 {
            /*mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
            *///?} else {
            mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            //?}
            mc.entityRenderer.enableLightmap();
            GlStateManager.disableAlpha();
            drawLayer(p, 0, view, ox, oy, oz);
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
            drawLayer(p, 1, view, ox, oy, oz);
            drawLayer(p, 2, view, ox, oy, oz);
            GlStateManager.shadeModel(GL11.GL_FLAT);
            mc.entityRenderer.disableLightmap();

            drawTiles(p, view, ox, oy, oz, partialTick);
            components.run();

            // The translucent layer: blended, depth kept
            //? if <1.9 {
            /*mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
            *///?} else {
            mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            //?}
            mc.entityRenderer.enableLightmap();
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.shadeModel(GL11.GL_SMOOTH);
            drawLayer(p, TRANSLUCENT, view, ox, oy, oz);
        } finally {
            GlStateManager.shadeModel(GL11.GL_FLAT);
            GlStateManager.depthMask(true);
            GlStateManager.disableBlend();
            mc.entityRenderer.disableLightmap();
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
            mc.getFramebuffer().bindFramebuffer(true);
        }
        return v.drawn;
    }

    // RenderGlobal.renderBlockLayer then VboRenderList.renderChunkLayer: the block format's arrays on, each section's
    // buffer pointed at and drawn under the view and its offset
    private void drawLayer(Pocket p, int layer, Matrix4fc view, double ox, double oy, double oz) {
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        for (Map.Entry<Long, Section> e : p.sections.entrySet()) {
            VertexBuffer vb = e.getValue().layers[layer];
            if (vb == null) continue;
            long sk = e.getKey();
            GlStateManager.loadIdentity();
            GlStateManager.multMatrix(load(matrix.set(view).translate((float) ((sectionX(sk) << 4) - ox),
                    (float) ((sectionY(sk) << 4) - oy), (float) ((sectionZ(sk) << 4) - oz))));
            vb.bindBuffer();
            GL11.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, 0L);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, STRIDE, 12L);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, STRIDE, 16L);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glTexCoordPointer(2, GL11.GL_SHORT, STRIDE, 24L);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
            vb.drawArrays(GL11.GL_QUADS);
        }
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        GlStateManager.resetColor();
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
    }

    // Each tile entity by the dispatcher at its offset under the view, in full light: its own would be the client
    // world's at that block
    private void drawTiles(Pocket p, Matrix4fc view, double ox, double oy, double oz, float partialTick) {
        GlStateManager.loadIdentity();
        GlStateManager.multMatrix(load(matrix.set(view)));
        RenderHelper.enableStandardItemLighting();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, FULL_LIGHT, FULL_LIGHT);
        GlStateManager.color(1f, 1f, 1f, 1f);
        TileEntityRendererDispatcher tiles = TileEntityRendererDispatcher.instance;
        for (Section s : p.sections.values()) {
            for (TileEntity tile : s.tiles) {
                BlockPos pos = tile.getPos();
                //? if <1.12 {
                /*tiles.renderTileEntityAt(tile, pos.getX() - ox, pos.getY() - oy, pos.getZ() - oz, partialTick);
                *///?} else {
                tiles.render(tile, pos.getX() - ox, pos.getY() - oy, pos.getZ() - oz, partialTick);
                //?}
            }
        }
        RenderHelper.disableStandardItemLighting();
    }

    private FloatBuffer load(Matrix4fc m) {
        buffer.clear();
        m.get(buffer);
        return buffer;
    }

    // RenderChunk.rebuildChunk: each block in every layer it renders in, written at world positions translated to the
    // section; liquids render through renderBlock as any block does here
    private void mesh(Pocket p) {
        Set<Long> occupied = new HashSet<>();
        for (long key : p.states.keySet()) {
            BlockPos pos = BlockPos.fromLong(key);
            occupied.add(sectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
        }
        BlockRendererDispatcher blocks = Minecraft.getMinecraft().getBlockRendererDispatcher();
        Object[] layers = layers();
        for (Map.Entry<Long, Section> e : p.sections.entrySet()) {
            Section s = e.getValue();
            if (!s.dirty) continue;
            s.dirty = false;
            s.release();
            s.tiles.clear();
            if (!occupied.contains(e.getKey())) continue;
            long sk = e.getKey();
            int sx = sectionX(sk) << 4, sy = sectionY(sk) << 4, sz = sectionZ(sk) << 4;
            Builder[] builders = new Builder[layers.length];
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockPos pos = new BlockPos(sx + x, sy + y, sz + z);
                        if (!p.holds(pos)) continue;
                        IBlockState state = p.getBlockState(pos);
                        TileEntity tile = p.getTileEntity(pos);
                        if (tile != null) s.tiles.add(tile);
                        if (!rendered(state)) continue;
                        for (int i = 0; i < layers.length; i++) {
                            if (!inLayer(state, layers[i])) continue;
                            ForgeHooksClient.setRenderLayer(asLayer(layers[i]));
                            if (builders[i] == null) {
                                builders[i] = new Builder();
                                builders[i].begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                                builders[i].setTranslation(-sx, -sy, -sz);
                            }
                            blocks.renderBlock(state, pos, p, builders[i]);
                        }
                        ForgeHooksClient.setRenderLayer(null);
                    }
                }
            }
            for (int i = 0; i < builders.length; i++) {
                if (builders[i] == null) continue;
                builders[i].finishDrawing();
                VertexBuffer vb = new VertexBuffer(DefaultVertexFormats.BLOCK);
                vb.bufferData(builders[i].getByteBuffer());
                s.layers[i] = vb;
            }
        }
    }

    //? if >=1.9 {
    private static Object[] layers() {
        return BlockRenderLayer.values();
    }

    private static BlockRenderLayer asLayer(Object layer) {
        return (BlockRenderLayer) layer;
    }

    private static boolean inLayer(IBlockState state, Object layer) {
        return state.getBlock().canRenderInLayer(state, (BlockRenderLayer) layer);
    }

    private static boolean rendered(IBlockState state) {
        return state.getRenderType() != EnumBlockRenderType.INVISIBLE;
    }
    //?} else {
    /*private static Object[] layers() {
        return EnumWorldBlockLayer.values();
    }

    private static EnumWorldBlockLayer asLayer(Object layer) {
        return (EnumWorldBlockLayer) layer;
    }

    private static boolean inLayer(IBlockState state, Object layer) {
        return state.getBlock().canRenderInLayer((EnumWorldBlockLayer) layer);
    }

    private static boolean rendered(IBlockState state) {
        return state.getBlock().getRenderType() != -1;
    }
    *///?}

    @Override
    public void tick(Object level) {
        for (TileEntity tile : ((Pocket) level).tiles.values()) {
            if (tile instanceof ITickable) ((ITickable) tile).update();
        }
    }

    @Override
    public void visibleRange(Object level, int minY, int maxY) {
        Pocket p = (Pocket) level;
        int oldMin = p.minY, oldMax = p.maxY;
        p.minY = minY;
        p.maxY = maxY;
        for (Map.Entry<Long, Section> e : p.sections.entrySet()) {
            int y0 = sectionY(e.getKey()) << 4, y1 = y0 + 15;
            if (crosses(y0, y1, oldMin, minY) || crosses(y0, y1, oldMax, maxY)) e.getValue().dirty = true;
        }
    }

    // Whether a section from y0 to y1 lies within a block of the span between two bounds
    private static boolean crosses(int y0, int y1, int a, int b) {
        if (a == b) return false;
        long lo = Math.min((long) a, b) - 1, hi = Math.max((long) a, b) + 1;
        return y1 >= lo && y0 <= hi;
    }

    /**
     * The ray walked block by block (Amanatides and Woo, as {@code World.rayTraceBlocks} steps), each block met tested
     * against its selection box, the box legacy's crosshair outlines.
     */
    @Nullable
    @Override
    public CgPocketLevel.Hit pick(Object level, double ox, double oy, double oz, double dx, double dy, double dz,
                                  double reach) {
        Pocket p = (Pocket) level;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0) return null;
        double ux = dx / length, uy = dy / length, uz = dz / length;
        double ex = ox + ux * reach, ey = oy + uy * reach, ez = oz + uz * reach;
        int x = floor(ox), y = floor(oy), z = floor(oz);
        int stepX = ux > 0 ? 1 : -1, stepY = uy > 0 ? 1 : -1, stepZ = uz > 0 ? 1 : -1;
        double tx = boundary(ox, ux, x), ty = boundary(oy, uy, y), tz = boundary(oz, uz, z);
        double dtx = ux == 0 ? Double.MAX_VALUE : Math.abs(1 / ux), dty = uy == 0 ? Double.MAX_VALUE : Math.abs(1 / uy),
                dtz = uz == 0 ? Double.MAX_VALUE : Math.abs(1 / uz);
        for (double t = 0; t <= reach; ) {
            CgPocketLevel.Hit hit = test(p, new BlockPos(x, y, z), ox, oy, oz, ex, ey, ez);
            if (hit != null) return hit;
            if (tx <= ty && tx <= tz) {
                t = tx;
                tx += dtx;
                x += stepX;
            } else if (ty <= tz) {
                t = ty;
                ty += dty;
                y += stepY;
            } else {
                t = tz;
                tz += dtz;
                z += stepZ;
            }
        }
        return null;
    }

    @Override
    public boolean picksByShape() {
        return false;
    }

    // The distance along a unit ray to the first boundary it crosses on one axis from a block's corner
    private static double boundary(double from, double u, int block) {
        if (u > 0) return (block + 1 - from) / u;
        if (u < 0) return (from - block) / -u;
        return Double.MAX_VALUE;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    @Nullable
    private static CgPocketLevel.Hit test(Pocket p, BlockPos pos, double ox, double oy, double oz, double ex, double ey,
                                          double ez) {
        if (!p.holds(pos)) return null;
        IBlockState state = p.getBlockState(pos);
        //? if <1.9 {
        /*if (state.getBlock().getMaterial().isLiquid()) return null;
        state.getBlock().setBlockBoundsBasedOnState(p, pos);
        Block b = state.getBlock();
        AxisAlignedBB box = new AxisAlignedBB(pos.getX() + b.getBlockBoundsMinX(), pos.getY() + b.getBlockBoundsMinY(),
                pos.getZ() + b.getBlockBoundsMinZ(), pos.getX() + b.getBlockBoundsMaxX(), pos.getY() + b.getBlockBoundsMaxY(),
                pos.getZ() + b.getBlockBoundsMaxZ());
        MovingObjectPosition hit = box.calculateIntercept(new Vec3(ox, oy, oz), new Vec3(ex, ey, ez));
        if (hit == null) return null;
        double hx = hit.hitVec.xCoord, hy = hit.hitVec.yCoord, hz = hit.hitVec.zCoord;
        *///?} else {
        if (state.getMaterial().isLiquid()) return null;
        AxisAlignedBB box = state.getActualState(p, pos).getBoundingBox(p, pos).offset(pos);
        RayTraceResult hit = box.calculateIntercept(new Vec3d(ox, oy, oz), new Vec3d(ex, ey, ez));
        if (hit == null) return null;
        //? if <1.12 {
        /*double hx = hit.hitVec.xCoord, hy = hit.hitVec.yCoord, hz = hit.hitVec.zCoord;
        *///?} else {
        double hx = hit.hitVec.x, hy = hit.hitVec.y, hz = hit.hitVec.z;
        //?}
        //?}
        List<CgAABBc> outline = new ArrayList<>();
        outline.add(CgAABB.of(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
        return new CgPocketLevel.Hit(new CgBlockPos(pos.getX(), pos.getY(), pos.getZ()),
                CgDirection.valueOf(hit.sideHit.name()), hx, hy, hz, CgBlocks.of(state), outline);
    }

    @Override
    public void forget(Object level, Object key) {
        View v = ((Pocket) level).views.remove(key);
        if (v != null) v.release();
    }

    @Override
    public void close(Object level) {
        Pocket p = (Pocket) level;
        for (View v : p.views.values()) v.release();
        p.views.clear();
        for (Section s : p.sections.values()) s.release();
        p.sections.clear();
    }

    private static long sectionKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) << 42 | ((long) z & 0x3FFFFF) << 20 | (long) y & 0xFFFFF;
    }

    private static int sectionX(long key) {
        return (int) (key >> 42);
    }

    private static int sectionZ(long key) {
        return (int) (key << 22 >> 42);
    }

    private static int sectionY(long key) {
        return (int) (key << 44 >> 44);
    }
}
