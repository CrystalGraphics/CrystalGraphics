package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.render.CgGpuBudget;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Chunks drawn into textures a pass declares before any draw: its colours and depth with their loads, what it reads,
 * its pass constants, the render state its pipelines' unset slots take, and how its draws may be batched. Unreal's
 * render-graph pass with its parameters. Made with {@link CgRecording#raster(String)}; ended before the recording is
 * sealed.
 *
 * <pre>{@code
 * CgRasterPass pass = rec.raster("ui.layer")
 *         .color(layer, CgLoad.CLEAR)
 *         .constants(constants).state(uiState).order(CgOrder.LOOKBACK);
 * pass.add(chunk);
 * pass.add(otherChunk);
 * pass.end();                // the layer's contents are complete: a draw recorded after this reads them
 *
 * rec.raster("world.glow")
 *         .color(glow, CgLoad.CLEAR)
 *         .color(normals, CgLoad.load())                                 // colour 1
 *         .depth(sceneDepth, CgLoad.load(), CgDepthAccess.WRITE)
 *         .reads(noise)                                                   // a graph texture its draws sample
 *         .readsDepth(sceneDepth, CgBindingPoints.DEPTH_TEXTURE_UNIT)     // cg_DepthBuffer
 *         .constants(constants);
 * }</pre>
 *
 * <ul>
 *   <li>What a pass reads is ordered as of its declaration: the graph runs whatever wrote it before, whenever either
 *       pass was made. Declare it before the draw that reads it; a pass held open while another records (a UI layer
 *       composited into its parent) declares it after that pass, where the layer is drawn.</li>
 *   <li>A draw names what it reads itself as it is added: the graph textures its bindings sample, the graph buffers
 *       it binds as storage, its objects and its indirect count. Those are the pass's reads from there, as a
 *       declaration is. A step binding a graph resource that neither its pass declared nor a draw named is refused
 *       where the undeclared-access check is on (a dev run, the harness, tests; {@link CgGraphCheck}).</li>
 *   <li>A recorder that must read its own target mid-pass (a backdrop) ends the pass, copies, and opens another
 *       on the same target with {@link CgLoad#load()}.</li>
 *   <li>A chunk is drawn under the scissor set when it was added, as a command buffer's set-scissor works:
 *       {@link #scissor(int, int, int, int)}, {@link #useScissor} and {@link #noScissor}. A pass that never sets
 *       one leaves GL's scissor as it finds it. A scissor given in a spatial node's space moves with the node, as
 *       its draws do, and one made inside another is cut by it wherever either moves.</li>
 *   <li>A pass into a layer names where the layer sits in the recording's root space with {@link #view}: what a
 *       record under a spatial node is placed through. Records at node 0 are already in the target's space.</li>
 *   <li>{@link #texture} binds a texture for the whole pass, with its constants: what every draw of it samples at a
 *       fixed unit.</li>
 *   <li>{@link #readsColor} and {@link #readsDepth} of its own colour let its draws sample the target itself: a draw
 *       whose shader reads {@code cg_SceneColor} or {@code cg_DepthBuffer} sees every draw of the pass sorted before
 *       it. The graph copies the target before the first such draw and again wherever a draw since wrote what it
 *       reads; readers in a row share a copy, so they never see each other. A colour copy is cut to its readers'
 *       bounds grown by their shader's {@code SceneColorMargin} tag, so a reader with no bounds copies the whole
 *       target. Of another texture, it is copied once, whole, when the pass begins.</li>
 *   <li>{@link #damage} limits the pass to what changed in a target that keeps its contents: its clear and every
 *       draw are cut to the rect, and an empty rect executes nothing at all.</li>
 * </ul>
 */
public final class CgRasterPass extends CgPass {

    final CgRecording recording;
    // Not final: the recording reuses the pass after a reset (reuse).
    /** The mip level of the target it draws into. */
    int level;
    /** The layer of an array target it draws into; 0 for any other. */
    int layer;
    /** Its whole load: its colour's, with its depth's depth and stencil. */
    CgLoad load;
    private CgLoad colorLoad, depthLoad;
    /** The depth it declares, or null; and what it does with it. */
    @Nullable
    private CgGraphTexture depth;
    /** Whether it named a depth: its own declared, or its target's when it draws depth alone. */
    private boolean depthNamed;
    private CgDepthAccess depthAccess;
    final float[] constants = new float[CgPassConstants.FLOATS];
    @Nullable
    CgRenderState state;
    CgOrder order;
    private final List<CgDrawChunk> chunks = new ArrayList<>();
    private boolean ended;
    /** What changed in the target, in its bottom-left pixels -- x, y, width, height -- or null for all of it. */
    @Nullable
    private int[] damage;


    /** A chunk's scissor: an index into {@link #scissorRects}, {@link #NO_SCISSOR}, or {@link #INHERIT}. */
    static final int NO_SCISSOR = -1, INHERIT = -2;
    private int[] chunkScissor = new int[16];
    private int[] scissorRects = new int[16];
    /** Per scissor, the spatial node its box is in, or -1 for a rect in target pixels; and the one it is inside. */
    private int[] scissorNodes = new int[4];
    private int[] scissorParents = new int[4];
    private float[] scissorBoxes = new float[16];
    private int scissorCount;
    private int scissor = INHERIT;

    private int viewOwner;
    private float viewX, viewY;

    /** The units its draws sample the target's colour and depth at, or -1. */
    private int sceneColorUnit = -1, sceneDepthUnit = -1;
    /** Whether its first draw writes every pixel of its target ({@link #replaces}). */
    private boolean replaces;
    /** Its copy of its target, made when it first declares a read of it. */
    @Nullable
    private CgTargetCopy targetCopy;
    /** Another target whose depth its draws sample ({@link #readsDepth}), the unit, and the copy. */
    @Nullable
    private CgGraphTexture depthFrom;
    private int depthFromUnit = -1;
    @Nullable
    private CgTargetCopy depthFromCopy;
    /** Another target whose colour its draws sample ({@link #readsColor}), the unit, and the copy. */
    @Nullable
    private CgGraphTexture colorFrom;
    private int colorFromUnit = -1;
    @Nullable
    private CgTargetCopy colorFromCopy;

    /** Its second colour attachment, or null; and whether a target that takes none draws without it. */
    @Nullable
    private CgGraphTexture attachment;
    private boolean attachmentOptional;

    /** What {@link #draw} draws through, made at its first draw and kept across the pass's reuse; null until then. */
    @Nullable
    private CgDraw drawer;
    /** What {@link #camera} writes the constants through, made at its first camera. */
    @Nullable
    private CgPassConstants cameraConstants;

    /** Textures bound with the pass's constants: units and textures, in parallel. */
    private int[] textureUnits = NO_UNITS;
    private CgTexture[] textures = NO_TEXTURES;
    /** How many of them this pass binds: the arrays are kept, grown by doubling, across the pass's reuse. */
    private int textureCount;

    CgRasterPass(CgRecording recording, String name, CgPassConstants constants) {
        super(name, null, null);
        this.recording = recording;
        reuse(name, constants);
    }

    /** Makes this pass a new one named {@code name}: its recording's, after a reset, so a steady frame allocates none. */
    void reuse(String name, CgPassConstants constants) {
        this.name = name;
        this.target = null;
        level = 0;
        layer = 0;
        load = colorLoad = depthLoad = CgLoad.load();
        depth = null;
        depthNamed = false;
        depthAccess = CgDepthAccess.WRITE;
        constants.write(this.constants, 0);
        state = null;
        order = CgOrder.SORTED;
        gpuZone = -1;
        budget = null;
        chunks.clear();
        ended = false;
        damage = null;
        scissorCount = 0;
        scissor = INHERIT;
        viewOwner = 0;
        viewX = 0f;
        viewY = 0f;
        sceneColorUnit = -1;
        sceneDepthUnit = -1;
        replaces = false;
        targetCopy = null;
        depthFrom = null;
        depthFromUnit = -1;
        depthFromCopy = null;
        colorFrom = null;
        colorFromUnit = -1;
        colorFromCopy = null;
        attachment = null;
        attachmentOptional = false;
        dropTextures();
    }

    private void dropTextures() {
        Arrays.fill(textures, 0, textureCount, null);
        textureCount = 0;
    }

    /** Drops what the pass names once its recording is reset, keeping its arrays for the next reuse. */
    @Override
    void clearReferences() {
        if (drawer != null) drawer.reset();
        chunks.clear();
        target = null;
        depth = null;
        state = null;
        depthFrom = null;
        colorFrom = null;
        attachment = null;
        targetCopy = depthFromCopy = colorFromCopy = null;
        dropTextures();
    }

    private static final int[] NO_UNITS = new int[0];
    private static final CgTexture[] NO_TEXTURES = new CgTexture[0];

    /**
     * Draws {@code mesh} under {@code material}: one instance, more with {@link CgDraw#instance()}, each an object
     * record set through what it answers. In painter's order with the chunks added.
     *
     * <pre>{@code
     * CgRasterPass pass = rec.raster("preview")
     *         .color(picture, CgLoad.CLEAR)
     *         .depth(depth, CgLoad.CLEAR_DEPTH, CgDepthAccess.WRITE)
     *         .camera(CgCamera.perspective(60f, 1f, 0.05f, 100f).lookAt(eye, target));
     * pass.draw(mesh, material).transform(model).customData(0, r, g, b, 1f);
     * pass.end();
     * }</pre>
     *
     * <ul>
     *   <li>Its target and depth are declared first: the draw is the pass's from here.</li>
     *   <li>What it answers is valid until the pass's next {@code draw}, {@code add} or {@code end}.</li>
     * </ul>
     */
    public CgDraw draw(CgMesh mesh, CgMaterial material) {
        declaring();
        requireTarget();
        if (drawer == null) drawer = new CgDraw(this, recording);
        return drawer.begin(mesh, material);
    }

    /**
     * What its draws are seen through: view, projection, position, resolution and time. The depth convention and the
     * target's encoding are the target's. Copied now: changing {@code camera} afterwards changes no recorded pass.
     */
    public CgRasterPass camera(CgCamera camera) {
        declaring();
        CgGraphTexture sized = target != null ? target : depth;
        if (sized == null) throw new IllegalStateException(this + " names its camera before its colour or depth");
        float width = 0f, height = 0f;
        if (camera.width() > 0f && camera.height() > 0f) {
            width = camera.width();
            height = camera.height();
        } else if (sized.isCurrent()) {
            CgExecutor.Into open = CgGraphDevice.get().current;
            if (open != null && open.target != null) {
                width = open.target.width();
                height = open.target.height();
            }
        } else {
            width = Math.max(1, sized.desc().width() >> level);
            height = Math.max(1, sized.desc().height() >> level);
        }
        if (cameraConstants == null) cameraConstants = new CgPassConstants();
        cameraConstants.seenThrough(camera, width, height);
        return constants(cameraConstants);
    }

    /** Appends a chunk, in painter's order. */
    public CgRasterPass add(CgDrawChunk chunk) {
        if (drawer != null) drawer.land();
        return addDrawn(chunk);
    }

    /** {@link #add}, for what {@link #draw} drew. */
    CgRasterPass addDrawn(CgDrawChunk chunk) {
        declaring();
        requireTarget();
        if (chunks.size() == chunkScissor.length) chunkScissor = Arrays.copyOf(chunkScissor, chunkScissor.length * 2);
        chunkScissor[chunks.size()] = scissor;
        chunks.add(chunk);
        CgBindingTable table = chunk.bindings();
        for (int d = 0; d < chunk.draws(); d++) {
            if (chunk.indirectCount(d) instanceof CgGraphBuffer count) named(count, CgAccess.COMPUTE_READ);
            if (chunk.objects(d) instanceof CgGraphBuffer objects) {
                named(objects, CgAccess.VERTEX_READ | CgAccess.FRAGMENT_READ);
            }
            for (int k = 0; k < CgDrawChunk.BUFFERS; k++) {
                if (chunk.buffer(d, k) instanceof CgGraphBuffer read) named(read, CgAccess.VERTEX_READ | CgAccess.FRAGMENT_READ);
            }
            int id = chunk.binding(d);
            for (int t = 0; t < table.textures(id); t++) {
                CgGraphTexture graph = CgGraphTexture.sampled(table.texture(id, t));
                if (graph != null) {
                    requireNotOwnArray(graph);
                    named(graph, CgAccess.SAMPLED_READ);
                }
            }
            for (int s = 0; s < table.storages(id); s++) {
                if (table.storage(id, s) instanceof CgGraphBuffer buffer) {
                    named(buffer, CgAccess.VERTEX_READ | CgAccess.FRAGMENT_READ);
                }
            }
        }
        return this;
    }

    /**
     * Draws into {@code texture}: its first colour, level 0 and layer 0, or a second beside it. {@code load} clears
     * colour or keeps it; a depth is declared with {@link #depth}.
     *
     * <pre>{@code
     * rec.raster("ui.layer").color(layer, CgLoad.CLEAR);
     * rec.raster("world.opaque").color(scene, CgLoad.load()).color(normals, CgLoad.load());   // normals are colour 1
     * }</pre>
     *
     * <ul>
     *   <li>A second colour loads as the first does (one colour load a pass), has the first's size, one colour slot
     *       and no depth, beside a first drawn at level 0 and layer 0. A pipeline writes it only where its program has a
     *       location-1 output ({@code CgPipeline.slotWrites}); for every other draw the slot is masked off.</li>
     *   <li>Beside an external target, the target must lend its attachments ({@code CgExternalTarget.lendsAttachments()}):
     *       the window's lends none, and throws; {@link #colorIfTaken} draws without it instead.</li>
     *   <li>One blend serves both slots below GL 4.0: what a pipeline writes at location 1 must suit the pass's.</li>
     * </ul>
     */
    public CgRasterPass color(CgGraphTexture texture, CgLoad load) {
        return target == null ? color(texture, 0, 0, load) : second(texture, load);
    }

    /**
     * Draws into mip level {@code level} and layer {@code layer} of {@code texture}, its first colour: one step of a chain
     * drawn level by level, reading the level before it through {@link CgGraphTexture#level}, or one layer of an array.
     *
     * <pre>{@code
     * rec.raster("bloom.down").color(bloom, 2, 0, CgLoad.load()).constants(constants.resolution(w >> 2, h >> 2));
     * for (int k = 0; k < used; k++) rec.raster("field.slot").color(field, 0, k, CgLoad.CLEAR);
     * }</pre>
     *
     * <ul>
     *   <li>The viewport is the level's size, which the constants' resolution should be too; a level above 0 has no
     *       depth.</li>
     *   <li>Each layer of an array is a target of its own: its load clears or keeps that layer alone. Sampled, the array
     *       is read whole, so no pass draws into an array it samples.</li>
     * </ul>
     */
    public CgRasterPass color(CgGraphTexture texture, int level, int layer, CgLoad load) {
        declaring();
        if (target != null) {
            if (level != 0 || layer != 0) throw new IllegalArgumentException(this + ": a second colour is level 0, layer 0");
            return second(texture, load);
        }
        requireColorLoad(load);
        if (level < 0 || level >= texture.getLevels()) throw new IllegalArgumentException(texture + " has no level " + level);
        if (texture.isVolume()) {
            throw new IllegalArgumentException(texture + " is a volume: kernels write it as a 3d image, and no pass draws into one");
        }
        if (layer < 0 || layer >= texture.getLayers()) {
            throw new IllegalArgumentException(texture + " has " + texture.getLayers() + " layers, not layer " + layer);
        }
        target = texture;
        this.level = level;
        this.layer = layer;
        colorLoad = load;
        this.load = CgLoad.of(colorLoad, depthLoad);
        encodeTarget();
        return this;
    }

    /**
     * As a second {@link #color}, for an optimisation with a fallback of its own: where the target takes no second
     * attachment (an external one lending none, or one of another size), the pass draws into the first alone, what its
     * draws write at location 1 is lost, and it says why once in the log.
     */
    public CgRasterPass colorIfTaken(CgGraphTexture texture, CgLoad load) {
        if (target == null) throw new IllegalStateException(this + ": colorIfTaken is a second colour; declare color(...) first");
        second(texture, load);
        attachmentOptional = true;
        return this;
    }

    private CgRasterPass second(CgGraphTexture texture, CgLoad load) {
        declaring();
        if (attachment != null) throw new IllegalStateException(this + " draws into two colours already");
        if (!sameColorLoad(load, colorLoad)) throw new IllegalArgumentException(this + ": a second colour loads as the first");
        if (level != 0 || layer != 0) throw new IllegalStateException(this + " draws level " + level + " layer " + layer
                + ", and takes a second colour only at level 0, layer 0");
        if (texture == target) throw new IllegalArgumentException(this + " already draws into " + texture);
        attachment = texture;
        recording.read(this, texture, CgAccess.COLOR_WRITE);
        return this;
    }

    /**
     * Tests against {@code texture}'s depth, and writes it unless {@code access} is {@link CgDepthAccess#READ}.
     * {@code load} clears depth and stencil or keeps them. A pass declaring a depth and no colour draws depth alone.
     *
     * <pre>{@code
     * rec.raster("preview").color(picture, CgLoad.clear(0, 0, 0, 1)).depth(pictureDepth, CgLoad.CLEAR_DEPTH, CgDepthAccess.WRITE);
     * rec.raster("world.base").color(scene, CgLoad.load()).depth(CgGraphTexture.current(), CgLoad.load(), CgDepthAccess.WRITE);
     * }</pre>
     *
     * <ul>
     *   <li>{@code texture} is a depth texture, or an external target, whose depth is the one it lends. An external
     *       target's depth is its owner's: never cleared here.</li>
     *   <li>Beside a colour of another texture, it has the colour's size, or the pass draws without it and logs why.</li>
     * </ul>
     */
    public CgRasterPass depth(CgGraphTexture texture, CgLoad load, CgDepthAccess access) {
        declaring();
        if (depth != null) throw new IllegalStateException(this + " declares a depth already: " + depth);
        if (load.clearsColor()) throw new IllegalArgumentException(this + ": a depth's load clears depth, not colour");
        if (texture.kind() == CgGraphTexture.Kind.EXTERNAL && (load.clearsDepth() || load.clearsStencil())) {
            throw new IllegalArgumentException(this + " would clear " + texture + "'s depth, someone else's");
        }
        if (texture.kind() != CgGraphTexture.Kind.EXTERNAL && texture.desc() != null && !texture.desc().isDepth()) {
            throw new IllegalArgumentException(this + ": " + texture + " is no depth texture");
        }
        depth = texture;
        depthNamed = true;
        depthLoad = load;
        depthAccess = java.util.Objects.requireNonNull(access, "access");
        this.load = CgLoad.of(colorLoad, depthLoad);
        return this;
    }

    /**
     * Samples {@code texture} in its draws, a graph texture or a view of one ({@code texture.level(k)}): the graph runs
     * whatever wrote it first.
     */
    public CgRasterPass reads(CgTexture texture) {
        declaring();
        CgGraphTexture graph = CgGraphTexture.sampled(texture);
        if (graph == null) throw new IllegalArgumentException(this + " reads " + texture + ": a graph texture is declared, any other is a material's");
        if (target != null) requireNotOwnArray(graph);
        recording.read(this, graph, CgAccess.SAMPLED_READ);
        return this;
    }

    /** Reads {@code buffer} in its draws, as storage or vertex data: the graph runs whatever wrote it first. */
    public CgRasterPass reads(CgGraphBuffer buffer) {
        declaring();
        recording.read(this, buffer, CgAccess.VERTEX_READ | CgAccess.FRAGMENT_READ);
        return this;
    }

    /** Takes its indirect draws' arguments or counts from {@code buffer}, which a kernel writes first. */
    public CgRasterPass indirect(CgGraphBuffer buffer) {
        declaring();
        recording.read(this, buffer, CgAccess.COMPUTE_READ);
        return this;
    }

    /**
     * Lets draws whose shader reads {@code cg_SceneColor} sample {@code texture}'s colour at {@code unit}. Of its own
     * colour, as it stands after every draw sorted before them; of another texture, as it stands after every write to
     * it recorded before this call, copied once, whole, when the pass begins, in that texture's format. Declare its
     * colour first.
     *
     * <pre>{@code
     * rec.raster("world.distortion").color(scene, CgLoad.load())
     *         .readsColor(scene, CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT);
     * rec.raster("world.scene").color(scene, CgLoad.load())
     *         .readsColor(CgGraphTexture.current(), CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT);   // the host's picture
     * }</pre>
     *
     * <ul>
     *   <li>A pass into a mip level or layer above 0 reads no copy of its own colour, and throws.</li>
     * </ul>
     */
    public CgRasterPass readsColor(CgGraphTexture texture, int unit) {
        declaring();
        requireTarget();
        return texture == target ? ownColor(unit) : colorFrom(unit, texture);
    }

    /**
     * Lets draws whose shader reads {@code cg_DepthBuffer} sample {@code texture}'s depth at {@code unit}. Of its own
     * colour's depth, as it stands after every draw sorted before them; of another texture, as it stands after every
     * write to it recorded before this call, copied once, whole, when the pass begins, at that texture's size, so readers
     * sample by normalized uv ({@code gl_FragCoord.xy / CG_RESOLUTION}). Declare its colour first.
     *
     * <pre>{@code
     * rec.raster("world.glow").color(glow, CgLoad.CLEAR)
     *         .readsDepth(CgGraphTexture.current(), CgBindingPoints.DEPTH_TEXTURE_UNIT);   // occluded by the scene
     * }</pre>
     *
     * <ul>
         *   <li>Throws here where {@code texture} has no depth, and at execution where an external target has none.</li>
     *   <li>Give the pass the host's depth convention ({@code CgStageFrame.defaults}), or eye depths disagree.</li>
     * </ul>
     */
    public CgRasterPass readsDepth(CgGraphTexture texture, int unit) {
        declaring();
        requireTarget();
        return texture == target || texture == depth ? ownDepth(unit) : depthFrom(unit, texture);
    }

    /** The block its draws read as the frame's: time, camera, resolution. The recording's identity block unless set. */
    public CgRasterPass constants(CgPassConstants constants) {
        declaring();
        constants.write(this.constants, 0);
        encodeTarget();
        return this;
    }

    /** The render state its pipelines' unset slots take; null, the default, leaves them as the target has them. */
    public CgRasterPass state(@Nullable CgRenderState state) {
        declaring();
        this.state = state;
        return this;
    }

    /** How its draws may be reordered for batching: {@link CgOrder#SORTED} unless set. */
    public CgRasterPass order(CgOrder order) {
        declaring();
        this.order = java.util.Objects.requireNonNull(order, "order");
        return this;
    }

    /** What a draw names, its own declaration as it is added (renderer.md § 4.2): read by the pass from here. */
    private void named(CgGraphResource view, int access) {
        recording.read(this, view, access);
    }

    /** CG_ENCODED_TARGET: the host's own colour is sRGB-encoded, every texture of ours holds what is written. */
    private void encodeTarget() {
        if (target != null) constants[CgPassConstants.ENCODED_TARGET] = target.isCurrent() ? 1f : 0f;
    }

    private void declaring() {
        if (ended) throw new IllegalStateException(this + " has ended");
        recording.requireOpen();
    }

    private static boolean sameColorLoad(CgLoad a, CgLoad b) {
        return a.color() == b.color() && (!a.clearsColor() || a.r() == b.r() && a.g() == b.g() && a.b() == b.b() && a.a() == b.a());
    }

    private static void requireColorLoad(CgLoad load) {
        if (load.clearsDepth() || load.clearsStencil()) {
            throw new IllegalArgumentException("a colour's load clears colour: clear depth and stencil in depth(...)");
        }
    }

    /** Its target: its first colour, or the depth of a pass drawing depth alone. */
    private void requireTarget() {
        if (target != null) return;
        if (depth == null) throw new IllegalStateException(this + " declares no colour or depth");
        target = depth;
        depth = null;
        encodeTarget();
    }

    /** What its draws test and write: its declared depth, else its target, whose own depth it may be. */
    CgGraphTexture depthTexture() {
        return depth != null ? depth : target != null ? target : CgGraphTexture.current();
    }

    /** A depth of another texture it draws with, or null for its target's own or none. */
    @Nullable
    CgGraphTexture depthBeside() {
        return depth == null || depth == target ? null : depth;
    }

    /** Whether it draws with a depth: one it declared, or its target's where it draws depth alone. */
    boolean depthNamed() {
        return depthNamed;
    }

    /** What it does with its depth. */
    CgDepthAccess depthAccess() {
        return depthAccess;
    }

    /**
     * Times it on the GPU on its own, under {@code zone}, a name made once with {@code CgGpuTrace.name}. Inside a stage
     * it splits the stage's own zone, which GPU zones' not nesting allows.
     */
    public CgRasterPass timed(int zone) {
        if (ended) throw new IllegalStateException(this + " has ended");
        recording.requireOpen();
        gpuZone = zone;
        return this;
    }

    /**
     * Charges its GPU time to {@code budget}, which scales its consumer's work to fit. Timed whatever the trace, under
     * the budget's zone unless {@link #timed(int)} names one.
     */
    public CgRasterPass timed(CgGpuBudget budget) {
        if (ended) throw new IllegalStateException(this + " has ended");
        recording.requireOpen();
        this.budget = Objects.requireNonNull(budget, "budget");
        return this;
    }

    /** Draws the chunks added from now on inside {@code (x, y, w, h)}, in the target's bottom-left pixels. */
    public CgRasterPass scissor(int x, int y, int w, int h) {
        return useScissor(scissor(-1, x, y, w, h));
    }

    /**
     * A scissor inside scissor {@code parent} (-1 for none): {@code (x, y, w, h)} in the target's bottom-left pixels.
     * Answers its index for {@link #useScissor}, and for a scissor made inside it.
     */
    public int scissor(int parent, int x, int y, int w, int h) {
        int last = scissorCount - 1, set = last * 4;
        if (last >= 0 && scissorNodes[last] < 0 && scissorParents[last] == parent && scissorRects[set] == x
                && scissorRects[set + 1] == y && scissorRects[set + 2] == w && scissorRects[set + 3] == h) return last;
        int at = nextScissor(-1, parent);
        scissorRects[at] = x;
        scissorRects[at + 1] = y;
        scissorRects[at + 2] = w;
        scissorRects[at + 3] = h;
        return at / 4;
    }

    /**
     * A scissor inside scissor {@code parent} (-1 for none): {@code (x0, y0)-(x1, y1)} in spatial node {@code node}'s
     * space, top-down, whose bounds in the target are taken as the node stands when the pass executes, whole pixels
     * outward. Answers its index.
     */
    public int scissor(int parent, int node, float x0, float y0, float x1, float y1) {
        int last = scissorCount - 1, set = last * 4;
        if (last >= 0 && scissorNodes[last] == node && scissorParents[last] == parent && scissorBoxes[set] == x0
                && scissorBoxes[set + 1] == y0 && scissorBoxes[set + 2] == x1 && scissorBoxes[set + 3] == y1) return last;
        int at = nextScissor(node, parent);
        scissorBoxes[at] = x0;
        scissorBoxes[at + 1] = y0;
        scissorBoxes[at + 2] = x1;
        scissorBoxes[at + 3] = y1;
        return at / 4;
    }

    /** Draws the chunks added from now on under scissor {@code index}, which this pass answered. */
    public CgRasterPass useScissor(int index) {
        if (index < 0 || index >= scissorCount) throw new IllegalArgumentException("no scissor " + index + " in " + this);
        scissor = index;
        return this;
    }

    /**
     * Executes only what lies in {@code (x, y, width, height)}, the target's bottom-left pixels: the clear and every
     * draw cut to it, the rest of the target kept as it was -- a surface whose window changed in one place. An empty
     * rect executes nothing. May be set after {@link #end}, until the recording is sealed.
     *
     * <pre>{@code
     * pass.end();
     * pass.damage(x, y, w, h);     // a caret blinked: 2 x 18 pixels of a window drawn again
     * }</pre>
     */
    public CgRasterPass damage(int x, int y, int width, int height) {
        recording.requireOpen();
        damage = new int[] {x, y, Math.max(0, width), Math.max(0, height)};
        return this;
    }

    /** The rect {@link #damage} limits the pass to, x, y, width, height; null when it draws the whole target. */
    @Nullable
    public int[] damage() {
        return damage;
    }

    /**
     * Places this pass's target at {@code (originX, originY)} in the recording's root space, moving with spatial node
     * {@code owner}: a layer whose content was recorded under {@code owner} and is composited back through it. A
     * pass with no view is the root's own target, at the origin.
     */
    public CgRasterPass view(int owner, float originX, float originY) {
        viewOwner = owner;
        viewX = originX;
        viewY = originY;
        return this;
    }

    int viewOwner() {
        return viewOwner;
    }

    float viewX() {
        return viewX;
    }

    float viewY() {
        return viewY;
    }

    private int nextScissor(int node, int parent) {
        if (parent < -1 || parent >= scissorCount) throw new IllegalArgumentException("no scissor " + parent + " in " + this);
        if ((scissorCount + 1) * 4 > scissorRects.length) {
            scissorRects = Arrays.copyOf(scissorRects, scissorRects.length * 2);
            scissorBoxes = Arrays.copyOf(scissorBoxes, scissorRects.length);
        }
        if (scissorCount == scissorNodes.length) {
            scissorNodes = Arrays.copyOf(scissorNodes, scissorNodes.length * 2);
            scissorParents = Arrays.copyOf(scissorParents, scissorNodes.length);
        }
        scissorNodes[scissorCount] = node;
        scissorParents[scissorCount] = parent;
        return scissorCount++ * 4;
    }

    /** Binds {@code texture} at {@code unit} for every draw of this pass. A graph texture is read as of this call. */
    public CgRasterPass texture(int unit, CgTexture texture) {
        declaring();
        int n = textureCount++;
        if (n == textureUnits.length) {
            textureUnits = Arrays.copyOf(textureUnits, Math.max(4, n * 2));
            textures = Arrays.copyOf(textures, textureUnits.length);
        }
        textureUnits[n] = unit;
        textures[n] = texture;
        CgGraphTexture graph = CgGraphTexture.sampled(texture);
        if (graph != null) {
            if (target != null) requireNotOwnArray(graph);
            recording.read(this, graph, CgAccess.SAMPLED_READ);
        }
        return this;
    }

    /** An array is sampled whole, so a pass reading the one it draws into reads the layer it is writing too. */
    private void requireNotOwnArray(CgGraphTexture graph) {
        if (graph == target && graph.isArray()) {
            throw new IllegalArgumentException(this + " samples the array it draws into: draw each layer from another texture");
        }
    }

    private CgRasterPass ownColor(int unit) {
        requireLevelZero();
        sceneColorUnit = unit;
        if (targetCopy == null) targetCopy = keptCopy();
        return this;
    }

    /**
     * Says its first draw writes every pixel of its target, unblended, discarding none: a full-screen post pass. Its
     * {@link #readsColor} of its own colour then needs no copy of the target, as Unreal's post passes write a new
     * texture rather than copy SceneColor: the pass draws into fresh storage and samples the target's storage as it
     * found it.
     *
     * <pre>{@code
     * rec.raster("post.grade").color(scene, CgLoad.load())
     *         .readsColor(scene, CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT).replaces();
     * }</pre>
     *
     * <ul>
     *   <li>A pixel its first draw leaves out holds whatever the pool's storage held: never declare it otherwise.</li>
     *   <li>Taken only where the target is a transient of one colour slot and no depth of its own (the HDR scene),
     *       drawn whole at level 0 with no clear; anywhere else the copy is taken as before.</li>
     * </ul>
     */
    public CgRasterPass replaces() {
        declaring();
        replaces = true;
        return this;
    }

    boolean replacesTarget() {
        return replaces;
    }

    private CgRasterPass ownDepth(int unit) {
        requireLevelZero();
        sceneDepthUnit = unit;
        if (targetCopy == null) targetCopy = keptCopy();
        return this;
    }

    /** The copy this pass made once, reused by its later uses: its storage goes back to the pool as each ends. */
    private CgTargetCopy keptCopy() {
        if (kept == null) kept = new CgTargetCopy();
        return kept;
    }

    private CgTargetCopy kept, keptDepthFrom, keptColorFrom;

    /** A target copy is of level 0 and layer 0: a pass drawing another would read the wrong picture. */
    private void requireLevelZero() {
        if (level != 0) throw new IllegalStateException(this + " draws level " + level + ", and reads no copy of its target");
        if (layer != 0) throw new IllegalStateException(this + " draws layer " + layer + ", and reads no copy of its target");
    }

    private CgRasterPass depthFrom(int unit, CgGraphTexture from) {
        if (from.desc() != null && !from.desc().format().hasDepth()) {
            throw new IllegalArgumentException(this + " reads the depth of " + from + ", which has none: name its depth texture");
        }
        depthFrom = from;
        depthFromUnit = unit;
        if (depthFromCopy == null) depthFromCopy = keptDepthFrom != null ? keptDepthFrom : (keptDepthFrom = new CgTargetCopy());
        recording.read(this, from, CgAccess.SAMPLED_READ);
        return this;
    }

    private CgRasterPass colorFrom(int unit, CgGraphTexture from) {
        colorFrom = from;
        colorFromUnit = unit;
        if (colorFromCopy == null) colorFromCopy = keptColorFrom != null ? keptColorFrom : (keptColorFrom = new CgTargetCopy());
        recording.read(this, from, CgAccess.SAMPLED_READ);
        return this;
    }

    @Nullable
    CgGraphTexture colorFrom() {
        return colorFrom;
    }

    int colorFromUnit() {
        return colorFromUnit;
    }

    @Nullable
    CgTargetCopy colorFromCopy() {
        return colorFromCopy;
    }

    @Nullable
    CgGraphTexture depthFrom() {
        return depthFrom;
    }

    int depthFromUnit() {
        return depthFromUnit;
    }

    @Nullable
    CgTargetCopy depthFromCopy() {
        return depthFromCopy;
    }

    int sceneColorUnit() {
        return sceneColorUnit;
    }

    int sceneDepthUnit() {
        return sceneDepthUnit;
    }

    @Nullable
    CgTargetCopy targetCopy() {
        return targetCopy;
    }

    int textureCount() {
        return textureCount;
    }

    int textureUnit(int i) {
        return textureUnits[i];
    }

    CgTexture texture(int i) {
        return textures[i];
    }

    boolean attachmentOptional() {
        return attachmentOptional;
    }

    /** Its second colour, or null. */
    @Nullable
    CgGraphTexture attachment() {
        return attachment;
    }

    /** Draws the chunks added from now on unscissored. */
    public CgRasterPass noScissor() {
        scissor = NO_SCISSOR;
        return this;
    }

    /** Ends the pass: what it drew is now what a later read of its target sees. */
    public void end() {
        if (ended) throw new IllegalStateException(this + " has already ended");
        if (drawer != null) drawer.land();
        recording.requireOpen();
        requireTarget();
        ended = true;
        CgGraphTexture beside = depthBeside();
        if (beside != null) {
            // It tests and writes another's depth: ordered after what wrote it, before what reads it.
            recording.read(this, beside, CgAccess.SAMPLED_READ);
            recording.write(this, beside, CgAccess.COLOR_WRITE);
        }
        recording.write(this, target, CgAccess.COLOR_WRITE);
        if (attachment != null) recording.write(this, attachment, CgAccess.COLOR_WRITE);
    }

    /** The mip level of its target it draws into: 0 unless made with one. */
    public int level() {
        return level;
    }

    /** The layer of an array target it draws into: 0 unless made with one. */
    public int layer() {
        return layer;
    }

    public List<CgDrawChunk> chunks() {
        return Collections.unmodifiableList(chunks);
    }

    boolean ended() {
        return ended;
    }

    List<CgDrawChunk> chunkList() {
        return chunks;
    }

    /** Chunk {@code i}'s scissor. @see #NO_SCISSOR */
    int chunkScissor(int i) {
        return chunkScissor[i];
    }

    /** The scissor rects {@link #chunkScissor} indexes, four ints each: {@code x, y, w, h}. */
    int[] scissorRects() {
        return scissorRects;
    }

    /** Scissor {@code i}'s spatial node, or -1 when it is a rect in {@link #scissorRects}. */
    int scissorNode(int i) {
        return scissorNodes[i];
    }

    /** The scissor scissor {@code i} was made inside, or -1. */
    int scissorParent(int i) {
        return scissorParents[i];
    }

    /** The boxes of node scissors, four floats each at the scissor's index: {@code x0, y0, x1, y1}. */
    float[] scissorBoxes() {
        return scissorBoxes;
    }
}
