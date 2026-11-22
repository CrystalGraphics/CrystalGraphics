package com.crystalgraphics.mc.modern.platform;

//? if >=1.15 <26.4 {
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.block.CgBlocks;
import com.crystalgraphics.mc.render.CgHostPictures;
import com.crystalgraphics.mc.render.world.CgPocketLevel;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.render.host.CgHostLevel;
import com.crystalgraphics.mc.level.CgBlockPos;
import com.crystalgraphics.mc.level.CgDirection;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.mojang.blaze3d.pipeline.RenderTarget;
//? if >=1.21.5 {
/*import java.nio.ByteBuffer;
import com.crystalgraphics.platform.rhi.CgHostImports;
import org.lwjgl.system.MemoryUtil;
*///?}
//? if >=1.20.5 {
/*import net.minecraft.nbt.CompoundTag;
*///?}
//? if >=1.21.6 {
/*import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
*///?}
import org.lwjgl.opengl.GL11;
//? if >=1.17 <1.19.2 {
/*import com.mojang.blaze3d.vertex.BufferUploader;
*///?}
//? if <1.21.5 {
import com.mojang.blaze3d.vertex.VertexBuffer;
//?}
//? if >=1.17 <1.21.5 {
import com.mojang.blaze3d.shaders.Uniform;
//?}
//? if <1.17 {
/*import java.nio.FloatBuffer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import com.mojang.math.Quaternion;
import net.minecraft.world.level.block.entity.TickableBlockEntity;
*///?}
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
//? if <26.2 {
import com.mojang.blaze3d.vertex.VertexFormat;
//?}
//? if >=1.20 {
import com.mojang.blaze3d.vertex.VertexSorting;
//?}
//? if >=1.21 {
/*import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
*///?}
//? if >=1.20.5 {
/*import org.joml.Matrix4fStack;
*///?}

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
//? if <26.1 {
import net.minecraft.client.renderer.ItemBlockRenderTypes;
//?}
//? if <1.21.10 {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
//? if <1.21.11 {
import net.minecraft.client.renderer.RenderType;
//?} else {
/*import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
*///?}
//? if >=1.21.5 <26.1 {
/*import net.minecraft.client.renderer.block.model.BlockModelPart;
*///?}
//? if >=1.21.6 <26.1 {
/*import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
*///?}
//? if >=26.2 <26.3 {
/*import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
*///?} elif >=26.3 {
/*import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
*///?}
//? if >=26.2 {
/*import net.minecraft.client.renderer.SubmitNodeStorage;
import org.joml.Vector4f;
*///?}
//? if >=26.1 {
/*import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.resources.model.geometry.BakedQuad;
*///?}
//? if >=1.21.10 {
/*import com.crystalgraphics.mc.render.world.CgHostWorld;
import com.crystalgraphics.platform.CgPlatform;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
*///?}
//? if >=1.21.10 <26.1 {
/*import net.minecraft.client.renderer.state.CameraRenderState;
*///?} elif >=26.1 {
/*import net.minecraft.client.renderer.state.level.CameraRenderState;
*///?}
//? if >=1.17 <1.21.2 {
import net.minecraft.client.renderer.ShaderInstance;
//?} elif >=1.21.2 {
/*import com.mojang.blaze3d.ProjectionType;
*///?}
//? if >=1.21.2 <1.21.5 {
/*import com.mojang.blaze3d.buffers.BufferUsage;
import net.minecraft.client.renderer.CompiledShaderProgram;
*///?}
//? if <26.1 {
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
//?}
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//? if >=1.19.3 {
import net.minecraft.core.registries.Registries;
//?} elif >=1.16 {
/*import net.minecraft.core.Registry;
*///?}
//? if >=1.19 {
import net.minecraft.util.RandomSource;
//?} else {
/*import java.util.Random;
*///?}
//? if <26.1 {
import net.minecraft.world.level.BlockAndTintGetter;
//?} else {
/*import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.world.level.CardinalLighting;
*///?}
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
//? if >=1.17 {
import net.minecraft.world.level.block.entity.BlockEntityTicker;
//?}
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ClipContext;

/**
 * {@link CgPocketLevel} on the modern tree, client: the blocks held by a getter of their own, meshed 16³ at a time per
 * render type as the section compiler meshes them (1.20.1 {@code RebuildTask.compile}, block by block), drawn as
 * {@code LevelRenderer.renderChunkLayer} draws a layer, into a target of Minecraft's own per view. Block entities are
 * made from their data and drawn by their renderers, borrowing the client world as their level. Full light: block and
 * sky 15 everywhere; tints are plains'.
 */
public final class PocketLevelsModern implements CgPocketLevel.Host {

    private static final int FULL_LIGHT = 0xF000F0;

    /** The level: its blocks, as the renderers read them, and its meshed sections. */
    static final class Pocket implements BlockAndTintGetter {
        final Map<Long, BlockState> states = new HashMap<>();
        final Map<Long, BlockEntity> blockEntities = new HashMap<>();
        final Map<Long, Section> sections = new HashMap<>();
        final Map<Object, View> views = new IdentityHashMap<>();
        final ClientLevel world;
        final Biome biome;
        int minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE;

