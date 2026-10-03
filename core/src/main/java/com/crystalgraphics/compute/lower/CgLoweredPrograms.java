package com.crystalgraphics.compute.lower;

import com.crystalgraphics.api.shader.CgShaderProgram;
import com.crystalgraphics.platform.gl.CgGL;

import java.util.HashMap;
import java.util.Map;

/**
 * The engine's own small programs a lowered dispatch runs around a kernel's passes (gpu-compute C5), compiled once per
 * variant: copying a buffer into a scatter target and back, placing appended elements at their counter, adding to a
 * counter, and turning a dispatch's group counts into a draw command. Render thread.
 *
 * <pre>{@code
 * CgLoweredPrograms.Helper add = CgLoweredPrograms.counterAdd();
 * add.use();
 * add.set("_cg_at", wordOffset);
 * }</pre>
 */
public final class CgLoweredPrograms {

    /** A compiled helper and its uniforms, looked up once each. */
    public static final class Helper {
        private final CgShaderProgram program;
        private final Map<String, Integer> locations = new HashMap<>();

        Helper(CgShaderProgram program) {
            this.program = program;
        }

        public void use() {
            CgGL.glUseProgram(program.getId());
        }

        /** Sets int uniform (or sampler unit) {@code name}: the program is the one in use. */
        public void set(String name, int value) {
            int location = locations.computeIfAbsent(name, n -> CgGL.glGetUniformLocation(program.getId(), n));
            if (location >= 0) CgGL.glUniform1i(location, value);
        }

        void delete() {
            program.delete();
        }
    }

    private static final Map<String, Helper> HELPERS = new HashMap<>();

    private CgLoweredPrograms() {}

    /**
     * A dispatch's group counts as a {@code DrawArraysIndirectCommand}: one vertex, capturing (elements, 1, 0, 0).
     * Uniforms {@code _cg_args} (a unit), {@code _cg_at} (a word), {@code _cg_sx/_cg_sy/_cg_sz} (the local size).
     */
    public static Helper command() {
        return HELPERS.computeIfAbsent("command", k -> capture("""
                #version 330 core
                uniform usamplerBuffer _cg_args;
                uniform int _cg_at;
                uniform int _cg_sx;
                uniform int _cg_sy;
                uniform int _cg_sz;
                flat out uvec4 _cg_command;
                void main() {
                    uvec3 g = uvec3(texelFetch(_cg_args, _cg_at).r, texelFetch(_cg_args, _cg_at + 1).r,
                                    texelFetch(_cg_args, _cg_at + 2).r);
                    uvec3 n = g * uvec3(uint(_cg_sx), uint(_cg_sy), uint(_cg_sz));
                    _cg_command = uvec4(n.x * n.y * n.z, 1u, 0u, 0u);
                    gl_Position = vec4(0.0);
                }
                """, "_cg_command"));
    }

    /**
     * A buffer's view into a scatter target, drawn as one triangle over it: texel {@code t} is the view's texel
     * {@code t}, its words, or the scalar as a float where the target holds floats.
     * Uniforms {@code _cg_src}, {@code _cg_first} (a texel), {@code _cg_count} (texels), {@code _cg_width}.
     *
     * @param scalar the element's type when {@code floats}: float, int or uint
     */
    public static Helper scatterInit(boolean floats, String scalar) {
        String key = "init/" + floats + "/" + scalar;
        String out = floats ? "vec4(" + toFloat(scalar, "w.r") + ")" : "w";
        return HELPERS.computeIfAbsent(key, k -> draw(CgLoweredEmitter.FULLSCREEN_VERTEX, """
                #version 330 core
                uniform usamplerBuffer _cg_src;
                uniform int _cg_first;
                uniform int _cg_count;
                uniform int _cg_width;
                out %s _cg_o;
                void main() {
                    ivec2 p = ivec2(gl_FragCoord.xy);
                    int t = p.y * _cg_width + p.x;
                    if (t >= _cg_count) discard;
                    uvec4 w = texelFetch(_cg_src, _cg_first + t);
                    _cg_o = %s;
                }
                """.formatted(floats ? "vec4" : "uvec4", out)));
    }

