package io.github.somehussar.crystalgraphics.gl.material;

import io.github.somehussar.crystalgraphics.api.shader.CgShaderBindings;
import io.github.somehussar.crystalgraphics.api.texture.CgTexture;
import lombok.Getter;

/**
 * A material property — declaration, current value, and binding logic for one
 * entry in a {@code .shader} file's {@code Properties { }} block.
 *
 * <p>Replaces the former {@code PropertyDecl} + {@code CgPropertyParser} split.
 * At parse time, {@link #fromDecl} creates the property and eagerly applies the
 * default value into {@link #floatValue}. At draw time, {@link #applyTo} flushes
 * the current value to the shader's persistent {@link CgShaderBindings}.</p>
 */
public final class CgMaterialProperty {

    /** GLSL type categories for the Properties block. */
    public enum Type {
        FLOAT("float", 1),
        VEC2("vec2",   2),
        VEC3("vec3",   3),
        VEC4("vec4",   4),
        SAMPLER2D("sampler2D", 0);  // 0 = not float-family

        private final String glslName;
        private final int components; // float components; 0 for sampler2D

        Type(String glslName, int components) {
            this.glslName   = glslName;
            this.components = components;
        }

        public String getGlslName()   { return glslName; }
        public int    getComponents() { return components; }

        public static Type fromGlsl(String glslType) {
            for (Type t : values()) {
                if (t.glslName.equals(glslType)) return t;
            }
            throw new IllegalArgumentException("Unknown GLSL property type: " + glslType);
        }
    }

    @Getter
    private final String name;
    @Getter
    private final Type   type;
    /**
     * -- GETTER --
     * The raw default string as written in the source, or 
     * . 
     */
    @Getter
    private final String rawDefault; // original source string, for resetToDefault() and reference

    // Float-family current value (length = type.components, or 1 for FLOAT)
    private final float[] floatValue;

    // Sampler state
    @Getter
    private int       samplerUnit    = -1;   // -1 = not yet set
    @Getter
    private CgTexture samplerTexture = null;

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Creates a property from parse-time declaration data, eagerly parsing the
     * raw default string into {@link #floatValue}.
     * {@code sampler2D} properties always start with no texture (unit = -1).
     */
    public static CgMaterialProperty fromDecl(String name, String glslType, String rawDefault) {
        Type type = Type.fromGlsl(glslType);
        CgMaterialProperty p = new CgMaterialProperty(name, type, rawDefault);
        if (rawDefault != null && type != Type.SAMPLER2D) {
            p.setFromRaw(rawDefault);
        }
        return p;
    }

    private CgMaterialProperty(String name, Type type, String rawDefault) {
        this.name       = name;
        this.type       = type;
        this.rawDefault = rawDefault;
        // Allocate at least 1 slot; zero-initialised by JVM
        this.floatValue = new float[Math.max(1, type.getComponents())];
    }

    // ── Declaration accessors ─────────────────────────────────────────────────

    /** Returns the GLSL type name (e.g. {@code "vec4"}) for use in GLSL code generation. */
    public String getGlslType()   { return type.getGlslName(); }

    // ── Value setters ─────────────────────────────────────────────────────────

    public void set(float v)                              { floatValue[0] = v; }
    public void set(float x, float y)                    { floatValue[0] = x; floatValue[1] = y; }
    public void set(float x, float y, float z)           { floatValue[0] = x; floatValue[1] = y; floatValue[2] = z; }
    public void set(float x, float y, float z, float w)  { floatValue[0] = x; floatValue[1] = y; floatValue[2] = z; floatValue[3] = w; }
    public void setTexture(int unit, CgTexture texture)  { this.samplerUnit = unit; this.samplerTexture = texture; }

    // ── Value accessors ───────────────────────────────────────────────────────

    /** Returns a defensive copy of the float value array. */
    public float[]   getFloatValue()     { return floatValue.clone(); }

    // ── Bind ──────────────────────────────────────────────────────────────────

    /**
     * Applies this property's current value to {@code bindings}.
     * {@code sampler2D} properties with no texture set (unit {@literal <} 0) are skipped.
     */
    public void applyTo(CgShaderBindings bindings) {
        switch (type) {
            case FLOAT:
                bindings.set1f(name, floatValue[0]);
                break;
            case VEC2:
                bindings.vec2(name, floatValue[0], floatValue[1]);
                break;
            case VEC3:
                bindings.vec3(name, floatValue[0], floatValue[1], floatValue[2]);
                break;
            case VEC4:
                bindings.vec4(name, floatValue[0], floatValue[1], floatValue[2], floatValue[3]);
                break;
            case SAMPLER2D:
                if (samplerUnit >= 0 && samplerTexture != null) {
                    bindings.sampler(name, samplerUnit, samplerTexture);
                }
                break;
        }
    }

    // ── Default reset ─────────────────────────────────────────────────────────

    /**
     * Resets this property to its parsed default value.
     * No-op if no default was specified or if this is a {@code sampler2D}.
     */
    public void resetToDefault() {
        if (rawDefault != null && type != Type.SAMPLER2D) {
            setFromRaw(rawDefault);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void setFromRaw(String raw) {
        try {
            if (type.getComponents() == 1) {
                floatValue[0] = Float.parseFloat(raw.trim());
            } else {
                float[] parsed = parseVecDefault(raw, type.getComponents());
                System.arraycopy(parsed, 0, floatValue, 0, parsed.length);
            }
        } catch (NumberFormatException ignored) {
            // Malformed default — leave as zero; driver zero-initialises too
        }
    }

    /**
     * Parses a parenthesised vector default string like {@code "(1.0, 0.0, 0.0, 1.0)"}
     * into a float array. Parentheses are optional.
     */
    static float[] parseVecDefault(String raw, int components) {
        String s = raw.trim();
        if (s.startsWith("(")) s = s.substring(1);
        if (s.endsWith(")"))   s = s.substring(0, s.length() - 1);
        String[] parts = s.split(",");
        float[] result = new float[components];
        for (int i = 0; i < components && i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }
}
