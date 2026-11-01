package com.crystalgraphics.api.mesh.geckolib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.joml.Quaternionf;
import org.junit.Assume;
import org.junit.Test;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgPose;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.asset.curve.CgRichCurve;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.format.CgFormats;
import com.crystalgraphics.shape.bounds.CgAABB;

/**
 * GeckoLib's models and animations as this engine reads them: each easing against GeckoLib 4.8.4's own maths (a
 * reference sampled from its {@code EasingType}), the keyed rotation against JOML's, the bake's cube, and, with
 * {@code -PgeckoAssets}, every model and animation of a mod's assets loaded and converted.
 */
public class CgGeckoModelTest {

    /** One bone at the origin with one 2x4x6 cube, box-UV'd on a 64x64 texture. */
    private static final String GEO = "{\"format_version\": \"1.12.0\", \"minecraft:geometry\": [{"
            + "\"description\": {\"identifier\": \"geometry.test\", \"texture_width\": 64, \"texture_height\": 64},"
            + "\"bones\": [{\"name\": \"b\", \"pivot\": [0, 0, 0],"
            + " \"cubes\": [{\"origin\": [0, 0, 0], \"size\": [2, 4, 6], \"uv\": [0, 0]}],"
            + " \"locators\": {\"hand\": [-8, 16, 0]}}]}]}";

    private static CgGeckoModel model() {
        return CgGeckoModel.read(GEO, "test.geo.json");
    }

    /** Bone b's scale x through two keys at 0 and 1 s (and a third at 2 s), the second eased as GeckoLib names it. */
    private static CgRichCurve scaleX(String easing, Double arg, boolean third) {
        String key = "{\"vector\": [7, 7, 7], \"easing\": \"" + easing + "\"" + (arg == null ? "" : ", \"easingArgs\": [" + arg + "]") + "}";
        String keys = "\"0\": {\"vector\": [2, 2, 2]}, \"1\": " + key + (third ? ", \"2\": {\"vector\": [11, 11, 11]}" : "");
        String json = "{\"animations\": {\"a\": {\"bones\": {\"b\": {\"scale\": {" + keys + "}}}}}}";
        CgAnimSequence s = model().animations(json, "test.animation.json").get(0).sequence();
        return s.channel(s.track("b"), 6);
    }

