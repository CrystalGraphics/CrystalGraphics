package com.crystalgraphics.asset.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A namespace's {@code preload.yaml}: what its gameplay plays, loaded behind the loading screen (A14). */
public class CgPreloadYamlTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private CgAssetMounts.Preload read(String yaml) throws IOException {
        Path file = folder.newFile("preload.yaml").toPath();
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return CgAssetMounts.Preload.read(file);
    }

    @Test
    public void kindsByShortOrFullNameEachTheirIds() throws IOException {
        CgAssetMounts.Preload p = read("cue: [mymod:hit, kakarot_kit:Blueprints/C001A/*]\n"
                + "crystalgraphics:sequence: spzero_kit:SS/Skill/0000/*\n");
        assertTrue("declaring adds; its own namespace still loads whole", p.all());
        assertEquals(List.of("mymod:hit", "kakarot_kit:Blueprints/C001A/*"), p.declared().get(CgGameplayKinds.CUE));
        assertEquals(List.of("spzero_kit:SS/Skill/0000/*"), p.declared().get(CgSequenceKinds.SEQUENCE));
    }

    @Test
    public void allFalseOptsOutAndAKindThatIsNoneIsSkipped() throws IOException {
        CgAssetMounts.Preload p = read("all: false\nnot_a_kind: [x:y]\n");
        assertFalse("a library: its cues alone", p.all());
        assertTrue(p.declared().isEmpty());
    }

    @Test
    public void aGlobNamesAMoveInEitherKitsLayout() {
        // Kakarot: one folder, one name stem for every piece and variant
        var kakarot = CgAssetMounts.glob("kakarot_kit:Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001*");
        assertTrue(kakarot.matcher("kakarot_kit:Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001_01").matches());
        assertTrue(kakarot.matcher("kakarot_kit:Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001_Cut").matches());
        assertFalse("another move", kakarot.matcher("kakarot_kit:Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL002_10").matches());
        assertFalse("* stays in its folder", kakarot.matcher("kakarot_kit:Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001/x").matches());
        // SPZero: the montage and its pieces in two folders, the move in the middle of each name
        var spzero = CgAssetMounts.glob("spzero_kit:SS/**_0000_00_actSPM2*");
        assertTrue(spzero.matcher("spzero_kit:SS/Characters/0000/Animation/00/AM_0000_00_actSPM2_ADMHD1P").matches());
        assertTrue(spzero.matcher("spzero_kit:SS/InGameComp/Equipment/0000/00/KP_0000_00_actSPM2_ADMSD").matches());
        assertFalse("another move", spzero.matcher("spzero_kit:SS/Characters/0000/Animation/00/AM_0000_00_actRVCR").matches());
        assertFalse("regex characters are literal", CgAssetMounts.glob("a.b*").matcher("axb").matches());
    }

    @Test
    public void noFileLoadsEverything() {
        CgAssetMounts.Preload p = CgAssetMounts.Preload.read(folder.getRoot().toPath().resolve("preload.yaml"));
        assertSame(CgAssetMounts.Preload.NONE, p);
        assertTrue("a mod with no file loads every asset, as before", p.all());
    }
}
