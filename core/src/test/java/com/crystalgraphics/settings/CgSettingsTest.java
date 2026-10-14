package com.crystalgraphics.settings;

import com.crystalgraphics.variable.CgVar;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
    }

    private String written() throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    public void aMissingFileIsWrittenWithEveryDefault() throws Exception {
        declare();
        assertEquals(1f, density.get(), 0f);
        frame();
        String text = written();
        assertTrue(text, text.startsWith("# " + file.name() + " settings: edited here"));
        assertTrue(text, text.contains("[fx]\n# Density: Share spawned.\n"));
        assertTrue(text, text.contains("density = 1.0\n"));
        assertTrue(text, text.contains("trails = true\n"));
        assertTrue(text, text.contains("[look]\n"));
        assertTrue(text, text.contains("tier = \"high\"\n"));
    }

    @Test
    public void aSetValueIsSavedAndReadBack() throws Exception {
        declare();
        density.set(0.25f);
        tier.set(Tier.LOW);
        frame();
        String text = written();
        assertTrue(text, text.contains("density = 0.25\n"));
        assertTrue(text, text.contains("tier = \"low\"\n"));
    }

    @Test
    public void anOverrideOutranksTheFileAndIsNeverSaved() throws Exception {
        declare();
        density.set(0.25f);
        assertEquals(file.name() + ".fx.density", density.var().id());
        density.var().set(CgVar.Source.CONSOLE, 0.5f);
        assertEquals(0.5f, density.get(), 0f);
        assertEquals(0.25f, density.saved(), 0f);
        frame();
        assertTrue(written().contains("density = 0.25\n"));
        density.var().clear(CgVar.Source.CONSOLE);
        assertEquals(0.25f, density.get(), 0f);
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
        edit("[fx]\ndensity = \"lots\"\ntrails = false\n[look]\ntier = \"medium\"\n");
        assertEquals(1f, density.get(), 0f);
        assertFalse(trails.get());
        assertEquals(Tier.HIGH, tier.get());
        frame();
        String text = written();
        assertTrue(text, text.contains("density = 1.0\n"));
        assertTrue(text, text.contains("trails = false\n"));
        assertTrue(text, text.contains("tier = \"high\"\n"));
    }

    @Test
    public void aFileThatIsNotTomlIsKeptAsideAndWrittenAgain() throws Exception {
        declare();
        edit("[fx]\ndensity = lots\n");
        assertEquals(1f, density.get(), 0f);
        Path kept = path.resolveSibling("settings.toml.bak");
        assertEquals("[fx]\ndensity = lots\n", new String(Files.readAllBytes(kept), StandardCharsets.UTF_8));
        frame();
        assertTrue(written().contains("density = 1.0\n"));
    }

    @Test
    public void aValueOutsideItsRangeIsClamped() throws Exception {
        declare();
        edit("[fx]\ndensity = 7\n");
        assertEquals(1f, density.get(), 0f);
        density.set(-2f);
        assertEquals(0f, density.get(), 0f);
    }

    @Test
    public void keysThisVersionDoesNotKnowAreKept() throws Exception {
        declare();
        edit("[fx]\ndensity = 0.5\nbloom = \"soft\"\n[later]\nthing = 3\n");
        density.set(0.75f);
        frame();
        String text = written();
        assertTrue(text, text.contains("density = 0.75\n"));
        assertTrue(text, text.contains("# Not used by this version, kept as written.\nbloom = \"soft\"\n"));
        assertTrue(text, text.contains("[later]\nthing = 3\n"));
        assertEquals("no table opened twice", text.indexOf("[fx]"), text.lastIndexOf("[fx]"));
    }

    @Test
    public void aSettingDeclaredAfterTheFirstReadTakesItsValueFromTheFile() throws Exception {
        declare();
        edit("[fx]\ndensity = 0.5\nlate = 0.3\n");
        density.get();
        CgSetting.Number late = file.number("fx", "late", "Late", "Declared late.", 1f, 0f, 1f, 0f);
        assertEquals(0.3f, late.get(), 0f);
    }

    @Test
    public void aPlayersCommentsSurviveASave() throws Exception {
        declare();
        edit("# my notes\n\n[fx]\n# mine\ndensity = 0.5  # half\n");
        density.set(0.6f);
        frame();
        String text = written();
        assertTrue(text, text.startsWith("# my notes\n\n[fx]\n"));
        assertTrue(text, text.contains("# mine\ndensity = 0.6  # half\n"));
        assertTrue(text, text.contains("# Trails: Leave trails.\n"));
    }
}