    @Test
    public void everyEasingMatchesGeckoLibsOwnMaths() throws IOException {
        String text;
        try (InputStream in = CgGeckoModelTest.class.getResourceAsStream("/geckolib/geckolib-4.8.4-easings.json")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<?, ?> ref = (Map<?, ?>) CgFormats.JSON.fromText(CgCodecs.PLAIN, text);
        int checked = 0;
        for (Object o : (List<?>) ref.get("cases")) {
            Map<?, ?> c = (Map<?, ?>) o;
            String easing = (String) c.get("easing");
            Double arg = c.get("arg") instanceof Number n ? n.doubleValue() : null;
            CgRichCurve curve = scaleX(easing, arg, easing.equals("catmullrom"));
            List<?> values = (List<?>) c.get("values");
            for (int i = 0; i < values.size(); i++) {
                float u = i / (float) (values.size() - 1);
                double want = ((Number) values.get(i)).doubleValue();
                assertEquals(easing + " (" + arg + ") at " + u, want, curve.eval(u), 2e-5 * Math.max(1, Math.abs(want)));
            }
            checked++;
        }
        assertEquals("cases in the reference", 62, checked);
    }

    @Test
    public void aKeyedRotationIsZYXAsGeckoLibComposesIt() {
        CgRichCurve zero = CgRichCurve.decode(Map.of("keys", List.of(List.of(0, 0)))), one = CgRichCurve.decode(Map.of("keys", List.of(List.of(0, 1))));
        CgRichCurve rx = CgRichCurve.decode(Map.of("keys", List.of(List.of(0, 0.4)))), ry = CgRichCurve.decode(Map.of("keys", List.of(List.of(0, -1.1))));
        CgRichCurve rz = CgRichCurve.decode(Map.of("keys", List.of(List.of(0, 2.3))));
        CgAnimSequence s = CgAnimSequence.keyed("r", 1f).track("b", zero, zero, zero, rx, ry, rz, one, one, one).build();
        CgSkeletalMesh mesh = model().mesh();
        CgPose pose = new CgPose(mesh.skeleton());
        s.sample(pose, 0.5f);
        Quaternionf got = pose.rotation(0, new Quaternionf());
        Quaternionf want = new Quaternionf().rotationZ(2.3f).rotateY(-1.1f).rotateX(0.4f);
        assertEquals(1f, Math.abs(got.dot(want)), 1e-6f);
    }

    @Test
    public void theCubeBakesWhereGeckoLibDrawsIt_andItsLocatorIsASocket() {
        CgSkeletalMesh mesh = model().mesh();
        assertEquals("six faces of two triangles", 36, mesh.mesh().indexCount());
        CgAABB b = new CgAABB();
        mesh.mesh().bounds(b);
        // origin (-(0 + 2), 0, 0) / 16, size (2, 4, 6) / 16
        assertEquals(-2 / 16f, b.minX(), 1e-6f);
        assertEquals(0f, b.maxX(), 1e-6f);
        assertEquals(4 / 16f, b.maxY(), 1e-6f);
        assertEquals(6 / 16f, b.maxZ(), 1e-6f);
        assertEquals("hand", mesh.socket("hand").name());
        assertEquals(8 / 16f, mesh.socket("hand").tx(), 1e-6f);
        assertEquals(1f, mesh.socket("hand").ty(), 1e-6f);
    }

    @Test
    public void anUnknownEasingOrAMolangValueFailsNamingWhere() {
        String[] bad = {
                "{\"animations\": {\"a\": {\"bones\": {\"b\": {\"scale\": {\"0\": [1, 1, 1], \"1\": {\"vector\": [2, 2, 2], \"easing\": \"easeInWobble\"}}}}}}}",
                "{\"animations\": {\"a\": {\"bones\": {\"b\": {\"position\": [\"linear\", \"linear\", \"linear\"]}}}}}"};
        String[] says = {"easeInWobble", "molang 'linear'"};
        for (int i = 0; i < bad.length; i++) {
            try {
                model().animations(bad[i], "bad.animation.json");
                fail("read " + bad[i]);
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("bad.animation.json: animation a: bone b") && e.getMessage().contains(says[i]));
            }
        }
    }

    @Test
    public void keysWrittenOutOfOrderPlayInTimeOrder() {
        String json = "{\"animations\": {\"a\": {\"bones\": {\"b\": {\"scale\": {\"0\": [1, 1, 1], \"1\": [3, 3, 3], \"0.5\": [5, 5, 5]}}}}}}";
        CgAnimSequence s = model().animations(json, "order.animation.json").get(0).sequence();
        CgRichCurve x = s.channel(s.track("b"), 6);
        assertEquals(5f, x.eval(0.5f), 0f);
        assertEquals(4f, x.eval(0.75f), 1e-6f);
    }

    /** {@code -PgeckoAssets=<dir>;...}: every model under each {@code geo/} bakes, every animation converts for each. */
    @Test
    public void everyModelAndAnimationOfTheGivenAssetsLoads() throws IOException {
        String roots = System.getProperty("crystalgraphics.geckoAssets");
        Assume.assumeTrue("no -PgeckoAssets", roots != null);
        List<Path> geos = new ArrayList<>(), animations = new ArrayList<>();
        for (String root : roots.split(";")) {
            try (Stream<Path> files = Files.walk(Paths.get(root))) {
                for (Path p : files.collect(Collectors.toList())) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".geo.json")) geos.add(p);
                    else if (name.endsWith(".animation.json")) animations.add(p);
                }
            }
        }
        assertTrue("models under " + roots, !geos.isEmpty());
        int clips = 0;
        for (Path geo : geos) {
            CgGeckoModel model = CgGeckoModel.read(Files.readString(geo), geo.toString());
            for (Path animation : animations) clips += model.animations(Files.readString(animation), animation.toString()).size();
        }
        System.out.println("geckolib: " + geos.size() + " models, " + animations.size() + " animation files, " + clips + " clips converted");
    }
}
