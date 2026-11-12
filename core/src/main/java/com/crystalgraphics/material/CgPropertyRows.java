package com.crystalgraphics.material;

import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.gpu.resource.CgBufferLifetime;

import static com.crystalgraphics.api.buffer.CgBufferLayout.MemoryLayout.STD430;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.gpu.resource.CgShaderBufferRegistry;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * Per-draw property values: a frame's rows of vec4s, which a material declaring {@code #pragma cg_use rows} reads its
 * numbers from in place of its own, property by property (Godot's instance uniforms, made per binding). A draw names
 * where its row starts in {@code CG_OBJECT_CUSTOM3.x} ({@code CG_PROPERTY_ROW}); the material says where in that row
 * each property is, through the int the parser gives it ({@code _cg_at<name>}, -1 for the material's own value).
 *
 * <pre>{@code
 * // the material, once: _Radius is the row's first vec4, _Core its second
 * material.applyProperties(b -> b.set1i(CgMaterialProperty.rowSlotName("_Radius"), 0)
 *                                .set1i(CgMaterialProperty.rowSlotName("_Core"), 1));
 * // each frame, every draw's row written into one array, then uploaded once
 * data[at * 4] = radius;                           // a number takes its row vec4's x
 * System.arraycopy(core, 0, data, (at + 1) * 4, 4);
 * CgPropertyRows.upload(data, rows);
 * world.draw(mesh, material).custom(3, at, 0f, 0f, 0f).submit();
 * }</pre>
 *
 * <ul>
 *   <li>Write it every frame any row-reading draw draws: a FRAME buffer read in a frame that did not write it reads
 *       another frame's bytes, silently.</li>
 *   <li>Render thread only. The class makes its buffer when first touched, after {@code CgEngineLifecycle.ensureGraphicsContext}.</li>
 * </ul>
 */
public final class CgPropertyRows {

    /** The {@code attach()} macro the env file reads rows through. */
    public static final String MACRO_NAME = "ROWS_DATA";
    /** Where its shaders read it, and where a draw names one of its own. */
    public static final CgBindingModel.Slot SLOT = CgBindingModel.token("rows");

    public static final CgBufferLayout FORMAT = CgBufferLayout.builder("CgPropertyRow", STD430).vec4("value").build();

    /** Floats a row vec4 takes in {@link #upload}'s array. */
    public static final int VEC4_FLOATS = 4;

    private static final CgShaderBuffer BUFFER = CgShaderBufferRegistry.get()
            .getOrCreateInternal("CgPropertyRows", FORMAT, SLOT, CgBufferLifetime.FRAME);

    private CgPropertyRows() {
    }

    /** The buffer, for {@code CgEngineBufferRegistry}'s {@code rows} token, by method reference. */
    public static CgShaderBuffer buffer() {
        return BUFFER;
    }

    /** Uploads the first {@code vec4s} vec4s of {@code data} as this frame's rows. Render thread. */
    public static void upload(float[] data, int vec4s) {
        BUFFER.uploadRaw(data, Math.max(vec4s, 1) * VEC4_FLOATS);
    }
}
