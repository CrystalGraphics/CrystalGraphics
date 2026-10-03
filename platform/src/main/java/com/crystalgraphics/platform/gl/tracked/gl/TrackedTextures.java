package com.crystalgraphics.platform.gl.tracked.gl;

import com.crystalgraphics.platform.device.format.CgCompare;
import com.crystalgraphics.platform.device.format.CgFormat;
import com.crystalgraphics.platform.device.resource.CgGpuSampler;
import com.crystalgraphics.platform.device.resource.CgGpuTexture;
import com.crystalgraphics.platform.device.resource.CgTextureRegion;
import com.crystalgraphics.platform.device.resource.CgTextureView;
import com.crystalgraphics.platform.device.shader.CgGlslCompiler;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.tracked.tracker.CgDrawState;
import com.crystalgraphics.platform.gl.tracked.tracker.CgTracker;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Texture objects, texture units, image units and samplers. A texture's image is made when level 0 is specified,
 * with room for its whole mip chain; a draw samples the levels specified so far, and a texture with none samples as
 * GL's incomplete texture does, black and opaque.
 */
public final class TrackedTextures implements TrackedPrograms.Samplers, TrackedPrograms.Images {

    static final int KIND_2D = 0, KIND_ARRAY = 1, KIND_3D = 2, KIND_CUBE = 3, KIND_MS = 4, KIND_BUFFER = 5, KINDS = 6;
    private static final int GL_TEXTURE_BINDING_2D_ARRAY = 0x8C1D, GL_TEXTURE_BINDING_3D = 0x806A;
    private static final int GL_TEXTURE_BINDING_CUBE_MAP = 0x8514, GL_TEXTURE_BINDING_BUFFER = 0x8C2C;
    private static final int GL_SAMPLER_BINDING = 0x8919;
    private static final int GL_TEXTURE_LOD_BIAS = 0x8501, GL_TEXTURE_SWIZZLE_R = 0x8E42, GL_TEXTURE_SWIZZLE_A = 0x8E45;
    private static final int GL_TEXTURE_MAX_ANISOTROPY = 0x84FE;
    /** GL's guaranteed minimum. */
    static final int IMAGE_UNITS = 8;
    /** GL binds any texture as an image; a device needs the usage when the image is made, where its format allows. */
    private static final Set<CgGpuTexture.Usage> NOT_STORAGE = EnumSet.complementOf(EnumSet.of(CgGpuTexture.Usage.STORAGE));

    /** One GL texture object. */
    public static final class GlTexture {
        final int name;
        int kind = -1;
        public CgGpuTexture image;
        CgFormat format;
        int width, height, depth, mips, samples = 1;
        int specifiedLevels;                  // consecutive levels from 0 with contents defined
        boolean imported;
        int minFilter = CgGL.GL_NEAREST_MIPMAP_LINEAR, magFilter = CgGL.GL_LINEAR;
        int wrapS = CgGL.GL_REPEAT, wrapT = CgGL.GL_REPEAT, wrapR = CgGL.GL_REPEAT;
        int compareMode = CgGL.GL_NONE, compareFunc = CgGL.GL_LEQUAL, baseLevel, maxLevel = 1000;
        float minLod = -1000, maxLod = 1000;
        int buffer;
        CgFormat texelFormat;
        CgGpuSampler sampler;
        CgTextureView view;

        GlTexture(int name) { this.name = name; }

        void paramsChanged() { sampler = null; view = null; }
    }

    private final CgTracker tracker;
    private final TrackedGlErrors errors;
    private final TrackedBuffers buffers;
    private final GlNames<GlTexture> names = new GlNames<>("Texture");
    private final Map<CgGpuSampler.Desc, CgGpuSampler> samplers = new HashMap<>();
    private final int[][] units;
    private final CgTextureView[] incomplete = new CgTextureView[KINDS];
    /** Per image unit: texture, level, layer (-1: every layer), format, access. */
    private final int[][] imageUnits = new int[IMAGE_UNITS][5];
    private final CgTextureView[] imageViews = new CgTextureView[IMAGE_UNITS];
    public final GlPixels.Store unpack = new GlPixels.Store(), pack = new GlPixels.Store();
    private int active;

    public TrackedTextures(CgTracker tracker, TrackedGlErrors errors, TrackedBuffers buffers) {
        this.tracker = tracker;
        this.errors = errors;
        this.buffers = buffers;
        this.units = new int[tracker.device().info().limits().maxTextureUnits()][KINDS];
    }

