package com.crystalgraphics.render.stage;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CgRenderStageTest {

    @Test
    public void aStageIsDefinedOnceAndFoundById() {
        CgRenderStage stage = CgRenderStage.define("cgtest:once");
        assertSame(stage, CgRenderStage.byId("cgtest:once"));
        assertTrue(CgRenderStage.all().contains(stage));
        assertTrue(CgRenderStage.all().contains(CgRenderStage.WORLD_OPAQUE));
        try {
            CgRenderStage.define("cgtest:once");
            fail("a second define of one id must throw");
        } catch (IllegalArgumentException expected) {
            // what lets two mods know they picked the same id
        }
    }

    @Test
    public void anIdNeedsANamespaceAndAPath() {
        for (String bad : new String[] {"nocolon", ":path", "namespace:", "a:b:c"}) {
            try {
                CgRenderStage.define(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // malformed
            }
        }
    }

    @Test
    public void closingARegistrationUnregistersIt() {
        CgRenderStage stage = CgRenderStage.define("cgtest:registration");
        CgRenderStage.Registration first = stage.register(frame -> { });
        CgRenderStage.Registration second = stage.register(5, frame -> { });
        first.close();
        assertTrue(stage.hasRenderers());
        second.close();
        assertFalse(stage.hasRenderers());
    }
}
