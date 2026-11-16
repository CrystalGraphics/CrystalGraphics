package com.crystalgraphics.render.view;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.host.CgHostFrame;

import javax.annotation.Nullable;
import java.util.ArrayDeque;

/**
 * Engine side: where a view drawn below 100% draws its world (Unreal's scene textures at the view rect's size). A colour
 * and depth of the render size stand in for the host's target: the world's firings take them as their host, so
 * everything up to the post chain's upscale draws as it would into a smaller window. The host's own target is named in
 * the graph ({@link #output}), and the chain presents into it.
 *
 * <pre>{@code
 * CgViewRenderTarget small = view.renderTarget();
 * CgHostFrame drawn = small.host(host, view.width(), view.height());   // the world's host this firing
 * try (CgExecutor.Into ignored = CgExecutor.into(drawn.target())) { ... }
 * }</pre>
 *
 * <ul>
 *   <li>Its colour and depth take the host target's formats and depth convention, so a pass reading them reads what it
 *       would read from the host's.</li>
 *   <li>Kept from frame to frame; remade when the size or the host's form changes, the old pair outliving its
 *       replacement by a few ring frames.</li>
 * </ul>
 */
public final class CgViewRenderTarget {

    /** Ring frames a replaced pair outlives its replacement: a frame recorded against it may still execute. */
    private static final int RETIRE_FRAMES = 4;
    private static final String FILL = "crystalgraphics:shaders/world/view_fill.shader";
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);

    private record Retired(CgTexture2D colour, CgTexture2D depth, long frame) {
    }

    private final String name;
    private final CgHostFrame host = new CgHostFrame();
    private final ArrayDeque<Retired> retired = new ArrayDeque<>();
    @Nullable
    private CgTexture2D colour, depth;
    @Nullable
    private CgExternalTarget target;
    private int width, height;
    @Nullable
    private CgFormat colourFormat, depthFormat;
    private boolean asHost;
    /** The host target last presented into, and its name in the graph. */
    @Nullable
    private CgExternalTarget outputTarget;
    @Nullable
    private CgGraphTexture output;
    @Nullable
    private CgMaterialInstance fill;

    CgViewRenderTarget(String name) {
        this.name = name;
    }

    /**
     * The host frame the world's firings draw under this frame: {@code from}'s timing, camera, environment and textures
     * over this target at {@code width} x {@code height}, made or remade to match {@code from}'s target.
     */
    public CgHostFrame host(CgHostFrame from, int width, int height) {
        CgExternalTarget host = from.target();
        CgFormat c = format(host.color(), CgFormat.RGBA8_UNORM), d = format(host.depth(), CgFormat.DEPTH32_FLOAT);
        long now = CgFrameRing.frame();
        while (!retired.isEmpty() && now - retired.peekFirst().frame() > RETIRE_FRAMES) {
            Retired r = retired.pollFirst();
            r.colour().release();
            r.depth().release();
        }
        if (target == null || this.width != width || this.height != height || colourFormat != c || depthFormat != d
                || asHost != host.drawsAsHost()) {
            if (colour != null && depth != null) retired.addLast(new Retired(colour, depth, now));
            colour = CgTextures.of(name, CgTextureDesc.of(width, height, c));
            depth = CgTextures.of(name + ".depth", CgTextureDesc.of(width, height, d));
            target = CgTextures.target(name, colour, depth);
            // The host's convention: one execution draws in its current target's, which is this one now
            if (host.drawsAsHost()) target.drawnAsHost();
            this.width = width;
            this.height = height;
            colourFormat = c;
            depthFormat = d;
            asHost = host.drawsAsHost();
        }
        this.host.set(from.partialTick(), width, height, target).guiScale(from.guiScale()).view(from.view());
        this.host.camera().set(from.camera());
        this.host.environment().set(from.environment());
        this.host.textures().set(from.textures());
        return this.host;
    }

    /** {@code host}, the target this view presents into, as the graph names it: the same value while the target holds. */
    public CgGraphTexture output(CgExternalTarget host) {
        if (output == null || outputTarget != host) {
            output = CgGraphTexture.imported(name + ".output", host);
            outputTarget = host;
        }
        return output;
    }

    /**
     * Records the opening of the view's draw into this target: the host's picture so far, resampled, as the world's
     * background (the clear or the sky its host drew), and the far plane written into its depth. Into the current
     * target, which is this one, from {@code output}; nothing until its program is built.
     */
    public void fill(CgRecording recording, CgPassConstants constants, CgGraphTexture output) {
        CgPipeline pipeline = fillPipeline();
        if (pipeline == null) return;
        // The graph clears no external target's depth: the far plane is drawn
        CgRasterPass pass = recording.raster("view.fill").color(CgGraphTexture.current(), CgLoad.load())
                .depth(CgGraphTexture.current(), CgLoad.load(), CgDepthAccess.WRITE).constants(constants).readsColor(output);
        fill.vec4("_Fill", (float) output.getWidth() / width, (float) output.getHeight() / height, output.getWidth(), output.getHeight());
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, CgMaterialProxy.of(fill).bindings(), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
    }

    @Nullable
    private CgPipeline fillPipeline() {
        if (fill == null) fill = CgMaterial.load(FILL).instance();
        return CgMaterialProxy.of(fill).pipeline(CgInstanceKind.OBJECT);
    }

    /** The fill's shader, for the precache. */
    public static String fillShader() {
        return FILL;
    }

    /** Frees its textures; the next frame drawn below 100% makes them again. Render thread. */
    void release() {
        if (colour != null) colour.release();
        if (depth != null) depth.release();
        while (!retired.isEmpty()) {
            Retired r = retired.pollFirst();
            r.colour().release();
            r.depth().release();
        }
        colour = depth = null;
        target = null;
        output = null;
        outputTarget = null;
        fill = null;
    }

    private static CgFormat format(@Nullable CgTextureView view, CgFormat otherwise) {
        return view == null ? otherwise : view.texture().desc().format();
    }
}
