package com.crystalgraphics.api.state;

import org.junit.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.Assert.*;

public class CgStencilStateTest {

    @Test
    public void DISABLED_isNotEnabled() {
        assertFalse(CgStencilState.DISABLED.enabled());
    }

    @Test
    public void DISABLED_compFuncIsAlways() {
        assertEquals(GL11.GL_ALWAYS, CgStencilState.DISABLED.compFunc());
    }

    @Test
    public void DISABLED_passOpIsKeep() {
        assertEquals(GL11.GL_KEEP, CgStencilState.DISABLED.passOp());
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
        CgStencilState s = new CgStencilState(true, 3, 0x0F, 0xFF, GL11.GL_EQUAL, GL11.GL_REPLACE, GL11.GL_KEEP, GL11.GL_KEEP);
        assertTrue(s.enabled());
        assertEquals(3, s.ref());
        assertEquals(0x0F, s.readMask());
        assertEquals(GL11.GL_EQUAL, s.compFunc());
        assertEquals(GL11.GL_REPLACE, s.passOp());
    }

    @Test
    public void DISABLED_failOpIsKeep() {
        assertEquals(GL11.GL_KEEP, CgStencilState.DISABLED.failOp());
    }

    @Test
    public void DISABLED_zfailOpIsKeep() {
        assertEquals(GL11.GL_KEEP, CgStencilState.DISABLED.zfailOp());
    }
}
