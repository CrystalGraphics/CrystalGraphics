package com.crystalgraphics.widget;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgAttachment;

/**
 * A widget that follows a point in the world, drawn over the HUD at that point as it is on screen: UMG's
 * {@code UWidgetComponent} in screen space. Made by its {@link CgWorldWidgetLayer}, which places, orders and draws it.
 *
 * <pre>{@code
 * CgWidgetComponent pop = NUMBERS.add(POP.loaded(), CgEntityAttachments.of(targetId));
 * pop.widget().text(NUMBER, "1,250").style(NUMBER, gold);   // set up before it shows
 * pop.play(NORMAL_TEXT.loaded());                            // starts with it; it is released when this ends
 * }</pre>
 *
 * <pre>{@code
 * CgWidgetComponent plate = PLATES.add(NAME_PLATE.loaded(), CgEntityAttachments.of(mobId, CgEntityAttachments.HEAD))
 *         .pivot(0.5f, 1f);                                   // its bottom middle on the point
 * plate.widget().text("Txt_Name", name);
 * // ... it plays nothing, so it stays until:
 * plate.remove();
 * }</pre>
 *
 * <ul>
 *   <li>Its {@link #widget()} is pooled: set it up when it is added, and never keep it past {@link #remove()} or its
 *       sequence's end.</li>
 *   <li>It is drawn from the frame after its layer starts it, never before its sequence's first frame is evaluated, so
 *       it never shows the designer's values for a frame (Unreal evaluates a widget animation's first frame at play).</li>
 *   <li>Where its attachment is gone it stays where it was last seen; one gone before it was ever placed is not drawn.
 *       Behind the camera it is skipped for the frame.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgWidgetComponent {

    /** Where it stands in its layer. */
    public enum State {
        /** Added, waiting for its layer's next tick or for a place under its layer's cap. */
        QUEUED,
        /** Started: drawn each frame its point is in view. */
        LIVE,
        /** Released: its widget is back in the pool. */
        RELEASED
    }

    final CgWorldWidgetLayer layer;
    final CgUserWidget widget;
    final CgAttachment attachment;
    float drawWidth;
    float drawHeight;
    float pivotX = 0.5f;
    float pivotY = 0.5f;
    @Nullable
    private CgSequenceDefinition sequence;
    @Nullable
    private CgSequencePlayer player;
    State state = State.QUEUED;

    /** Its point in the world, absolute; whether it was ever placed. */
    final double[] point = new double[3];
    boolean located;
    /** Where it is on screen this frame, in canvas pixels, its distance from the eye squared, its laid-out size. */
    float screenX;
    float screenY;
    double depth;
    float width;
    float height;

    CgWidgetComponent(CgWorldWidgetLayer layer, CgUserWidget widget, CgAttachment attachment) {
        this.layer = layer;
        this.widget = widget;
        this.attachment = Objects.requireNonNull(attachment, "attachment");
    }

    /** Its widget, at its designer's values when added. */
    public CgUserWidget widget() {
        return widget;
    }

    /**
     * Its size in widget units: UMG's {@code DrawSize}. Zero, the default, draws it at its desired size, as
     * {@code bDrawAtDesiredSize}.
     */
    public CgWidgetComponent drawSize(float width, float height) {
        this.drawWidth = width;
        this.drawHeight = height;
        return this;
    }

    /** The point of its box on its attachment's point, a share of its size: UMG's {@code Pivot}, (0.5, 0.5) unless set. */
    public CgWidgetComponent pivot(float x, float y) {
        this.pivotX = x;
        this.pivotY = y;
        return this;
    }

    /**
     * Plays {@code definition} on its widget when it starts ({@link CgUserWidget#playAnimation}); when it ends, the
     * component is released. Before it starts only.
     *
     * @throws IllegalStateException once it has started
     */
    public CgWidgetComponent play(CgSequenceDefinition definition) {
        if (state != State.QUEUED) throw new IllegalStateException("a component's sequence is set before it starts");
        this.sequence = Objects.requireNonNull(definition, "definition");
        return this;
    }

    /** Its sequence's player once it has started; null before, or where it plays none. */
    @Nullable
    public CgSequencePlayer player() {
        return player;
    }

    public State state() {
        return state;
    }

    /** Releases it now: dropped from the queue, or taken off the screen. Nothing once released. */
    public void remove() {
        layer.release(this);
    }

    /** Starts it: its layer's tick. */
    void start() {
        state = State.LIVE;
        if (sequence != null) player = widget.playAnimation(sequence);
    }

    /** Whether its sequence has played out: never for one that plays none. */
    boolean finished() {
        return player != null && (player.finished() || player.stopped());
    }

    /** As it is released: its pool's reset stops what its widget plays, putting back what that wrote. */
    void stop() {
        state = State.RELEASED;
        player = null;
    }
}
