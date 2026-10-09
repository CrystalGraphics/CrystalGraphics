package com.crystalgraphics.render.post.volume;

/**
 * The preset looks of an impact frame, each a {@link CgImpactFrame}. A look of your own is a frame built with
 * {@link CgImpactFrame#drawn()}; both go wherever a look does.
 *
 * <pre>{@code
 * settings.impact(CgImpact.SUBJECT, 1f);
 * CgImpactSequence.at(24f).beat(CgImpact.SUBJECT, 2).beat(CgImpact.FOCUS_LINES, 4).beat(CgImpact.WHITE, 1).build();
 * }</pre>
 */
public enum CgImpact {
    /** The picture's negative. */
    INVERT(CgImpactFrame.NEGATIVE),
    /** Stark black and white, split at mid-grey. */
    CONTRAST(CgImpactFrame.CONTRAST),
    /** Dark focus lines over the picture, a new set every frame. */
    LINES(CgImpactFrame.PICTURE_LINES),
    /** What glows white, the world black: anime's impact frame. */
    SUBJECT(CgImpactFrame.drawn().paper(CgImpactFrame.Tone.DARK).fillSubject(true).build()),
    /** {@link #SUBJECT} the other way: what glows black on a white world. */
    SUBJECT_INVERTED(CgImpactFrame.drawn().paper(CgImpactFrame.Tone.LIGHT).fillSubject(true).build()),
    /** {@link #SUBJECT} with white focus lines round it, a new set each frame. */
    SUBJECT_LINES(CgImpactFrame.drawn().paper(CgImpactFrame.Tone.DARK).fillSubject(true).lines(0.8f).boil(1).build()),
    /** The subject's edge in white strokes on black, the subject itself left black, a star at the focus. */
    HATCHED(CgImpactFrame.drawn().paper(CgImpactFrame.Tone.DARK).hatch(0.12f).star(0.04f).cross(true).jitter(2f).boil(2)
            .build()),
    /** Black focus lines on white, stopping at the subject, which is left white with a fringe of strokes. */
    FOCUS_LINES(CgImpactFrame.drawn().paper(CgImpactFrame.Tone.LIGHT).lines(1f).hatch(0.06f).jitter(2f).boil(2).build()),
    /** All white: the frame that hands off into a flash. */
    WHITE(CgImpactFrame.drawn().paper(CgImpactFrame.Tone.LIGHT).build());

    private final CgImpactFrame frame;

    CgImpact(CgImpactFrame frame) {
        this.frame = frame;
    }

    /** What it draws. */
    public CgImpactFrame frame() {
        return frame;
    }
}
