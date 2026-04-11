package io.github.somehussar.crystalgraphics.api.vertex;

import io.github.somehussar.crystalgraphics.gl.batch.CgQuadBatcher;
import org.lwjgl.opengl.GL11;

/**
 * Lightweight value type representing a bound GL texture.
 *
 * <p>Carries the GL texture target (e.g. {@code GL_TEXTURE_2D}) and the
 * texture object ID. Used by {@link CgQuadBatcher}
 * to track texture state and detect batch-breaking texture changes.</p>
 *
 * <p>Designed to be extensible for texture arrays without rewriting the
 * batch API — a future subtype or additional field can carry layer index.</p>
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
