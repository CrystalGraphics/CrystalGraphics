package com.crystalgraphics.serialization;

import com.crystalgraphics.serialization.format.CgFormats;
import com.crystalgraphics.serialization.format.CgJsonOps;
import com.crystalgraphics.serialization.format.nbt.CgTestTags;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** An old save migrated before today's codec reads it. */
public class CgVersionedCodecTest {

    /** Version 3: {@code level} (was {@code Level} at 1), its abilities nested (flat {@code skills} until 2), power tenfold. */
    record Character(String name, int level, List<String> abilities) {
        static final CgCodec<Character> CODEC = CgCodecs.record(
                CgCodecs.field("name", CgCodecs.STRING, Character::name),
                CgCodecs.field("level", CgCodecs.INT, Character::level),
                CgCodecs.optionalField("abilities", CgCodecs.record(
                        CgCodecs.field("list", CgCodecs.listOf(CgCodecs.STRING), (List<String> l) -> l),
                        (List<String> l) -> l), Character::abilities, Collections.emptyList()),
                Character::new);
    }

    static final CgCodec<Character> SAVED = CgCodecs.versioned(3, Character.CODEC)
            .from(1, tree -> tree.rename("Level", "level"))
            .from(2, tree -> tree.move("skills", "abilities.list").map("level", v -> v.asInt() * 10));

    private static final Character GOKU = new Character("Goku", 90, Arrays.asList("kamehameha"));

    @Test
    public void everyOlderVersionLoadsAsToday() {
        assertEquals(GOKU, CgFormats.JSON.fromText(SAVED, "{\"name\":\"Goku\",\"Level\":9,\"skills\":[\"kamehameha\"]}"));
        assertEquals(GOKU, CgFormats.JSON.fromText(SAVED,
                "{\"_v\":2,\"name\":\"Goku\",\"level\":9,\"skills\":[\"kamehameha\"]}"));
        assertEquals(GOKU, CgFormats.JSON.fromText(SAVED,
                "{\"_v\":3,\"name\":\"Goku\",\"level\":90,\"abilities\":{\"list\":[\"kamehameha\"]}}"));
    }

    @Test
    public void aSaveIsWrittenAtTheCurrentVersionFirst() {
        Map<?, ?> tree = (Map<?, ?>) SAVED.encode(CgPlainOps.INSTANCE, GOKU);
        assertEquals("_v", tree.keySet().iterator().next());
        assertEquals(3, ((Number) tree.get("_v")).intValue());
        assertEquals(GOKU, SAVED.decode(CgPlainOps.INSTANCE, tree));
    }

    @Test
    public void aNewerSaveIsRefused() {
        CgCodecException refused = assertThrows(CgCodecException.class,
                () -> CgFormats.JSON.fromText(SAVED, "{\"_v\":4,\"name\":\"Goku\",\"level\":90}"));
        assertTrue(refused.getMessage(), refused.getMessage().contains("version 4") && refused.getMessage().contains("3"));
    }

    @Test
    public void theStepsRunInEveryFormat() {
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("name", "Goku");
        plain.put("Level", 9);
        plain.put("skills", Arrays.asList("kamehameha"));
        assertEquals(GOKU, SAVED.decode(CgPlainOps.INSTANCE, plain));
        assertEquals(Arrays.asList("name", "Level", "skills"), Arrays.asList(plain.keySet().toArray()));   // input untouched

        assertEquals(GOKU, SAVED.decode(CgJsonOps.INSTANCE,
                CgJsonOps.fromText("{\"name\":\"Goku\",\"Level\":9,\"skills\":[\"kamehameha\"]}")));

        try (CgTestTags tags = CgTestTags.install()) {
            Object old = CgTestTags.parse("{name:\"Goku\",Level:9,skills:[\"kamehameha\"]}");
            assertEquals(GOKU, CgFormats.fromNbt(SAVED, old));
            assertEquals(GOKU, CgFormats.fromNbt(SAVED, CgFormats.toNbt(SAVED, GOKU)));
        }
    }

    @Test
    public void aStepOnAMissingPathDoesNothingAndAStepIsDeclaredOnce() {
        assertEquals(new Character("Gohan", 10, Collections.emptyList()),
                CgFormats.JSON.fromText(SAVED, "{\"_v\":2,\"name\":\"Gohan\",\"level\":1}"));
        assertThrows(IllegalArgumentException.class, () -> CgCodecs.versioned(2, Character.CODEC)
                .from(1, tree -> { }).from(1, tree -> { }));
        assertThrows(IllegalArgumentException.class, () -> CgCodecs.versioned(2, Character.CODEC).from(2, tree -> { }));
    }
}
