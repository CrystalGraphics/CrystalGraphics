package com.crystalgraphics.render.stage;

import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.scene.CgScene;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CgRenderStageTest {

    @Test
    public void aStageIsDefinedOnceAndFoundById() {
        CgRenderStage stage = CgStageHost.define("cgtest:once");
        assertSame(stage, CgRenderStage.byId("cgtest:once"));
        assertSame(CgRenderStage.OPAQUE, CgRenderStage.byId("crystalgraphics:opaque"));
        try {
            CgStageHost.define("cgtest:once");
            fail("a second define of one id must throw");
        } catch (IllegalArgumentException expected) {
            // what lets two mods know they picked the same id
        }
    }

    @Test
    public void anIdNeedsANamespaceAndAPath() {
        for (String bad : new String[] {"nocolon", ":path", "namespace:", "a:b:c"}) {
            try {
                CgStageHost.define(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // malformed
            }
        }
    }

    @Test
    public void aHostCallbackTakesNoRenderer() {
        try {
            CgStageHost.WORLD_OPAQUE.register(frame -> { });
            fail("the host's world callback is the scene renderer's alone");
        } catch (IllegalStateException expected) {
            // a mod registers on the world stages the scene renderer runs inside it
        }
    }

    @Test
    public void aWorldStageReachesTheClientSceneUnlessItNamesOthers() {
        CgRenderStage stage = CgRenderStage.define("cgtest:scenes", CgRenderStage.Kind.WORLD);
        CgScene lab = new CgScene(), other = new CgScene();
        CgRenderStage.Registration game = stage.register(frame -> { });
        assertTrue(stage.hasRenderers(CgScene.client()));
        assertFalse("a mod's pass never reaches an editor's scene unasked", stage.hasRenderers(lab));

        CgRenderStage.Registration labs = stage.register(frame -> { }).scene(lab);
        assertTrue(stage.hasRenderers(lab));
        assertFalse(stage.hasRenderers(other));
        game.close();
        labs.close();

        stage.register(frame -> { }).allScenes();
        assertTrue(stage.hasRenderers(other));
    }

    @Test
    public void aGuiStageHasNoScenes() {
        CgRenderStage.Registration hud = CgRenderStage.HUD.register(frame -> { });
        try {
            hud.scene(new CgScene());
            fail("a GUI stage runs once a frame, over the host's interface");
        } catch (IllegalStateException expected) {
            // no views, so no scenes
        } finally {
            hud.close();
        }
    }

    @Test
    public void closingARegistrationUnregistersIt() {
        CgRenderStage stage = CgStageHost.define("cgtest:registration");
        CgRenderStage.Registration first = stage.register(frame -> { });
        CgRenderStage.Registration second = stage.register(5, frame -> { });
        first.close();
        assertTrue(stage.hasRenderers());
        second.close();
        assertFalse(stage.hasRenderers());
    }
}