        Pocket(ClientLevel world) {
            this.world = world;
            //? if <1.16 {
            /*this.biome = Biomes.PLAINS;
            *///?} elif <1.19.3 {
            /*this.biome = world.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY).getOrThrow(Biomes.PLAINS);
            *///?} elif <1.21.2 {
            this.biome = world.registryAccess().registryOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
            //?} else {
            /*this.biome = world.registryAccess().lookupOrThrow(Registries.BIOME).getValueOrThrow(Biomes.PLAINS);
            *///?}
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

        // No shade on the getter below 1.16: the block renderer shades by direction itself
        //? if >=1.16 <26.1 {
        @Override
        public float getShade(Direction direction, boolean shade) {
            return world.getShade(direction, shade);
        }
        //?} elif >=26.1 {
        /*@Override
        public CardinalLighting cardinalLighting() {
            return world.cardinalLighting();
        }
        *///?}

        @Override
        public LevelLightEngine getLightEngine() {
            return world.getLightEngine();
        }

        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            return 15;
        }

        @Override
        public int getRawBrightness(BlockPos pos, int darkening) {
            return 15;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return resolver.getColor(biome, pos.getX(), pos.getZ());
        }

        //? if >=1.17 {
        @Override
        public int getHeight() {
            return world.getHeight();
        }
        //?}

        //? if >=1.17 <1.21.2 {
        @Override
        public int getMinBuildHeight() {
            return world.getMinBuildHeight();
        }
        //?} elif >=1.21.2 {
        /*@Override
        public int getMinY() {
            return world.getMinY();
        }
        *///?}
    }

    /** A 16³ section's meshes, one per render type it holds; rebuilt where a block in it changed. */
    static final class Section {
        //? if <1.21.5 {
        final Map<RenderType, VertexBuffer> layers = new HashMap<>();
        //?} else {
        /*final Map<Object, Kept> layers = new HashMap<>();
        *///?}
        final List<BlockEntity> blockEntities = new ArrayList<>();
        boolean dirty = true;

        void release() {
            //? if <1.21.5 {
            for (VertexBuffer vb : layers.values()) vb.close();
            //?} elif <26.2 {
            /*for (Kept k : layers.values()) MemoryUtil.memFree(k.bytes);
            *///?} else {
            /*for (Kept k : layers.values()) k.buffer.close();
            *///?}
            layers.clear();
        }
    }

    //? if >=1.21.5 <26.2 {
    /*// A layer's tesselated vertices, kept off the heap: from 1.21.5 a section's mesh is drawn by its render type, which
    // takes a fresh MeshData each frame, so the bytes are copied into one, never tesselated again
    static final class Kept {
        final ByteBuffer bytes;
        final MeshData.DrawState state;

        Kept(ByteBuffer bytes, MeshData.DrawState state) {
            this.bytes = bytes;
            this.state = state;
        }
    }
    *///?}
    //? if >=26.2 {
    /*// A layer's mesh, uploaded once where it changed: from 26.2 a render type draws from a buffer of our own
    static final class Kept {
        final GpuBuffer buffer;
        final int vertexCount;

        Kept(GpuBuffer buffer, int vertexCount) {
            this.buffer = buffer;
            this.vertexCount = vertexCount;
        }
    }
    *///?}

    /** A view's target, remade on a new size. */
    static final class View {
        RenderTarget target;
        CgTexture2D colour;
        CgHostLevel.Drawn drawn;

        void release() {
            if (colour != null) CgHostPictures.forget(colour);
            if (target != null) {
                //? if <1.21.5 {
                CgExternalTarget.forgetHost(target.frameBufferId);
                //?}
                target.destroyBuffers();
            }
            target = null;
            colour = null;
            drawn = null;
        }
    }