    public int activeUnit() { return active; }

    public int bound2D(int unit) { return unit < units.length ? units[unit][KIND_2D] : 0; }

    public int gen() {
        int name = names.next();
        return names.add(new GlTexture(name));
    }

    public GlTexture get(int name) { return name == 0 ? null : names.get(name); }

    /** A host image under a GL name of its own; never freed by us. */
    public int adopt(CgGpuTexture image) {
        int name = gen();
        GlTexture t = get(name);
        CgGpuTexture.Desc d = image.desc();
        t.kind = d.kind() == CgGpuTexture.Kind.D2_ARRAY ? KIND_ARRAY : d.kind() == CgGpuTexture.Kind.D3 ? KIND_3D
                : d.kind() == CgGpuTexture.Kind.CUBE ? KIND_CUBE : d.samples() > 1 ? KIND_MS : KIND_2D;
        t.image = image;
        t.format = d.format();
        t.width = d.width(); t.height = d.height(); t.depth = d.depthOrLayers(); t.mips = d.mips(); t.samples = d.samples();
        t.specifiedLevels = d.mips();
        t.imported = true;
        return name;
    }

    public void active(int unit) {
        int u = unit - CgGL.GL_TEXTURE0;
        if (u < 0 || u >= units.length) { errors.invalidEnum("glActiveTexture", unit); return; }
        active = u;
    }

    public void bind(int target, int name) {
        int kind = kind(target);
        if (kind < 0) { errors.invalidEnum("glBindTexture", target); return; }
        if (name != 0) {
            if (!names.exists(name)) { errors.invalidOperation("glBindTexture: texture " + name + " was never generated"); return; }
            GlTexture t = get(name);
            if (t.kind < 0) t.kind = kind;
            else if (t.kind != kind) { errors.invalidOperation("glBindTexture: texture " + name + " has another target"); return; }
        }
        units[active][kind] = name;
    }

    public void delete(int name) {
        GlTexture t = names.remove(name);
        if (t == null) return;
        if (t.image != null && !t.imported) tracker.release(t.image);
        for (int[] unit : units) for (int k = 0; k < KINDS; k++) if (unit[k] == name) unit[k] = 0;
        for (int u = 0; u < IMAGE_UNITS; u++) {
            if (imageUnits[u][0] == name) {
                imageUnits[u][0] = 0;
                imageViews[u] = null;
            }
        }
    }

    /** {@code glBindImageTexture}. The access is kept for queries; a device binds every image read-write. */
    public void bindImage(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
        if (unit < 0 || unit >= IMAGE_UNITS) { errors.invalidValue("glBindImageTexture unit " + unit); return; }
        if (texture != 0 && !names.exists(texture)) {
            errors.invalidValue("glBindImageTexture: texture " + texture + " was never generated");
            return;
        }
        int[] u = imageUnits[unit];
        u[0] = texture;
        u[1] = level;
        u[2] = layered ? -1 : layer;
        u[3] = format;
        u[4] = access;
        imageViews[unit] = null;
    }

    /** Image unit {@code unit}: texture, level, layer (-1: every layer), format, access. */
    public int[] imageUnit(int unit) {
        return imageUnits[unit];
    }

    /** {@code glTexImage2D}/{@code 3D}: specifies a level; level 0 of a new size or format makes a new image. */
    public void image(int target, int level, int internalFormat, int width, int height, int depth, int format, int type, ByteBuffer pixels) {
        GlTexture t = boundFor(target, "glTexImage");
        if (t == null) return;
        CgFormat f = GlPixels.device(internalFormat);
        int layer = face(target);
        if (level == 0) {
            int layers = t.kind == KIND_CUBE ? 6 : depth;
            if (t.image == null || t.format != f || t.width != width || t.height != height || t.depth != layers) {
                allocate(t, f, width, height, layers, 1);
            }
        } else if (t.image == null) {
            throw new UnsupportedOperationException("Texture " + t.name + ": level " + level + " before level 0");
        }
        if (pixels != null) {
            upload(t, level, 0, 0, t.kind == KIND_CUBE ? layer : 0, width, height, t.kind == KIND_CUBE ? 1 : depth,
                    format, type, pixels);
        }
        if (level == t.specifiedLevels) t.specifiedLevels = level + 1;
        t.view = null;
    }

