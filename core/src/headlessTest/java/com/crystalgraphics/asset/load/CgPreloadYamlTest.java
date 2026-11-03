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
        assertFalse(p.all());
        assertEquals(List.of("mymod:hit", "kakarot_kit:Blueprints/C001A/*"), p.declared().get(CgGameplayKinds.CUE));
        assertEquals(List.of("spzero_kit:SS/Skill/0000/*"), p.declared().get(CgSequenceKinds.SEQUENCE));
    }

    @Test
    public void allLoadsTheNamespacesOwnAndAKindThatIsNoneIsSkipped() throws IOException {
        CgAssetMounts.Preload p = read("all: true\nnot_a_kind: [x:y]\n");
        assertTrue(p.all());
        assertTrue(p.declared().isEmpty());
    }

    @Test
    public void noFileDeclaresNothing() {
        assertSame(CgAssetMounts.Preload.NONE, CgAssetMounts.Preload.read(folder.getRoot().toPath().resolve("preload.yaml")));
    }
}
