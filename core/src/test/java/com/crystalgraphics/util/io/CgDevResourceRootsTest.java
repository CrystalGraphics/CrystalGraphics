package com.crystalgraphics.util.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CgDevResourceRootsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File project(String name) throws IOException {
        File project = tmp.newFolder(name);
        assertTrue(new File(project, "src/main/resources/assets/" + name).mkdirs());
        assertTrue(new File(project, "build/resources/main").mkdirs());
        assertTrue(new File(project, "build/classes/java/main").mkdirs());
        return project;
    }

    private static String list(String... entries) {
        return String.join(File.pathSeparator, entries);
    }

    @Test
    public void modDevGradleModFoldersMapToTheirSources() throws IOException {
        File dragon = project("dragon"), rpg = project("rpgcore");
        Map<String, String> props = new HashMap<>();
        props.put("fml.modFolders", list(
                "dragonforge%%" + new File(dragon, "build/classes/java/main"),
                "dragonforge%%" + new File(dragon, "build/resources/main"),
                "rpgcore%%" + new File(rpg, "build/resources/main")));

        List<File> roots = CgDevResourceRoots.discover(props::get, k -> null);
        assertEquals(2, roots.size());
        assertEquals(new File(dragon, "src/main/resources").getCanonicalFile(), roots.get(0));
        assertEquals(new File(rpg, "src/main/resources").getCanonicalFile(), roots.get(1));
    }

    @Test
    public void legacyModClassesAndTheClasspathCountOnce() throws IOException {
        File dragon = project("dragon");
        String resources = new File(dragon, "build/resources/main").getPath();
        Map<String, String> env = Collections.singletonMap("MOD_CLASSES", "dragonforge%%" + resources);
        Map<String, String> props = Collections.singletonMap("java.class.path", list("lib/a.jar", resources));

        assertEquals(1, CgDevResourceRoots.discover(props::get, env::get).size());
    }

    @Test
    public void jarsAndFoldersWithoutAssetsFindNothing() throws IOException {
        File plain = tmp.newFolder("plain");
        assertTrue(new File(plain, "build/resources/main").mkdirs());
        assertTrue(new File(plain, "src/main/resources").mkdirs());   // no assets/
        Map<String, String> props = Collections.singletonMap("java.class.path",
                list("mods/crystalgraphics.jar", new File(plain, "build/resources/main").getPath()));

        assertTrue(CgDevResourceRoots.discover(props::get, k -> null).isEmpty());
    }

    @Test
    public void eclipseAndIntellijOutputs() {
        File project = new File("proj").getAbsoluteFile();
        assertEquals(new File(project, "src/main/resources"), CgDevResourceRoots.sourceOf(new File(project, "bin/main")));
        assertEquals(new File(project, "src/main/resources"),
                CgDevResourceRoots.sourceOf(new File(project, "out/production/resources")));
        assertEquals(new File(project, "src/test/resources"),
                CgDevResourceRoots.sourceOf(new File(project, "build/resources/test")));
    }

    @Test
    public void anIncludedBuildsJarMapsToItsProject() {
        File rpg = new File("RPG-Core").getAbsoluteFile();
        assertEquals(new File(rpg, "src/main/resources"), CgDevResourceRoots.sourceOf(new File(rpg, "build/devlibs/rpgcore-0.0.1.jar")));
        assertEquals(new File(rpg, "src/main/resources"), CgDevResourceRoots.sourceOf(new File(rpg, "build/libs/rpgcore.jar")));
        assertEquals(null, CgDevResourceRoots.sourceOf(new File(".gradle/caches/transforms/abc/transformed/crystalgraphics.jar")));
    }
}
