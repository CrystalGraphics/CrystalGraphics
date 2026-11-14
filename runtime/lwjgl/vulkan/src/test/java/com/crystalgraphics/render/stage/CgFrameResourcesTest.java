package com.crystalgraphics.render.stage;

import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.vulkan.shader.ShadercGlslCompiler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The stage firing's blackboard lives one firing: two firings of one stage in one frame (1.7.10's anaglyph) never see
 * each other's values; and each firing draws a view, a second one for the same view drawing as nobody's. On the null
 * device, so a firing executes for real.
 */
public class CgFrameResourcesTest {

    private static final CgFrameKey<String> NOTE = CgFrameKey.of("test:note", String.class);
    private static final CgFrameKey<Integer> COUNT = CgFrameKey.of("test:count", Integer.class);

    private static ShadercGlslCompiler compiler;

    @BeforeClass
    public static void install() {
        compiler = new ShadercGlslCompiler();
        new CgNullRHI(64, 64).install(compiler);
        CgCapabilities.clearCache();
        CgFallbackTextures.init();
    }

    @AfterClass
    public static void uninstall() {
        compiler.close();
    }

    @Test
    public void aValueLivesOneFiring() {
        CgRenderStage stage = CgRenderStage.define("test:blackboard");
        stage.host().set(0f, 64, 64, 0);
        CgStageFrame frame = new CgStageFrame(stage);

        frame.begin(stage.host());
        frame.resources().put(NOTE, "first");
        frame.resources().put(COUNT, 3);
        assertEquals("first", frame.resources().get(NOTE));
        assertEquals(Integer.valueOf(3), frame.resources().get(COUNT));
        frame.execute();

        // The second firing of the frame starts empty.
        frame.begin(stage.host());
        assertNull(frame.resources().get(NOTE));
        assertFalse(frame.resources().has(COUNT));
        frame.resources().put(COUNT, 4);
        frame.execute();
        assertNull(frame.resources().get(COUNT));
    }

    @Test
    public void aSecondFiringForAViewInOneFrame_drawsAsUnkept_andANamedViewItsOwn() {
        CgRenderStage stage = CgRenderStage.define("test:views");
        CgHostFrame host = stage.host().set(0f, 64, 64, 0);
        CgStageFrame frame = new CgStageFrame(stage);
        frame.begin(host);
        assertSame(CgView.main(), frame.view());
        frame.execute();
        frame.begin(host);   // the same view again this frame: its histories must not be written twice
        assertSame(CgView.unkept(), frame.view());
        frame.execute();
        CgView eye = CgView.create("test.eye");
        frame.begin(host.view(eye));
        assertSame(eye, frame.view());
        frame.execute();
        host.view(CgView.main());
    }

    @Test
    public void keysTakeDistinctSlots() {
        assertNotEquals(NOTE.index, COUNT.index);
        assertNotEquals(NOTE.index, CgFrameKeys.SUBJECT.index);
    }
}
