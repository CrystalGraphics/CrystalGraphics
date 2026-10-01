package com.crystalgraphics.render.graph;

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

    /** Adds a sealed recording after those already added. */
    public CgFrameGraph add(CgRecording recording) {
        if (!recording.isSealed()) throw new IllegalArgumentException("seal() a recording before adding it");
        recordings.add(recording);
        return this;
    }

    public List<CgRecording> recordings() {
        return Collections.unmodifiableList(recordings);
    }

    /** Empties it for the next frame. */
    public void clear() {
        recordings.clear();
    }
}
