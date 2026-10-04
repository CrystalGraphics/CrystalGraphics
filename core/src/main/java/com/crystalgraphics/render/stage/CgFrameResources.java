package com.crystalgraphics.render.stage;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * One stage firing's blackboard: what its renderers publish for each other, by {@link CgFrameKey}. Read it through
 * {@link CgStageFrame#resources()}.
 *
 * <pre>{@code
 * frame.resources().put(CgFrameKeys.EMISSION, emission);
 * CgGraphTexture emission = frame.resources().get(CgFrameKeys.EMISSION);
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, inside the firing. Cleared when the firing executes.</li>
 *   <li>Nothing allocates once every key has been put once.</li>
 * </ul>
 */
public final class CgFrameResources {

    private Object[] slots = new Object[8];
    /** One past the highest slot written since the last clear. */
    private int used;

    CgFrameResources() {
    }

    /** Publishes {@code value} under {@code key} for the rest of this firing, replacing what was there. */
    public <T> void put(CgFrameKey<T> key, T value) {
        int i = key.index;
        if (i >= slots.length) slots = Arrays.copyOf(slots, Math.max(i + 1, slots.length * 2));
        slots[i] = key.type().cast(value);
        if (i >= used) used = i + 1;
    }

    /** What was published under {@code key} this firing, or null. */
    @Nullable
    public <T> T get(CgFrameKey<T> key) {
        int i = key.index;
        return i < used ? key.type().cast(slots[i]) : null;
    }

    public boolean has(CgFrameKey<?> key) {
        return key.index < used && slots[key.index] != null;
    }

    void clear() {
        Arrays.fill(slots, 0, used, null);
        used = 0;
    }
}
