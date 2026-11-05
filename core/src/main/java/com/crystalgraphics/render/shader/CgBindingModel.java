package com.crystalgraphics.render.shader;

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
 *   <tr><td>{@link #PASS}</td><td>the pass's records, the text block, bone palettes, engine buffers by {@code cg_use} token</td><td>once a pass</td></tr>
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

    // set 0, written in cg_env.glsl: CgViewBlock 0, cg_DepthBuffer 1, cg_SceneColor 2, cg_Lightmap 3, cg_Media 4,
    // cg_LocalLights 5, CgEnvironmentBlock 6

    /** {@code CgObjectDataBuffer}, the pass's draw records: written in {@code cg_env.glsl}. */
    public static final Slot OBJECTS = new Slot(PASS, 0);
    /** {@code CgTextRenderer}'s {@code TextData} block. */
    public static final Slot TEXT_DATA = new Slot(PASS, 1);
    /** {@code CgBonePalettes}, read by a skinned variant. */
    public static final Slot BONES = new Slot(PASS, 2);
    /** The first cg_use token's binding; token {@code i} in the registry's order is at this plus {@code i}. */
    public static final int FIRST_TOKEN = 3;

    /** {@code CgMaterialBlock}, the material's parameters. */
    public static final Slot MATERIAL_BLOCK = new Slot(MATERIAL, 0);
    /** The first of a material's {@code Buffers { }}: buffer {@code i} at this plus {@code i}. */
    public static final int FIRST_BUFFER = 1;
    /** The first buffer attached to a material at runtime; {@code CgMaterial.attach} allows {@link #ATTACHED_COUNT}. */
    public static final int FIRST_ATTACHED = 5, ATTACHED_COUNT = 8;
    /** A material's textures where they are bound rather than read by index: property {@code i} at this plus {@code i}. */
    public static final int FIRST_TEXTURE = 16;

    private CgBindingModel() {}

    /** The material's {@code Buffers { }} entry {@code i}. */
    public static Slot buffer(int i) {
        return new Slot(MATERIAL, FIRST_BUFFER + i);
    }

    /** The material's texture property {@code i}, counting texture properties alone, where textures are bound. */
    public static Slot texture(int i) {
        return new Slot(MATERIAL, FIRST_TEXTURE + i);
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
            int token = CgEngineBufferRegistry.indexOfMacro(ab.getMacroName());
            if (token >= 0) return new Slot(PASS, FIRST_TOKEN + token);
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
