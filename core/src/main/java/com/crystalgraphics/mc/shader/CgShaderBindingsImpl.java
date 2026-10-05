package com.crystalgraphics.mc.shader;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.api.shader.CgShaderProgram;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;

import com.crystalgraphics.api.texture.CgTexture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import com.crystalgraphics.util.CgBufferUtils;

/**
 * Default patch-list implementation for {@link CgShaderBindings}.
 *
 * <p>Bindings are stored as ordered operations and replayed against a
 * {@link CgShader} / {@link CgShaderProgram} pair when
 * {@link #apply(CgShader)} is called.</p>
 *
 * <p>This class logs missing uniforms once per program id to avoid flooding logs
 * while still surfacing real mistakes in uniform names.</p>
 */
final class CgShaderBindingsImpl implements CgShaderBindings {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /**
     * The deferred operations, one per uniform: a second write to a name replaces the first in place, as a GL
     * uniform keeps only its last value. Appending instead grew a persistent set by every call, replayed on
     * every bind. A UBO binding is keyed by its buffer.
     */
    private final List<BindingOp> ops = new ArrayList<>();
    private final Map<Object, Integer> slotOf = new HashMap<>();

    /**
     * Tracks the program ID from the last {@link #apply(CgShader)} call.
     * Used to detect when the program changes so warn-once state can be reset.
     * Initialized to -1 (no program seen yet).
     */
    private int warnedProgramId = -1;

    /**
     * Set of uniform names that have already been logged as missing.
     * Prevents log spam when the same missing uniform is queried multiple times
     * per program. Cleared whenever the program ID changes.
     */
    private final Set<String> warnedNames = new HashSet<>();

    @Override
    public CgShaderBindings set1i(String name, int value) {
        return put(name, new Set1iOp(name, value));
    }

    @Override
    public CgShaderBindings set1f(String name, float value) {
        return put(name, new Set1fOp(name, value));
    }

    @Override
    public CgShaderBindings vec2(String name, float x, float y) {
        return put(name, new Vec2Op(name, x, y));
    }

    @Override
    public CgShaderBindings vec2(String name, Vector2f vec) {
        return put(name, new JomlVec2Op(name, vec));
    }

    @Override
    public CgShaderBindings vec3(String name, float x, float y, float z) {
        return put(name, new Vec3Op(name, x, y, z));
    }

    @Override
    public CgShaderBindings vec3(String name, Vector3f vec) {
        return put(name, new JomlVec3Op(name, vec));
    }
    @Override
    public CgShaderBindings vec4(String name, float x, float y, float z, float w) {
        return put(name, new Vec4Op(name, x, y, z, w));
    }

    @Override
    public CgShaderBindings vec4(String name, Vector4f vec) {
        return put(name, new JomlVec4Op(name, vec));
    }
    
    @Override
    public CgShaderBindings array(String name, int[] array) {
        IntBuffer buf = CgBufferUtils.createIntBuffer(array.length);
        buf.put(array);
        buf.flip();
        return buffer(name, buf);
    }

    @Override
    public CgShaderBindings array(String name, float[] array) {
        FloatBuffer buf = CgBufferUtils.createFloatBuffer(array.length);
        buf.put(array);
        buf.flip();
        return buffer(name, buf);
    }

    @Override
    public CgShaderBindings buffer(String name, IntBuffer buffer) {
        return put(name, new IntBufferOp(name, ensureDirectInt(buffer)));
    }

    @Override
    public CgShaderBindings buffer(String name, FloatBuffer buffer) {
        return put(name, new FloatBufferOp(name, ensureDirectFloat(buffer)));
    }

    @Override
    public CgShaderBindings mat3(String name, FloatBuffer buffer) {
        return put(name, new Mat3Op(name, ensureDirectFloat(buffer)));
    }

    @Override
    public CgShaderBindings mat4(String name, FloatBuffer buffer) {
        return put(name, new Mat4Op(name, ensureDirectFloat(buffer)));
    }

    @Override
    public CgShaderBindings mat3(String name, Matrix3f matrix) {
        // Defensive copy to freeze state at record-time, preventing cross-frame mutation
        return put(name, new JomlMat3Op(name, new Matrix3f(matrix)));
    }

