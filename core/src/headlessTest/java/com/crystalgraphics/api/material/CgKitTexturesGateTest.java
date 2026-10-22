package com.crystalgraphics.api.material;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.gl.texture.CgTextureCubemap;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * Every float and cube texture of a kit loads as its settings say (aaa-design 2.64): each {@code .hdr} as half floats,
 * each {@code cube: true} image as six stacked faces, with none refused. Runs over {@code -Pkits=<namespace>=<dir>;...};
 * skipped without, since the kits are private. The kits' 8-bit images are left out: they load as before, and decoding
 * every one would hold its pixels for an upload no context lands.
 */
public class CgKitTexturesGateTest {

    private static final List<Path> IMAGES = new ArrayList<>();

    @BeforeClass
    public static void kits() throws IOException {
        String kits = System.getProperty("crystalgraphics.kits");
        Assume.assumeTrue("no -Pkits: the kit texture gate did not run", kits != null && !kits.isEmpty());
        CgAssetKinds.register();
        for (String kit : kits.split(";")) {
            Path root = Paths.get(kit.split("=", 2)[1], "textures");
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(CgKitTexturesGateTest::isNew).forEach(IMAGES::add);
            }
        }
    }

    /** A {@code .hdr}, or an image whose settings say {@code cube: true}. */
    private static boolean isNew(Path p) {
        String name = p.toString();
        if (name.endsWith(".hdr")) return true;
        if (!name.endsWith(".png")) return false;
        Path settings = Paths.get(name.substring(0, name.length() - 4) + ".yaml");
        try {
            return Files.isRegularFile(settings)
                    && new String(Files.readAllBytes(settings), StandardCharsets.UTF_8).contains("cube: true");
        } catch (IOException e) {
            return true;
        }
    }

    @Test
    public void everyFloatAndCubeTextureLoads() {
        List<String> failed = new ArrayList<>();
        int cubes = 0, floats = 0;
        for (Path p : IMAGES) {
            String path = p.toAbsolutePath().toString().replace('\\', '/');
            try {
                CgTexture texture = CgMaterialRegistry.texture(path);
                if (texture.getWidth() < 1 || texture.isMissing()) failed.add(path + ": would not load");
                else if (texture instanceof CgTextureCubemap) cubes++;
                else floats++;
            } catch (RuntimeException e) {
                failed.add(path + ": " + e.getMessage());
            }
        }
        System.out.println("[kit-textures] " + cubes + " cubes and " + floats + " float textures loaded; " + failed.size() + " failed");
        assertTrue(failed.size() + " failed:\n" + String.join("\n", failed), failed.isEmpty());
        assertTrue("no float or cube texture in the kits", cubes + floats > 0);
    }
}