    public void subImage(int target, int level, int x, int y, int z, int width, int height, int depth, int format, int type, ByteBuffer pixels) {
        GlTexture t = boundFor(target, "glTexSubImage");
        if (t == null) return;
        if (t.image == null) { errors.invalidOperation("glTexSubImage on texture " + t.name + " with no image"); return; }
        upload(t, level, x, y, t.kind == KIND_CUBE ? face(target) : z, width, height, depth, format, type, pixels);
    }

    public void multisample(int target, int samples, int internalFormat, int width, int height) {
        GlTexture t = boundFor(target, "glTexImage2DMultisample");
        if (t != null) allocate(t, GlPixels.device(internalFormat), width, height, 1, samples);
    }

    public void generateMipmap(int target) {
        GlTexture t = boundFor(target, "glGenerateMipmap");
        if (t == null || t.image == null) return;
        tracker.transfer().generateMipmaps(t.image);
        t.specifiedLevels = t.mips;
        t.view = null;
    }

    public void parameter(int target, int pname, int param) {
        GlTexture t = boundFor(target, "glTexParameteri");
        if (t == null) return;
        switch (pname) {
            case CgGL.GL_TEXTURE_MIN_FILTER: t.minFilter = param; break;
            case CgGL.GL_TEXTURE_MAG_FILTER: t.magFilter = param; break;
            case CgGL.GL_TEXTURE_WRAP_S: t.wrapS = param; break;
            case CgGL.GL_TEXTURE_WRAP_T: t.wrapT = param; break;
            case CgGL.GL_TEXTURE_WRAP_R: t.wrapR = param; break;
            case CgGL.GL_TEXTURE_COMPARE_MODE: t.compareMode = param; break;
            case CgGL.GL_TEXTURE_COMPARE_FUNC: t.compareFunc = param; break;
            case CgGL.GL_TEXTURE_BASE_LEVEL: t.baseLevel = param; break;
            case CgGL.GL_TEXTURE_MAX_LEVEL: t.maxLevel = param; break;
            case CgGL.GL_TEXTURE_MIN_LOD: t.minLod = param; break;
            case CgGL.GL_TEXTURE_MAX_LOD: t.maxLod = param; break;
            case GL_TEXTURE_LOD_BIAS: case GL_TEXTURE_MAX_ANISOTROPY: break;
            default:
                if (pname >= GL_TEXTURE_SWIZZLE_R && pname <= GL_TEXTURE_SWIZZLE_A)
                    throw new UnsupportedOperationException("Texture swizzles are not carried to a device");
                errors.invalidEnum("glTexParameteri", pname);
                return;
        }
        t.paramsChanged();
    }

    public void textureBuffer(int target, int internalFormat, int buffer) {
        GlTexture t = boundFor(target, "glTexBuffer");
        if (t == null) return;
        t.buffer = buffer;
        t.texelFormat = GlPixels.device(internalFormat);
    }

    public void bindSampler(int unit, int sampler) {
        if (sampler != 0) throw new UnsupportedOperationException("Sampler objects: glBindSampler only unbinds here");
    }

    public void pixelStore(int pname, int param) {
        switch (pname) {
            case CgGL.GL_UNPACK_ALIGNMENT: unpack.alignment = param; break;
            case CgGL.GL_UNPACK_ROW_LENGTH: unpack.rowLength = param; break;
            case CgGL.GL_UNPACK_SKIP_ROWS: unpack.skipRows = param; break;
            case CgGL.GL_UNPACK_SKIP_PIXELS: unpack.skipPixels = param; break;
            case CgGL.GL_UNPACK_IMAGE_HEIGHT: unpack.imageHeight = param; break;
            case CgGL.GL_UNPACK_SKIP_IMAGES: unpack.skipImages = param; break;
            case CgGL.GL_PACK_ALIGNMENT: pack.alignment = param; break;
            case GlPixels.GL_PACK_ROW_LENGTH: pack.rowLength = param; break;
            case GlPixels.GL_PACK_SKIP_ROWS: pack.skipRows = param; break;
            case GlPixels.GL_PACK_SKIP_PIXELS: pack.skipPixels = param; break;
            default: errors.invalidEnum("glPixelStorei", pname);
        }
    }

