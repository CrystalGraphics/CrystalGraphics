package com.crystalgraphics.text.layout;

import com.crystalgraphics.api.text.CgShapedRun;

/**
 * Carries the source text a paragraph's {@link CgShapedRun}s were shaped from,
 * external to the runs themselves.
 *
 * <p>{@link CgLineBreaker} needs the original text to re-shape sub-ranges when
 * splitting an overflowing run at a word or grapheme boundary ({@link RunReshaper}).
 * Threading it through as its own context — one instance per paragraph — means
 * {@code CgShapedRun} no longer has to carry {@code sourceText} on every instance
 * just to serve this one internal re-shaping need.</p>
 */
public record CgReshapeContext(String sourceText) {

    public CgReshapeContext {
        if (sourceText == null) throw new IllegalArgumentException("sourceText must not be null");
    }
}