    @Override
    public CgShaderBindings mat4(String name, Matrix4f matrix) {
        // Defensive copy to freeze state at record-time, preventing cross-frame mutation
        return put(name, new JomlMat4Op(name, new Matrix4f(matrix)));
    }

    @Override
    public CgShaderBindings colorARGB(String name, int argb) {
        float a = (float) ((argb >> 24) & 255) / 255.0f;
        float r = (float) ((argb >> 16) & 255) / 255.0f;
        float g = (float) ((argb >> 8) & 255) / 255.0f;
        float b = (float) (argb & 255) / 255.0f;
        return put(name, new Vec4Op(name, r, g, b, a));
    }

    @Override
    public CgShaderBindings colorRGB(String name, int rgb, float alpha) {
        float r = ((rgb >> 16) & 255) / 255.0f;
        float g = ((rgb >> 8) & 255) / 255.0f;
        float b = (rgb & 255) / 255.0f;
        return put(name, new Vec4Op(name, r, g, b, alpha));
    }

    @Override
    public CgShaderBindings sampler(String name, int unit, CgTexture texture) {
        return put(name, new SamplerOp(name, unit, texture.getId(), texture.getTarget()));
    }

    @Override
    public CgShaderBindings sampler(String name, int unit, int glTextureId, int glTarget) {
        return put(name, new SamplerOp(name, unit, glTextureId, glTarget));
    }

    @Override
    public CgShaderBindings ubo(CgUniformBuffer buffer) {
        return put(buffer, new UniformBufferBindingOp(buffer));
    }

    /**
     * Removes all accumulated binding operations without applying them.
     *
     * Subsequent calls to {@link #apply(CgShader)} will have no effect
     * until new bindings are recorded. Warn-once state is NOT cleared by this method.
     */
    @Override
    public void clear() {
        this.ops.clear();
        this.slotOf.clear();
    }

    private CgShaderBindings put(Object key, BindingOp op) {
        Integer slot = slotOf.putIfAbsent(key, ops.size());
        if (slot == null) ops.add(op);
        else ops.set(slot, op);
        return this;
    }

    /** How many operations are recorded: one per uniform name, however often each was written. */
    int size() {
        return ops.size();
    }

    /**
     * Applies all accumulated binding operations to the given managed shader.
     *
     * <p>This method:
     * <ol>
     *   <li>Checks if the shader is compiled; returns early if not.</li>
     *   <li>Gets the underlying program and checks it exists; returns early if not.</li>
     *   <li>Detects program ID changes and resets warn-once state if the program changed.</li>
     *   <li>Executes each accumulated operation in order, resolving uniforms and setting values.</li>
     * </ol></p>
     *
     * <p>The underlying program MUST already be bound via
     * {@link CgShaderProgram#bind()} before this method is called.</p>
     */
    @Override
    public void apply(CgShader shader) {
        if (!shader.isCompiled()) {
            return;
        }

        CgShaderProgram program = shader.getProgram();
        if (program == null) {
            return;
        }

        int currentProgramId = program.getId();
        if (currentProgramId != this.warnedProgramId) {
            this.warnedNames.clear();
            this.warnedProgramId = currentProgramId;
        }

        for (int i = 0, size = this.ops.size(); i < size; i++) {
            this.ops.get(i).execute(shader, program, this);
        }
    }

    /**
     * Resolves the uniform location for the given name in the managed shader.
     *
     * <p>If the uniform location is not found (returns -1), this method checks
     * if the uniform name has already been logged as missing for the current program.
     * If not, a warn-once message is logged and the name is added to {@link #warnedNames}
     * to prevent redundant logging.</p>
     *
     * <p>This method is called internally by binding operations to handle missing
     * uniforms gracefully without spamming logs.</p>
     *
     * @param shader the managed shader to query
     * @param name the uniform name to resolve
     * @return the uniform location (non-negative) or -1 if not found
     */
    int resolveLocation(CgShader shader, String name) {
        int loc = shader.getUniformLocation(name);
        if (loc < 0 && this.warnedNames.add(name)) {
            LOGGER.warn("[CrystalGraphics] Uniform '" + name + "' not found in program " + this.warnedProgramId + " (warn-once)");
        }
        return loc;
    }

