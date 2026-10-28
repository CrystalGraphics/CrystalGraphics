package com.crystalgraphics.sequence.notify;

import java.util.Map;

import javax.annotation.Nullable;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.definition.CgSequenceSection;
import com.crystalgraphics.sequence.definition.CgSequenceTrack;

/**
 * One notify section as its behaviour sees it: its sequence, its section, its properties, what it is bound to, and the
 * slots it may change. Made once a section, when its player binds.
 *
 * <pre>{@code
 * @Override public void begin(CgNotifyContext c) {
 *     float strength = ((Number) c.properties().get("strength")).floatValue();
 *     c.set(0, new float[] {strength, 0, 0, 0}, 0b1);     // held until the span ends, then restored
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@link #set} keeps its values: they are written every frame the span lasts, blended as an absolute section's.</li>
 *   <li>{@link #target} is null for a notify track bound to nothing.</li>
 * </ul>
 */
public final class CgNotifyContext {

    private final CgSequencePlayer player;
    private final CgSequenceTrack track;
    private final CgSequenceSection section;
    @Nullable
    private final CgSequenceTarget target;
    /** Its declared slots' blend channels, and what it set on each. */
    final int[] channels;
    final float[][] values;
    final int[] masks;
    boolean active;
    double lastSeconds;
    int fires;

    CgNotifyContext(CgSequencePlayer player, CgSequenceTrack track, CgSequenceSection section, @Nullable CgSequenceTarget target, int[] channels) {
        this.player = player;
        this.track = track;
        this.section = section;
        this.target = target;
        this.channels = channels;
        this.values = new float[channels.length][4];
        this.masks = new int[channels.length];
    }

    public CgSequencePlayer player() {
        return player;
    }

    public CgSequenceTrack track() {
        return track;
    }

    public CgSequenceSection section() {
        return section;
    }

    /** Its {@code properties}, as written, its kind's defaults not applied. */
    public Map<?, ?> properties() {
        return section.fields().get("properties") instanceof Map<?, ?> m ? m : Map.of();
    }

    /** What its track binds, or null. */
    @Nullable
    public CgSequenceTarget target() {
        return target;
    }

    /** Sets its kind's declared slot {@code slot} (an index into {@link CgNotifyKind#writes()}) to {@code values}' masked components. */
    public void set(int slot, float[] values, int mask) {
        for (int i = 0; i < 4; i++) if ((mask & 1 << i) != 0) this.values[slot][i] = values[i];
        masks[slot] |= mask;
    }

    /** Lets slot {@code slot} go: it is restored as if the span had ended. */
    public void clear(int slot) {
        masks[slot] = 0;
    }
}
