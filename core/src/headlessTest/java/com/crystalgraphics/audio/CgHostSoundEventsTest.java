package com.crystalgraphics.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/** The host's sound events as Minecraft merges its packs' sounds.json. */
public class CgHostSoundEventsTest {

    private static final String VANILLA = """
            {
              "entity.generic.explode": { "sounds": ["random/explode1", "random/explode2"], "subtitle": "subtitles.entity.generic.explode" },
              "block.note": { "sounds": [ { "name": "note/harp", "volume": 0.5, "pitch": 2, "weight": 3 } ] }
            }""";

    /** A name becomes its namespace's file under sounds/, and a subtitle is kept. */
    @Test
    public void aSoundNameIsItsFileUnderSounds() {
        CgHostSoundEvents t = new CgHostSoundEvents().merge("minecraft", VANILLA);
        String path = t.pick("minecraft:entity.generic.explode").path();
        assertTrue(path, path.equals("minecraft:sounds/random/explode1.ogg") || path.equals("minecraft:sounds/random/explode2.ogg"));
        assertEquals("subtitles.entity.generic.explode", t.subtitle("minecraft:entity.generic.explode"));
        CgHostSoundEvents.Variant harp = t.pick("minecraft:block.note");
        assertEquals(0.5f, harp.volume(), 0);
        assertEquals(2f, harp.pitch(), 0);
    }

    /** A higher pack adds to an event, unless it replaces it. */
    @Test
    public void aHigherPackAddsOrReplaces() {
        CgHostSoundEvents t = new CgHostSoundEvents().merge("minecraft", VANILLA)
                .merge("minecraft", "{ \"entity.generic.explode\": { \"sounds\": [\"mypack/boom\"] } }");
        Map<String, Integer> seen = picks(t, "minecraft:entity.generic.explode", 3000);
        assertEquals("both packs' variants", 3, seen.size());
        t.merge("minecraft", "{ \"entity.generic.explode\": { \"replace\": true, \"sounds\": [\"mypack/only\"] } }");
        seen = picks(t, "minecraft:entity.generic.explode", 200);
        assertEquals(1, seen.size());
        assertTrue(seen.containsKey("minecraft:sounds/mypack/only.ogg"));
    }

    /** An event reference is followed, its weight sharing the chance with its siblings, its volume carried in. */
    @Test
    public void anEventReferenceIsFollowedByWeight() {
        CgHostSoundEvents t = new CgHostSoundEvents().merge("minecraft", """
                {
                  "a": { "sounds": [ "x/one", { "name": "b", "type": "event", "weight": 3, "volume": 0.5 } ] },
                  "b": { "sounds": [ "x/two", "x/three" ] }
                }""");
        Map<String, Integer> seen = picks(t, "minecraft:a", 40000);
        assertEquals("a quarter for one", 0.25, seen.get("minecraft:sounds/x/one.ogg") / 40000.0, 0.02);
        assertEquals("three eighths each through b", 0.375, seen.get("minecraft:sounds/x/two.ogg") / 40000.0, 0.02);
        for (int i = 0; i < 100; i++) {
            CgHostSoundEvents.Variant v = t.pick("minecraft:a");
            if (!v.path().endsWith("one.ogg")) assertEquals(0.5f, v.volume(), 0);
        }
    }

    /** An unknown event fails naming it. */
    @Test
    public void anUnknownEventFailsNamingIt() {
        CgHostSoundEvents t = new CgHostSoundEvents().merge("minecraft", VANILLA);
        assertFalse(t.has("minecraft:entity.generic.exploed"));
        try {
            t.pick("minecraft:entity.generic.exploed");
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("minecraft:entity.generic.exploed"));
        }
    }

    private static Map<String, Integer> picks(CgHostSoundEvents t, String event, int n) {
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < n; i++) seen.merge(t.pick(event).path(), 1, Integer::sum);
        return seen;
    }
}
