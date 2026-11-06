package com.crystalgraphics.render.shader;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.material.CgAttachedBuffer;
import com.crystalgraphics.gl.buffer.shader.CgBonePalettes;
import com.crystalgraphics.gl.buffer.shader.CgEngineBufferRegistry;
import com.github.bsideup.jabel.Desugar;

/**
 * Where every resource a shader reads is bound: the set it belongs to by how often it changes, and its binding in that
 * set (Filament's sets by frequency, {@code rhi.md} §2.3). The compiler writes each declaration through
 * {@code cg_env.glsl}'s {@code CG_BIND(set, binding)} from here, so a source names its bindings explicitly and SPIR-V
 * needs none assigned.
 *
 * <pre>{@code
 * sb.append(CgBindingModel.MATERIAL_BLOCK.glsl()).append("layout(std140) uniform CgMaterialBlock { ... };\n");
 * // CG_BIND(2, 0) layout(std140) uniform CgMaterialBlock { ... };
 *
 * CgBindingModel.Slot s = CgBindingModel.attached(buffer, 0);   // an engine token's pass slot, or the material's
 * }</pre>
 *
 * <table>
 *   <tr><th>Set</th><th>Holds</th><th>Made</th></tr>
 *   <tr><td>{@link #VIEW}</td><td>the frame block, scene depth and colour, the lightmap, the media, local lights</td><td>once a firing</td></tr>
 *   <tr><td>{@link #PASS}</td><td>the object records, the text block, bone palettes, the pass's object ids, engine buffers by {@code cg_use} token</td><td>once a pass</td></tr>
 *   <tr><td>{@link #MATERIAL}</td><td>its block, its {@code Buffers { }}, buffers attached to it, its textures where bound</td><td>persistent</td></tr>
 *   <tr><td>{@link #BINDLESS}</td><td>every texture view and sampler, by index</td><td>once a frame</td></tr>
 * </table>
 *
 * <p>On GL {@code CG_BIND} is empty until GL implements {@code CgRHI} (S4): its programs are still wired by name after
 * link. A slot's numbers never depend on the device: a cg_use token's is its place in
 * {@link CgEngineBufferRegistry}'s order.</p>
 */
public final class CgBindingModel {

    public static final int VIEW = 0, PASS = 1, MATERIAL = 2, BINDLESS = 3;

    /** One binding: {@code layout(set = set, binding = binding)} on Vulkan. */
    @Desugar
    public record Slot(int set, int binding) {
        /** {@code CG_BIND(set, binding) }, to stand before a declaration. */
        public String glsl() {
            return "CG_BIND(" + set + ", " + binding + ") ";
        }
    }

    /** Set 0's bindings, as {@code cg_env.glsl} writes them. */
    public static final int VIEW_BLOCK = 0, DEPTH = 1, SCENE_COLOR = 2, LIGHTMAP = 3, MEDIA = 4, LOCAL_LIGHTS = 5,
            ENVIRONMENT_BLOCK = 6;

    /** A packed slot that names nothing: {@link #ofTexture}, {@link #ofBlock} and {@link #ofStorage} for a number the
     * model does not place. */
    public static final int NONE = -1;

    /** {@code CgObjectDataBuffer}, the firing's object records or a draw's own: written in {@code cg_env.glsl}. */
    public static final Slot OBJECTS = new Slot(PASS, 0);
    /** {@code CgTextRenderer}'s {@code TextData} block. */
    public static final Slot TEXT_DATA = new Slot(PASS, 1);
    /** {@code CgBonePalettes}, read by a skinned variant. */
    public static final Slot BONES = new Slot(PASS, 2);
    /** {@code CgObjectIdBuffer}, the pass's object ids: written in {@code cg_env.glsl}. */
    public static final Slot OBJECT_IDS = new Slot(PASS, 3);
    /** The first cg_use token's binding; token {@code i} in the registry's order is at this plus {@code i}. */
    public static final int FIRST_TOKEN = 4;

    /** {@code CgMaterialBlock}, the material's parameters. */
    public static final Slot MATERIAL_BLOCK = new Slot(MATERIAL, 0);
    /** The first of a material's {@code Buffers { }}: buffer {@code i} at this plus {@code i}. */
    public static final int FIRST_BUFFER = 1;
    /** The first buffer attached to a material at runtime; {@code CgMaterial.attach} allows {@link #ATTACHED_COUNT}. */
    public static final int FIRST_ATTACHED = 5, ATTACHED_COUNT = 8;
    /** A material's textures where they are bound rather than read by index: property {@code i} at this plus {@code i}. */
    public static final int FIRST_TEXTURE = 16;

    /**
     * A kernel's own resources are in {@link #MATERIAL}'s set, as a material's: its {@code Properties} block at
     * {@link #MATERIAL_BLOCK}, its samplers from {@link #FIRST_TEXTURE}, its buffers from this (append counts and
     * checked mode's report after them), images from {@link #FIRST_KERNEL_IMAGE}.
     */
    public static final int FIRST_KERNEL_BUFFER = 32, FIRST_KERNEL_IMAGE = 96;

