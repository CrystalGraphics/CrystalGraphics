package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.state.CgGlStateProvider;
import com.crystalgraphics.platform.gl.state.CgGlStateShadow;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A host section sets the host's cached domains back to its belief as it closes, and only those. */
public class CgHostSectionScopeTest {

    /** A host whose cache believes the depth test on with LEQUAL, and declares {@code section}. */
    private static final class Host implements CgGlStateProvider {
        final int section;

        Host(int section) {
            this.section = section;
        }

        @Override
        public void read(CgGlSlot slot, CgGlStateShadow t) {
            if (slot == CgGlSlot.DEPTH) {
                t.depthTest = true;
                t.depthMask = true;
                t.depthFunc = CgGL.GL_LEQUAL;
            }
        }

        @Override
        public int sectionSlots() {
            return section;
        }
    }

    private RecordingGlBackend gl;

    @Before
    public void setUp() {
        gl = RecordingGlBackend.install();
        CgGlState.reset();
    }

    @After
    public void tearDown() {
        CgGlState.reset();
    }

    @Test
    public void aSectionSetsTheHostsDomainBackAsItCloses() {
        CgGlState.setProvider(new Host(1 << CgGlSlot.DEPTH.ordinal()));
        CgGL.fromHost();
        CgGL.glDisable(CgGL.GL_DEPTH_TEST);
        CgGL.glDepthFunc(CgGL.GL_ALWAYS);
        gl.clear();
        CgGL.toHost();
        assertTrue("the depth test the host believes on is enabled again", gl.sawCall("glEnable"));
        assertEquals("and its compare function put back", CgGL.GL_LEQUAL, (int) gl.depthFuncs.get(gl.depthFuncs.size() - 1));
    }

    @Test
    public void aHostDeclaringNothingGetsNothingBack() {
        CgGlState.setProvider(new Host(0));
        CgGL.fromHost();
        CgGL.glDisable(CgGL.GL_DEPTH_TEST);
        gl.clear();
        CgGL.toHost();
        assertFalse(gl.sawCall("glEnable"));
        assertTrue(gl.depthFuncs.isEmpty());
    }
}
