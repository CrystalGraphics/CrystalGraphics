package com.crystalgraphics.gl.material;

import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import lombok.Getter;

import java.util.Locale;

/**
 * A material property — declaration, current value, and binding logic for one
 * entry in a {@code .shader} file's {@code Properties { }} block.
 *
 * <p>Replaces the former {@code PropertyDecl} + {@code CgPropertyParser} split.
 * At parse time, {@link #fromDecl} creates the property and eagerly applies the
 * default value into {@link #floatValue}. At draw time, non-sampler properties
 * are written to the material UBO via {@link #writeToUbo}, while sampler properties
 * bind their textures via {@link #applyToSampler}.</p>
 */
public final class CgMaterialProperty {

    /** GLSL type categories for the Properties block. */
    public enum Type {
        FLOAT("float", "float", 1),
        VEC2("vec2", "vec2", 2),
        VEC3("vec3", "vec3", 3),
        VEC4("vec4", "vec4", 4),
        INT("int", "int", 1),
        /**
         * Boolean property. User writes {@code bool} in the {@code .shader} Properties block;
         * GLSL type is {@code bool}. Stored as {@code intValue} (0 = false, 1 = true).
         * Default string accepts {@code "true"}/{@code "false"} (case-insensitive) or {@code "0"}/{@code "1"}.
         */
        BOOLEAN("boolean", "bool", 1),
        /**
         * Color property. User writes {@code color} in the {@code .shader} Properties block;
         * the compiled GLSL type is {@code vec4}. {@code fromGlsl("color")} returns {@code null}
         * because {@code color} is not a GLSL keyword.
         */
        COLOR("color", "vec4", 4),
        /**
         * Range property. User writes {@code Range(min, max)} in the Properties block;
         * the GLSL type is {@code float}.
         */
        RANGE("Range", "float", 1),

        SAMPLER2D("sampler2D", "sampler2D", 0, true),
        SAMPLER2D_ARRAY("sampler2DArray", "sampler2DArray", 0, true),
        SAMPLER3D("sampler3D", "sampler3D", 0, true),
        SAMPLER_CUBE("samplerCube", "samplerCube", 0, true);

        /** The token written by the user in the {@code .shader} Properties block. */
        @Getter
        private final String propertyTypeName;
        /** The GLSL type emitted in compiled shader source. */
        @Getter
        private final String glslName;
        /** Number of float components; 0 for sampler types. */
        @Getter
        private final int components;

        /** If type is any of the texture samplers**/
        private boolean isSampler;

        Type(String propertyTypeName, String glslName, int components) {
            this(propertyTypeName, glslName, components, false);
        }

        Type(String propertyTypeName, String glslName, int components, boolean isSampler) {
            this.propertyTypeName = propertyTypeName;
            this.glslName = glslName;
            this.components = components;
            this.isSampler = isSampler;
        }

        /**
         * Returns {@code true} if this type represents an opaque sampler (texture) type.
         * Sampler properties are bound via {@link CgMaterialProperty#applyToSampler} and
         * cannot be written into a UBO.
         */
        public boolean isSampler() {
            return isSampler;
        }

        /**
         * Looks up a type by its GLSL name (compiler usage).
         * Returns {@code null} for names that have no direct GLSL equivalent
         * (e.g. {@code "color"}).
         *
         * @param glslType the GLSL type string (e.g. {@code "float"}, {@code "vec4"})
         * @return the matching type or {@code null}
         */
        public static Type fromGlsl(String glslType) {
            for (Type t : values()) {
                if (t.glslName.equals(glslType) && !t.propertyTypeName.equals("color")) return t;
            }
            return null;
        }

        /**
         * Looks up a type by its property-block name (parser usage), case-insensitively.
         * Returns {@code null} for unknown names.
         *
         * @param typeName the property type string as written in the {@code .shader} file
         * @return the matching type or {@code null}
         */
        public static Type fromPropertyType(String typeName) {
            if (typeName == null) return null;
            String lower = typeName.toLowerCase(java.util.Locale.ROOT);
            for (Type t : values()) {
                if (t.propertyTypeName.toLowerCase(java.util.Locale.ROOT).equals(lower)) return t;
            }
            return null;
        }
    }

    @Getter
    private final String name;
    /**
     * The human-readable display name declared in the {@code .shader} Properties block,
     * e.g. {@code "Main Texture"} for {@code _MainTex ("Main Texture", sampler2D) = "white"}.
     * Falls back to {@link #name} for old-style declarations ({@code _Name : type}).
     */
    @Getter
    private final String displayName;
    @Getter
    private final Type   type;
    /**
     * The raw default string as written in the source, or {@code null}.
     */
    @Getter
    private final String rawDefault;

