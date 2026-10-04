package com.crystalgraphics.render.stage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A typed slot on a stage firing's blackboard ({@link CgStageFrame#resources()}): how one renderer hands another a
 * resource it made this firing, as Filament's blackboard and URP's frame data do, without a field read across
 * classes.
 *
 * <pre>{@code
 * public static final CgFrameKey<CgGraphTexture> MASK = CgFrameKey.of("mymod:mask", CgGraphTexture.class);
 *
 * // the producer, recording at an earlier order of the same stage
 * frame.resources().put(MASK, mask);
 * // the consumer, later in the same firing
 * CgGraphTexture mask = frame.resources().get(MASK);   // null when nothing published it this firing
 * }</pre>
 *
 * <ul>
 *   <li>Declare a key once, in a static field: each takes a slot for the life of the process.</li>
 *   <li>A value lives for one firing. The next firing of the same stage starts empty, so a hand-off across firings
 *       (opaque to transparent, or 1.7.10's second anaglyph firing) needs a resource that outlives the frame.</li>
 * </ul>
 */
public final class CgFrameKey<T> {

    private static final AtomicInteger NEXT = new AtomicInteger();

    final int index;
    private final String name;
    private final Class<T> type;

    private CgFrameKey(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.index = NEXT.getAndIncrement();
    }

    public static <T> CgFrameKey<T> of(String name, Class<T> type) {
        return new CgFrameKey<>(name, type);
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    @Override
    public String toString() {
        return name;
    }
}
