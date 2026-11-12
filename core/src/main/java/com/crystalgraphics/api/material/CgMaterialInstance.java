package com.crystalgraphics.api.material;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.render.draw.CgBufferHandle;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import com.crystalgraphics.material.CgMaterialProperty;
import java.util.List;

/**
 * Values of its own over a {@link CgMaterial}: its programs, starting from its values, changed by name. Unreal's
 * {@code UMaterialInstanceDynamic}.
 *
 * <pre>{@code
 * CgMaterialInstance hot = CgMaterial.load("mymod:shaders/glow.shader").instance()
 *         .set1f("_Strength", 2f).vec4("_Tint", 1f, 0.5f, 0.2f, 1f).sampler("_Mask", 0, mask);
 * hot.set1f("_Strength", 3f);                       // when it changes
 * world.draw(mesh, hot).submit();
 * hot.delete();                                     // its owner's, once nothing draws it
 * }</pre>
 *
 * <p>A writer kept in a field allocates nothing a frame:
 *
 * <pre>{@code
 * private final Consumer<CgMaterialValues> write = v -> v.set1f("_Alpha", alpha);
 * write.accept(instance);
 * }</pre>
 *
 * <ul>
 *   <li>Another keyword is another material: {@code material.keyword("MOSS", true).instance()}.</li>
 *   <li>A name its shader does not declare is ignored: a keyword variant may leave a property out.</li>
 *   <li>A value that does not move leaves its block as it is; a moved one makes a new version.</li>
 * </ul>
 */
public final class CgMaterialInstance implements CgMaterialInterface, CgMaterialValues {

    final CgMaterialProxy proxy;
    private final CgMaterial material;
    private final CgMaterialValues writer;

    CgMaterialInstance(CgMaterial material, CgMaterialProxy parent) {
        this.material = material;
        this.proxy = parent.instance(this);
        this.writer = proxy.writer();
    }

    @Override
    public CgMaterial material() {
        return material;
    }

    @Override
    public int changes() {
        return proxy.changes();
    }

    @Override
    public int getMaterialId() {
        return proxy.getMaterialId();
    }

    @Override
    public boolean precache() {
        return proxy.precache();
    }

    /** Its shader's properties with this instance's values. */
    @Override
    public List<CgMaterialProperty> properties() {
        return proxy.properties();
    }

    @Override
    public CgMaterialInstance set1i(String name, int value) {
        writer.set1i(name, value);
        return this;
    }

    @Override
    public CgMaterialInstance set1f(String name, float value) {
        writer.set1f(name, value);
        return this;
    }

    @Override
    public CgMaterialInstance vec2(String name, float x, float y) {
        writer.vec2(name, x, y);
        return this;
    }

    @Override
    public CgMaterialInstance vec2(String name, Vector2f v) {
        return vec2(name, v.x, v.y);
    }

    @Override
    public CgMaterialInstance vec3(String name, float x, float y, float z) {
        writer.vec3(name, x, y, z);
        return this;
    }

    @Override
    public CgMaterialInstance vec3(String name, Vector3f v) {
        return vec3(name, v.x, v.y, v.z);
    }

    @Override
    public CgMaterialInstance vec4(String name, float x, float y, float z, float w) {
        writer.vec4(name, x, y, z, w);
        return this;
    }

    @Override
    public CgMaterialInstance vec4(String name, Vector4f v) {
        return vec4(name, v.x, v.y, v.z, v.w);
    }

    @Override
    public CgMaterialInstance colorARGB(String name, int argb) {
        writer.colorARGB(name, argb);
        return this;
    }

    @Override
    public CgMaterialInstance colorRGB(String name, int rgb, float alpha) {
        writer.colorRGB(name, rgb, alpha);
        return this;
    }

    @Override
    public CgMaterialInstance array(String name, float[] values) {
        writer.array(name, values);
        return this;
    }

    @Override
    public CgMaterialInstance sampler(String name, int unit, CgTexture texture) {
        writer.sampler(name, unit, texture, null);
        return this;
    }

    @Override
    public CgMaterialInstance sampler(String name, int unit, CgTexture texture, @Nullable CgSampler sampler) {
        writer.sampler(name, unit, texture, sampler);
        return this;
    }

    /**
     * Binds {@code buffer} to the storage buffer its shader's {@code Buffers { }} declares as {@code name}.
     *
     * <pre>{@code
     * if (instance.declaresBuffer("FX_CLIPS")) instance.buffer("FX_CLIPS", clips);
     * }</pre>
     *
     * @throws IllegalArgumentException if the shader declares no buffer {@code name}
     */
    public CgMaterialInstance buffer(String name, CgBufferHandle buffer) {
        proxy.buffer(name, buffer);
        return this;
    }

    /** Frees its values' block. Idempotent; it draws nothing after. Its material and programs stay. */
    public void delete() {
        proxy.delete();
    }

    @Override
    public String toString() {
        return "CgMaterialInstance{" + material + "}";
    }
}