    /**
     * Ensures the given FloatBuffer is direct and position/limit are frozen.
     *
     * <p>If the buffer is heap-allocated (!isDirect()), copies its remaining
     * contents into a new direct FloatBuffer via {@link BufferUtils#createFloatBuffer(int)}.
     * If the buffer is already direct, returns a {@link FloatBuffer#duplicate()}
     * to freeze position/limit at record-time and prevent cross-frame mutation.</p>
     *
     * @param buf the source buffer (heap or direct)
     * @return a direct FloatBuffer with the same contents, ready for LWJGL consumption
     */
    private static FloatBuffer ensureDirectFloat(FloatBuffer buf) {
        if (!buf.isDirect()) {
            FloatBuffer direct = CgBufferUtils.createFloatBuffer(buf.remaining());
            direct.put(buf.duplicate());
            direct.flip();
            return direct;
        }
        return buf.duplicate();
    }

    /**
     * Ensures the given IntBuffer is direct and position/limit are frozen.
     *
     * <p>If the buffer is heap-allocated (!isDirect()), copies its remaining
     * contents into a new direct IntBuffer via {@link BufferUtils#createIntBuffer(int)}.
     * If the buffer is already direct, returns a {@link IntBuffer#duplicate()}
     * to freeze position/limit at record-time and prevent cross-frame mutation.</p>
     *
     * @param buf the source buffer (heap or direct)
     * @return a direct IntBuffer with the same contents, ready for LWJGL consumption
     */
    private static IntBuffer ensureDirectInt(IntBuffer buf) {
        if (!buf.isDirect()) {
            IntBuffer direct = CgBufferUtils.createIntBuffer(buf.remaining());
            direct.put(buf.duplicate());
            direct.flip();
            return direct;
        }
        return buf.duplicate();
    }

    @Desugar
    private record Set1iOp(String name, int value) implements BindingOp {

