package com.crystalgraphics.settings;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CgSettingsTest {

    private static final AtomicInteger NAMES = new AtomicInteger();
    private static final long SECOND = 1_000_000_000L;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private enum Tier { LOW, HIGH }

    private CgSettings file;
    private CgSetting.Number density;
    private CgSetting.Toggle trails;
    private CgSetting.Choice<Tier> tier;
    private Path path;
    private long clock = 10 * SECOND;

    private void declare() throws Exception {
        file = CgSettings.file("test" + NAMES.incrementAndGet());
        path = folder.getRoot().toPath().resolve("config").resolve("settings.toml");
        file.at(path);
        density = file.number("fx", "density", "Density", "Share spawned.", 1f, 0f, 1f, 0.05f);
        trails = file.toggle("fx", "trails", "Trails", "Leave trails.", true);
        tier = file.choice("look", "tier", "Tier", "How much draws.", Tier.HIGH);
    }

    private void frame() {
        clock += 2 * SECOND;
        file.poll(clock);
    }

    private void edit(String text) throws Exception {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
    }

    @Test
    public void aMissingFileIsWrittenWithEveryDefault() throws Exception {
        declare();
        assertEquals(1f, density.get(), 0f);
        frame();
        Map<String, String> written = CgSettings.parse(Files.readAllLines(path));
        assertEquals("1.0", written.get("fx.density"));
        assertEquals("true", written.get("fx.trails"));
        assertEquals("\"high\"", written.get("look.tier"));
    }

    @Test
    public void aSetValueIsSavedAndReadBack() throws Exception {
        declare();
        density.set(0.25f);
        tier.set(Tier.LOW);
        frame();
        Map<String, String> written = CgSettings.parse(Files.readAllLines(path));
        assertEquals("0.25", written.get("fx.density"));
        assertEquals("\"low\"", written.get("look.tier"));
    }

    @Test
    public void anEditOnDiskIsPickedUpWhileRunning() throws Exception {
        declare();
        density.get();
        frame();
        edit("[fx]\ndensity = 0.4  # thinner\ntrails = false\n[look]\ntier = \"low\"\n");
        frame();
        assertEquals(0.4f, density.get(), 0f);
        assertFalse(trails.get());
        assertEquals(Tier.LOW, tier.get());
    }

    @Test
    public void anUnreadableValueKeepsItsDefaultAndIsRewritten() throws Exception {
        declare();
        Files.createDirectories(path.getParent());
        edit("[fx]\ndensity = lots\ntrails = false\n[look]\ntier = \"medium\"\n");
        assertEquals(1f, density.get(), 0f);
        assertFalse(trails.get());
        assertEquals(Tier.HIGH, tier.get());
        frame();
        Map<String, String> written = CgSettings.parse(Files.readAllLines(path));
        assertEquals("1.0", written.get("fx.density"));
        assertEquals("\"high\"", written.get("look.tier"));
    }

    @Test
    public void aValueOutsideItsRangeIsClamped() throws Exception {
        declare();
        Files.createDirectories(path.getParent());
        edit("[fx]\ndensity = 7\n");
        assertEquals(1f, density.get(), 0f);
        density.set(-2f);
        assertEquals(0f, density.get(), 0f);
    }

    @Test
    public void keysThisVersionDoesNotKnowAreKept() throws Exception {
        declare();
        Files.createDirectories(path.getParent());
        edit("[fx]\ndensity = 0.5\nbloom = \"soft\"\n[later]\nthing = 3\n");
        density.set(0.75f);
        frame();
        List<String> lines = Files.readAllLines(path);
        Map<String, String> written = CgSettings.parse(lines);
        assertEquals("0.75", written.get("fx.density"));
        assertEquals("\"soft\"", written.get("fx.bloom"));
        assertEquals("3", written.get("later.thing"));
        assertEquals("no table opened twice", 1, lines.stream().filter("[fx]"::equals).count());
    }

    @Test
    public void aSettingDeclaredAfterTheFirstReadTakesItsValueFromTheFile() throws Exception {
        declare();
        Files.createDirectories(path.getParent());
        edit("[fx]\ndensity = 0.5\nlate = 0.3\n");
        density.get();
        CgSetting.Number late = file.number("fx", "late", "Late", "Declared late.", 1f, 0f, 1f, 0f);
        assertEquals(0.3f, late.get(), 0f);
    }

    @Test
    public void commentsAndQuotedHashesParse() {
        Map<String, String> entries = CgSettings.parse(Arrays.asList(
                "# a comment", "", "top = 1", "[s]", "name = \"a # b\" # trailing", "  spaced   =   2  "));
        assertEquals("1", entries.get("top"));
        assertEquals("\"a # b\"", entries.get("s.name"));
        assertEquals("2", entries.get("s.spaced"));
        assertTrue(entries.size() == 3);
    }
}