    // Float-family current value (length = type.components, or 1 for FLOAT/INT/RANGE)
    private final float[] floatValue;

    /**
     * -- GETTER --
     * Returns the current int value for 
     *  properties. 
     */
    // INT type current value
    @Getter
    private int intValue;

    // RANGE type bounds
    @Getter
    private float rangeMin;
    @Getter
    private float rangeMax;

    // Sampler state
    @Getter
    private int       samplerUnit    = -1;   // -1 = not yet set
    @Getter
    private CgTexture samplerTexture = null;

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Creates a property from parse-time declaration data, eagerly parsing the
     * raw default string into {@link #floatValue}.
     * Sampler properties always start with no texture (unit = -1).
     *
     * @param name        the property name (e.g. {@code "_Alpha"})
     * @param displayName the human-readable display name (e.g. {@code "Opacity"}), or {@code null} to fall back to name
     * @param typeName    the property type as written in the {@code .shader} Properties block
     * @param rawDefault  the default value string, or {@code null}
     */
    public static CgMaterialProperty fromDecl(String name, String displayName, String typeName, String rawDefault) {
        Type type = Type.fromPropertyType(typeName);
        if (type == null) {
            // Fallback: try glsl lookup for backward compatibility
            type = Type.fromGlsl(typeName);
        }
        if (type == null) {
            throw new IllegalArgumentException("Unknown material property type: " + typeName);
        }
        if (type == Type.VEC3) {
            throw new UnsupportedOperationException(
                    "vec3 is not supported as a material property type due to STD140 alignment "
                    + "ambiguity — use vec4 instead.");
        }
        CgMaterialProperty p = new CgMaterialProperty(name, displayName != null ? displayName : name, type, rawDefault);
        if (rawDefault != null && !type.isSampler()) {
            p.setFromRaw(rawDefault);
        }
        return p;
    }

    private CgMaterialProperty(String name, String displayName, Type type, String rawDefault) {
        this.name        = name;
        this.displayName = displayName;
        this.type        = type;
        this.rawDefault  = rawDefault;
        this.floatValue  = new float[Math.max(1, type.getComponents())];
    }

    // ── Declaration accessors ─────────────────────────────────────────────────

    /** Returns the GLSL type name (e.g. {@code "vec4"}) for use in GLSL code generation. */
    public String getGlslType() {return type.getGlslName();}

    // ── Value setters ─────────────────────────────────────────────────────────

    public void set(float v)                             { floatValue[0] = (type == Type.RANGE) ? Math.max(rangeMin, Math.min(rangeMax, v)) : v; }
    public void set(float x, float y)                    { floatValue[0] = x; floatValue[1] = y; }
    public void set(float x, float y, float z)           { floatValue[0] = x; floatValue[1] = y; floatValue[2] = z; }
    public void set(float x, float y, float z, float w)  { floatValue[0] = x; floatValue[1] = y; floatValue[2] = z; floatValue[3] = w; }

    public void setInt(int v) {this.intValue = v;}
    public void setTexture(int unit, CgTexture texture)  { this.samplerUnit = unit; this.samplerTexture = texture; }

    /** Sets the Range bounds. Use alongside {@link #set(float)} to set both bounds and current value. */
    public void setRange(float min, float max) {
        this.rangeMin = min;
        this.rangeMax = max;
    }

    // ── Value accessors ───────────────────────────────────────────────────────

    /** Returns a defensive copy of the float value array. */
    public float[] getFloatValue() {return floatValue.clone();}

    // ── UBO write ─────────────────────────────────────────────────────────────

    /**
     * Writes this property's current value into the material UBO writer at the field named
     * {@link #getName()}. Must only be called on non-sampler properties.
     *
     * <p>Dispatch table: FLOAT/RANGE → {@code float_}; INT/BOOLEAN → {@code int_};
     * COLOR/VEC4 → {@code vec4}; VEC2 → {@code vec2}.</p>
     * 
     * Doesn't write sampler properties, these must be passed to shader via {@code #applyToSampler}
     *
     * @param w the UBO buffer writer for the current material properties record
     
     */
    public void writeToUbo(CgBufferWriter w) {
        switch (type) {
            case FLOAT:
            case RANGE:
                w.float_(name, floatValue[0]);
                break;
            case INT:
                w.int_(name, intValue);
                break;
            case BOOLEAN:
                w.int_(name, intValue);
                break;
            case COLOR:
            case VEC4:
                w.vec4(name, floatValue[0], floatValue[1], floatValue[2], floatValue[3]);
                break;
            case VEC2:
                w.vec2(name, floatValue[0], floatValue[1]);
                break;
            default:
                break; 
        }
    }

    // ── Format builder ────────────────────────────────────────────────────────

    /**
     * Adds this property's type and name as a field in the given {@link CgBufferFormat.Builder}.
     * Called by {@link CgMaterialProperties#buildUboFormat()} to construct the
     * {@code CgMaterialBlock} format.
     * Must only be called on non-sampler properties.
     *
     * <p>Builder method names use underscore suffixes for Java keyword avoidance:
     * {@code float_()}, {@code int_()}, {@code vec2()}, {@code vec3()}, {@code vec4()}.</p>
     *
     * @param b the format builder to add this field to
     * @throws IllegalStateException if called on a sampler property
     */
    public void addToFormatBuilder(CgBufferFormat.Builder b) {
        switch (type) {
            case FLOAT:
            case RANGE:
                b.float_(name);
                break;
            case INT:
                b.int_(name);
                break;
            case BOOLEAN:
                b.int_(name);
                break;
            case COLOR:
            case VEC4:
                b.vec4(name);
                break;
            case VEC2:
                b.vec2(name);
                break;
            default:
                break;
        }
    }

    // ── Sampler bind ──────────────────────────────────────────────────────────

    /**
     * Applies this property's current texture to the given shader bindings.
     * Sampler properties with no texture set (unit {@literal <} 0) are skipped.
     * Uses {@link CgShaderBindings#sampler(String, int, CgTexture)}, which dispatches
     * to the texture's native GL target — supporting 2D, 2DArray, 3D, and Cubemap.
     *
     * @param bindings the shader bindings to apply the sampler to
     * @throws IllegalStateException if called on a non-sampler property
     */
    public void applyToSampler(CgShaderBindings bindings) {
        if (!type.isSampler()) {
            throw new IllegalStateException("applyToSampler() called on non-sampler property: " + name);
        }
        if (samplerUnit >= 0 && samplerTexture != null) {
            bindings.sampler(name, samplerUnit, samplerTexture);
        }
    }

    // ── Default reset ─────────────────────────────────────────────────────────

    /**
     * Resets this property to its parsed default value.
     * No-op if no default was specified or if this is a sampler type.
     */
    public void resetToDefault() {
        if (rawDefault != null && !type.isSampler()) {
            setFromRaw(rawDefault);
        }
    }

    /**
     * Returns a fresh {@code CgMaterialProperty} with the same name, display name, type, and
     * raw default, with the value reset to that default. Used when initialising per-material
     * property stores from a shared {@link CgParsedShader}.
     *
     * <p>The returned instance is independent — mutating the copy does not affect the original.</p>
     */
    public CgMaterialProperty copyWithDefaults() {
        CgMaterialProperty copy = CgMaterialProperty.fromDecl(name, displayName, type.getPropertyTypeName(), rawDefault);
        // For RANGE type, fromDecl clamps the default value to [rangeMin, rangeMax] = [0, 0],
        // which gives 0 instead of the parsed default. Copy the bounds and re-apply the default
        // so the copy has the correct value within the correct range.
        if (type == Type.RANGE) {
            copy.setRange(rangeMin, rangeMax);
            copy.resetToDefault();
        }
        return copy;
    }

    /**
     * Copies the current runtime value from {@code src} into this property.
     * No-op if types differ — type-changed properties retain their default value.
     * Used by {@code CgMaterial.onShaderRecompiled()} to preserve user-set values across
     * hot-reload rebuilds of the property store.
     *
     * @param src the source property to copy values from; must not be null
     */
    public void copyValueFrom(CgMaterialProperty src) {
        if (src.type != this.type) return;
        if (type.isSampler()) {
            this.samplerUnit    = src.samplerUnit;
            this.samplerTexture = src.samplerTexture;
        } else if (type == Type.INT || type == Type.BOOLEAN) {
            this.intValue = src.intValue;
        } else {
            System.arraycopy(src.floatValue, 0, this.floatValue, 0, this.floatValue.length);
            if (type == Type.RANGE) {
                // Preserve the range bounds so the copy stays within the same clamp window.
                this.rangeMin = src.rangeMin;
                this.rangeMax = src.rangeMax;
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void setFromRaw(String raw) {
        try {
            if (type == Type.INT) {
                intValue = Integer.parseInt(raw.trim());
            } else if (type == Type.BOOLEAN) {
                String v = raw.trim().toLowerCase(Locale.ROOT);
                intValue = (v.equals("true") || v.equals("1")) ? 1 : 0;
            } else if (type.getComponents() == 1) {
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
