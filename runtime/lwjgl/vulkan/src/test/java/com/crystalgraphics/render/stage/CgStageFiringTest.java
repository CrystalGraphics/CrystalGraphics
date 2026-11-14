package com.crystalgraphics.render.stage;

import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import com.crystalgraphics.render.host.CgHostFrame;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewKey;
import com.crystalgraphics.vulkan.shader.ShadercGlslCompiler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * A world callback draws its host's view once a frame: a second firing for the same view (1.7.10's anaglyph pass
 * repeated) draws as nobody's, and what a view publishes lives its frame. On the null device, so a firing executes.
 */
public class CgStageFiringTest {

    private static final CgViewKey<String> NOTE = new CgViewKey<>("test:note");

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
    public void aSecondFiringForAViewInOneFrame_drawsAsUnkept_andANamedViewItsOwn() {
        CgHostFrame host = CgStageHost.frame(CgStageHost.define("test:views")).set(0f, 64, 64, 0);
        CgStageFiring firing = new CgStageFiring("test.views");
        firing.beginWorld(host.view(CgView.main()), true);
        assertSame(CgView.main(), firing.view());
        firing.execute();
        firing.beginWorld(host, true);   // the same view again this frame: its histories must not be written twice
        assertSame(CgView.unkept(), firing.view());
        firing.execute();
        CgView eye = CgView.create("test.eye");
        firing.beginWorld(host.view(eye), true);
        assertSame(eye, firing.view());
        firing.execute();
        host.view(CgView.main());
    }

    @Test
    public void whatAViewPublishesLivesItsFrame() {
        CgView view = CgView.create("test.publish");
        view.beginFrame(1, null);
        view.publish(NOTE, "mask");
        view.beginFrame(1, null);   // a later firing of the same frame reads it
        assertEquals("mask", view.read(NOTE));
        view.beginFrame(2, null);
        assertNull(view.read(NOTE));
    }
}
