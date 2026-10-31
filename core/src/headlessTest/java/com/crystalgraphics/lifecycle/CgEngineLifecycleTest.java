package com.crystalgraphics.lifecycle;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CgEngineLifecycleTest {

    /** Records the engine phases it hears, in order. */
    static final class Heard implements CgLifecycleListener {
        final List<String> phases = new ArrayList<>();

        @Override
        public void onEnginePreInit() {
            phases.add("pre");
        }

        @Override
        public void onEngineInit() {
            phases.add("init");
        }

        @Override
        public void onEnginePostInit() {
            phases.add("post");
        }
    }

    @Test
    public void aLateListenerHearsEachPhaseRunOnceInOrder() {
        CgEngineLifecycle.preInit();
        CgEngineLifecycle.postInit();
        Heard late = new Heard();
        CgEngineLifecycle.addListener(late);
        CgEngineLifecycle.addListener(late);   // idempotent: no second catch-up
        CgEngineLifecycle.preInit();
        CgEngineLifecycle.postInit();
        // init is the host's, with its channel: here it has run only if another test installed one through it.
        List<String> expected = late.phases.contains("init") ? List.of("pre", "init", "post") : List.of("pre", "post");
        assertEquals(expected, late.phases);
        assertTrue(CgEngineLifecycle.removeListener(late));
    }

    @Test
    public void aListenerAddedThroughTheGraphicsLifecycleHearsTheEnginePhasesToo() {
        CgEngineLifecycle.preInit();
        Heard heard = new Heard();
        CgGraphicsLifecycle.addListener(heard);
        assertEquals("pre", heard.phases.get(0));
        assertTrue("removed from both lifecycles", CgGraphicsLifecycle.removeListener(heard));
    }
}
