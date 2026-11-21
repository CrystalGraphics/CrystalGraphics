package com.crystalgraphics.net.object;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.load.CgAssetMounts;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.serialization.CgCodecs;

/** A net_type file: the type its Java declaration is, read by a graph by name, refused at load by name, declared at mount. */
public class CgNetTypeCompilerTest {

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgNetType compile(String id, String yaml) {
        return CgNetTypeCompiler.compile(CgAssetData.of(id, CgGameplayKinds.NET_TYPE,
                (Map<?, ?>) CgAssetYaml.read(yaml), null), (kind, ref) -> null);
    }

    @Test
    public void aFileDeclaresTheTypeItsJavaDeclarationIs() {
        CgNetType ki = compile("filetest:ki", """
                _v: 1
                fields:
                  current: { kind: float, fallback: 5.0, sync: owner, predicted: true }
                  charging: { kind: bool }
                  aura: { kind: string, fallback: calm, initial_only: true }
                  scratch: { kind: int, sync: local }
                events:
                  overcharged: { payload: float }
                  spent: {}
                entities: players
                """);
        assertSame("declared under its id", ki, CgNetType.named("filetest:ki"));
        assertTrue(ki.isPerHolder() && !ki.isLocal());
        assertEquals(CgNetField.Kind.FLOAT, ki.field("current").kind());
        assertEquals(5f, ki.field("current").fallbackNumber(), 0f);
        assertTrue(ki.field("scratch").isLocal());
        ki.event("overcharged");
        CgNetType java = CgNetType.define("filetest:ki").fields(
                        CgNetField.ofFloat("current").fallback(5f).ownerOnly().predicted(),
                        CgNetField.ofBool("charging"),
                        CgNetField.of("aura", CgCodecs.STRING).fallback("calm").initialOnly(),
                        CgNetField.ofInt("scratch").local())
                .events(CgNetEvent.of("overcharged", CgCodecs.FLOAT), CgNetEvent.of("spent"))
                .onEntities(type -> type.equals(CgNetEntities.PLAYER)).buildOnce();
        assertSame("the file and the Java of one shape are one type", ki, java);
        assertEquals(ki.field("current"), CgAnimGraph.EntityBinding.named("filetest:ki#current").field());
    }

    @Test
    public void aFileFailsAtLoadByName() {
        String[][] cases = {
                {"filetest:localsaved", """
                        fields: { hp: { kind: float, sync: local, persisted: true } }
                        entities: all
                        """, "fields.hp"},
                {"filetest:nowhere", """
                        fields: { hp: { kind: float } }
                        """, "entities"}};
        for (String[] c : cases) {
            CgAssetException e = assertThrows(c[2], CgAssetException.class, () -> compile(c[0], "_v: 1\n" + c[1]));
            assertTrue(e.getMessage(), e.getMessage().contains(c[0]) && e.getMessage().contains(c[2]));
        }
        CgNetType rage = compile("filetest:rage", """
                _v: 1
                fields: { level: { kind: int, persisted: true }, target: { kind: int } }
                entities: all
                """);
        assertTrue("a field persisted saves it alone", rage.persisted && rage.saves[0] && !rage.saves[1]);
        assertSame("a reload of the same file is the same type", rage, compile("filetest:rage", """
                _v: 1
                fields: { level: { kind: int, persisted: true }, target: { kind: int } }
                entities: all
                """));
        CgNetType all = compile("filetest:whole", """
                _v: 1
                fields: { level: { kind: int }, mood: { kind: float, sync: local } }
                persisted: true
                entities: all
                """);
        assertTrue("the type persisted saves every field but a local one", all.saves[0] && !all.saves[1]);
        compile("filetest:grows", """
                _v: 1
                fields: { a: { kind: int } }
                entities: all
                """);
        CgAssetException changed = assertThrows(CgAssetException.class, () -> compile("filetest:grows", """
                _v: 1
                fields: { a: { kind: int }, b: { kind: int } }
                entities: all
                """));
        assertTrue(changed.getMessage(), changed.getMessage().contains("filetest:grows") && changed.getMessage().contains("restart"));
    }

    @Test
    public void mountingDeclaresEveryFilesTypeOnThisSide() throws IOException {
        Path ns = Files.createTempDirectory("cg-net-types").resolve("assets").resolve("mounttest");
        Files.createDirectories(ns.resolve("net_types"));
        Files.write(ns.resolve("net_types/form.yaml"), """
                _v: 1
                fields: { level: { kind: int } }
                entities: [ minecraft:zombie ]
                """.getBytes(StandardCharsets.UTF_8));
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        registry.scanResources(ns.getParent().getParent().toFile());
        CgAssetMounts.declareNetTypes(new CgVfxAssets(registry));
        assertNotNull("declared before any entity or connection", CgNetType.named("mounttest:form"));
    }
}
