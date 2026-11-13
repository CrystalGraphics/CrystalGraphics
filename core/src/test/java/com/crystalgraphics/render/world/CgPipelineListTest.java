package com.crystalgraphics.render.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialManifest;
import com.crystalgraphics.api.material.CgMaterialPermutation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The pipeline list: a request ready once its entries are built, re-warmed before a toggle applies, never held for ever. */
public class CgPipelineListTest {

    private static final String SOURCE = String.join(System.lineSeparator(), "#domain surface",
            "Keywords { FOG_ON (\"Fog\")  NORMAL_MAP (\"Normal map\") }",
            "Properties { _Color (\"Color\", color) = (1, 1, 1, 1) }",
            "void surface(in v2f i, inout CgSurface s) { s.baseColor = _Color.rgb; }");
    private static String shader;
    private static final CgPipelineList.Settings HALF = new CgPipelineList.Settings(true, true);
    private static final CgPipelineList.Settings FULL = new CgPipelineList.Settings(true, false);

    /** Builds a material the second time it is asked under the same settings. */
    private static final class Fake implements CgPipelineList.Precacher {
        final Set<Object> seen = new HashSet<>();
        final List<CgPipelineList.Settings> asked = new ArrayList<>();
        boolean never;

        @Override
        public boolean precache(CgMaterial material, boolean half, boolean skinned, CgPipelineList.Settings settings) {
            asked.add(settings);
            return !never && !seen.add(Arrays.asList(material, settings));
        }
    }

    @BeforeClass
    public static void writeShader() throws IOException {
        Path file = Files.createTempFile("programs", ".shader");
        Files.write(file, SOURCE.getBytes(StandardCharsets.UTF_8));
        file.toFile().deleteOnExit();
        shader = file.toAbsolutePath().toString();
    }

    private static CgMaterialManifest manifest() {
        CgMaterialPermutation p = CgMaterialPermutation.of(shader, Arrays.asList("FOG_ON"), "opaque", false, true, null);
        return CgMaterialManifest.builder().add(p, null, false).add(p, null, false).add(p, "NORMAL_MAP", true).build();
    }

    private static CgPipelineList list(Fake fake) {
        return new CgPipelineList(fake, () -> HALF);
    }

    @Test
    public void aRequestIsReadyOnceWhatItNamedIsBuilt() {
        CgPipelineList pipelines = list(new Fake());
        CgPipelinePrecache.Request move = new CgPipelinePrecache.Request("move", null, List.of(p -> p.add(manifest())));
        pipelines.start(move);
        assertEquals(2, pipelines.size());
        assertFalse("its first poll starts them", pipelines.ready(move));
        assertTrue(pipelines.ready(move));
        pipelines.collect(p -> p.add(manifest()), null);
        assertEquals("a second manifest naming them shares the materials", 2, pipelines.size());
    }

    @Test
    public void aRequestCollectsNothingUntilItsWorkerPhaseIsDone() throws InterruptedException {
        CgPipelineList pipelines = list(new Fake());
        CountDownLatch release = new CountDownLatch(1);
        CgPipelinePrecache.Request move = new CgPipelinePrecache.Request("move", () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, List.of(p -> p.add(manifest()), p -> p.add(manifest())));
        pipelines.start(move);
        assertEquals("held for its worker phase", 0, pipelines.size());
        assertFalse(pipelines.ready(move));
        release.countDown();
        move.ahead.join();
        assertFalse("its steps collected, its entries started", pipelines.ready(move));
        assertTrue(move.collected());
        assertEquals(2, pipelines.size());
        assertTrue(pipelines.ready(move));
    }

    @Test
    public void aToggleWaitsForTheListWarmedUnderIt() {
        Fake fake = new Fake();
        CgPipelineList pipelines = list(fake);
        pipelines.collect(p -> p.add(manifest()), null);
        pipelines.frame(HALF, HALF);
        pipelines.frame(HALF, HALF);
        assertEquals(0, pipelines.pending());
        fake.asked.clear();
        assertFalse("the list re-warms before the toggle applies", pipelines.frame(FULL, HALF));
        assertEquals(Arrays.asList(FULL, FULL), fake.asked);
        assertTrue(pipelines.frame(FULL, HALF));
        assertTrue(pipelines.frame(FULL, FULL));
    }

    @Test
    public void aToggleNeverWaitsForEver() {
        Fake fake = new Fake();
        fake.never = true;
        CgPipelineList pipelines = list(fake);
        pipelines.collect(p -> p.add(manifest()), null);
        int frames = 1;
        while (!pipelines.frame(FULL, HALF)) frames++;
        assertEquals(CgPipelineList.MAX_WAIT_FRAMES + 1, frames);
    }

    @Test
    public void anEmptyListAppliesAtOnce() {
        assertTrue(list(new Fake()).frame(FULL, HALF));
    }
}
