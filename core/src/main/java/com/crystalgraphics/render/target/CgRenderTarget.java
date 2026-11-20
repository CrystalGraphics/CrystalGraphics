package com.crystalgraphics.render.target;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgDeviceTextures;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.gpu.resource.CgTextureAbstract;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.gpu.resource.CgTextureSettings;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.CgRenderer;
import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRecording;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A texture the engine draws into at run time, which a material samples like any other: Unreal's
 * {@code UTextureRenderTarget2D}. A scene capture fills one ({@code CgSceneCaptureComponent}); a material can be drawn
 * into one with no scene, and one can be cleared.
 *
 * <pre>{@code
 * CgRenderTarget mirror = CgRenderTarget.create("mymod:mirror", 512, 512, CgRenderTarget.Format.RGBA16F);
 * CgMaterialDefinition.of("mymod:mirror_glass", "mymod:shaders/glass.shader").texture("_Reflection", mirror.texture());
 * scene.capture().target(mirror).at(x, y, z).rotation(facing).everyFrame(true).spawn();
 * mirror.release();                                        // when nothing draws or samples it any more
 * }</pre>
 *
 * <p>As an asset, a settings file with a {@code render_target} block and no image, named by its path without an
 * extension: a material's {@code textures:} binding and a capture spawnable's {@code target} name it alike.</p>
 *
 * <pre>{@code
 * # assets/mymod/textures/fx/mirror.yaml
 * render_target: { size: [512, 512], format: rgba16f, clear: [0, 0, 0, 1] }
 * mips: true
 *
 * CgRenderTarget mirror = CgRenderTarget.of("mymod:textures/fx/mirror");   // the one its materials sample
 * }</pre>
 *
 * <pre>{@code
 * target.drawMaterial(noiseMaterial);                     // a #domain ui material over the whole target, no scene
 * target.clear();                                         // its clear colour
 * target.readback(pixels -> save(pixels));                // a frame or two later, never blocking
 * }</pre>
 *
 * <ul>
 *   <li>Its size is fixed: a material holds its texture, so a target of another size is another target.</li>
 *   <li>Drawing, clearing and reading back are the render thread's, outside a stage firing's recording.</li>
 *   <li>With mips it remakes its chain after each draw into it; a sampler with mip filtering reads them.</li>
 *   <li>A capture lends it a depth texture of its own size ({@link #depth()}): the scene's, reversed, near 1.</li>
 * </ul>
 */
public final class CgRenderTarget {

    /** Unreal's ten render target formats ({@code ETextureRenderTargetFormat}), by the name a settings file gives. */
    public enum Format {
        R8(CgFormat.R8_UNORM), RG8(CgFormat.RG8_UNORM), RGBA8(CgFormat.RGBA8_UNORM), RGBA8_SRGB(CgFormat.RGBA8_SRGB),
        R16F(CgFormat.R16_FLOAT), RG16F(CgFormat.RG16_FLOAT), RGBA16F(CgFormat.RGBA16_FLOAT),
        R32F(CgFormat.R32_FLOAT), RG32F(CgFormat.RG32_FLOAT), RGBA32F(CgFormat.RGBA32_FLOAT),
        RGB10A2(CgFormat.RGB10A2_UNORM);

        private final CgFormat format;

        Format(CgFormat format) {
            this.format = format;
        }

        /** The device format it is made in. */
        public CgFormat format() {
            return format;
        }

        /** {@code name} as a settings file writes it ({@code rgba16f}); null for none of the ten. */
        @Nullable
        public static Format named(String name) {
            for (Format f : values()) if (f.name().toLowerCase(Locale.ROOT).equals(name)) return f;
            return null;
        }
    }

    /** Asset targets by path, one each: every material naming the path samples the one a capture draws into. */
    private static final Map<String, CgRenderTarget> ASSETS = new ConcurrentHashMap<>();

    private final String name;
    private final int width, height;
    private final Format format;
    private final CgTexture2D colour;
    @Nullable
    private CgTexture2D depth;
    @Nullable
    private CgExternalTarget colourOnly, withDepth;
    private float clearR, clearG = 1f, clearB, clearA = 1f;

    private CgRenderTarget(String name, int width, int height, Format format, boolean mips) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException(name + " is " + width + " x " + height);
        this.name = name;
        this.width = width;
        this.height = height;
        this.format = format;
        CgTextureDesc desc = CgTextureDesc.of(width, height, format.format());
        this.colour = CgTextures.of(name, mips ? desc.withMips() : desc);
    }

    /**
     * A target of your own, {@code width} x {@code height} in {@code format}, cleared to Unreal's green until told
     * otherwise, one level. Render thread or any; its storage lands at its first use.
     */
    public static CgRenderTarget create(String name, int width, int height, Format format) {
        return new CgRenderTarget(name, width, height, format, false);
    }

    /** {@link #create} with every level down to 1x1, remade after each draw: Unreal's {@code bAutoGenerateMips}. */
    public static CgRenderTarget withMips(String name, int width, int height, Format format) {
        return new CgRenderTarget(name, width, height, format, true);
    }

    /**
     * The render target asset at {@code path} (its settings file's {@code render_target} block), one for every caller
     * of the path: what its materials sample.
     *
     * @throws CgAssetException naming the file, where it has no {@code render_target} block or names a format that is
     *                          not one of the ten
     */
    public static CgRenderTarget of(String path) {
        return ASSETS.computeIfAbsent(path, CgRenderTarget::fromFile);
    }

    /** Whether {@code path} is a render target asset: a settings file with a {@code render_target} block. */
    public static boolean isAsset(String path) {
        return ASSETS.containsKey(path) || CgTextureSettings.of(path).renderTarget() != null;
    }

    private static CgRenderTarget fromFile(String path) {
        CgTextureSettings settings = CgTextureSettings.of(path);
        CgTextureSettings.RenderTarget rt = settings.renderTarget();
        if (rt == null) throw new CgAssetException(path + " is no render target: its settings hold no render_target block");
        Format format = Format.named(rt.format());
        if (format == null) {
            throw new CgAssetException(path + "'s render_target format is " + rt.format() + ", not one of r8, rg8, rgba8,"
                    + " rgba8_srgb, r16f, rg16f, rgba16f, r32f, rg32f, rgba32f, rgb10a2");
        }
        CgRenderTarget target = new CgRenderTarget(path, rt.width(), rt.height(), format, settings.mips());
        return target.clearColor(rt.r(), rt.g(), rt.b(), rt.a());
    }

    /** Its clear colour, linear: what {@link #clear()} and every capture into it starts from. */
    public CgRenderTarget clearColor(float r, float g, float b, float a) {
        clearR = r;
        clearG = g;
        clearB = b;
        clearA = a;
        return this;
    }

    /** {@link #clearColor(float, float, float, float)} from {@code 0xAARRGGBB}. */
    public CgRenderTarget clearColor(int argb) {
        return clearColor((argb >> 16 & 0xFF) / 255f, (argb >> 8 & 0xFF) / 255f, (argb & 0xFF) / 255f, (argb >>> 24) / 255f);
    }

    public String name() {
        return name;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public Format format() {
        return format;
    }

    /** What a material samples: the same texture for its whole life. */
    public CgTexture2D texture() {
        return colour;
    }

    /** The depth its last capture drew, reversed (near 1, far 0); null before a capture lent it one. */
    @Nullable
    public CgTexture2D depth() {
        return depth;
    }

    /** Fills it with its clear colour now. Render thread, outside a recording. */
    public void clear() {
        CgRenderer.clear(target(false), true, clearR, clearG, clearB, clearA, false, 0.0, false, 0);
        drawn();
    }

    /**
     * Draws {@code material} over the whole of it now, with no scene: Unreal's {@code DrawMaterialToRenderTarget}. A
     * {@code #domain ui} material on {@code Primitive Element}, its uv 0 to 1 across the target from the top left, over
     * what the target holds. Render thread, outside a recording.
     *
     * <pre>{@code
     * target.clear();
     * target.drawMaterial(ripples);   // ripples' uv(0, 0) at the target's top left
     * target.drawMaterial(CgUiMaterials.image(splash));   // a texture: the engine's image material (keep the instance)
     * }</pre>
     */
    public void drawMaterial(CgMaterialInterface material) {
        CgRecording rec = new CgRecording();
        CgCanvas canvas = new CgCanvas();
        canvas.begin(rec, CgGraphTexture.imported(name, target(false)), CgLoad.load(), CgCamera.screen(width, height), 1f);
        canvas.box(0f, 0f, width, height).material(material).fill(0xFFFFFFFF).draw();
        canvas.end();
        CgRenderer.executeNow(rec);
        drawn();
    }

    /**
     * Reads it back to the CPU without waiting: {@code sink} takes its top level's pixels, rows bottom first, tightly
     * packed in its format, a frame or more later. Render thread, outside a recording. The only read a target has, as
     * Unreal's is an async readback; none blocks.
     */
    public void readback(CgReadback.Sink sink) {
        CgRecording rec = new CgRecording();
        rec.readback(CgGraphTexture.imported(name, target(false)), 0, 0, 0, width, height, sink);
        CgRenderer.executeNow(rec);
    }

    /** Frees its textures. Nothing may draw into or sample it after; an asset target is made again by its next {@code of}. */
    public void release() {
        ASSETS.remove(name, this);
        colour.release();
        if (depth != null) depth.release();
        depth = null;
        colourOnly = withDepth = null;
    }

    // ── Engine side ───────────────────────────────────────────────────────────────────────────────

    /**
     * Engine side: what a view draws into, cleared to its clear colour and the far depth, a depth texture of its size
     * lent with it where {@code depth}. A scene capture's and a view picture's.
     */
    public CgExternalTarget beginDraw(boolean depth) {
        CgExternalTarget t = target(depth);
        CgRenderer.clear(t, true, clearR, clearG, clearB, clearA, depth, 0.0, depth, 0);
        return t;
    }

    /** Engine side: a draw into it ended, its chain remade where it has one. */
    public void drawn() {
        CgGpuTexture storage = ((CgTextureAbstract) colour).rhiTexture();
        if (storage != null) CgDeviceTextures.mipmaps(storage);
    }

    private CgExternalTarget target(boolean withDepthTexture) {
        if (!withDepthTexture) {
            if (colourOnly == null) colourOnly = CgTextures.target(name, colour, null);
            return colourOnly;
        }
        if (withDepth == null) {
            if (depth == null) depth = CgTextures.of(name + ".depth", CgTextureDesc.of(width, height, CgFormat.DEPTH32_FLOAT_STENCIL8));
            withDepth = CgTextures.target(name, colour, depth);
        }
        return withDepth;
    }

    @Override
    public String toString() {
        return "CgRenderTarget(" + name + ", " + width + " x " + height + " " + format.name().toLowerCase(Locale.ROOT) + ")";
    }
}
