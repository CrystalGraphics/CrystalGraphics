package com.crystalgraphics.render.shader;

import com.crystalgraphics.material.CgAttachedBuffer;
import com.crystalgraphics.animation.CgBonePalettes;
import com.crystalgraphics.gpu.resource.CgEngineBufferRegistry;
import com.github.bsideup.jabel.Desugar;

/**
 * Where every resource a shader reads is bound: the set it belongs to by how often it changes, and its binding in that
 * set (Unreal's uniform buffers by frequency, Vulkan's descriptor sets). The compiler writes each declaration through
 * {@code cg_env.glsl}'s {@code CG_BIND(set, binding)} from here, and a pass, a draw or a material names what fills a
 * slot by the same {@link Slot}: there are no binding points or texture units beside it.
 *
 * <pre>{@code
 * sb.append(CgBindingModel.MATERIAL_BLOCK.glsl()).append("layout(std140) uniform CgMaterialBlock { ... };\n");
 * // CG_BIND(2, 0) layout(std140) uniform CgMaterialBlock { ... };
 *
 * pass.texture(CgBindingModel.LIGHTMAP, host.lightmapTexture());          // a view texture, by its slot
 * world.draw(quads, spark).buffer(CgParticleBuffer.SLOT, pool.drawn());    // a cg_use token's buffer, for one draw
 * }</pre>
 *
 * <table>
 *   <tr><th>Set</th><th>Holds</th><th>Made</th></tr>
 *   <tr><td>{@link #VIEW}</td><td>the view and environment blocks, scene depth and colour, the lightmap, the media, local lights</td><td>once a pass</td></tr>
 *   <tr><td>{@link #PASS}</td><td>the object records and ids, the text block, bone palettes, the mesh's vertex words, engine buffers by {@code cg_use} token</td><td>once a pass</td></tr>
 *   <tr><td>{@link #MATERIAL}</td><td>its block, its {@code Buffers { }}, buffers attached to it, its textures where bound</td><td>persistent</td></tr>
 *   <tr><td>{@link #BINDLESS}</td><td>every texture view and sampler, by index</td><td>the device's</td></tr>
 * </table>
 *
 * <p>A slot's numbers never depend on the device: a cg_use token's is its place in {@link CgEngineBufferRegistry}'s
 * order.</p>
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

        /** Set and binding in one int, as a binding table keys it ({@link #pack}). */
        public int packed() {
            return pack(set, binding);
        }
    }

    /** {@code CgViewBlock}: the pass's camera and frame, from its constants. */
    public static final Slot VIEW_BLOCK = new Slot(VIEW, 0);
    /** {@code cg_DepthBuffer}: the scene's depth a pass reads ({@code readsDepth}). */
    public static final Slot SCENE_DEPTH = new Slot(VIEW, 1);
    /** {@code cg_SceneColor}: the scene's colour a pass reads ({@code readsColor}). */
    public static final Slot SCENE_COLOR = new Slot(VIEW, 2);
    /** {@code cg_Lightmap}: the host's lightmap; white where a pass names none. */
    public static final Slot LIGHTMAP = new Slot(VIEW, 3);
    /** {@code cg_Media}: the froxel transmittance; white where a pass names none. */
    public static final Slot MEDIA = new Slot(VIEW, 4);
    /** {@code cg_LocalLights}: the world's lights on transparent surfaces; black where a pass names none. */
    public static final Slot LOCAL_LIGHTS = new Slot(VIEW, 5);
    /** {@code CgEnvironmentBlock}: the sun, sky and fog, from the pass's constants. */
    public static final Slot ENVIRONMENT_BLOCK = new Slot(VIEW, 6);

    /** A packed slot that names nothing. */
    public static final int NONE = -1;

    /** {@code CgObjectDataBuffer}, the frame's object records or a draw's own: written in {@code cg_env.glsl}. */
    public static final Slot OBJECTS = new Slot(PASS, 0);
    /** {@code CgTextBatch}'s {@code TextData} block. */
    public static final Slot TEXT_DATA = new Slot(PASS, 1);
    /** {@code CgBonePalettes}, read by a skinned variant. */
    public static final Slot BONES = new Slot(PASS, 2);
    /** {@code CgObjectIdBuffer}, the pass's object ids: written in {@code cg_env.glsl}. */
    public static final Slot OBJECT_IDS = new Slot(PASS, 3);
    /** {@code CgVertexWords}, the words the vertex stage pulls the drawn mesh from ({@code CgMeshStore}). */
    public static final Slot VERTICES = new Slot(PASS, 4);
    /** The first cg_use token's binding; token {@code i} in the registry's order is at this plus {@code i}. */
    public static final int FIRST_TOKEN = 5;

    /** {@code CgMaterialBlock}, the material's parameters, or its shader's arena of them ({@code CgMaterialArena}). */
    public static final Slot MATERIAL_BLOCK = new Slot(MATERIAL, 0);
    /** The first of a material's {@code Buffers { }}: buffer {@code i} at this plus {@code i}. */
    public static final int FIRST_BUFFER = 1;
    /** How many {@code Buffers { }} a material declares at most. */
    public static final int MATERIAL_BUFFER_COUNT = 4;
    /** The first buffer attached to a material at runtime; {@code CgMaterial.attach} allows {@link #ATTACHED_COUNT}. */
    public static final int FIRST_ATTACHED = 5, ATTACHED_COUNT = 8;
    /** A material's textures where they are bound rather than read by index: property {@code i} at this plus {@code i}. */
    public static final int FIRST_TEXTURE = 16;
    /** How many textures a material binds at most, below the kernel buffers. */
    public static final int MATERIAL_TEXTURES = 16;

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

    /** The material's {@code Buffers { }} entry {@code i}. */
    public static Slot buffer(int i) {
        return new Slot(MATERIAL, FIRST_BUFFER + i);
    }

    /** The material's texture property {@code i}, counting texture properties alone, where textures are bound. */
    public static Slot texture(int i) {
        return new Slot(MATERIAL, FIRST_TEXTURE + i);
    }

    /** {@link #texture}'s packed, allocating nothing: what a binding table keys a material texture by. */
    public static int packedTexture(int i) {
        return pack(MATERIAL, FIRST_TEXTURE + i);
    }

    /**
     * The pass slot of cg_use token {@code token}: where its buffer binds, and where a draw names one of its own.
     *
     * @throws IllegalArgumentException for a token no one registered
     */
    public static Slot token(String token) {
        int i = CgEngineBufferRegistry.indexOf(token);
        if (i < 0) throw new IllegalArgumentException("no cg_use token '" + token + "' is registered");
        return new Slot(PASS, FIRST_TOKEN + i);
    }

    /** The pass slot of the cg_use token whose macro is {@code macro}; null for a macro no token has. */
    public static Slot tokenOfMacro(String macro) {
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
            Slot token = tokenOfMacro(ab.getMacroName());
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
