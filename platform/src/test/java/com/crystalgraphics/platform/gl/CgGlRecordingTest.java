package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.gl.state.*;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** The recording's spine: replay is the direct stream, the live shadow is untouched, scopes run on replay. */
public class CgGlRecordingTest {

    private static final int LEQUAL = 0x0203;

    /** Answers a depth read with LEQUAL, so a restore has a known value to put back. */
    private static final CgGlStateProvider PROVIDER = (slot, t) -> {
        if (slot == CgGlSlot.DEPTH) { t.depthTest = true; t.depthMask = true; t.depthFunc = LEQUAL; }
    };

    private RecordingGlBackend gl;
    private CgGlRecording recording;

    @Before
    public void setUp() {
        gl = RecordingGlBackend.install();
        CgGlState.reset();
        CgGlState.setProvider(PROVIDER);
        recording = new CgGlRecording();
    }

    private static void paint() {
        FloatBuffer matrix = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
        CgGL.glEnable(CgGL.GL_BLEND);
        CgGL.glBlendFunc(CgGL.GL_SRC_ALPHA, CgGL.GL_ONE_MINUS_SRC_ALPHA);
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, 3);
        CgGL.glActiveTexture(CgGL.GL_TEXTURE0);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 9);
        CgGL.glUniformMatrix4fv(1, false, matrix);
        CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 6);
    }

    @Test
    public void aReplayIssuesWhatDrawingDirectlyWould() {
        paint();
        List<String> direct = new ArrayList<>(gl.calls());

        gl.clear();
        CgGlState.reset();
        CgGlState.setProvider(PROVIDER);
        recording.begin();
        try { paint(); } finally { recording.end(); }
        assertTrue("nothing reaches the driver while recording", gl.calls().isEmpty());

        recording.replay();
        assertEquals(direct, gl.calls());
    }

    @Test
    public void theLiveShadowIsUntouchedByARecording() {
        CgGlStateManager mgr = CgGlState.manager();
        assertTrue(mgr.depthMaskChanged(true));

        recording.begin();
        try { CgGL.glDepthMask(false); } finally { recording.end(); }

        assertFalse("the driver still holds true: the recorded write has not been replayed", mgr.depthMaskChanged(true));
    }

    @Test
    public void aRecordedScopeRestoresOnReplay() {
        recording.begin();
        try {
            try (CgGlScope ignored = CgGlState.save(CgGlSlot.DEPTH)) {
                CgGL.glDepthFunc(CgGL.GL_ALWAYS);
            }
        } finally {
            recording.end();
        }

        recording.replay();
        assertEquals("the set, then the restore", 2, gl.countOf("glDepthFunc"));
        assertFalse("the live shadow holds the restored value", CgGlState.manager().depthFuncChanged(LEQUAL));
    }

    @Test
    public void foreignDrawingRunsOnReplayInItsPlace() {
        int[] runs = {0};
        recording.begin();
        try {
            CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
            CgGlState.hostForeign(() -> { runs[0]++; CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 6); }, CgGlSlot.BLEND);
            CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 9);
        } finally {
            recording.end();
        }
        assertEquals("the foreign body waits for replay", 0, runs[0]);

        recording.replay();
        assertEquals(1, runs[0]);
        assertEquals(3, gl.countOf("glDrawArrays"));
    }

    @Test
    public void creatingAnObjectIsRefusedByName() {
        recording.begin();
        try {
            CgGL.glGenTextures();
            fail("expected a refusal");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("glGenTextures"));
        } finally {
            recording.end();
        }
    }

    @Test
    public void aQueryReadsWhatTheRecordingSetAndOtherwiseTheLiveContext() {
        recording.begin();
        try {
            CgGL.glGetInteger(CgGL.GL_DRAW_FRAMEBUFFER_BINDING);
            assertTrue("not set yet, so the live context answers", gl.sawCall("glGetInteger"));
            gl.clear();
            CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, 7);
            assertEquals(7, CgGL.glGetInteger(CgGL.GL_DRAW_FRAMEBUFFER_BINDING));
            assertFalse("set by the recording, so the recording answers", gl.sawCall("glGetInteger"));
        } finally {
            recording.end();
        }
    }

    @Test
    public void anOpenScopeIsRefusedAtEndAndCgGlStillDraws() {
        recording.begin();
        CgGlState.save(CgGlSlot.DEPTH);
        try {
            recording.end();
            fail("expected the open scope to be refused");
        } catch (IllegalStateException expected) {
        }
        CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        assertTrue("the live backend is back even though end() threw", gl.sawCall("glDrawArrays"));
    }
}
