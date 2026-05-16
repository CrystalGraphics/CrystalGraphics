package com.crystalgraphics.api.state;

import org.junit.Test;
import com.crystalgraphics.platform.gl.CgGL;

import static org.junit.Assert.*;

public class CgStencilStateTest {

    @Test
    public void DISABLED_isNotEnabled() {
        assertFalse(CgStencilState.DISABLED.enabled());
    }

    @Test
    public void DISABLED_compFuncIsAlways() {
        assertEquals(CgGL.GL_ALWAYS, CgStencilState.DISABLED.compFunc());
    }

    @Test
    public void DISABLED_passOpIsKeep() {
        assertEquals(CgGL.GL_KEEP, CgStencilState.DISABLED.passOp());
    }

    @Test
    public void DISABLED_readMaskIsFullByte() {
        assertEquals(0xFF, CgStencilState.DISABLED.readMask());
    }

    @Test
    public void DISABLED_writeMaskIsFullByte() {
        assertEquals(0xFF, CgStencilState.DISABLED.writeMask());
    }

    @Test
    public void constructor_roundTrip() {
        CgStencilState s = new CgStencilState(true, 3, 0x0F, 0xFF, CgGL.GL_EQUAL, CgGL.GL_REPLACE, CgGL.GL_KEEP, CgGL.GL_KEEP);
        assertTrue(s.enabled());
        assertEquals(3, s.ref());
        assertEquals(0x0F, s.readMask());
        assertEquals(CgGL.GL_EQUAL, s.compFunc());
        assertEquals(CgGL.GL_REPLACE, s.passOp());
    }

    @Test
    public void DISABLED_failOpIsKeep() {
        assertEquals(CgGL.GL_KEEP, CgStencilState.DISABLED.failOp());
    }

    @Test
    public void DISABLED_zfailOpIsKeep() {
        assertEquals(CgGL.GL_KEEP, CgStencilState.DISABLED.zfailOp());
    }
}
