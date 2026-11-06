package com.crystalgraphics.storage.config;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.format.CgFiles;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A store's typed values go through the format the name's extension picks, on every store. */
public class CgConfigStorageTypedTest {

    record Shelf(boolean rows, int size) {
        static final CgCodec<Shelf> CODEC = CgCodecs.record(
                CgCodecs.comment("Rows or columns.").field("rows", CgCodecs.BOOL, Shelf::rows),
                CgCodecs.field("size", CgCodecs.INT, Shelf::size),
                Shelf::new);
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aValueRoundTripsInEveryFormatAndOnEveryStore() throws IOException {
        Shelf shelf = new Shelf(true, 7);
        CgConfigStorage[] stores = {new CgInMemoryConfigStorage(), new CgLocalConfigStorage(folder.newFolder().toPath()),
                new CgInMemoryConfigStorage().scoped("a")};
        for (CgConfigStorage store : stores) {
            for (String name : new String[]{"shelf.json", "shelf.jsonc", "shelf.toml", "shelf.yml", "shelf.bin"}) {
                store.write(name, Shelf.CODEC, shelf);
                assertEquals(name, shelf, store.read(name, Shelf.CODEC));
            }
        }
    }

    @Test
    public void absenceIsNull() throws IOException {
        assertNull(new CgLocalConfigStorage(folder.newFolder().toPath()).read("none.toml", Shelf.CODEC));
    }

    @Test
    public void aPersonsCommentsSurviveTheStoreWritingAgain() {
        CgInMemoryConfigStorage store = new CgInMemoryConfigStorage();
        store.write("shelf.toml", Shelf.CODEC, new Shelf(true, 1));
        String edited = store.read("shelf.toml").replace("size = 1", "# mine\nsize = 1");
        store.write("shelf.toml", edited);

        store.write("shelf.toml", Shelf.CODEC, new Shelf(true, 2));

        String text = store.read("shelf.toml");
        assertTrue(text, text.contains("# mine"));
        assertTrue(text, text.contains("size = 2"));
    }

    @Test
    public void aFileThatIsNotItsFormatThrowsNamingIt() {
        CgInMemoryConfigStorage store = new CgInMemoryConfigStorage();
        store.write("shelf.toml", "this is = = not toml");

        CgCodecException thrown = assertThrows(CgCodecException.class, () -> store.read("shelf.toml", Shelf.CODEC));

        assertTrue(thrown.getMessage(), thrown.getMessage().contains("shelf.toml"));
    }

    @Test
    public void aWriteLeavesNoTempFileAndReplacesWhole() throws IOException {
        Path dir = folder.newFolder().toPath();
        CgLocalConfigStorage store = new CgLocalConfigStorage(dir);
        store.write("a.json", Shelf.CODEC, new Shelf(false, 1));
        store.write("a.json", Shelf.CODEC, new Shelf(false, 2));

        assertEquals(Collections.singletonList("a.json"), store.list());
        assertTrue(new String(Files.readAllBytes(dir.resolve("a.json")), StandardCharsets.UTF_8).contains("2"));
    }

    @Test
    public void aMapOfNamesToMillisSurvivesJson() throws IOException {
        CgCodec<Map<String, Long>> history = CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.LONG);
        Map<String, Long> times = new LinkedHashMap<>();
        times.put("mymod:runes/bake", 1234L);
        times.put("minecraft", 98_765_432_100L);
        Path file = folder.newFolder().toPath().resolve("history.json");

        CgFiles.JSON.write(file, history, times);

        assertEquals(times, CgFiles.JSON.read(file, history));
    }

    @Test
    public void rawTextStillWorks() {
        CgInMemoryConfigStorage store = new CgInMemoryConfigStorage();
        store.write("n.txt", "héllo");
        assertEquals("héllo", store.read("n.txt"));
    }
}
