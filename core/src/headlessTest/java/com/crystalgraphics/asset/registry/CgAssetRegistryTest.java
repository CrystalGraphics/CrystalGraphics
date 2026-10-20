package com.crystalgraphics.asset.registry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.asset.tree.CgAssetReferences;
import com.crystalgraphics.object.CgType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A namespace scanned: ids, kinds, binaries, redirects, strays, the content index, references and referencers, the
 * cache and events.
 */
public class CgAssetRegistryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final CgType SYSTEM = CgParticleKinds.SYSTEM;

    private Path ns;

    @Before
    public void folder() throws IOException {
        ns = tmp.newFolder("assets", "mymod").toPath();
        write("vfx/systems/kamehameha/charge.yaml",
                "_v: 1\ndelay: 0.5\nemitters:\n  - { id: h1, name: core, emitter: mymod:kamehameha/core }\n");
        write("vfx/emitters/kamehameha/core.yaml", "_v: 1\n");
        write("cameras/shakes/hit.yaml", "_v: 1\n");
        write("vfx/systems/kamehameha/old_charge.yaml", "_v: 1\nredirect: mymod:kamehameha/charge\n");
        write("textures/fx/noise.png", "png");
        write("content.yaml", "\"0007f1f467f0e18d\": mymod:textures/fx/noise.png\n");
        write("import/charge.md", "what the importer wrote");
        write("vfx/systems/kamehameha/notes.txt", "a stray");
    }

    private void write(String rel, String text) throws IOException {
        Path p = ns.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    private CgAssetRegistry scanned() {
        CgAssetRegistry r = new CgAssetRegistry();
        r.scanResources(tmp.getRoot());
        return r;
    }

    @Test
    public void idsAndKindsComeFromTheListing() {
        CgAssetRegistry r = scanned();
        List<String> ids = new ArrayList<>();
        for (CgAssetEntry e : r.entries("mymod")) ids.add(e.id() + " " + (e.binary() ? "binary" : e.kind().id()));
        assertEquals("[mymod:hit crystalgraphics:shake, mymod:kamehameha/charge crystalgraphics:system, "
                + "mymod:kamehameha/core crystalgraphics:emitter, mymod:kamehameha/old_charge crystalgraphics:system, "
                + "mymod:textures/fx/noise.png binary]", ids.toString());
        assertEquals(1, r.strays().size());
        assertTrue(r.strays().get(0).endsWith("notes.txt"));
    }

    @Test
    public void aRedirectIsFollowed() {
        CgAssetRegistry r = scanned();
        assertEquals("mymod:kamehameha/charge", r.entry(SYSTEM, "mymod:kamehameha/old_charge").redirect());
        assertEquals("mymod:kamehameha/charge", r.resolve(SYSTEM, "mymod:kamehameha/old_charge").id());
        assertNull(r.entry(SYSTEM, "mymod:kamehameha/charge").redirect());
    }

    @Test
    public void theContentIndexNamesABinaryByHash() {
        assertEquals("mymod:textures/fx/noise.png", scanned().binaryByHash("mymod", "0007f1f467f0e18d"));
    }

    @Test
    public void anIdIsUniqueWithinItsKind() throws IOException {
        write("sequences/kamehameha/charge.yaml", "_v: 1\n");
        CgAssetRegistry r = scanned();
        assertEquals(CgSequenceKinds.SEQUENCE, r.entry(CgSequenceKinds.SEQUENCE, "mymod:kamehameha/charge").kind());
        assertEquals(SYSTEM, r.entry(SYSTEM, "mymod:kamehameha/charge").kind());
    }

    @Test
    public void aBuildersIdAndAFilesCollide() {
        CgAssetRegistry r = new CgAssetRegistry();
        r.registerCode("mymod:kamehameha/charge", CgParticleKinds.SYSTEM);
        try {
            r.scanResources(tmp.getRoot());
            fail("a file took a builder's id");
        } catch (CgAssetException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("charge.yaml"));
        }
    }

    @Test
    public void aScanAnnouncesEachEntryThenItself() {
        CgAssetRegistry r = new CgAssetRegistry();
        List<String> heard = new ArrayList<>();
        r.onEvent(e -> heard.add(e.type() + (e.entry() == null ? "" : " " + e.entry().id())));
        r.scanResources(tmp.getRoot());
        assertEquals(6, heard.size());
        assertEquals("SCANNED", heard.get(5));
    }

    @Test
    public void aBatchComesBeforeItsEventsOneByOne() {
        CgAssetRegistry r = new CgAssetRegistry();
        List<String> heard = new ArrayList<>();
        r.onEvents(batch -> heard.add("batch " + batch.size() + " " + batch.get(0).type()));
        r.onEvent(e -> heard.add(e.type().toString()));
        r.scanResources(tmp.getRoot());
        assertEquals("[batch 5 ADDED, ADDED, ADDED, ADDED, ADDED, ADDED, batch 1 SCANNED, SCANNED]", heard.toString());
    }

    @Test
    public void aFilesReferencesAndItsReferencers() {
        CgAssetRegistry r = scanned();
        CgAssetEntry charge = r.entry(SYSTEM, "mymod:kamehameha/charge");
        assertEquals("[crystalgraphics:emitter mymod:kamehameha/core HARD]", refs(charge));
        assertEquals("[mymod:kamehameha/charge]", ids(r.referencers(CgParticleKinds.EMITTER, "mymod:kamehameha/core")));
        assertEquals("a redirect is hard on where it points", "[mymod:kamehameha/old_charge]",
                ids(r.referencers(SYSTEM, "mymod:kamehameha/charge")));
        r.remove(SYSTEM, "mymod:kamehameha/charge");
        assertEquals("[]", ids(r.referencers(CgParticleKinds.EMITTER, "mymod:kamehameha/core")));
    }

    @Test
    public void aChangedFileIsReadAgain() throws IOException {
        CgAssetRegistry r = scanned();
        write("vfx/systems/kamehameha/charge.yaml", "_v: 1\n");
        Files.setLastModifiedTime(ns.resolve("vfx/systems/kamehameha/charge.yaml"), FileTime.fromMillis(1_000_000));
        r.changed(SYSTEM, "mymod:kamehameha/charge");
        assertEquals("[]", refs(r.entry(SYSTEM, "mymod:kamehameha/charge")));
        assertEquals("[]", ids(r.referencers(CgParticleKinds.EMITTER, "mymod:kamehameha/core")));
    }

    @Test
    public void theCacheIsReadWhileAFilesTimeHolds() throws IOException {
        Path cache = tmp.getRoot().toPath().resolve("cache/registry.bin");
        Path charge = ns.resolve("vfx/systems/kamehameha/charge.yaml");
        FileTime time = Files.getLastModifiedTime(charge);
        CgAssetRegistry first = new CgAssetRegistry();
        first.cache(cache);
        first.scanResources(tmp.getRoot());
        assertTrue(Files.isRegularFile(cache));

        write("vfx/systems/kamehameha/charge.yaml", "_v: 1\n");
        Files.setLastModifiedTime(charge, time);
        CgAssetRegistry second = new CgAssetRegistry();
        second.cache(cache);
        second.scanResources(tmp.getRoot());
        assertEquals("unchanged time: the cached record", "[crystalgraphics:emitter mymod:kamehameha/core HARD]",
                refs(second.entry(SYSTEM, "mymod:kamehameha/charge")));

        Files.setLastModifiedTime(charge, FileTime.fromMillis(time.toMillis() + 2000));
        CgAssetRegistry third = new CgAssetRegistry();
        third.cache(cache);
        third.scanResources(tmp.getRoot());
        assertEquals("a new time: read again", "[]", refs(third.entry(SYSTEM, "mymod:kamehameha/charge")));
    }

    private static String refs(CgAssetEntry e) {
        List<String> out = new ArrayList<>();
        for (CgAssetReferences.Reference r : e.references()) out.add(r.kind().id() + " " + r.id() + " " + r.strength());
        return out.toString();
    }

    private static String ids(List<CgAssetEntry> entries) {
        List<String> out = new ArrayList<>();
        for (CgAssetEntry e : entries) out.add(e.id());
        return out.toString();
    }

    @Test
    public void theHashSeesAnIdAddedOrRetypedButNotAScanRepeated() throws IOException {
        String first = scanned().hash("mymod");
        assertEquals(first, scanned().hash("mymod"));
        write("vfx/emitters/kamehameha/ring.yaml", "_v: 1\n");
        assertTrue(!first.equals(scanned().hash("mymod")));
    }
}