    /** {@code glGetTexImage}: a whole level, every layer, as GL's (format, type). */
    public void read(int target, int level, int format, int type, ByteBuffer out) {
        GlTexture t = boundFor(target, "glGetTexImage");
        if (t == null || t.image == null) return;
        int w = Math.max(1, t.width >> level), h = Math.max(1, t.height >> level);
        int layers = t.kind == KIND_3D ? Math.max(1, t.depth >> level) : t.depth;
        ByteBuffer texels = ByteBuffer.allocateDirect(w * h * layers * t.format.bytes()).order(ByteOrder.nativeOrder());
        tracker.transfer().readTexture(t.image, new CgTextureRegion(level, 0, 0, 0, w, h, layers), texels);
        GlPixels.pack(texels, t.format, w, h * layers, format, type, pack, out);
    }

    // ── sampling ───────────────────────────────────────────────────────────────

    @Override
    public void bind(CgDrawState state, CgGlslCompiler.Sampler sampler, int unit) {
        int kind = samplerKind(sampler.glType());
        GlTexture t = unit < units.length ? get(units[unit][kind]) : null;
        if (sampler.texel()) {
            TrackedBuffers.GlBuffer b = t == null ? null : buffers.get(t.buffer);
            if (b == null || b.storage.allocation() == null) throw new IllegalStateException(sampler.name()
                    + " reads a buffer texture with no buffer on unit " + unit);
            state.texel(sampler.binding(), b.storage.allocation(), 0, b.storage.allocation().size(), t.texelFormat);
            return;
        }
        if (t == null || t.image == null || t.specifiedLevels <= t.baseLevel) {
            state.texture(sampler.binding(), incomplete(kind), sampler(CgGpuSampler.Desc.GL_DEFAULT));
            return;
        }
        state.texture(sampler.binding(), view(t), sampler(t));
    }

    @Override
    public void bind(CgDrawState state, CgGlslCompiler.Image image, int unit) {
        int[] u = unit >= 0 && unit < IMAGE_UNITS ? imageUnits[unit] : null;
        GlTexture t = u == null ? null : get(u[0]);
        if (t == null || t.image == null)
            throw new IllegalStateException(image.name() + " reads image unit " + unit + ", which has no texture");
        if (!t.image.desc().usage().contains(CgGpuTexture.Usage.STORAGE))
            throw new UnsupportedOperationException("Texture " + t.name + ": " + t.format + " cannot be a storage image here");
        if (GlPixels.device(u[3]) != t.format)
            throw new UnsupportedOperationException("Texture " + t.name + " bound as an image in a format other than its own");
        if (t.kind == KIND_3D && u[2] >= 0)
            throw new UnsupportedOperationException("One slice of a 3D texture as an image: bind it layered");
        CgTextureView v = imageViews[unit];
        if (v == null || v.texture() != t.image) {
            v = imageViews[unit] = u[2] < 0 ? new CgTextureView(t.image, u[1], 1, 0, t.kind == KIND_3D ? 1 : t.depth)
                    : new CgTextureView(t.image, u[1], 1, u[2], 1);
        }
        state.image(image.binding(), v);
    }

    private CgTextureView view(GlTexture t) {
        if (t.view == null) {
            boolean mipmapped = t.minFilter != CgGL.GL_NEAREST && t.minFilter != CgGL.GL_LINEAR;
            int top = Math.min(Math.min(t.maxLevel, t.mips - 1), t.specifiedLevels - 1);
            int mips = mipmapped ? Math.max(1, top - t.baseLevel + 1) : 1;
            int layers = t.kind == KIND_3D ? 1 : t.depth;
            t.view = new CgTextureView(t.image, t.baseLevel, mips, 0, layers);
        }
        return t.view;
    }

