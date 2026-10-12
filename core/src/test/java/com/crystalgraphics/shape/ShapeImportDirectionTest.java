package com.crystalgraphics.shape;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * The shape layer sits under rendering and VFX: {@code shape} names nothing else of CrystalGraphics but the platform
 * SPI (its host-shape conversions), and {@code collision} names no renderer, GL or VFX type, so a dedicated server and
 * any consumer can take them alone.
 */
public class ShapeImportDirectionTest {

    private static final Pattern IMPORT = Pattern.compile("^import (?:static )?(com\\.crystalgraphics\\.[\\w.]+);",
            Pattern.MULTILINE);

    @Test
    public void shapeImportsNothingElseOfTheEngine() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String hit : scan("shape", "com.crystalgraphics.shape.")) {
            if (!hit.contains(": com.crystalgraphics.platform.")) bad.add(hit);
        }
        assertTrue("shape must stay math over JOML and the platform SPI:\n  " + String.join("\n  ", bad),
                bad.isEmpty());
    }

    @Test
    public void collisionImportsNoRendererOrVfx() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String hit : scan("collision", "com.crystalgraphics.")) {
            if (hit.contains("com.crystalgraphics.render.") || hit.contains("com.crystalgraphics.vfx.")
                    || hit.contains("com.crystalgraphics.gl.")) {
                if (!hit.startsWith("collision/debug/")) bad.add(hit);
            }
        }
        assertTrue("collision runs on a dedicated server; drawing is collision.debug's:\n  " + String.join("\n  ", bad),
                bad.isEmpty());
    }

    /** Every engine import under {@code pkg} not starting with {@code allowed}, as "file: import". */
    private static List<String> scan(String pkg, String allowed) throws IOException {
        Path root = sources().resolve(pkg);
        List<String> hits = new ArrayList<>();
        if (!Files.isDirectory(root)) return hits;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                Matcher m = IMPORT.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                while (m.find()) {
                    if (!m.group(1).startsWith(allowed)) {
                        hits.add(pkg + "/" + root.relativize(file).toString().replace('\\', '/') + ": " + m.group(1));
                    }
                }
            }
        }
        return hits;
    }

    private static Path sources() {
        Path here = Paths.get("src/main/java/com/crystalgraphics");
        return Files.isDirectory(here) ? here : Paths.get("core/src/main/java/com/crystalgraphics");
    }
}
