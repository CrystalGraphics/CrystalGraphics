package io.github.somehussar.crystalgraphics.gl.shader;

import io.github.somehussar.crystalgraphics.api.shader.CgShaderProgram;
import io.github.somehussar.crystalgraphics.gl.CrossApiTransition;
import io.github.somehussar.crystalgraphics.gl.state.CallFamily;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Abstract base class for all CrystalGraphics shader program implementations.
 *
 * <p>Provides the common lifecycle (bind, unbind, delete) and resource tracking
 * shared by the Core GL20 and ARB shader-objects backends.  Concrete subclasses
 * supply the actual OpenGL calls through {@link #freeGlResources()},
 * {@link #callFamily()}, and the uniform setter / location query methods.</p>
 *
 * <h3>Ownership Model</h3>
 * <p>Shader programs created by CrystalGraphics are <em>owned</em>
 * ({@code owned == true}) and tracked in {@link #ALL_OWNED} for bulk cleanup.
 * Externally-created programs can be <em>wrapped</em> ({@code owned == false})
 * for binding purposes, but calling {@link #delete()} on them throws
 * {@link IllegalStateException}.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>The static tracker set uses {@link CopyOnWriteArraySet} for safe iteration
 * during {@link #freeAll()}.  Individual instances are <strong>not</strong>
 * thread-safe and must only be used from the GL context thread (the Minecraft
 * render thread).</p>
 *
 * @see CgShaderProgram
 * @see CoreShaderProgram
 * @see ArbShaderProgram
 */
public abstract class AbstractCgShaderProgram implements CgShaderProgram {

    // ── Static tracking ────────────────────────────────────────────────

    /**
     * Set of all owned shader programs created by CrystalGraphics.
     *
     * <p>Used by {@link #freeAll()} to delete every owned program during
     * shutdown.  Uses {@link CopyOnWriteArraySet} so that iteration in
     * {@code freeAll()} is safe even though {@code delete()} removes
     * elements.</p>
     */
    protected static final Set<AbstractCgShaderProgram> ALL_OWNED =
            new CopyOnWriteArraySet<AbstractCgShaderProgram>();

    // ── Instance fields ────────────────────────────────────────────────

    /**
     * OpenGL program object ID (the name returned by {@code glCreateProgram}
     * or {@code glCreateProgramObjectARB}).
     */
    protected int programId;

    /**
     * Whether CrystalGraphics owns this program and is responsible for
     * deleting it.
     *
     * <p>When {@code false}, the program was created externally (e.g. by
     * another mod or by Minecraft's vanilla shader system) and wrapped for
     * convenience.  Calling {@link #delete()} on a non-owned program throws
     * {@link IllegalStateException}.</p>
     */
    protected final boolean owned;

    /**
     * Whether {@link #delete()} has been called on this shader program.
     *
     * <p>Once set to {@code true}, no further operations on this program
     * are valid.</p>
     */
    protected boolean deleted;

    // ── Constructor ────────────────────────────────────────────────────

    /**
     * Initialises the abstract shader program fields.
     *
     * <p>If {@code owned} is {@code true}, the new instance is added to
     * {@link #ALL_OWNED} for lifecycle tracking.</p>
     *
     * @param programId the OpenGL program object ID
     * @param owned     {@code true} if CrystalGraphics created this program
     */
    protected AbstractCgShaderProgram(int programId, boolean owned) {
        this.programId = programId;
        this.owned = owned;
        this.deleted = false;

        if (owned) {
            ALL_OWNED.add(this);
        }
    }

    // ── CgShaderProgram simple getters ─────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * @return {@code true} if CrystalGraphics created and owns this program
     */
    @Override
    public boolean isOwned() {
        return owned;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} if {@link #delete()} has been called
     */
    @Override
    public boolean isDeleted() {
        return deleted;
    }

    /**
     * {@inheritDoc}
     *
     * @return the OpenGL program object ID
     */
    @Override
    public int getId() {
        return programId;
    }

    // ── Binding ────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Binds this shader program for rendering via
     * {@link CrossApiTransition#bindProgram(int, CallFamily)}, ensuring safe
     * cross-API transitions when the previously-active program was bound
     * through a different call family.</p>
     */
    @Override
    public void bind() {
        CrossApiTransition.bindProgram(programId, callFamily());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Unbinds this shader program by binding program 0 (the fixed-function
     * pipeline) via {@link CrossApiTransition#bindProgram(int, CallFamily)}.</p>
     */
    @Override
    public void unbind() {
        CrossApiTransition.bindProgram(0, callFamily());
    }

    // ── Deletion ───────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Deletes this shader program and its underlying GL resource.  Throws
     * {@link IllegalStateException} if this is a wrapped (non-owned) program.
     * Subsequent calls after the first successful deletion are no-ops.</p>
     *
     * @throws IllegalStateException if this program is not owned
     */
    @Override
    public void delete() {
        if (!owned) {
            throw new IllegalStateException(
                    "Cannot delete a wrapped (non-owned) shader program");
        }
        if (deleted) {
            return;
        }
        freeGlResources();
        deleted = true;
        ALL_OWNED.remove(this);
    }

    // ── Abstract hooks for subclasses ──────────────────────────────────

    /**
     * Releases the underlying OpenGL program object.
     *
     * <p>Called exactly once by {@link #delete()}.  Implementations must
     * call the appropriate GL delete function (e.g.
     * {@code GL20.glDeleteProgram} or
     * {@code ARBShaderObjects.glDeleteObjectARB}).</p>
     */
    protected abstract void freeGlResources();

    /**
     * Returns the GL call family used by this shader program implementation.
     *
     * <p>Used by binding methods to route through
     * {@link CrossApiTransition#bindProgram(int, CallFamily)}.</p>
     *
     * @return the {@link CallFamily} for this shader backend
     */
    protected abstract CallFamily callFamily();

    // ── Static lifecycle ───────────────────────────────────────────────

    /**
     * Deletes all owned shader programs tracked by CrystalGraphics.
     *
     * <p>Intended to be called during shutdown or context destruction.
     * After this call, {@link #ALL_OWNED} is empty.  Each program's
     * {@link #freeGlResources()} is called exactly once.</p>
     */
    public static void freeAll() {
        for (AbstractCgShaderProgram program : ALL_OWNED) {
            if (!program.deleted) {
                program.freeGlResources();
                program.deleted = true;
            }
        }
        ALL_OWNED.clear();
    }
}
