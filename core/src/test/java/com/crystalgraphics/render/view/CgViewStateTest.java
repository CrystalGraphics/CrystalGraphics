package com.crystalgraphics.render.view;

import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CgViewStateTest {

    private static final class Kept {
        int n;
    }

    private static final CgViewState.Key<Kept> KEPT = new CgViewState.Key<>("test.kept", Kept::new);
    private static final CgViewState.HistoryKey TRAIL = new CgViewState.HistoryKey("test.trail");

    @Test
    public void eachViewHasItsOwnRecord_madeOnceAtTheFirstAsk() {
        CgView left = CgView.create("left"), right = CgView.create("right");
        Kept a = left.state().get(KEPT);
        a.n = 7;
        assertSame(a, left.state().get(KEPT));
        assertNotSame(a, right.state().get(KEPT));
        assertEquals(0, right.state().get(KEPT).n);
    }

    @Test
    public void aViewIsContinuousOnlyOverConsecutiveFramesItDrew() {
        CgViewState state = CgView.create("v").state();
        state.begin(10);
        assertFalse("its first frame follows none", state.continuous());
        state.begin(10);   // a later firing of the same frame
        assertEquals(0, state.frameIndex());
        state.begin(11);
        assertTrue(state.continuous());
        assertEquals(1, state.frameIndex());
        state.begin(13);   // a frame it did not draw
        assertFalse(state.continuous());
    }

    @Test
    public void aHistoryIsFreshInTheFrameItIsMade_andAfterAGapOrAResize() {
        CgViewState state = CgView.create("v").state();
        CgRecording recording = new CgRecording();
        CgTextureDesc small = new CgTextureDesc(8, 8, CgFormat.RGBA16_FLOAT), big = new CgTextureDesc(16, 16, CgFormat.RGBA16_FLOAT);
        state.begin(1);
        CgGraphTexture first = state.history(TRAIL, small, recording);
        assertTrue(state.fresh(TRAIL));
        state.begin(2);
        assertSame(first, state.history(TRAIL, small, recording));
        assertFalse("last frame's, kept", state.fresh(TRAIL));
        state.begin(4);
        assertTrue("a frame skipped", state.fresh(TRAIL));
        state.begin(5);
        assertNotSame(first, state.history(TRAIL, big, recording));
        assertTrue("made again at the new size", state.fresh(TRAIL));
    }

    @Test
    public void anUnkeptViewHandsWorkOnButKeepsNoHistory() {
        CgViewState state = CgView.unkept().state();
        assertFalse(state.keepsHistory());
        state.begin(100);
        state.begin(101);
        assertFalse(state.continuous());
        state.history(TRAIL, new CgTextureDesc(4, 4, CgFormat.RGBA16_FLOAT), new CgRecording());
        state.begin(102);
        assertTrue(state.fresh(TRAIL));
    }
}
