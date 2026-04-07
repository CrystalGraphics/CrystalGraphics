package io.github.somehussar.crystalgraphics.mc.shader;

import io.github.somehussar.crystalgraphics.api.shader.CgShaderProgram;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderBindings;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBMultitexture;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GLContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;

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

    /** OpenGL constant for querying/setting the active texture unit (GL_ACTIVE_TEXTURE = 34016). */
    private static final int GL_ACTIVE_TEXTURE = 34016;

    /** OpenGL constant for texture unit 0 (GL_TEXTURE0 = 33984). */
    private static final int GL_TEXTURE0 = 33984;

    /**
     * Ordered list of deferred binding operations.
     * Each operation (set1f, set2f, etc.) is appended here and executed
     * in order when {@link #apply(CgShader)} is called.
     */
    private final List<BindingOp> ops = new ArrayList<>();

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
        this.ops.add(new Set1iOp(name, value));
        return this;
    }

    @Override
    public CgShaderBindings set1f(String name, float value) {
        this.ops.add(new Set1fOp(name, value));
        return this;
    }

    @Override
    public CgShaderBindings vec2(String name, float x, float y) {
        this.ops.add(new Vec2Op(name, x, y));
        return this;
    }

    @Override
    public CgShaderBindings vec3(String name, float x, float y, float z) {
        this.ops.add(new Vec3Op(name, x, y, z));
        return this;
    }

    @Override
    public CgShaderBindings vec4(String name, float x, float y, float z, float w) {
        this.ops.add(new Vec4Op(name, x, y, z, w));
        return this;
    }

    @Override
    public CgShaderBindings array(String name, int[] array) {
        IntBuffer buf = BufferUtils.createIntBuffer(array.length);
        buf.put(array);
        buf.flip();
        return buffer(name, buf);
    }

    @Override
    public CgShaderBindings array(String name, float[] array) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(array.length);
        buf.put(array);
        buf.flip();
        return buffer(name, buf);
    }

    @Override
    public CgShaderBindings buffer(String name, IntBuffer buffer) {
        this.ops.add(new IntBufferOp(name, ensureDirectInt(buffer)));
        return this;
    }

    @Override
    public CgShaderBindings buffer(String name, FloatBuffer buffer) {
        this.ops.add(new FloatBufferOp(name, ensureDirectFloat(buffer)));
        return this;
    }

    @Override
    public CgShaderBindings mat3(String name, FloatBuffer buffer) {
        this.ops.add(new Mat3Op(name, ensureDirectFloat(buffer)));
        return this;
    }

    @Override
    public CgShaderBindings mat4(String name, FloatBuffer buffer) {
        this.ops.add(new Mat4Op(name, ensureDirectFloat(buffer)));
        return this;
    }

    @Override
    public CgShaderBindings mat3(String name, Matrix3f matrix) {
        // Defensive copy to freeze state at record-time, preventing cross-frame mutation
        this.ops.add(new JomlMat3Op(name, new Matrix3f(matrix)));
        return this;
    }

    @Override
    public CgShaderBindings mat4(String name, Matrix4f matrix) {
        // Defensive copy to freeze state at record-time, preventing cross-frame mutation
        this.ops.add(new JomlMat4Op(name, new Matrix4f(matrix)));
        return this;
    }

    @Override
    public CgShaderBindings colorARGB(String name, int argb) {
        float a = (float) ((argb >> 24) & 255) / 255.0f;
        float r = (float) ((argb >> 16) & 255) / 255.0f;
        float g = (float) ((argb >> 8) & 255) / 255.0f;
        float b = (float) (argb & 255) / 255.0f;
        this.ops.add(new Vec4Op(name, r, g, b, a));
        return this;
    }

    @Override
    public CgShaderBindings colorRGB(String name, int rgb, float alpha) {
        float r = ((rgb >> 16) & 255) / 255.0f;
        float g = ((rgb >> 8) & 255) / 255.0f;
        float b = (rgb & 255) / 255.0f;
        this.ops.add(new Vec4Op(name, r, g, b, alpha));
        return this;
    }

    @Override
    public CgShaderBindings sampler2D(String name, int unit, ResourceLocation texture) {
        this.ops.add(new Sampler2DOp(name, unit, texture));
        return this;
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
            LOGGER.warn("[CrystalGraphics] Uniform '{}' not found in program {} (warn-once)",
                    name, Integer.valueOf(this.warnedProgramId));
        }
        return loc;
    }

    /**
     * Queries the currently active texture unit via {@code glGetInteger(GL_ACTIVE_TEXTURE)}.
     *
     * <p>This is used by texture binding operations to save and restore the
     * active texture unit around texture binding, ensuring no side effects
     * on the GL state.</p>
     *
     * @return the current active texture unit (as a GL_TEXTURE0 + N constant)
     */
    static int getActiveTextureUnit() {
        return GL11.glGetInteger(GL_ACTIVE_TEXTURE);
    }

    /**
     * Sets the active texture unit via either {@code GL13.glActiveTexture} or
     * {@code ARBMultitexture.glActiveTextureARB}, depending on hardware support.
     *
     * <p>This method bridges the difference between OpenGL 1.3+ core and legacy
     * ARB_multitexture extension calls, ensuring compatibility across hardware.</p>
     *
     * @param glTextureConstant the texture constant (e.g., GL_TEXTURE0 + unit)
     */
    static void setActiveTextureUnit(int glTextureConstant) {
        ContextCapabilities caps = GLContext.getCapabilities();
        if (caps.OpenGL13) {
            GL13.glActiveTexture(glTextureConstant);
        } else if (caps.GL_ARB_multitexture) {
            ARBMultitexture.glActiveTextureARB(glTextureConstant);
        }
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
            FloatBuffer direct = BufferUtils.createFloatBuffer(buf.remaining());
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
            IntBuffer direct = BufferUtils.createIntBuffer(buf.remaining());
            direct.put(buf.duplicate());
            direct.flip();
            return direct;
        }
        return buf.duplicate();
    }

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

    private record IntBufferOp(String name, IntBuffer buffer) implements BindingOp {

        @Override
            public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
                int loc = patch.resolveLocation(shader, this.name);
                if (loc >= 0) {
                    program.setUniformIntBuffer(loc, this.buffer);
                }
            }
        }

    private record FloatBufferOp(String name, FloatBuffer buffer) implements BindingOp {

        @Override
            public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
                int loc = patch.resolveLocation(shader, this.name);
                if (loc >= 0) {
                    program.setUniformFloatBuffer(loc, this.buffer);
                }
            }
        }

    private record Mat3Op(String name, FloatBuffer buffer) implements BindingOp {

        @Override
            public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
                int loc = patch.resolveLocation(shader, this.name);
                if (loc >= 0) {
                    program.setUniformMatrix3f(loc, this.buffer);
                }
            }
        }

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

    private static final class Sampler2DOp implements BindingOp {
        final String name;
        final int unit;
        final ResourceLocation texture;

        Sampler2DOp(String name, int unit, ResourceLocation texture) {
            this.name = name;
            this.unit = unit;
            this.texture = texture;
        }

    private record Sampler2DOp(String name, int unit, ResourceLocation texture) implements BindingOp {

        /**
             * Executes this binding operation:
             * <ol>
             *   <li>Resolves the sampler uniform location.</li>
             *   <li>Saves the current active texture unit.</li>
             *   <li>Activates the specified texture unit.</li>
             *   <li>Binds the texture via Minecraft's texture manager.</li>
             *   <li>Sets the sampler uniform to the texture unit index.</li>
             *   <li>Restores the previously active texture unit (with exception safety).</li>
             * </ol>
             */
            @Override
            public void execute(CgShader shader, CgShaderProgram program, CgShaderBindingsImpl patch) {
                int loc = patch.resolveLocation(shader, this.name);
                if (loc < 0) {
                    return;
                }
    
                int previousUnit = CgShaderBindingsImpl.getActiveTextureUnit();
                int targetUnit = GL_TEXTURE0 + this.unit;
    
                try {
                    CgShaderBindingsImpl.setActiveTextureUnit(targetUnit);
                    Minecraft.getMinecraft().getTextureManager().bindTexture(this.texture);
                    program.setUniform1i(loc, this.unit);
                } finally {
                    if (previousUnit != targetUnit) {
                        CgShaderBindingsImpl.setActiveTextureUnit(previousUnit);
                    }
                }
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
