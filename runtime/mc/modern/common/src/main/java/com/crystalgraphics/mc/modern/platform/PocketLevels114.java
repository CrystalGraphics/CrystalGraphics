package com.crystalgraphics.mc.modern.platform;

//? if >=1.14.3 <1.15 {
/*import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.block.CgBlocks;
import com.crystalgraphics.mc.level.CgBlockPos;
import com.crystalgraphics.mc.level.CgDirection;
import com.crystalgraphics.mc.render.CgHostPictures;
import com.crystalgraphics.mc.render.world.CgPocketLevel;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.render.host.CgHostLevel;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerLevel;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndBiomeGetter;
import net.minecraft.world.level.BlockLayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickableBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/^*
 * {@link CgPocketLevel} on 1.14.3-1.14.4, client. Before the pose stack, so it ports 1.14's own terrain path: blocks
 * meshed as {@code RenderChunk.compile} meshes them (each block's layer, world positions offset to its section), a layer
 * drawn as {@code VboRenderList} draws one (the block format's client arrays, each section's buffer under GL's
 * model-view), in {@code GameRenderer.renderLevel}'s layer state. Placing, picking, ticking and the visible range are
 * {@link PocketLevelsModern}'s.
 ^/
public final class PocketLevels114 implements CgPocketLevel.Host {

    private static final BlockLayer[] OPAQUE = {BlockLayer.SOLID, BlockLayer.CUTOUT_MIPPED, BlockLayer.CUTOUT};
    // The block format as VboRenderList points at it: position, colour, texture, lightmap
    private static final int STRIDE = 28;
    private static final int LAYER_BYTES = 262144;
    private static final float FULL_LIGHT = 240f;

    private final Random random = new Random();
    private final Matrix4f matrix = new Matrix4f();
    private final FloatBuffer buffer = BufferUtils.createFloatBuffer(16);

    /^* The blocks, by a getter of their own; full light and plains, as {@link PocketLevelsModern}'s. ^/
    static final class Pocket implements BlockAndBiomeGetter {
        final Map<Long, BlockState> states = new HashMap<>();
        final Map<Long, BlockEntity> blockEntities = new HashMap<>();
        final Map<Long, Section> sections = new HashMap<>();
        final Map<Object, View> views = new IdentityHashMap<>();
        final MultiPlayerLevel world;
        int minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE;

        Pocket(MultiPlayerLevel world) {
            this.world = world;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (pos.getY() < minY || pos.getY() > maxY) return Blocks.AIR.defaultBlockState();
            BlockState s = states.get(pos.asLong());
            return s == null ? Blocks.AIR.defaultBlockState() : s;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            if (pos.getY() < minY || pos.getY() > maxY) return null;
            return blockEntities.get(pos.asLong());
        }

        @Override
        public Biome getBiome(BlockPos pos) {
            return Biomes.PLAINS;
        }

        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            return 15;
        }
    }

    /^* A 16³ section's buffers, one per layer it holds; rebuilt where a block in it changed. ^/
    static final class Section {
        final Map<BlockLayer, VertexBuffer> layers = new EnumMap<>(BlockLayer.class);
        final List<BlockEntity> blockEntities = new ArrayList<>();
        boolean dirty = true;

        void release() {
            for (VertexBuffer vb : layers.values()) vb.delete();
            layers.clear();
        }
    }

    /^* A view's target, remade on a new size. ^/
    static final class View {
        RenderTarget target;
        CgTexture2D colour;
        CgHostLevel.Drawn drawn;

        void release() {
            if (colour != null) CgHostPictures.forget(colour);
            if (target != null) {
                CgExternalTarget.forgetHost(target.frameBufferId);
                PicturesModern.destroy(target);
            }
            target = null;
            colour = null;
            drawn = null;
        }
    }

    @Override
    public String absent() {
        return null;
    }

    @Override
    public Object create() {
        MultiPlayerLevel world = Minecraft.getInstance().level;
        if (world == null) throw new IllegalStateException("a pocket level needs a client world open: its registries shape it");
        return new Pocket(world);
    }

    @Override
    public void setBlock(Object level, int x, int y, int z, @Nullable CgBlockState state) {
        Pocket p = (Pocket) level;
        BlockPos pos = new BlockPos(x, y, z);
        long key = pos.asLong();
        p.blockEntities.remove(key);
        if (state == null) {
            p.states.remove(key);
        } else {
            BlockState s = CgBlocks.toHost(state);
            p.states.put(key, s);
            if (s.getBlock() instanceof EntityBlock) {
                BlockEntity be = ((EntityBlock) s.getBlock()).newBlockEntity(p);
                if (be != null) {
                    be.setLevel(p.world);
                    be.setPosition(pos);
                    if (state.hasBlockEntity()) be.load(CgNbt.toHost(state.blockEntity()));
                    p.blockEntities.put(key, be);
                }
            }
        }
        // Its section and those whose faces and light it touches
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Section s = p.sections.computeIfAbsent(sectionKey((x + dx) >> 4, (y + dy) >> 4, (z + dz) >> 4),
                            k -> new Section());
                    s.dirty = true;
                }
            }
        }
    }

    @Override
    public CgHostLevel.Drawn draw(Object level, Object key, int width, int height, Matrix4fc view, double ox, double oy,
                                  double oz, Matrix4fc projection, float partialTick, Runnable components) {
        Pocket p = (Pocket) level;
        Minecraft mc = Minecraft.getInstance();
        View v = p.views.computeIfAbsent(key, k -> new View());
        if (v.target == null || v.target.width != width || v.target.height != height) {
            v.release();
            v.target = PicturesModern.make(width, height);
            v.target.setClearColor(0f, 0f, 0f, 0f);
            v.colour = (CgTexture2D) CgHostPictures.imported(PicturesModern.handle(v.target));
            v.drawn = new CgHostLevel.Drawn(CgExternalTarget.host(v.target.frameBufferId, width, height), v.colour);
        }
        mesh(p);
        v.target.clear(Minecraft.ON_OSX);
        v.target.bindWrite(true);
        // GL's own matrices: the projection loaded, the model-view each section's
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.multMatrix(load(matrix.set(projection)));
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        try {
            // GameRenderer.renderLevel's terrain: smooth shading, the block atlas, the lightmap, alpha-tested cutouts
            GlStateManager.enableDepthTest();
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.enableCull();
            GlStateManager.disableBlend();
            GlStateManager.shadeModel(GL11.GL_SMOOTH);
            Lighting.turnOff();
            mc.getTextureManager().bind(TextureAtlas.LOCATION_BLOCKS);
            mc.gameRenderer.turnOnLightLayer();
            GlStateManager.disableAlphaTest();
            drawLayer(p, BlockLayer.SOLID, view, ox, oy, oz);
            GlStateManager.enableAlphaTest();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
            drawLayer(p, BlockLayer.CUTOUT_MIPPED, view, ox, oy, oz);
            drawLayer(p, BlockLayer.CUTOUT, view, ox, oy, oz);
            GlStateManager.shadeModel(GL11.GL_FLAT);

            drawBlockEntities(p, view, ox, oy, oz, partialTick);
            components.run();

            // The translucent layer: blended, depth kept
            mc.getTextureManager().bind(TextureAtlas.LOCATION_BLOCKS);
            mc.gameRenderer.turnOnLightLayer();
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.shadeModel(GL11.GL_SMOOTH);
            drawLayer(p, BlockLayer.TRANSLUCENT, view, ox, oy, oz);
        } finally {
            GlStateManager.shadeModel(GL11.GL_FLAT);
            GlStateManager.depthMask(true);
            GlStateManager.disableBlend();
            mc.gameRenderer.turnOffLightLayer();
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
            mc.getMainRenderTarget().bindWrite(true);
        }
        return v.drawn;
    }

    // LevelRenderer.render(layer) then VboRenderList.render: the block format's arrays on, each section's buffer
    // pointed at and drawn under the view and its offset
    private void drawLayer(Pocket p, BlockLayer layer, Matrix4fc view, double ox, double oy, double oz) {
        GlStateManager.enableClientState(GL11.GL_VERTEX_ARRAY);
        GlStateManager.enableClientState(GL11.GL_COLOR_ARRAY);
        GLX.glClientActiveTexture(GLX.GL_TEXTURE0);
        GlStateManager.enableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GLX.glClientActiveTexture(GLX.GL_TEXTURE1);
        GlStateManager.enableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GLX.glClientActiveTexture(GLX.GL_TEXTURE0);
        for (Map.Entry<Long, Section> e : p.sections.entrySet()) {
            VertexBuffer vb = e.getValue().layers.get(layer);
            if (vb == null) continue;
            long sk = e.getKey();
            GlStateManager.loadIdentity();
            GlStateManager.multMatrix(load(matrix.set(view).translate((float) ((sectionX(sk) << 4) - ox),
                    (float) ((sectionY(sk) << 4) - oy), (float) ((sectionZ(sk) << 4) - oz))));
            vb.bind();
            GlStateManager.vertexPointer(3, GL11.GL_FLOAT, STRIDE, 0);
            GlStateManager.colorPointer(4, GL11.GL_UNSIGNED_BYTE, STRIDE, 12);
            GlStateManager.texCoordPointer(2, GL11.GL_FLOAT, STRIDE, 16);
            GLX.glClientActiveTexture(GLX.GL_TEXTURE1);
            GlStateManager.texCoordPointer(2, GL11.GL_SHORT, STRIDE, 24);
            GLX.glClientActiveTexture(GLX.GL_TEXTURE0);
            vb.draw(GL11.GL_QUADS);
        }
        VertexBuffer.unbind();
        GlStateManager.clearCurrentColor();
        GlStateManager.disableClientState(GL11.GL_VERTEX_ARRAY);
        GlStateManager.disableClientState(GL11.GL_COLOR_ARRAY);
        GLX.glClientActiveTexture(GLX.GL_TEXTURE1);
        GlStateManager.disableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GLX.glClientActiveTexture(GLX.GL_TEXTURE0);
        GlStateManager.disableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
    }

    // Each block entity by the dispatcher at its offset under the view, in full light: its own light would be the
    // client world's at that block
    private void drawBlockEntities(Pocket p, Matrix4fc view, double ox, double oy, double oz, float partialTick) {
        GlStateManager.loadIdentity();
        GlStateManager.multMatrix(load(matrix.set(view)));
        Lighting.turnOn();
        GLX.glMultiTexCoord2f(GLX.GL_TEXTURE1, FULL_LIGHT, FULL_LIGHT);
        GlStateManager.color4f(1f, 1f, 1f, 1f);
        for (Section s : p.sections.values()) {
            for (BlockEntity be : s.blockEntities) {
                BlockPos pos = be.getBlockPos();
                BlockEntityRenderDispatcher.instance.render(be, pos.getX() - ox, pos.getY() - oy, pos.getZ() - oz,
                        partialTick, -1, false);
            }
        }
        Lighting.turnOff();
    }

    private FloatBuffer load(Matrix4fc m) {
        buffer.clear();
        m.get(buffer);
        return buffer;
    }

    // RenderChunk.compile: each block in its layer, the fluid in its own, written at world positions offset to the section
    private void mesh(Pocket p) {
        Set<Long> occupied = new HashSet<>();
        for (long key : p.states.keySet()) {
            BlockPos pos = BlockPos.of(key);
            occupied.add(sectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
        }
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        for (Map.Entry<Long, Section> e : p.sections.entrySet()) {
            Section s = e.getValue();
            if (!s.dirty) continue;
            s.dirty = false;
            s.release();
            s.blockEntities.clear();
            if (!occupied.contains(e.getKey())) continue;
            long sk = e.getKey();
            int sx = sectionX(sk) << 4, sy = sectionY(sk) << 4, sz = sectionZ(sk) << 4;
            Map<BlockLayer, BufferBuilder> builders = new EnumMap<>(BlockLayer.class);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        pos.set(sx + x, sy + y, sz + z);
                        BlockState state = p.getBlockState(pos);
                        if (state.isAir()) continue;
                        BlockEntity be = p.getBlockEntity(pos);
                        if (be != null) s.blockEntities.add(be);
                        FluidState fluid = state.getFluidState();
                        if (!fluid.isEmpty()) {
                            blocks.renderLiquid(pos, p, builder(builders, fluid.getRenderLayer(), sx, sy, sz), fluid);
                        }
                        if (state.getRenderShape() == RenderShape.MODEL) {
                            blocks.renderBatched(state, pos, p, builder(builders, state.getBlock().getRenderLayer(), sx, sy,
                                    sz), random);
                        }
                    }
                }
            }
            for (Map.Entry<BlockLayer, BufferBuilder> b : builders.entrySet()) {
                BufferBuilder built = b.getValue();
                built.end();
                VertexBuffer vb = new VertexBuffer(DefaultVertexFormat.BLOCK);
                vb.upload(built.getBuffer());
                s.layers.put(b.getKey(), vb);
            }
        }
    }

    private static BufferBuilder builder(Map<BlockLayer, BufferBuilder> builders, BlockLayer layer, int sx, int sy, int sz) {
        return builders.computeIfAbsent(layer, l -> {
            BufferBuilder b = new BufferBuilder(LAYER_BYTES);
            b.begin(GL11.GL_QUADS, DefaultVertexFormat.BLOCK);
            b.offset(-sx, -sy, -sz);
            return b;
        });
    }

    @Override
    public void tick(Object level) {
        for (BlockEntity be : ((Pocket) level).blockEntities.values()) {
            if (be instanceof TickableBlockEntity) ((TickableBlockEntity) be).tick();
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

    @Nullable
    @Override
    public CgPocketLevel.Hit pick(Object level, double ox, double oy, double oz, double dx, double dy, double dz,
                                  double reach) {
        Pocket p = (Pocket) level;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0) return null;
        Vec3 from = new Vec3(ox, oy, oz), to = from.add(dx / length * reach, dy / length * reach, dz / length * reach);
        // The crosshair's clip: outline shapes, no fluids; a player only for the collision context it asks of one
        BlockHitResult hit = p.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                Minecraft.getInstance().player));
        if (hit.getType() == HitResult.Type.MISS) return null;
        BlockPos pos = hit.getBlockPos();
        BlockState state = p.getBlockState(pos);
        List<CgAABBc> outline = new ArrayList<>();
        for (AABB box : state.getShape(p, pos).toAabbs()) {
            outline.add(CgAABB.of(box.minX + pos.getX(), box.minY + pos.getY(), box.minZ + pos.getZ(),
                    box.maxX + pos.getX(), box.maxY + pos.getY(), box.maxZ + pos.getZ()));
        }
        Vec3 at = hit.getLocation();
        return new CgPocketLevel.Hit(new CgBlockPos(pos.getX(), pos.getY(), pos.getZ()),
                CgDirection.valueOf(hit.getDirection().name()), at.x, at.y, at.z, CgBlocks.of(state), outline);
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
*///?} else {
// 1.14.3-1.14.4 only: 1.13.2 provides CgPocketLevel.Host.absent with its reason
final class PocketLevels114 {
    private PocketLevels114() {
    }
}
//?}
