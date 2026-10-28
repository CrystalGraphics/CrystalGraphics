package com.crystalgraphics.vfx.camera;

import com.crystalgraphics.asset.camera.CgCameraShakeDefinition;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgCameraKinds;
import com.crystalgraphics.asset.load.CgAssetLoad;
import com.crystalgraphics.asset.load.CgAssetPipeline;
import com.crystalgraphics.asset.load.CgAssetState;
import com.crystalgraphics.asset.registry.CgAssetEntry;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.object.value.CgUnits;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CgCameraShakeTest {

    private static final float EPS = 1e-4f;

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgCameraShakeDefinition shake(Map<String, Object> pattern, String space, CgUnits units) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("pattern", pattern);
        tree.put("play", Map.of("space", space));
        return CgCameraShakeDefinition.compile(CgAssetData.of("test:shake", CgCameraKinds.SHAKE, tree, null), units);
    }

    private static Map<String, Object> wave(float amplitude, float frequency) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", "wave");
        p.put("location", Map.of("x", Map.of("amplitude", amplitude, "frequency", frequency, "initial_offset", "zero")));
        p.put("rotation", Map.of("yaw", Map.of("amplitude", amplitude * 2, "frequency", frequency, "initial_offset", "zero")));
        return p;
    }

    @Test
    public void aSampleHoldSamplesOnItsFirstFrameThenHoldsForItsInterval() {
        CgSampleHoldShakePattern hold = new CgSampleHoldShakePattern(0.1f).amplitude(CgShakePattern.X, 2f).amplitude(CgShakePattern.YAW, 1f).start(7);
        float[] a = new float[CgShakePattern.CHANNELS], b = new float[CgShakePattern.CHANNELS];
        hold.update(0.016f, a);
        assertTrue(a[CgShakePattern.X] != 0f && Math.abs(a[CgShakePattern.X]) <= 2f);
        assertTrue(Math.abs(a[CgShakePattern.YAW]) <= 1f);
        assertEquals("an axis with no amplitude stays still", 0f, a[CgShakePattern.PITCH], 0f);
        hold.update(0.05f, b);
        assertEquals("held within the interval", a[CgShakePattern.X], b[CgShakePattern.X], 0f);
        hold.update(0.05f, b);
        assertNotEquals("a new sample once the interval has passed", a[CgShakePattern.X], b[CgShakePattern.X], 0f);
        hold.start(7).update(0.016f, b);
        assertEquals("a seed repeats its samples", a[CgShakePattern.X], b[CgShakePattern.X], 0f);
    }

    @Test
    public void aCompositeAddsItsChildren() {
        Map<String, Object> composite = new LinkedHashMap<>();
        composite.put("kind", "composite");
        composite.put("children", List.of(wave(1f, 2f), wave(0.5f, 5f)));
        CgShakePattern sum = shake(composite, "camera_local", CgUnits.HOST).sample(3);
        CgShakePattern a = shake(wave(1f, 2f), "camera_local", CgUnits.HOST).sample(3);
        CgShakePattern b = shake(wave(0.5f, 5f), "camera_local", CgUnits.HOST).sample(3);
        float[] s = new float[CgShakePattern.CHANNELS], x = new float[CgShakePattern.CHANNELS], y = new float[CgShakePattern.CHANNELS];
        for (int frame = 0; frame < 5; frame++) {
            sum.update(0.03f, s);
            a.update(0.03f, x);
            b.update(0.03f, y);
            for (int c = 0; c < CgShakePattern.CHANNELS; c++) assertEquals("channel " + c, x[c] + y[c], s[c], EPS);
        }
        assertTrue("the waves moved", s[CgShakePattern.X] != 0f && s[CgShakePattern.YAW] != 0f);

        Map<String, Object> brief = wave(1f, 2f);
        brief.put("duration", 0.05);
        composite.put("children", List.of(brief, wave(0.5f, 5f)));
        CgShakePattern ended = shake(composite, "camera_local", CgUnits.HOST).sample(3);
        CgShakePattern rest = shake(wave(0.5f, 5f), "camera_local", CgUnits.HOST).sample(3);
        for (int frame = 0; frame < 5; frame++) {
            ended.update(0.03f, s);
            rest.update(0.03f, y);
        }
        assertEquals("a finished child adds nothing", y[CgShakePattern.X], s[CgShakePattern.X], EPS);
    }

    @Test
    public void aPatternNotPlayedFailsAtLoad() {
        Map<String, Object> sequence = new LinkedHashMap<>();
        sequence.put("kind", "sequence");
        RuntimeException e = assertThrows(RuntimeException.class, () -> shake(sequence, "world", CgUnits.HOST));
        assertTrue(e.getMessage(), e.getMessage().contains("sequence"));
        RuntimeException space = assertThrows(RuntimeException.class, () -> shake(wave(1f, 1f), "user_defined", CgUnits.HOST));
        assertTrue(space.getMessage(), space.getMessage().contains("user_defined"));
    }

    @Test
    public void aCameraLocalShakeMovesAlongTheCamerasAxes() {
        Vector3f forward = new Vector3f(0, 0, 1), right = new Vector3f(-1, 0, 0), up = new Vector3f(0, 1, 0);
        float[] result = {1f, 2f, 3f, 5f, 10f, 4f, 0f}, out = new float[6];
        CgShakePlay.toHost(result, false, CgShakeSource.UNREAL_AXES, forward, right, up, out);
        assertEquals(-2f, out[0], EPS);
        assertEquals(3f, out[1], EPS);
        assertEquals(1f, out[2], EPS);
        assertEquals("yaw turns right", 10f, out[3], EPS);
        assertEquals("Unreal's pitch is up, the host's down", -5f, out[4], EPS);
        assertEquals(4f, out[5], EPS);
    }

    @Test
    public void aWorldShakeTurnsTheWorldAsUnrealsPlaySpace() {
        float[] out = new float[6];
        // Looking along Unreal's +X: the world's axes are the camera's.
        Vector3f forward = new Vector3f(1, 0, 0), right = new Vector3f(0, 0, 1), up = new Vector3f(0, 1, 0);
        CgShakePlay.toHost(new float[] {1f, 2f, 3f, 5f, 10f, 0f, 0f}, true, CgShakeSource.UNREAL_AXES, forward, right, up, out);
        assertEquals("Unreal's Y is the host's z", 1f, out[0], EPS);
        assertEquals(3f, out[1], EPS);
        assertEquals(2f, out[2], EPS);
        assertEquals(10f, out[3], EPS);
        assertEquals(-5f, out[4], EPS);
        assertEquals(0f, out[5], EPS);
        // Looking along Unreal's +Y, a world pitch (about Y) turns about the camera's forward: a roll.
        forward.set(0, 0, 1);
        right.set(-1, 0, 0);
        CgShakePlay.toHost(new float[] {0f, 0f, 0f, 5f, 0f, 0f, 0f}, true, CgShakeSource.UNREAL_AXES, forward, right, up, out);
        assertEquals(0f, out[3], EPS);
        assertEquals(0f, out[4], EPS);
        assertEquals(5f, Math.abs(out[5]), EPS);
    }

    @Test
    public void anFovChangeScalesTheHostsVerticalFov() {
        Matrix4f square = new Matrix4f().perspective((float) Math.toRadians(90), 1f, 0.1f, 100f);
        assertEquals(1f, CgShakeRuntime.fovScale(square, 0f), 0f);
        assertEquals("square: horizontal and vertical alike", 100f / 90f, CgShakeRuntime.fovScale(square, 10f), EPS);
    }

    /** -Pkits=<ns>=<dir>;...: every kit shake compiles, and Kakarot's play in centimetres and degrees. */
    @Test
    public void everyKitShakeCompiles() throws InterruptedException {
        String kits = System.getProperty("crystalgraphics.kits");
        Assume.assumeTrue("no -Pkits", kits != null && !kits.isEmpty());
        CgCameraShakeDefinition.register();
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        for (String kit : kits.split(";")) {
            String[] nd = kit.split("=", 2);
            registry.scan(nd[0], Paths.get(nd[1]), CgAssetSource.DEV);
        }
        CgAssetPipeline pipeline = new CgAssetPipeline(registry, CgJobLoop.create("shakes"));
        List<CgAssetLoad> loads = new ArrayList<>();
        for (String ns : registry.namespaces()) {
            for (CgAssetEntry e : registry.entries(ns)) {
                if (e.kind() == CgCameraKinds.SHAKE && e.redirect() == null) loads.add(pipeline.request(e.kind(), e.id()));
            }
        }
        Assume.assumeFalse("no shake in the kits", loads.isEmpty());
        for (int i = 0; i < 60_000 && !pipeline.idle(); i++) {
            pipeline.drain();
            Thread.sleep(1);
        }
        pipeline.drain();
        float[] out = new float[CgShakePattern.CHANNELS];
        for (CgAssetLoad l : loads) {
            assertEquals(l + ": " + l.failure(), CgAssetState.COMPILED, l.state());
            CgCameraShakeDefinition d = (CgCameraShakeDefinition) l.definition();
            if (!d.id().startsWith("kakarot_kit:cameras/shakes/ECameraShake/01")) continue;
            assertTrue(d.world());
            d.sample(1).update(1 / 60f, out);
            assertTrue("1 cm", out[CgShakePattern.X] != 0f && Math.abs(out[CgShakePattern.X]) <= 0.01f);
            assertTrue("0.1 degrees", Math.abs(out[CgShakePattern.PITCH]) <= 0.1f + EPS);
            assertEquals("no roll", 0f, out[CgShakePattern.ROLL], 0f);
        }
        System.out.println("[shakes] " + loads.size() + " kit shakes compiled");
    }
}