    private final Matrix4f modelView = new Matrix4f();
    //? if <1.21.5 {
    private static final Object[] OPAQUE = {RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout()};
    private static final Object[] TRANSLUCENT = {RenderType.translucent()};
    //?} elif <1.21.6 {
    /*private static final Object[] OPAQUE = {RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout()};
    private static final Object[] TRANSLUCENT = {RenderType.translucent(), RenderType.tripwire()};
    *///?} elif <1.21.11 {
    /*private static final Object[] OPAQUE = {ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT_MIPPED, ChunkSectionLayer.CUTOUT};
    private static final Object[] TRANSLUCENT = {ChunkSectionLayer.TRANSLUCENT, ChunkSectionLayer.TRIPWIRE};
    *///?} elif <26.1 {
    /*private static final Object[] OPAQUE = {ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT};
    private static final Object[] TRANSLUCENT = {ChunkSectionLayer.TRANSLUCENT, ChunkSectionLayer.TRIPWIRE};
    *///?} else {
    /*private static final Object[] OPAQUE = {ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT};
    private static final Object[] TRANSLUCENT = {ChunkSectionLayer.TRANSLUCENT};
    *///?}
    //? if >=26.2 {
    /*@Nullable private SubmitNodeStorage submitStorage;
    *///?}
    //? if >=26.3 {
    /*// What one pass draws: 26.3 writes uniforms and stages buffers before a pass opens and draws only inside one
    private final List<Pending> pending = new ArrayList<>();
    @Nullable private FeatureRenderDispatcher.PreparedFrame preparedFrame;

    private static final class Pending {
        final PreparedRenderType type;
        final StagedVertexBuffer.ExecuteInfo info;

        Pending(PreparedRenderType type, StagedVertexBuffer.ExecuteInfo info) {
            this.type = type;
            this.info = info;
        }
    }

    // The prepared layers and features in one pass on our target, as a picture-in-picture renderer draws on 26.3:
    // cleared as it opens (reversed depth), the opaque layers, the features, then the translucent layers
    private void drawPass(View v, int translucentFrom) {
        try (FeatureRenderDispatcher.PreparedFrame frame = preparedFrame;
             RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                     () -> "crystalgraphics pocket level", v.target.getColorTextureView(), Optional.of(new Vector4f(0f)),
                     v.target.getDepthTextureView(), OptionalDouble.of(0.0))) {
            RenderSystem.bindDefaultUniforms(pass);
            for (int i = 0; i < translucentFrom; i++) pending.get(i).type.drawFromBuffer(pending.get(i).info, pass);
            if (frame != null) FeatureRenderDispatcher.renderAllFeatures(pass, frame);
            for (int i = translucentFrom; i < pending.size(); i++) pending.get(i).type.drawFromBuffer(pending.get(i).info, pass);
        } finally {
            pending.clear();
            preparedFrame = null;
        }
    }
    *///?}
    //? if >=1.21.6 <26.1 {
    /*// Made on the first draw: the GPU device does not exist yet as the host is made
    @Nullable private PerspectiveProjectionMatrixBuffer projectionBuffer;
    *///?} elif >=26.1 {
    /*@Nullable private ProjectionMatrixBuffer projectionBuffer;
    *///?}
    //? if >=1.19 {
    private final RandomSource random = RandomSource.create();
    //?} else {
    /*private final Random random = new Random();
    *///?}

    @Override
    public String absent() {
        return null;
    }