    /**
     * A scatter target back into words, a vertex per texel, captured in order: the target's words, or the float
     * converted back to the element's type. A float still equal to its seed keeps the view's own word, so a word the
     * blends never touched comes back exactly, above 2^24 too. Uniforms {@code _cg_texels} (a unit), {@code _cg_width},
     * and for floats {@code _cg_src} (the view, a unit) and {@code _cg_first} (its first texel).
     *
     * @param words a texel's words: 1, 2 or 4
     */
    public static Helper scatterResolve(boolean floats, String scalar, int words) {
        String key = "resolve/" + floats + "/" + scalar + "/" + words;
        if (floats) {
            return HELPERS.computeIfAbsent(key, k -> capture("""
                    #version 330 core
                    uniform sampler2D _cg_texels;
                    uniform usamplerBuffer _cg_src;
                    uniform int _cg_first;
                    uniform int _cg_width;
                    flat out uint _cg_w;
                    void main() {
                        int t = gl_VertexID;
                        float v = texelFetch(_cg_texels, ivec2(t %% _cg_width, t / _cg_width), 0).r;
                        uint seed = texelFetch(_cg_src, _cg_first + t).r;
                        _cg_w = v == %s ? seed : %s;
                        gl_Position = vec4(0.0);
                    }
                    """.formatted(toFloat(scalar, "seed"), fromFloat(scalar, "v")), "_cg_w"));
        }
        String type = CgLoweredEmitter.uintType(words);
        String swizzle = words == 1 ? ".r" : words == 2 ? ".rg" : "";
        return HELPERS.computeIfAbsent(key, k -> capture("""
                #version 330 core
                uniform usampler2D _cg_texels;
                uniform int _cg_width;
                flat out %s _cg_w;
                void main() {
                    int t = gl_VertexID;
                    uvec4 v = texelFetch(_cg_texels, ivec2(t %% _cg_width, t / _cg_width), 0);
                    _cg_w = v%s;
                    gl_Position = vec4(0.0);
                }
                """.formatted(type, swizzle), "_cg_w"));
    }

    /**
     * Appended elements, captured from zero, placed at their counter in a scatter target of the append buffer, a point
     * per texel. Uniforms {@code _cg_src}, {@code _cg_counter}, {@code _cg_count} (units), {@code _cg_at} (the
     * counter's word), {@code _cg_k} (texels an element), {@code _cg_len} (the view's elements),
     * {@code _cg_width}, {@code _cg_height}.
     */
    public static Helper place() {
        return HELPERS.computeIfAbsent("place", k -> draw("""
                #version 330 core
                uniform usamplerBuffer _cg_src;
                uniform usamplerBuffer _cg_counter;
                uniform sampler2D _cg_count;
                uniform int _cg_at;
                uniform int _cg_k;
                uniform int _cg_len;
                uniform int _cg_width;
                uniform int _cg_height;
                flat out uvec4 _cg_value;
                void main() {
                    int t = gl_VertexID, e = t / _cg_k;
                    uint n = uint(texelFetch(_cg_count, ivec2(0), 0).r + 0.5);
                    int to = int(texelFetch(_cg_counter, _cg_at).r) + e;
                    _cg_value = texelFetch(_cg_src, t);
                    if (uint(e) >= n || to >= _cg_len) {
                        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                        return;
                    }
                    int dst = to * _cg_k + t % _cg_k;
                    vec2 texel = vec2(float(dst % _cg_width), float(dst / _cg_width)) + 0.5;
                    gl_Position = vec4(texel / vec2(float(_cg_width), float(_cg_height)) * 2.0 - 1.0, 0.0, 1.0);
                }
                """, CgLoweredEmitter.valueFragment(false)));
    }

    /**
     * A counter plus the count texel, captured as one word. Uniforms {@code _cg_counter}, {@code _cg_count} (units),
     * {@code _cg_at} (the counter's word).
     */
    public static Helper counterAdd() {
        return HELPERS.computeIfAbsent("counterAdd", k -> capture("""
                #version 330 core
                uniform usamplerBuffer _cg_counter;
                uniform sampler2D _cg_count;
                uniform int _cg_at;
                flat out uint _cg_sum;
                void main() {
                    _cg_sum = texelFetch(_cg_counter, _cg_at).r + uint(texelFetch(_cg_count, ivec2(0), 0).r + 0.5);
                    gl_Position = vec4(0.0);
                }
                """, "_cg_sum"));
    }

    static String toFloat(String scalar, String bits) {
        return scalar.equals("float") ? "uintBitsToFloat(" + bits + ")" : scalar.equals("int") ? "float(int(" + bits + "))"
                : "float(" + bits + ")";
    }

    static String fromFloat(String scalar, String value) {
        return scalar.equals("float") ? "floatBitsToUint(" + value + ")" : scalar.equals("int") ? "uint(int(" + value + "))"
                : "uint(" + value + ")";
    }

    private static Helper capture(String vertex, String varying) {
        return new Helper(CgShaderProgram.compileCapture(vertex, null, null, new String[]{varying}));
    }

    private static Helper draw(String vertex, String fragment) {
        return new Helper(CgShaderProgram.compileCapture(vertex, null, fragment, null));
    }

    static void releaseAll() {
        for (Helper h : HELPERS.values()) h.delete();
        HELPERS.clear();
    }
}
