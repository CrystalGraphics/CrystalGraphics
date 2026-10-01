package com.crystalgraphics.render.graph;

import com.crystalgraphics.render.property.CgPropertyValues;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One frame's sealed recordings, in the order they were added: a document's surface, the compositor's own, a
 * world stage's. A read in one sees writes in those added before it.
 *
 * <pre>{@code
 * CgFrameGraph graph = new CgFrameGraph()
 *         .add(documentRecording)          // writes its surface
 *         .add(compositorRecording);       // reads every surface
 * CgFrame frame = builder.build(graph);    // any thread
 * CgExecutor.get().execute(frame);         // render thread
 * }</pre>
 */
public final class CgFrameGraph {

    private final List<CgRecording> recordings = new ArrayList<>();
    private final List<CgPropertyValues> values = new ArrayList<>();

    /** Adds a sealed recording after those already added, drawn with its recorded values. */
    public CgFrameGraph add(CgRecording recording) {
        return add(recording, null);
    }

    /**
     * Adds a sealed recording drawn with {@code values} changing its recorded ones — read when the frame executes, so
     * a later write moves what the next execution of the same frame draws.
     */
    public CgFrameGraph add(CgRecording recording, @Nullable CgPropertyValues values) {
        if (!recording.isSealed()) throw new IllegalArgumentException("seal() a recording before adding it");
        recordings.add(recording);
        this.values.add(values);
        return this;
    }

    public List<CgRecording> recordings() {
        return Collections.unmodifiableList(recordings);
    }

    /** The values the recording at {@code index} is drawn with, or null. */
    @Nullable
    CgPropertyValues values(int index) {
        return values.get(index);
    }

    /** Empties it for the next frame. */
    public void clear() {
        recordings.clear();
        values.clear();
    }
}