    @Override
    public Object create() {
        ClientLevel world = Minecraft.getInstance().level;
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
                //? if >=1.17 {
                BlockEntity be = ((EntityBlock) s.getBlock()).newBlockEntity(pos, s);
                //?} else {
                /*BlockEntity be = ((EntityBlock) s.getBlock()).newBlockEntity(p);
                *///?}
                if (be != null) {
                    //? if >=1.17 {
                    be.setLevel(p.world);
                    //?} else {
                    /*be.setLevelAndPosition(p.world, pos);
                    *///?}
                    //? if <1.16 {
                    /*if (state.hasBlockEntity()) be.load(CgNbt.toHost(state.blockEntity()));
                    *///?} elif <1.17 {
                    /*if (state.hasBlockEntity()) be.load(s, CgNbt.toHost(state.blockEntity()));
                    *///?} elif <1.20.5 {
                    if (state.hasBlockEntity()) be.load(CgNbt.toHost(state.blockEntity()));
                    //?} else {
                    /*if (state.hasBlockEntity()) load(be, CgNbt.toHost(state.blockEntity()), p);
                    *///?}
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
            //? if <1.21.5 {
            v.target.setClearColor(0f, 0f, 0f, 0f);
            v.colour = (CgTexture2D) CgHostPictures.imported(PicturesModern.handle(v.target));
            v.drawn = new CgHostLevel.Drawn(CgExternalTarget.host(v.target.frameBufferId, width, height), v.colour);
            //?} else {
            /*Object colour = PicturesModern.handle(v.target), depth = PicturesModern.depthHandle(v.target);
            v.colour = (CgTexture2D) CgHostPictures.imported(colour);
            CgHostImports imports = CgHostImports.get();
            v.drawn = new CgHostLevel.Drawn(CgExternalTarget.of("crystalgraphics.pocket", imports.texture(colour),
                    imports.texture(depth)).drawnAsHost(), v.colour);
            *///?}
        }
        mesh(p);
        //? if <1.21.2 {
        v.target.clear(Minecraft.ON_OSX);
        //?} elif <1.21.5 {
        /*v.target.clear();
        *///?} elif <26.2 {
        /*RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(v.target.getColorTexture(), 0, v.target.getDepthTexture(), 1.0);
        *///?} elif <26.3 {
        /*// Reversed depth from 26.2: cleared to 0, as Minecraft clears a picture's
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(v.target.getColorTexture(), new Vector4f(0f), v.target.getDepthTexture(), 0.0);
        *///?}
        //? if <1.21.5 {
        v.target.bindWrite(true);
        //?}
        // From 1.21.2 a render type's main-target state binds the main target: it is answered with ours while we draw
        RenderTarget answeredBefore = PicturesModern.answerMain(v.target);
        //? if >=26.2 <26.3 {
        /*RenderSystem.outputColorTextureOverride = v.target.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = v.target.getDepthTextureView();
        *///?}
        //? if >=1.17 {
        RenderSystem.backupProjectionMatrix();
        //?}
        //? if >=1.21.6 {
        /*//? if <26.1 {
        if (projectionBuffer == null) projectionBuffer = new PerspectiveProjectionMatrixBuffer("crystalgraphics pocket");
        //?} else {
        /^if (projectionBuffer == null) projectionBuffer = new ProjectionMatrixBuffer("crystalgraphics pocket");
        ^///?}
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(new Matrix4f(projection)), ProjectionType.PERSPECTIVE);
        *///?} elif >=1.21.2 {
        /*RenderSystem.setProjectionMatrix(new Matrix4f(projection), ProjectionType.PERSPECTIVE);
        *///?} elif >=1.20 {
        RenderSystem.setProjectionMatrix(new Matrix4f(projection), VertexSorting.DISTANCE_TO_ORIGIN);
        //?} elif >=1.19.3 {
        /*RenderSystem.setProjectionMatrix(new Matrix4f(projection));
        *///?} elif >=1.17 {
        /*RenderSystem.setProjectionMatrix(MojangMatrices.of(projection));
        *///?} else {
        /*// Fixed function below 1.17: the projection loaded into GL's own stack, the model-view held at identity
        RenderSystem.matrixMode(GL11.GL_PROJECTION);
        RenderSystem.pushMatrix();
        RenderSystem.loadIdentity();
        GL11.glMultMatrixf(projection.get(MATRIX));
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
        RenderSystem.pushMatrix();
        RenderSystem.loadIdentity();
        *///?}
        try {
            //? if <1.21.5 {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            //?}
            //? if <1.21.11 {
            mc.gameRenderer.lightTexture().turnOnLightLayer();
            //?}
            for (Object layer : OPAQUE) drawLayer(p, layer, view, ox, oy, oz, projection);
            // Block entities and the scene's host-drawn components draw through Minecraft's buffers, which apply
            // RenderSystem's model-view: at the GUI draw point that is the GUI's, so it is held at identity for them.
            // From 1.21.10 they are submitted camera-relative and rendered as the level renders its features: under
            // the view's model-view
            //? if >=1.17 <1.20.5 {
            PoseStack modelViewStack = RenderSystem.getModelViewStack();
            modelViewStack.pushPose();
            modelViewStack.setIdentity();
            //?} elif >=1.20.5 <1.21.10 {
            /*Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
            modelViewStack.pushMatrix();
            modelViewStack.identity();
            *///?} elif >=1.21.10 {
            /*Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
            modelViewStack.pushMatrix();
            modelViewStack.set(view);
            *///?}
            //? if >=1.17 <1.21.2 {
            RenderSystem.applyModelViewMatrix();
            //?}
            try {
                //? if <1.21.10 {
                drawBlockEntities(p, view, ox, oy, oz, partialTick);
                components.run();
                //?} else {
                /*//? if <26.2 {
                FeatureRenderDispatcher features = mc.gameRenderer.getFeatureRenderDispatcher();
                SubmitNodeCollector submits = features.getSubmitNodeStorage();
                //?} else {
                /^FeatureRenderDispatcher features = mc.gameRenderer.featureRenderDispatcher();
                if (submitStorage == null) submitStorage = new SubmitNodeStorage();
                SubmitNodeCollector submits = submitStorage;
                ^///?}
                CameraRenderState camera = new CameraRenderState();
                camera.pos = new Vec3(ox, oy, oz);
                camera.blockPos = BlockPos.containing(ox, oy, oz);
                camera.initialized = true;
                submitBlockEntities(p, ox, oy, oz, partialTick, submits, camera);
                // The components submit where the level's would: into ours, as seen from the pocket's origin
                ((HostWorldModern) CgPlatform.get(CgHostWorld.HOST)).submitting(submits, camera);
                components.run();
                // Rendering fills the shared buffers; flushed here, as a GUI picture's renderer does, into our target
                //? if <26.2 {
                features.renderAllFeatures();
                mc.renderBuffers().bufferSource().endBatch();
                //?} elif <26.3 {
                /^features.renderAllFeatures(submitStorage);
                ^///?} else {
                /^preparedFrame = features.prepareFrame(submitStorage);
                ^///?}
                *///?}
            } finally {
                //? if >=1.17 <1.20.5 {
                modelViewStack.popPose();
                //?} elif >=1.20.5 {
                /*modelViewStack.popMatrix();
                *///?}
                //? if >=1.17 <1.21.2 {
                RenderSystem.applyModelViewMatrix();
                //?}
            }
            //? if >=26.3 {
            /*int translucentFrom = pending.size();
            *///?}
            for (Object layer : TRANSLUCENT) drawLayer(p, layer, view, ox, oy, oz, projection);
            //? if >=26.3 {
            /*drawPass(v, translucentFrom);
            *///?}
            //? if <1.21.11 {
            mc.gameRenderer.lightTexture().turnOffLightLayer();
            //?}
        } finally {
            //? if >=1.17 {
            RenderSystem.restoreProjectionMatrix();
            //?} else {
            /*RenderSystem.matrixMode(GL11.GL_PROJECTION);
            RenderSystem.popMatrix();
            RenderSystem.matrixMode(GL11.GL_MODELVIEW);
            RenderSystem.popMatrix();
            *///?}
            PicturesModern.answerMain(answeredBefore);
            //? if >=26.2 <26.3 {
            /*RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
            *///?}
            //? if <1.21.5 {
            mc.getMainRenderTarget().bindWrite(true);
            //?}
        }
        return v.drawn;
    }