    private CgBindingModel() {}

    /** {@code set} and {@code binding} in one int: {@link #setOf} and {@link #bindingOf} read it back. */
    public static int pack(int set, int binding) {
        return set << 16 | binding;
    }

    public static int setOf(int packed) {
        return packed >>> 16;
    }

    public static int bindingOf(int packed) {
        return packed & 0xFFFF;
    }

    /**
     * The slot a texture recorded at engine unit {@code unit} reads: set 0's for the pass's units, the material's texture
     * {@code unit} below them. {@link #NONE} for a negative unit.
     */
    public static int ofTexture(int unit) {
        if (unit < 0) return NONE;
        if (unit == CgBindingPoints.DEPTH_TEXTURE_UNIT) return pack(VIEW, DEPTH);
        if (unit == CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT) return pack(VIEW, SCENE_COLOR);
        if (unit == CgBindingPoints.LIGHTMAP_TEXTURE_UNIT) return pack(VIEW, LIGHTMAP);
        if (unit == CgBindingPoints.MEDIA_TEXTURE_UNIT) return pack(VIEW, MEDIA);
        if (unit == CgBindingPoints.LOCAL_LIGHTS_TEXTURE_UNIT) return pack(VIEW, LOCAL_LIGHTS);
        return pack(MATERIAL, FIRST_TEXTURE + unit);
    }

    /** The slot a uniform block recorded at engine point {@code point} reads; {@link #NONE} for one it does not place. */
    public static int ofBlock(int point) {
        if (point < 0) return NONE;
        if (point == CgBindingPoints.VIEW_UBO) return pack(VIEW, VIEW_BLOCK);
        if (point == CgBindingPoints.ENVIRONMENT_UBO) return pack(VIEW, ENVIRONMENT_BLOCK);
        if (point == CgBindingPoints.MATERIAL_PROPERTIES_UBO) return pack(MATERIAL, MATERIAL_BLOCK.binding());
        if (point == CgBindingPoints.TEXT_DATA_UBO) return pack(PASS, TEXT_DATA.binding());
        return NONE;
    }

    /**
     * The slot a storage buffer recorded at engine point {@code point} reads: the object records and ids, bone palettes
     * and cg_use tokens, the material's {@code Buffers { }}. {@link #NONE} for one it does not place.
     */
    public static int ofStorage(int point) {
        if (point < 0 || !CgBindingPoints.isInitialized()) return NONE;
        if (point == CgBindingPoints.OBJECT_DATA.ssbo()) return pack(PASS, OBJECTS.binding());
        if (point == CgBindingPoints.OBJECT_IDS.ssbo()) return pack(PASS, OBJECT_IDS.binding());
        if (point == CgBindingPoints.BONE_PALETTES.ssbo()) return pack(PASS, BONES.binding());
        int material = point - CgBindingPoints.MATERIAL_BUFFERS_SSBO;
        if (material >= 0 && material < CgBindingPoints.MATERIAL_BUFFER_COUNT) return pack(MATERIAL, FIRST_BUFFER + material);
        int token = CgEngineBufferRegistry.indexOfPoint(point);
        return token >= 0 ? pack(PASS, FIRST_TOKEN + token) : NONE;
    }

    /** The material's {@code Buffers { }} entry {@code i}. */
    public static Slot buffer(int i) {
        return new Slot(MATERIAL, FIRST_BUFFER + i);
    }

    /** The material's texture property {@code i}, counting texture properties alone, where textures are bound. */
    public static Slot texture(int i) {
        return new Slot(MATERIAL, FIRST_TEXTURE + i);
    }

    /** The pass slot of the cg_use token whose macro is {@code macro}; null for a macro no token has. */
    public static Slot token(String macro) {
        int token = CgEngineBufferRegistry.indexOfMacro(macro);
        return token >= 0 ? new Slot(PASS, FIRST_TOKEN + token) : null;
    }

    /**
     * Where an attached buffer binds: an engine buffer's pass slot, found by its macro name or block name, or the
     * material's {@code user}th attached slot.
     */
    public static Slot attached(CgAttachedBuffer ab, int user) {
        if (ab.isUbo()) {
            if (TEXT_DATA_BLOCK.equals(ab.getBuffer().getName())) return TEXT_DATA;
        } else {
            if (CgBonePalettes.MACRO_NAME.equals(ab.getMacroName())) return BONES;
            Slot token = token(ab.getMacroName());
            if (token != null) return token;
        }
        if (user >= ATTACHED_COUNT)
            throw new IllegalArgumentException("a material takes " + ATTACHED_COUNT + " attached buffers; "
                    + ab.getBuffer().getName() + " is one more");
        return new Slot(MATERIAL, FIRST_ATTACHED + user);
    }

    /** Whether {@link #attached} places this buffer in the material's set, counting against its attached slots. */
    public static boolean isMaterials(CgAttachedBuffer ab) {
        return attached(ab, 0).set() == MATERIAL;
    }

    private static final String TEXT_DATA_BLOCK = "TextData";
}
