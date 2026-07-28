package com.crystalgraphics.text.atlas;

/**
 * Atlas storage-format tag, shared by the paged atlas system
 * ({@link CgGlyphAtlasPage}, {@link CgPagedGlyphAtlas}).
 *
 * <p>This class previously also implemented a single-page, LRU-evicting atlas
 * storage model (the predecessor to the paged system). That implementation had
 * zero production callers left once {@code CgFontRegistry}'s paged path became
 * the sole rasterization pipeline, and has been removed; only the type tag
 * both storage models share remains here, to avoid a wider rename of every
 * {@code CgGlyphAtlas.Type} reference across the atlas/cache/msdf packages.</p>
 */
public final class CgGlyphAtlas {

    /** Discriminates bitmap (GL_R8), MSDF and MTSDF (both GL_RGBA8) atlas textures. */
    public enum Type {
        /** Single-channel bitmap atlas ({@code GL_R8}, {@code GL_UNSIGNED_BYTE}). */
        BITMAP,
        /** Three-channel MSDF atlas ({@code GL_RGB16F}, uploaded as {@code GL_FLOAT}). */
        MSDF,
        /** Four-channel MTSDF atlas ({@code GL_RGBA8}, uploaded as {@code GL_UNSIGNED_BYTE}). */
        MTSDF
    }

    private CgGlyphAtlas() { }
}
