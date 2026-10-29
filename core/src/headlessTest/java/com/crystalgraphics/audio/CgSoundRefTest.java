package com.crystalgraphics.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.BeforeClass;
import org.junit.Test;

/** A sound as an asset names it: a host event, a file with settings, a game's cue through its kit's table, a pick. */
public class CgSoundRefTest {

    @BeforeClass
    public static void aKitTable() throws IOException {
        Path kit = Files.createTempDirectory("testgame_kit");
        Files.write(kit.resolve("sounds.yaml"), """
                _v: 1
                namespace: testgame
                sounds:
                  S_battle_punch_sw: { path: testgame_kit:sounds/Sound/Se/S_battle_punch_sw.ogg, volume: 0.8 }
                  V_C001A/V_PL_SKL_01: { path: testgame_kit:sounds/Sound/Voice/V_C001A/V_C001A_SKL_01.ogg }
                """.getBytes(StandardCharsets.UTF_8));
        Files.write(kit.resolve("cast.yaml"), """
                Goku:
                  voice: V_C001A
                """.getBytes(StandardCharsets.UTF_8));
        CgKitSounds.registerKit(kit);
    }

    /** A game's cue resolves through its kit's table to the file in the kit. */
    @Test
    public void aCueResolvesThroughItsKitsTable() {
        CgSoundRef punch = CgSoundRef.of("testgame:sound/S_battle_punch_sw", "test:hit");
        assertEquals(CgSoundRef.Kind.CUE, punch.kind());
        assertEquals(0.8f, punch.volume(), 0);
        assertNotNull(punch.file());
        assertTrue(punch.file().toString(), punch.file().endsWith(Path.of("sounds", "Sound", "Se", "S_battle_punch_sw.ogg")));
    }

    /** A cue the table lacks fails at load, naming the asset and the cue. */
    @Test
    public void aMissingCueFailsAtLoadNamingIt() {
        try {
            CgSoundRef.of("testgame:sound/S_nope", "test:hit");
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("test:hit") && e.getMessage().contains("S_nope"));
        }
        try {
            CgSoundRef.of("nogame:sound/S_x", "test:hit");
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("nogame"));
        }
    }

    /** A host event, a file, a file with its settings, and a speaker this engine does not read yet. */
    @Test
    public void eachFormCompiles() {
        assertEquals(CgSoundRef.Kind.EVENT, CgSoundRef.of("minecraft:entity.generic.explode", "t").kind());
        assertEquals(CgSoundRef.Kind.FILE, CgSoundRef.of("mymod:sounds/a.ogg", "t").kind());
        CgSoundRef loop = CgSoundRef.of(Map.of("path", "mymod:sounds/kame_loop.ogg", "stream", true, "volume", 0.5,
                "attenuation", Map.of("inner", 4, "outer", 48, "falloff", "natural_sound")), "t");
        assertTrue(loop.stream());
        assertEquals(1f, loop.attenuation().gain(4), 0);
        assertEquals("at -60 dB a natural sound reaches its outer", 48, loop.attenuation().maxDistance(), 1e-6);
        CgSoundRef line = CgSoundRef.of(Map.of("cue", "testgame:sound/V_PL_SKL_01", "speaker", "Goku"), "t");
        assertTrue(line.id(), line.id().endsWith("V_C001A_SKL_01.ogg"));
        try {
            CgSoundRef.of(Map.of("cue", "testgame:sound/V_PL_SKL_01", "speaker", "Vegeta"), "t");
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Vegeta"));
        }
    }

    /** A pick is rolled from the play's seed: the same seed, the same sound, and every one reached. */
    @Test
    public void aPickIsRolledFromTheSeed() {
        CgSoundRef pick = CgSoundRef.of(Map.of("pick", List.of("mymod:sounds/a.ogg", "mymod:sounds/b.ogg",
                "mymod:sounds/c.ogg")), "t");
        assertEquals(pick.pick(42).id(), pick.pick(42).id());
        Set<String> seen = new HashSet<>();
        for (long s = 0; s < 64; s++) seen.add(pick.pick(s).id());
        assertEquals(3, seen.size());
    }
}
