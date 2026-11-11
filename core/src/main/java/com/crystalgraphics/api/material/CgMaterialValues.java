package com.crystalgraphics.api.material;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Writes a material's values by the names its {@code Properties { }} declares: what
 * {@link CgMaterial#applyProperties} hands its writer. Unreal's {@code UMaterialInstanceDynamic::SetScalarParameterValue},
 * {@code SetVectorParameterValue} and {@code SetTextureParameterValue}.
 *
 * <pre>{@code
 * material.applyProperties(v -> v
 *         .set1f("_Alpha", 0.5f)
 *         .vec4("_Color", 1f, 0f, 0f, 1f)
 *         .colorARGB("_Tint", 0xFF3080FF)
 *         .sampler("_MainTex", 0, texture));
 * }</pre>
 *
 * <p>A writer kept in a field allocates nothing a frame:
 *
 * <pre>{@code
 * private final Consumer<CgMaterialValues> writeAlpha = v -> v.set1f("_Alpha", alpha);
 * material.applyProperties(writeAlpha);
 * }</pre>
 *
 * <ul>
 *   <li>A name the shader does not declare is ignored: a keyword variant may leave a property out.</li>
 *   <li>A value that does not move leaves the material's block as it is; a moved one makes a new version (SH5).</li>
 *   <li>{@code unit} is the sampler's place among the declared samplers ({@link CgMaterial#samplerUnit}).</li>
 * </ul>
 */
public interface CgMaterialValues {

    /** An int property; a float one takes it as a float. */
    CgMaterialValues set1i(String name, int value);

    /** A float property; an int one takes it truncated. */
    CgMaterialValues set1f(String name, float value);

    CgMaterialValues vec2(String name, float x, float y);

    default CgMaterialValues vec2(String name, Vector2f v) {
        return vec2(name, v.x, v.y);
    }

    /** A vec3 property; on a vec4 one, x, y and z, its w kept. */
    CgMaterialValues vec3(String name, float x, float y, float z);

    default CgMaterialValues vec3(String name, Vector3f v) {
        return vec3(name, v.x, v.y, v.z);
    }

    CgMaterialValues vec4(String name, float x, float y, float z, float w);

    default CgMaterialValues vec4(String name, Vector4f v) {
        return vec4(name, v.x, v.y, v.z, v.w);
    }

    /** A colour as {@code 0xAARRGGBB}, into a vec4 property. */
    default CgMaterialValues colorARGB(String name, int argb) {
        return vec4(name, ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f,
                ((argb >> 24) & 0xFF) / 255f);
    }

    /** A colour as {@code 0xRRGGBB} with its alpha, into a vec4 property. */
    default CgMaterialValues colorRGB(String name, int rgb, float alpha) {
        return vec4(name, ((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, alpha);
    }

    /** An array property's elements ({@code color[n]}, {@code vec4[n]}), four floats an element. */
    CgMaterialValues array(String name, float[] values);

    /** A sampler property's texture, sampled as the property declares. */
    default CgMaterialValues sampler(String name, int unit, CgTexture texture) {
        return sampler(name, unit, texture, null);
    }

    /** A sampler property's texture, with how it is sampled; a null {@code sampler} as the property declares. */
    CgMaterialValues sampler(String name, int unit, CgTexture texture, CgSampler sampler);
}
