package com.crystalgraphics.api.state;

import com.crystalgraphics.platform.gl.driver.GLC;
import org.junit.Test;

import static org.junit.Assert.*;

public class CgStencilStateTest {

    @Test
    public void DISABLED_isNotEnabled() {
        assertFalse(CgStencilState.DISABLED.enabled());
    }

    @Test
    public void DISABLED_compFuncIsAlways() {
        assertEquals(GLC.GL_ALWAYS, CgStencilState.DISABLED.compFunc());
    }

    @Test
    public void DISABLED_passOpIsKeep() {
        assertEquals(GLC.GL_KEEP, CgStencilState.DISABLED.passOp());
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
        CgStencilState s = new CgStencilState(true, 3, 0x0F, 0xFF, GLC.GL_EQUAL, GLC.GL_REPLACE, GLC.GL_KEEP, GLC.GL_KEEP);
        assertTrue(s.enabled());
        assertEquals(3, s.ref());
        assertEquals(0x0F, s.readMask());
        assertEquals(GLC.GL_EQUAL, s.compFunc());
        assertEquals(GLC.GL_REPLACE, s.passOp());
    }

    @Test
    public void DISABLED_failOpIsKeep() {
        assertEquals(GLC.GL_KEEP, CgStencilState.DISABLED.failOp());
    }

    @Test
    public void DISABLED_zfailOpIsKeep() {
        assertEquals(GLC.GL_KEEP, CgStencilState.DISABLED.zfailOp());
    }
}
