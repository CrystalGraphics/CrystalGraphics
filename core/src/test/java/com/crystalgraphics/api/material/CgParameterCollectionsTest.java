package com.crystalgraphics.api.material;

import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** A collection asset declares its collection: its base's parameters, its overrides, and the same one on a reload. */
public class CgParameterCollectionsTest {

    private static final CgAssetCompiler.Dependencies NO_DEPS = (kind, id) -> null;

    private static CgAssetData asset(String id, Map<String, Object> tree, CgAssetData parent) {
        return CgAssetData.of(id, CgLookKinds.PARAMETERS, tree, parent);
    }

    @Test
    public void aChildTakesItsBasesParametersAndOverridesTheirDefaults() {
        CgAssetData base = asset("test:parameters/grade", Map.of("_v", 1, "namespace", "assetGrade",
                "scalars", List.of(Map.of("id", "p1", "name", "power", "default", 2.0)),
                "vectors", List.of(Map.of("id", "p2", "name", "tint", "default", List.of(1.0, 0.5, 0.25, 1.0)))), null);
        CgParameterCollection grade = CgParameterCollections.compile(base, NO_DEPS);
        CgAssetData child = asset("test:parameters/grade_hot", Map.of("_v", 1, "namespace", "assetGradeHot",
                "base", "test:parameters/grade", "overrides", Map.of("p1", 4.0)), null);
        CgParameterCollection hot = CgParameterCollections.compile(child,
                (kind, id) -> id.equals("test:parameters/grade") ? grade : null);
        String glsl = CgParameterCollection.glsl();
        assertEquals("assetGradeHot", hot.namespace());
        assertTrue(glsl, glsl.contains("CG_GLOBAL_assetGradeHot_power"));
        assertTrue(glsl, glsl.contains("CG_GLOBAL_assetGradeHot_tint"));
        hot.reset();
        assertEquals(List.of(4f, 1f), List.of(scalar(hot, "power"), vector(hot, "tint")[0]));
    }

    @Test
    public void aReloadAnswersTheSameCollectionAndAMovedLayoutThrows() {
        Map<String, Object> tree = Map.of("_v", 1, "namespace", "assetReload",
                "scalars", List.of(Map.of("id", "p1", "name", "a", "default", 0.0)));
        CgParameterCollection first = CgParameterCollections.compile(asset("test:parameters/reload", tree, null), NO_DEPS);
        assertSame(first, CgParameterCollections.compile(asset("test:parameters/reload", tree, null), NO_DEPS));
        Map<String, Object> moved = Map.of("_v", 1, "namespace", "assetReload",
                "scalars", List.of(Map.of("id", "p1", "name", "a", "default", 0.0), Map.of("id", "p2", "name", "b", "default", 0.0)));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CgParameterCollections.compile(asset("test:parameters/reload", moved, null), NO_DEPS));
        assertTrue(e.getMessage(), e.getMessage().contains("assetReload"));
    }

    @Test
    public void aKitsFolderDeclaresEveryCollectionItsBaseFirst() throws IOException {
        Path dir = Files.createTempDirectory("parameters");
        // "a_hot" sorts before its base "b_grade": declared on demand all the same.
        Files.writeString(dir.resolve("a_hot.yaml"), "_v: 1\nnamespace: folderHot\nbase: kit:parameters/b_grade\noverrides: { p1: 3 }\n");
        Files.writeString(dir.resolve("b_grade.yaml"), "_v: 1\nnamespace: folderGrade\nscalars: [ { id: p1, name: power, default: 1 } ]\n");
        CgParameterCollections.declareAll("kit", dir);
        String glsl = CgParameterCollection.glsl();
        assertTrue(glsl, glsl.contains("CG_GLOBAL_folderGrade_power"));
        assertTrue(glsl, glsl.contains("CG_GLOBAL_folderHot_power"));
        CgParameterCollections.declareAll("kit", dir.resolve("absent"));
    }

    private static float scalar(CgParameterCollection c, String name) {
        return c.scalarNamed(name).get();
    }

    private static float[] vector(CgParameterCollection c, String name) {
        return c.vectorNamed(name).get(new float[4]);
    }
}