    private CgGpuSampler sampler(GlTexture t) {
        if (t.sampler == null) {
            CgGpuSampler.MipFilter mip = t.minFilter == CgGL.GL_NEAREST || t.minFilter == CgGL.GL_LINEAR
                    ? CgGpuSampler.MipFilter.NONE
                    : t.minFilter == CgGL.GL_NEAREST_MIPMAP_NEAREST || t.minFilter == CgGL.GL_LINEAR_MIPMAP_NEAREST
                    ? CgGpuSampler.MipFilter.NEAREST : CgGpuSampler.MipFilter.LINEAR;
            CgGpuSampler.Filter min = t.minFilter == CgGL.GL_LINEAR || t.minFilter == CgGL.GL_LINEAR_MIPMAP_NEAREST
                    || t.minFilter == CgGL.GL_LINEAR_MIPMAP_LINEAR ? CgGpuSampler.Filter.LINEAR : CgGpuSampler.Filter.NEAREST;
            CgGpuSampler.Filter mag = t.magFilter == CgGL.GL_NEAREST ? CgGpuSampler.Filter.NEAREST : CgGpuSampler.Filter.LINEAR;
            CgCompare compare = t.compareMode == CgGL.GL_COMPARE_R_TO_TEXTURE ? GlEnums.compare(t.compareFunc) : null;
            t.sampler = sampler(new CgGpuSampler.Desc(min, mag, mip, wrap(t.wrapS), wrap(t.wrapT), wrap(t.wrapR),
                    compare, t.minLod, t.maxLod, 1f));
        }
        return t.sampler;
    }

    private CgGpuSampler sampler(CgGpuSampler.Desc desc) {
        return samplers.computeIfAbsent(desc, d -> tracker.device().createSampler(d));
    }