    //? if >=1.20.5 {
    /*// A block entity's data, as the era loads it: with its components, then through a value input from 1.21.6
    private static void load(BlockEntity be, CompoundTag tag, Pocket p) {
        //? if <1.21.6 {
        be.loadWithComponents(tag, p.world.registryAccess());
        //?} else {
        /^be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, p.world.registryAccess(), tag));
        ^///?}
    }
    *///?}

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void tick(Object level) {
        Pocket p = (Pocket) level;
        // The client tickers, as the client world runs them: the world as their level, as the block entity's is
        for (BlockEntity be : p.blockEntities.values()) {
            //? if >=1.17 {
            BlockEntityTicker ticker = be.getBlockState().getTicker(p.world, be.getType());
            if (ticker != null) ticker.tick(p.world, be.getBlockPos(), be.getBlockState(), be);
            //?} else {
            /*if (be instanceof TickableBlockEntity) ((TickableBlockEntity) be).tick();
            *///?}
        }
    }

    @Override
    public void visibleRange(Object level, int minY, int maxY) {
        Pocket p = (Pocket) level;
        int oldMin = p.minY, oldMax = p.maxY;
        p.minY = minY;
        p.maxY = maxY;
        // Sections whose blocks or whose neighbours' faces the cut moves past: between the old and new bounds, and one more
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

    @Override
    @Nullable
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

    // The section compiler's loop, block by block, for each section a change marked
    private void mesh(Pocket p) {
        Set<Long> occupied = new HashSet<>();
        for (long key : p.states.keySet()) {
            BlockPos pos = BlockPos.of(key);
            occupied.add(sectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
        }
        //? if <26.1 {
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        //?} else {
        /*Minecraft mc = Minecraft.getInstance();
        boolean cutoutLeaves = mc.options.cutoutLeaves().get();
        ModelBlockRenderer blocks = new ModelBlockRenderer(mc.options.ambientOcclusion().get(), false, mc.getBlockColors());
        FluidRenderer fluids = new FluidRenderer(mc.getModelManager().getFluidStateModelSet());
        *///?}
        PoseStack pose = new PoseStack();
        for (Map.Entry<Long, Section> e : p.sections.entrySet()) {
            Section s = e.getValue();
            if (!s.dirty) continue;
            s.dirty = false;
            s.release();
            s.blockEntities.clear();
            if (!occupied.contains(e.getKey())) continue;
            long sk = e.getKey();
            int sx = sectionX(sk) << 4, sy = sectionY(sk) << 4, sz = sectionZ(sk) << 4;
            Map<Object, BufferBuilder> builders = new IdentityHashMap<>();
            //? if >=26.1 {
            /*BlockQuadOutput quads = (qx, qy, qz, quad, instance) -> putQuad(builders, pose, qx, qy, qz, quad, instance,
                    quad.materialInfo().layer());
            BlockQuadOutput opaqueQuads = (qx, qy, qz, quad, instance) -> putQuad(builders, pose, qx, qy, qz, quad, instance,
                    ChunkSectionLayer.SOLID);
            *///?}
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
                        //? if <26.1 {
                        if (!fluid.isEmpty()) {
                            //? if >=1.18 {
                            blocks.renderLiquid(pos, p, builder(builders, ItemBlockRenderTypes.getRenderLayer(fluid)), state, fluid);
                            //?} else {
                            /*blocks.renderLiquid(pos, p, builder(builders, ItemBlockRenderTypes.getRenderLayer(fluid)), fluid);
                            *///?}
                        }
                        //?} else {
                        /*if (!fluid.isEmpty()) fluids.tesselate(p, pos, layer -> builder(builders, layer), state, fluid);
                        if (state.getRenderShape() == RenderShape.MODEL) {
                            BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
                            blocks.tesselateBlock(ModelBlockRenderer.forceOpaque(cutoutLeaves, state) ? opaqueQuads : quads,
                                    x, y, z, p, pos, state, model, state.getSeed(pos));
                        }
                        *///?}
                        //? if <26.1 {
                        if (state.getRenderShape() == RenderShape.MODEL) {
                            Object type = ItemBlockRenderTypes.getChunkRenderType(state);
                            pose.pushPose();
                            pose.translate(x, y, z);
                            random.setSeed(state.getSeed(pos));
                            //? if <1.21.5 {
                            blocks.renderBatched(state, pos, p, pose, builder(builders, type), true, random);
                            //?} else {
                            /*parts.clear();
                            blocks.getBlockModel(state).collectParts(random, parts);
                            blocks.renderBatched(state, pos, p, pose, builder(builders, type), true, parts);
                            *///?}
                            pose.popPose();
                        }
                        //?}
                    }
                }
            }
            for (Map.Entry<Object, BufferBuilder> b : builders.entrySet()) {
                //? if <1.19.2 {
                /*BufferBuilder rendered = b.getValue();
                rendered.end();
                *///?} elif <1.21 {
                BufferBuilder.RenderedBuffer rendered = b.getValue().endOrDiscardIfEmpty();
                //?} else {
                /*MeshData rendered = b.getValue().build();
                *///?}
                if (rendered == null) continue;
                //? if >=1.21.5 {
                /*//? if <26.2 {
                ByteBuffer from = rendered.vertexBuffer(), kept = MemoryUtil.memAlloc(from.remaining());
                MemoryUtil.memCopy(from, kept);
                s.layers.put(b.getKey(), new Kept(kept, rendered.drawState()));
                //?} else {
                /^GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "crystalgraphics pocket section",
                        GpuBuffer.USAGE_VERTEX, rendered.vertexBuffer());
                s.layers.put(b.getKey(), new Kept(buffer, rendered.drawState().vertexCount()));
                ^///?}
                rendered.close();
                *///?} else {
                //? if <1.17 {
                /*VertexBuffer vb = new VertexBuffer(DefaultVertexFormat.BLOCK);
                *///?} elif <1.20 {
                /*VertexBuffer vb = new VertexBuffer();
                *///?} else {
                //? if <1.21.2 {
                VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
                //?} else {
                /*VertexBuffer vb = new VertexBuffer(BufferUsage.STATIC_WRITE);
                *///?}
                //?}
                vb.bind();
                vb.upload(rendered);
                VertexBuffer.unbind();
                s.layers.put((RenderType) b.getKey(), vb);
                //?}
            }
            //? if >=1.21 {
            /*for (ByteBufferBuilder memory : memories) memory.close();
            memories.clear();
            *///?}
        }
    }

    //? if >=1.21 {
    /*// Each layer's builder writes into memory of its own, freed once uploaded
    private final List<ByteBufferBuilder> memories = new ArrayList<>();
    *///?}

    //? if >=1.21.5 <26.1 {
    /*private final List<BlockModelPart> parts = new ArrayList<>();
    *///?}

    //? if >=26.1 {
    /*// A quad as BlockFeatureRenderer writes a moving block's: at its offset in the section, in its layer's builder
    private void putQuad(Map<Object, BufferBuilder> builders, PoseStack pose, float x, float y, float z, BakedQuad quad,
                         QuadInstance instance, ChunkSectionLayer layer) {
        pose.pushPose();
        pose.translate(x, y, z);
        builder(builders, layer).putBakedQuad(pose.last(), quad, instance);
        pose.popPose();
    }
    *///?}

    // A layer's builder: the render type to 1.21.5, the chunk section layer from 1.21.6; grown as written
    private BufferBuilder builder(Map<Object, BufferBuilder> builders, Object type) {
        return builders.computeIfAbsent(type, t -> {
            //? if <1.17 {
            /*BufferBuilder b = new BufferBuilder(((RenderType) t).bufferSize());
            b.begin(GL11.GL_QUADS, DefaultVertexFormat.BLOCK);
            return b;
            *///?} elif <1.21 {
            BufferBuilder b = new BufferBuilder(((RenderType) t).bufferSize());
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            return b;
            //?} elif <26.1 {
            /*ByteBufferBuilder memory = new ByteBufferBuilder(LAYER_BYTES);
            memories.add(memory);
            return new BufferBuilder(memory, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            *///?} elif <26.2 {
            /*ByteBufferBuilder memory = new ByteBufferBuilder(LAYER_BYTES);
            memories.add(memory);
            return new BufferBuilder(memory, VertexFormat.Mode.QUADS, renderType(t).format());
            *///?} else {
            /*ByteBufferBuilder memory = new ByteBufferBuilder(LAYER_BYTES);
            memories.add(memory);
            return new BufferBuilder(memory, PrimitiveTopology.QUADS, renderType(t).format());
            *///?}
        });
    }

    //? if >=1.17 <1.21.2 {
    private static Uniform chunkOffset(ShaderInstance shader) {
        return shader.CHUNK_OFFSET;
    }
    //?} elif >=1.21.2 <1.21.5 {
    /*private static Uniform chunkOffset(CompiledShaderProgram shader) {
        return shader.MODEL_OFFSET;
    }
    *///?}

    //? if >=1.21 {
    /*private static final int LAYER_BYTES = 786432;
    *///?}

    // LevelRenderer.renderChunkLayer: the layer's state, its shader's uniforms, then each section at its offset
    //? if <1.17 {
    /*// LevelRenderer.renderChunkLayer below 1.17: fixed function, each section's buffer drawn under its pose
    private void drawLayer(Pocket p, Object layer, Matrix4fc view, double ox, double oy, double oz, Matrix4fc projection) {
        RenderType type = (RenderType) layer;
        type.setupRenderState();
        PoseStack pose = new PoseStack();
        viewPose(pose, view);
        for (Map.Entry<Long, Section> e : p.sections.entrySet()) {
            VertexBuffer vb = e.getValue().layers.get(type);
            if (vb == null) continue;
            long sk = e.getKey();
            pose.pushPose();
            pose.translate((sectionX(sk) << 4) - ox, (sectionY(sk) << 4) - oy, (sectionZ(sk) << 4) - oz);
            vb.bind();
            DefaultVertexFormat.BLOCK.setupBufferState(0L);
            vb.draw(pose.last().pose(), GL11.GL_QUADS);
            pose.popPose();
        }
        VertexBuffer.unbind();
        DefaultVertexFormat.BLOCK.clearBufferState();
        type.clearRenderState();
    }

    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);

    // The view as a pose of Minecraft's own: its translation, then its rotation (a view has no scale)
    private static void viewPose(PoseStack pose, Matrix4fc view) {
        Vector3f t = view.getTranslation(new Vector3f());
        Quaternionf q = view.getNormalizedRotation(new Quaternionf());
        pose.translate(t.x, t.y, t.z);
        pose.mulPose(new Quaternion(q.x, q.y, q.z, q.w));
    }
    *///?}
    //? if >=1.17 <1.21.5 {
    private void drawLayer(Pocket p, Object layer, Matrix4fc view, double ox, double oy, double oz, Matrix4fc projection) {
        RenderType type = (RenderType) layer;
        type.setupRenderState();
        //? if <1.21.2 {
        ShaderInstance shader = RenderSystem.getShader();
        for (int i = 0; i < 12; i++) shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        //?} else {
        /*CompiledShaderProgram shader = RenderSystem.getShader();
        // As renderSectionLayer sets them; the fog and the rest set again below, for a level of its own
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView.set(view), new Matrix4f(projection),
                Minecraft.getInstance().getWindow());
        for (int i = 0; i < 12; i++) shader.bindSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        *///?}
        //? if >=1.19.3 {
        if (shader.MODEL_VIEW_MATRIX != null) shader.MODEL_VIEW_MATRIX.set(modelView.set(view));
        if (shader.PROJECTION_MATRIX != null) shader.PROJECTION_MATRIX.set(new Matrix4f(projection));
        //?} else {
        /*if (shader.MODEL_VIEW_MATRIX != null) shader.MODEL_VIEW_MATRIX.set(MojangMatrices.of(view));
        if (shader.PROJECTION_MATRIX != null) shader.PROJECTION_MATRIX.set(MojangMatrices.of(projection));
        *///?}
        if (shader.COLOR_MODULATOR != null) shader.COLOR_MODULATOR.set(1f, 1f, 1f, 1f);
        if (shader.FOG_START != null) shader.FOG_START.set(Float.MAX_VALUE);
        if (shader.FOG_END != null) shader.FOG_END.set(Float.MAX_VALUE);
        if (shader.TEXTURE_MATRIX != null) shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        if (shader.GAME_TIME != null) shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
        RenderSystem.setupShaderLights(shader);
        shader.apply();
        for (Map.Entry<Long, Section> e : p.sections.entrySet()) {
            VertexBuffer vb = e.getValue().layers.get(type);
            if (vb == null) continue;
            long sk = e.getKey();
            if (chunkOffset(shader) != null) {
                chunkOffset(shader).set((float) ((sectionX(sk) << 4) - ox), (float) ((sectionY(sk) << 4) - oy),
                        (float) ((sectionZ(sk) << 4) - oz));
                chunkOffset(shader).upload();
            }
            // To 1.18 a section's array and format are bound by drawChunkLayer, as renderChunkLayer draws one
            //? if >=1.17 <1.19 {
            /*vb.drawChunkLayer();
            *///?} else {
            vb.bind();
            vb.draw();
            //?}
        }
        if (chunkOffset(shader) != null) chunkOffset(shader).set(0f, 0f, 0f);
        shader.clear();
        VertexBuffer.unbind();
        // As renderChunkLayer leaves it to 1.18: no section's array bound, the uploader's cached binding forgotten
        //? if >=1.17 <1.19 {
        /*DefaultVertexFormat.BLOCK.clearBufferState();
        VertexBuffer.unbindVertexArray();
        *///?}
        //? if >=1.17 <1.19.2 {
        /*BufferUploader.reset();
        *///?}
        type.clearRenderState();
    }
    //?} elif >=1.21.5 {
    /*private void drawLayer(Pocket p, Object layer, Matrix4fc view, double ox, double oy, double oz, Matrix4fc projection) {
        RenderType type = renderType(layer);
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        for (Map.Entry<Long, Section> e : p.sections.entrySet()) {
            Kept kept = e.getValue().layers.get(layer);
            if (kept == null) continue;
            long sk = e.getKey();
            stack.pushMatrix();
            stack.set(view).translate((float) ((sectionX(sk) << 4) - ox), (float) ((sectionY(sk) << 4) - oy),
                    (float) ((sectionZ(sk) << 4) - oz));
            //? if <26.2 {
            try (ByteBufferBuilder memory = new ByteBufferBuilder(kept.bytes.capacity())) {
                long at = memory.reserve(kept.bytes.capacity());
                MemoryUtil.memCopy(MemoryUtil.memAddress(kept.bytes), at, kept.bytes.capacity());
                type.draw(new MeshData(memory.build(), kept.state));
            } finally {
                stack.popMatrix();
            }
            //?} elif <26.3 {
            /^try {
                RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
                int indices = PrimitiveTopology.QUADS.indexCount(kept.vertexCount);
                type.prepare().drawFromBuffer(kept.buffer, quads.getBuffer(indices), quads.type(), 0, 0, indices);
            } finally {
                stack.popMatrix();
            }
            ^///?} else {
            /^try {
                RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
                int indices = PrimitiveTopology.QUADS.indexCount(kept.vertexCount);
                quads.getBuffer(indices);
                pending.add(new Pending(type.prepare(), new StagedVertexBuffer.ExecuteInfo(kept.buffer, null, quads.type(),
                        0, 0, indices, PrimitiveTopology.QUADS)));
            } finally {
                stack.popMatrix();
            }
            ^///?}
        }
    }

    // The render type a layer draws with: itself to 1.21.5, the chunk layer's moving-block twin from 1.21.6
    private static RenderType renderType(Object layer) {
        //? if <1.21.6 {
        return (RenderType) layer;
        //?} elif <1.21.11 {
        /^switch ((ChunkSectionLayer) layer) {
            case SOLID: return RenderType.solid();
            case CUTOUT_MIPPED: return RenderType.cutoutMipped();
            case CUTOUT: return RenderType.cutout();
            case TRIPWIRE: return RenderType.tripwire();
            default: return RenderType.translucentMovingBlock();
        }
        ^///?} else {
        /^switch ((ChunkSectionLayer) layer) {
            case SOLID: return RenderTypes.solidMovingBlock();
            case CUTOUT: return RenderTypes.cutoutMovingBlock();
            //? if <26.1 {
            case TRIPWIRE: return RenderTypes.tripwireMovingBlock();
            //?}
            default: return RenderTypes.translucentMovingBlock();
        }
        ^///?}
    }
    *///?}

    //? if >=1.21.10 {
    /*// Each block entity extracted and submitted by its renderer, in full light: the dispatcher would cull by distance
    // from the client world's camera and light by the client world
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void submitBlockEntities(Pocket p, double ox, double oy, double oz, float partialTick,
                                     SubmitNodeCollector collector, CameraRenderState camera) {
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = new PoseStack();
        for (Section s : p.sections.values()) {
            for (BlockEntity be : s.blockEntities) {
                BlockEntityRenderer renderer = mc.getBlockEntityRenderDispatcher().getRenderer(be);
                if (renderer == null) continue;
                BlockEntityRenderState state = renderer.createRenderState();
                renderer.extractRenderState(be, state, partialTick, camera.pos, null);
                state.lightCoords = FULL_LIGHT;
                BlockPos pos = be.getBlockPos();
                pose.pushPose();
                pose.translate((float) (pos.getX() - ox), (float) (pos.getY() - oy), (float) (pos.getZ() - oz));
                renderer.submit(state, pose, collector, camera);
                pose.popPose();
            }
        }
    }
    *///?}

    //? if <1.21.10 {
    // Each block entity by its renderer, in full light: the dispatcher would cull by distance and light by the world
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void drawBlockEntities(Pocket p, Matrix4fc view, double ox, double oy, double oz, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack pose = new PoseStack();
        //? if >=1.19.3 {
        pose.last().pose().set(view);
        pose.last().normal().set(new Matrix4f(view).normal(new Matrix3f()));
        //?} elif >=1.17 {
        /*MojangMatrices.pose(pose.last(), view);
        *///?} else {
        /*viewPose(pose, view);
        *///?}
        for (Section s : p.sections.values()) {
            for (BlockEntity be : s.blockEntities) {
                //? if >=1.17 {
                BlockEntityRenderer renderer = mc.getBlockEntityRenderDispatcher().getRenderer(be);
                //?} else {
                /*BlockEntityRenderer renderer = BlockEntityRenderDispatcher.instance.getRenderer(be);
                *///?}
                if (renderer == null) continue;
                BlockPos pos = be.getBlockPos();
                pose.pushPose();
                pose.translate((float) (pos.getX() - ox), (float) (pos.getY() - oy), (float) (pos.getZ() - oz));
                //? if <1.21.5 {
                renderer.render(be, partialTick, pose, buffers, FULL_LIGHT, OverlayTexture.NO_OVERLAY);
                //?} else {
                /*renderer.render(be, partialTick, pose, buffers, FULL_LIGHT, OverlayTexture.NO_OVERLAY, new Vec3(ox, oy, oz));
                *///?}
                pose.popPose();
            }
        }
        buffers.endBatch();
    }
    //?}

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
//?} else {
/*// Pocket levels on this node: none yet, CgPocketLevel.Host.NONE answers
final class PocketLevelsModern {
}
*///?}
