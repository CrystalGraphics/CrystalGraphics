package com.crystalgraphics.frame;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.job.CgJobLoop;

import static org.junit.Assert.assertEquals;

/** The boundary: drains before it publishes, functions by name, a throwing one disabled. */
public class CgFrameBoundaryTest {

    private final List<CgFrameBoundary.Function> added = new ArrayList<>();
    private final List<String> ran = new ArrayList<>();

    @Before
    public void assets() {
        CgVfxAssets.install(new CgVfxAssets(new CgAssetRegistry(), CgJobLoop.create("boundary-test")));
    }

    @After
    public void close() {
        for (CgFrameBoundary.Function f : added) f.close();
    }

    private void add(CgFrameBoundary.Phase phase, String name, Runnable body) {
        added.add(CgFrameBoundary.add(phase, name, body));
    }

    @Test
    public void drainsRunBeforePublishesInNameOrder() {
        add(CgFrameBoundary.Phase.PUBLISH, "test:a-publish", () -> ran.add("a-publish"));
        add(CgFrameBoundary.Phase.DRAIN, "test:b-drain", () -> ran.add("b-drain"));
        add(CgFrameBoundary.Phase.DRAIN, "test:a-drain", () -> ran.add("a-drain"));
        CgFrameBoundary.run();
        assertEquals("[a-drain, b-drain, a-publish]", ran.toString());
    }

    @Test
    public void aThrowingFunctionIsDisabledAndTheRestRunOn() {
        add(CgFrameBoundary.Phase.DRAIN, "test:boom", () -> {
            ran.add("boom");
            throw new IllegalStateException("boom");
        });
        add(CgFrameBoundary.Phase.DRAIN, "test:next", () -> ran.add("next"));
        CgFrameBoundary.run();
        CgFrameBoundary.run();
        assertEquals("[boom, next, next]", ran.toString());
    }

    @Test
    public void aServerTickRunsTheBoundaryOnlyWhereNoHostFrameDoes() {
        add(CgFrameBoundary.Phase.PUBLISH, "test:tick", () -> ran.add("tick"));
        CgFrameBoundary.unframed();
        CgFrameBoundary.serverTick();                  // a dedicated server
        CgFrameBoundary.run();                         // a client's frame
        CgFrameBoundary.serverTick();                  // its integrated server: skipped
        assertEquals("[tick, tick]", ran.toString());
    }
}
