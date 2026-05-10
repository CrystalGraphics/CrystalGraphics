package io.github.somehussar.crystalgraphics.api.material;

import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class CgMaterialTest {

    private static CgMaterial mat() {
        return CgMaterial.forTest(CgRenderState.DEFAULT, 2000);
    }

    // ── nextPass ──────────────────────────────────────────────────────────────

    @Test
    public void setNextPass_directCycle_throws() {
        CgMaterial a = mat();
        CgMaterial b = mat();
        a.setNextPass(b);
        try {
            b.setNextPass(a);
            fail("Expected IllegalStateException for cyclic chain");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Cyclic"));
        }
    }

    @Test
    public void setNextPass_selfCycle_throws() {
        CgMaterial a = mat();
        try {
            a.setNextPass(a);
            fail("Expected IllegalStateException for self-cycle");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Cyclic"));
        }
    }

    @Test
    public void setNextPass_longChainCycle_throws() {
        CgMaterial a = mat();
        CgMaterial b = mat();
        CgMaterial c = mat();
        a.setNextPass(b);
        b.setNextPass(c);
        try {
            c.setNextPass(a);
            fail("Expected IllegalStateException for 3-node cycle");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Cyclic"));
        }
    }

    @Test
    public void setNextPass_null_clearsChain() {
        CgMaterial a = mat();
        CgMaterial b = mat();
        a.setNextPass(b);
        a.setNextPass(null);
        assertNull(a.getNextPass());
    }

    @Test
    public void getNextPass_returnsSetValue() {
        CgMaterial a = mat();
        CgMaterial b = mat();
        a.setNextPass(b);
        assertSame(b, a.getNextPass());
    }

    // ── drawChain ─────────────────────────────────────────────────────────────

    @Test
    public void drawChain_singlePass_callsOnce() {
        CgMaterial a = mat();
        AtomicInteger count = new AtomicInteger(0);
        a.drawChain(count::incrementAndGet);
        assertEquals(1, count.get());
    }

    @Test
    public void drawChain_twoPass_callsTwice() {
        CgMaterial a = mat();
        CgMaterial b = mat();
        a.setNextPass(b);
        AtomicInteger count = new AtomicInteger(0);
        a.drawChain(count::incrementAndGet);
        assertEquals(2, count.get());
    }

    @Test
    public void drawChain_threePass_callsThrice() {
        CgMaterial a = mat();
        CgMaterial b = mat();
        CgMaterial c = mat();
        a.setNextPass(b);
        b.setNextPass(c);
        AtomicInteger count = new AtomicInteger(0);
        a.drawChain(count::incrementAndGet);
        assertEquals(3, count.get());
    }

    // ── getRenderQueue ────────────────────────────────────────────────────────

    @Test
    public void getRenderQueue_returnsConstructedValue() {
        CgMaterial m = CgMaterial.forTest(CgRenderState.DEFAULT, 3000);
        assertEquals(3000, m.getRenderQueue());
    }
}