    /** A 1x1 black, opaque image of {@code kind}: what GL samples from an incomplete texture. */
    private CgTextureView incomplete(int kind) {
        if (incomplete[kind] == null) {
            CgGpuTexture.Kind k = kind == KIND_ARRAY ? CgGpuTexture.Kind.D2_ARRAY : kind == KIND_3D ? CgGpuTexture.Kind.D3
                    : kind == KIND_CUBE ? CgGpuTexture.Kind.CUBE : CgGpuTexture.Kind.D2;
            int layers = kind == KIND_CUBE ? 6 : 1;
            CgGpuTexture image = tracker.device().createTexture(new CgGpuTexture.Desc("incomplete", k,
                    CgFormat.RGBA8_UNORM, 1, 1, layers, 1, 1, CgGpuTexture.Usage.SAMPLED_UPLOADED));
            ByteBuffer black = ByteBuffer.allocateDirect(4 * layers);
            for (int i = 0; i < layers; i++) black.put(i * 4 + 3, (byte) 0xFF);
            tracker.transfer().writeTexture(image, new CgTextureRegion(0, 0, 0, 0, 1, 1, layers), black);
            incomplete[kind] = CgTextureView.whole(image);
        }
        return incomplete[kind];
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void allocate(GlTexture t, CgFormat format, int width, int height, int layers, int samples) {
        if (t.image != null && !t.imported) tracker.release(t.image);
        int chain = 32 - Integer.numberOfLeadingZeros(Math.max(width, Math.max(height, t.kind == KIND_3D ? layers : 1)));
        CgGpuTexture.Kind kind = t.kind == KIND_ARRAY ? CgGpuTexture.Kind.D2_ARRAY : t.kind == KIND_3D
                ? CgGpuTexture.Kind.D3 : t.kind == KIND_CUBE ? CgGpuTexture.Kind.CUBE : CgGpuTexture.Kind.D2;
        t.format = format;
        t.width = width; t.height = height; t.depth = layers; t.samples = samples;
        t.mips = samples > 1 ? 1 : chain;
        Set<CgGpuTexture.Usage> usage = samples == 1 && tracker.device().supports(format, CgGpuTexture.Usage.STORAGE)
                ? CgGpuTexture.Usage.ALL : NOT_STORAGE;
        t.image = tracker.device().createTexture(new CgGpuTexture.Desc("texture " + t.name, kind, format, width, height,
                layers, t.mips, samples, usage));
        t.specifiedLevels = 0;
        t.imported = false;
        t.view = null;
    }

    private void upload(GlTexture t, int level, int x, int y, int z, int w, int h, int d, int format, int type, ByteBuffer pixels) {
        if (pixels == null || w == 0 || h == 0 || d == 0) return;
        ByteBuffer texels = GlPixels.unpack(pixels, format, type, w, h, d, unpack, t.format);
        tracker.transfer().writeTexture(t.image, new CgTextureRegion(level, x, y, z, w, h, d), texels);
    }

    private GlTexture boundFor(int target, String call) {
        int kind = kind(target);
        if (kind < 0) { errors.invalidEnum(call, target); return null; }
        int name = units[active][kind];
        if (name == 0) { errors.invalidOperation(call + " with no texture bound"); return null; }
        return get(name);
    }

    /** The texture kind a target (or a cube face) addresses, or -1. */
    static int kind(int target) {
        switch (target) {
            case CgGL.GL_TEXTURE_2D: return KIND_2D;
            case CgGL.GL_TEXTURE_2D_ARRAY: return KIND_ARRAY;
            case CgGL.GL_TEXTURE_3D: return KIND_3D;
            case CgGL.GL_TEXTURE_CUBE_MAP: return KIND_CUBE;
            case CgGL.GL_TEXTURE_2D_MULTISAMPLE: return KIND_MS;
            case CgGL.GL_TEXTURE_BUFFER: return KIND_BUFFER;
            default:
                return target >= CgGL.GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= CgGL.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z ? KIND_CUBE : -1;
        }
    }

    private static int face(int target) {
        return target >= CgGL.GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= CgGL.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z
                ? target - CgGL.GL_TEXTURE_CUBE_MAP_POSITIVE_X : 0;
    }

    private static int samplerKind(int glType) {
        switch (glType) {
            case 0x8DC1: case 0x8DC4: case 0x8DCF: case 0x8DD7: return KIND_ARRAY;
            case 0x8B5F: case 0x8DCB: case 0x8DD3: return KIND_3D;
            case 0x8B60: case 0x8DC5: return KIND_CUBE;
            case 0x9108: return KIND_MS;
            case 0x8DC2: case 0x8DD0: case 0x8DD8: return KIND_BUFFER;
            default: return KIND_2D;
        }
    }

    private static CgGpuSampler.Wrap wrap(int mode) {
        switch (mode) {
            case CgGL.GL_CLAMP_TO_EDGE: return CgGpuSampler.Wrap.CLAMP_TO_EDGE;
            case CgGL.GL_MIRRORED_REPEAT: return CgGpuSampler.Wrap.MIRRORED_REPEAT;
            case CgGL.GL_CLAMP_TO_BORDER: return CgGpuSampler.Wrap.CLAMP_TO_BORDER;
            default: return CgGpuSampler.Wrap.REPEAT;
        }
    }

    public int query(int pname, double[] out) {
        switch (pname) {
            case CgGL.GL_ACTIVE_TEXTURE: return TrackedRenderState.one(out, CgGL.GL_TEXTURE0 + active);
            case CgGL.GL_TEXTURE_BINDING_2D: return TrackedRenderState.one(out, units[active][KIND_2D]);
            case GL_TEXTURE_BINDING_2D_ARRAY: return TrackedRenderState.one(out, units[active][KIND_ARRAY]);
            case GL_TEXTURE_BINDING_3D: return TrackedRenderState.one(out, units[active][KIND_3D]);
            case GL_TEXTURE_BINDING_CUBE_MAP: return TrackedRenderState.one(out, units[active][KIND_CUBE]);
            case GL_TEXTURE_BINDING_BUFFER: return TrackedRenderState.one(out, units[active][KIND_BUFFER]);
            case GL_SAMPLER_BINDING: return TrackedRenderState.one(out, 0);
            case CgGL.GL_UNPACK_ALIGNMENT: return TrackedRenderState.one(out, unpack.alignment);
            case CgGL.GL_UNPACK_ROW_LENGTH: return TrackedRenderState.one(out, unpack.rowLength);
            case CgGL.GL_UNPACK_SKIP_ROWS: return TrackedRenderState.one(out, unpack.skipRows);
            case CgGL.GL_UNPACK_SKIP_PIXELS: return TrackedRenderState.one(out, unpack.skipPixels);
            case CgGL.GL_UNPACK_IMAGE_HEIGHT: return TrackedRenderState.one(out, unpack.imageHeight);
            case CgGL.GL_UNPACK_SKIP_IMAGES: return TrackedRenderState.one(out, unpack.skipImages);
            case CgGL.GL_PACK_ALIGNMENT: return TrackedRenderState.one(out, pack.alignment);
            case GlPixels.GL_PACK_ROW_LENGTH: return TrackedRenderState.one(out, pack.rowLength);
            case GlPixels.GL_PACK_SKIP_ROWS: return TrackedRenderState.one(out, pack.skipRows);
            case GlPixels.GL_PACK_SKIP_PIXELS: return TrackedRenderState.one(out, pack.skipPixels);
            default: return -1;
        }
    }
}
