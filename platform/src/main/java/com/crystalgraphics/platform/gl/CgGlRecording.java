package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;

/**
 * Records what the engine draws through {@link CgGL} and draws it later, through {@code CgGL} again.
 *
 * <pre>{@code
 * CgGlRecording recording = new CgGlRecording();   // keep it: it reuses its memory
 *
 * recording.begin();                // CgGL records from here; nothing reaches the driver
 * try {
 *     paint();
 * } finally {
 *     recording.end();              // CgGL draws again
 * }
 * recording.replay();               // the same calls, onto the live backend
 * }</pre>
 *
 * <p>Scopes, {@code hostForeign} and invalidations are recorded as operations and run against the live state
 * manager on replay, so a replayed frame restores exactly what a directly drawn one would. Replay from inside
 * the host's bracket, as any drawing is.</p>
 *
 * <p>What a recording refuses, each with an exception naming the call:</p>
 * <ul>
 *   <li>creating a GL object, or compiling and linking: its name is needed before replay;</li>
 *   <li>reading pixels back: they do not exist until replay.</li>
 * </ul>
 *
 * <p>A state query answers what the recording set — framebuffer, program, VAO, buffer and texture bindings, a
 * few capabilities — and reads anything else from the live context, which nothing recorded has reached. That
 * is right for a replay that starts from the state {@link #begin()} saw, as one on the same thread straight
 * after does.</p>
 *
 * <p>Owner thread only. Always {@link #end()} in a {@code finally}: an exception leaves {@code CgGL} recording
 * until then. A recording holds references to the scope slot arrays it was given, until the next {@link #begin()}.</p>
 */
public final class CgGlRecording {

    private CgGlRecordingBackend tape;
    private CgGLBackend live;
    private CgGlStateManager manager;
    private boolean recording;

    /** Starts recording; {@code CgGL} records until {@link #end()}. Discards what was recorded before. */
    public void begin() {
        if (recording) throw new IllegalStateException("Already recording");
        CgGLBackend current = CgGL.backend();
        CgGlStateManager m = CgGlState.manager();
        if (tape == null || current != live || m != manager) {
            live = current;
            manager = m;
            tape = new CgGlRecordingBackend(current, m);
        }
        tape.reset();
        manager.beginRecording(this);
        CgGL.init(tape);
        recording = true;
    }

    /**
     * Stops recording and puts the live backend back.
     *
     * @throws IllegalStateException if a recorded scope is still open, or a mapped buffer was never unmapped
     */
    public void end() {
        if (!recording) throw new IllegalStateException("Not recording");
        recording = false;
        CgGL.init(live);
        manager.endRecording();
        tape.finish();
    }

    /** Draws what was recorded, through {@code CgGL} onto the live backend. May run more than once. */
    public void replay() {
        if (recording) throw new IllegalStateException("Cannot replay while recording");
        if (tape != null) tape.replay();
    }

    public boolean isRecording() { return recording; }

    /** Bytes recorded: operations and the data they carry. */
    public int size() { return tape == null ? 0 : tape.size(); }

    // Called by the state manager while this recording captures.

    CgGlScope recordScope(boolean foreign, CgGlSlot[] slots) { return tape.recordScope(foreign, slots); }

    void recordInvalidate(CgGlSlot[] slots) { tape.recordInvalidate(slots); }

    void recordInvalidateAll() { tape.recordInvalidateAll(); }

    void recordForeign(Runnable body, CgGlSlot[] slots) { tape.recordForeign(body, slots); }
}
