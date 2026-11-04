package com.crystalgraphics.vfx.look;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.render.post.volume.CgPostSettings;

/** A kit's look: read and validated against its declaration, registered by namespace. */
public class CgVfxKitLookTest {

    /** Unreal 5's film chain and Gaussian layers, with an exposure of its own draws. */
    private static final String FILM = """
            _v: 1
            post:
              tonemapper: film
              film: { slope: 0.88, toe: 0.55, shoulder: 0.26, black_clip: 0, white_clip: 0.04 }
              bloom: 1.25
              bloom_threshold: -1
              bloom1: { size: 0.3, tint: [0.3466, 0.3466, 0.3466] }
              bloom6: { size: 64, tint: [0.0149, 0.0149, 0.0149] }
              vignette: 0.4
            effect_exposure: 0.2
            editor:
              note: "read from captures"
            """;

    /** Unreal 4's legacy curve, a threshold, what the engine leaves out listed. */
    private static final String LEGACY = """
            _v: 1
            post:
              tonemapper: legacy
              bloom_threshold: 1
              bloom1: { size: 1, tint: [0.5, 0.5, 0.5] }
            unsupported:
              - what: "two glow masks the bloom setup adds"
            """;

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    @Test
    public void aFilmLookCarriesItsPostAndItsExposure() throws IOException {
        CgVfxKitLook look = CgVfxKitLook.read(file(FILM));
        CgPostSettings post = look.settings();
        assertTrue(post.bloomsGaussian());
        assertEquals(1.25f, post.bloom(), 1e-6f);
        assertEquals(-1f, post.bloomThreshold(), 1e-6f);
        assertEquals(0.3f, post.bloomSize(1), 1e-6f);
        assertEquals(64f, post.bloomSize(6), 1e-6f);
        assertEquals(0.4f, post.vignette(), 1e-6f);
        assertEquals(0.2f, look.exposure(), 1e-6f);
    }

    @Test
    public void aLookListingWhatItLeavesOutStillReadsAndDefaultsItsExposure() throws IOException {
        CgVfxKitLook look = CgVfxKitLook.read(file(LEGACY));
        assertEquals(1f, look.exposure(), 0f);
        assertEquals(1f, look.settings().bloomThreshold(), 1e-6f);
    }

    @Test
    public void aLookWithNoPostIsRefusedNamingItsFile() throws IOException {
        Path file = file("_v: 1\neffect_exposure: 1\n");
        try {
            CgVfxKitLook.read(file);
            fail("a look with no post was read");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(file.toString()));
        }
    }

    @Test
    public void aKitRegistersItsLookAndLosesItWhenTheFileGoes() throws IOException {
        Path kit = Files.createTempDirectory("kitlook");
        Files.write(kit.resolve(CgVfxKitLook.FILE), FILM.getBytes(StandardCharsets.UTF_8));
        CgVfxKitLook.registerKit("lookkit", kit);
        CgVfxKitLook look = CgVfxKitLook.of("lookkit");
        assertNotNull(look);
        assertEquals(0.2f, look.exposure(), 1e-6f);
        assertNull("another kit has none", CgVfxKitLook.of("otherkit"));
        Files.delete(kit.resolve(CgVfxKitLook.FILE));
        CgVfxKitLook.registerKit("lookkit", kit);
        assertNull(CgVfxKitLook.of("lookkit"));
        assertFalse(Files.exists(kit.resolve(CgVfxKitLook.FILE)));
    }

    private static Path file(String text) throws IOException {
        Path file = Files.createTempFile("look", ".yaml");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
