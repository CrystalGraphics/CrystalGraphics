package io.github.somehussar.crystalgraphics.api.vertex;

import org.lwjgl.opengl.GL11;

/**
 * Lightweight immutable value type identifying a GL texture: (target, textureId).
 *
 * <p>This is a <strong>raw texture handle</strong>, not a render-state policy.
 * It carries the GL texture target (e.g. {@code GL_TEXTURE_2D}) and the texture
 * object ID. Provides {@link #bind()} for direct GL binding and value-based
 * {@link #equals(Object)}/{@link #hashCode()} for use as map keys or batch-break
 * comparators.</p>
 *
 * <h3>Relationship to {@code CgTextureState}</h3>
 * <p>{@code CgTextureState} (in {@code api/state/}) is the <em>policy</em> layer:
 * it knows about texture units, sampler uniform names, and fixed/dynamic/none modes.
 * {@code CgTextureState.fixed()} accepts a {@code CgTextureBinding} to identify
 * the texture. The two types compose — they do not duplicate each other.</p>
 *
 * <h3>Package placement note</h3>
 * <p>This class lives in {@code api/vertex/} for historical reasons (the legacy
 * {@code CgQuadBatcher} references it). It is not semantically a vertex concept.
 * A future cleanup may relocate it to {@code api/} or a dedicated package.</p>
 */
public final class CgTextureBinding {

    /** GL_TEXTURE_2D constant, avoiding GL import in this pure-data class. */
    public static final int TARGET_TEXTURE_2D = 0x0DE1;

    private final int target;
    private final int textureId;

    public CgTextureBinding(int target, int textureId) {
        this.target = target;
        this.textureId = textureId;
    }

    /** Convenience factory for GL_TEXTURE_2D bindings. */
    public static CgTextureBinding texture2D(int textureId) {
        return new CgTextureBinding(TARGET_TEXTURE_2D, textureId);
    }

    /** Returns the GL texture target (e.g. {@code GL_TEXTURE_2D}). */
    public int getTarget() { return target; }

    /** Returns the GL texture object ID. */
    public int getTextureId() { return textureId; }
    
    public void bind(){
        GL11.glBindTexture(target, textureId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CgTextureBinding that = (CgTextureBinding) o;
        return target == that.target && textureId == that.textureId;
    }

    @Override
    public int hashCode() {
        return 31 * target + textureId;
    }

    @Override
    public String toString() {
        return "CgTextureBinding{target=0x" + Integer.toHexString(target) + ", id=" + textureId + "}";
    }
}