        /**
             * Executes this binding operation: resolves the uniform location and
             * calls {@link CgShaderProgram#setUniform1i(int, int)} if found.
             */
            @Override
            public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
                int loc = patch.resolveLocation(shader, this.name);
                if (loc >= 0) {
                    program.setUniform1i(loc, this.value);
                }
            }
    }

    @Desugar
    private record Set1fOp(String name, float value) implements BindingOp {

        /**
             * Executes this binding operation: resolves the uniform location and
             * calls {@link CgShaderProgram#setUniform1f(int, float)} if found.
             */
            @Override
            public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
                int loc = patch.resolveLocation(shader, this.name);
                if (loc >= 0) {
                    program.setUniform1f(loc, this.value);
                }
            }
    }

    @Desugar
    private record Vec2Op(String name, float x, float y) implements BindingOp {

        /**
             * Executes this binding operation: resolves the uniform location and
             * calls {@link CgShaderProgram#setUniform2f(int, float, float)} if found.
             */
            @Override
            public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
                int loc = patch.resolveLocation(shader, this.name);
                if (loc >= 0) {
                    program.setUniform2f(loc, this.x, this.y);
                }
            }
    }
    
    @Desugar
    private record Vec3Op(String name, float x, float y, float z) implements BindingOp {

        /**
             * Executes this binding operation: resolves the uniform location and
             * calls {@link CgShaderProgram#setUniform3f(int, float, float, float)} if found.
             */
            @Override
            public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
                int loc = patch.resolveLocation(shader, this.name);
                if (loc >= 0) {
                    program.setUniform3f(loc, this.x, this.y, this.z);
                }
            }
    }
    
    @Desugar
    private record Vec4Op(String name, float x, float y, float z, float w) implements BindingOp {

        /**
             * Executes this binding operation: resolves the uniform location and
             * calls {@link CgShaderProgram#setUniform4f(int, float, float, float, float)} if found.
             */
            @Override
            public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
                int loc = patch.resolveLocation(shader, this.name);
                if (loc >= 0) {
                    program.setUniform4f(loc, this.x, this.y, this.z, this.w);
                }
            }
    }

    @Desugar
    private record JomlVec2Op(String name, Vector2f vec) implements BindingOp {

        /**
         * Executes this binding operation: resolves the uniform location and
         * calls {@link CgShaderProgram#setUniform2f(int, float, float)} if found.
         */
        @Override
        public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
            int loc = patch.resolveLocation(shader, this.name);
            if (loc >= 0) {
                program.setUniform2f(loc, vec);
            }
        }
    }

    @Desugar
    private record JomlVec3Op(String name, Vector3f vec) implements BindingOp {

        /**
         * Executes this binding operation: resolves the uniform location and
         * calls {@link CgShaderProgram#setUniform3f(int, float, float, float)} if found.
         */
        @Override
        public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
            int loc = patch.resolveLocation(shader, this.name);
            if (loc >= 0) {
                program.setUniform3f(loc, vec);
            }
        }
    }

    @Desugar
    private record JomlVec4Op(String name, Vector4f vec) implements BindingOp {

        /**
         * Executes this binding operation: resolves the uniform location and
         * calls {@link CgShaderProgram#setUniform4f(int, float, float, float, float)} if found.
         */
        @Override
        public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
            int loc = patch.resolveLocation(shader, this.name);
            if (loc >= 0) {
                program.setUniform4f(loc, vec);
            }
        }
    }
    
    @Desugar
    private record IntBufferOp(String name, IntBuffer buffer) implements BindingOp {

        @Override
            public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
                int loc = patch.resolveLocation(shader, this.name);
                if (loc >= 0) {
                    program.setUniformIntBuffer(loc, this.buffer);
                }
            }
    }
    
    @Desugar
    private record FloatBufferOp(String name, FloatBuffer buffer) implements BindingOp {

        @Override
            public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
                int loc = patch.resolveLocation(shader, this.name);
                if (loc >= 0) {
                    program.setUniformFloatBuffer(loc, this.buffer);
                }
            }
    }
    
    @Desugar
    private record Mat3Op(String name, FloatBuffer buffer) implements BindingOp {

        @Override
            public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
                int loc = patch.resolveLocation(shader, this.name);
                if (loc >= 0) {
                    program.setUniformMatrix3f(loc, this.buffer);
                }
            }
    }
    
    @Desugar
    private record Mat4Op(String name, FloatBuffer buffer) implements BindingOp {

        /**
             * Executes this binding operation: resolves the uniform location and
             * calls {@link CgShaderProgram#setUniformMatrix4f(int, FloatBuffer)} if found.
             */
            @Override
            public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
                int loc = patch.resolveLocation(shader, this.name);
                if (loc >= 0) {
                    program.setUniformMatrix4f(loc, this.buffer);
                }
            }
    }
    
    @Desugar
    private record JomlMat3Op(String name, Matrix3f matrix) implements BindingOp {

        @Override
            public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
                int loc = patch.resolveLocation(shader, this.name);
                if (loc >= 0) {
                    program.setUniformMatrix3f(loc, this.matrix);
                }
            }
    }
    
    @Desugar
    private record JomlMat4Op(String name, Matrix4f matrix) implements BindingOp {

        @Override
            public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
                int loc = patch.resolveLocation(shader, this.name);
                if (loc >= 0) {
                    program.setUniformMatrix4f(loc, this.matrix);
                }
            }
    }
    
    @Desugar
    private record SamplerOp(String name, int unit, int textureId, int target) implements BindingOp {

        // Same shape as Sampler2DOp, but binds a raw GL texture id directly
        // instead of going through Minecraft's texture manager. Used for FBO
        // attachments and any non-MC-managed textures (CgTexture, etc.).
        @Override
        public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
            int loc = patch.resolveLocation(shader, this.name);
            if (loc < 0) return;

            CgTexture.active(unit);   // left active: the state shadow tracks it, and reading it back is a glGet
            CgTexture.bind(target, textureId);
            program.setUniform1i(loc, this.unit);
        }
    }

    @Desugar
    private record UniformBufferBindingOp(CgUniformBuffer ubo) implements BindingOp {

        @Override
        public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
            ubo.wireShader(shader);
        }
    }

    /**
     * Internal marker interface for deferred binding operations.
     *
     * <p>Each operation (set1f, set2f, etc.) is represented as a {@code PatchOp}
     * that stores the uniform name and values, then applies them to a program
     * when {@link #execute(CgShader, CgShaderProgram, CgShaderBindingsImpl)}
     * is called.</p>
     */
    private interface BindingOp {
        /**
         * Applies this operation to the given shader and program.
         *
         * @param shader the managed shader (used for uniform location resolution)
         * @param program the underlying shader program (must be already bound)
         * @param patch the patch instance containing warn-once state
         */
        void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch);
    }
}
