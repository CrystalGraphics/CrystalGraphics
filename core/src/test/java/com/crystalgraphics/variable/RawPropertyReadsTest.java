package com.crystalgraphics.variable;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * No source of CrystalGraphics reads a {@code crystalgraphics.*} property itself: each is a {@link CgVar}, so the
 * console, the settings file and {@code docs/DEBUG_FLAGS.md} see it.
 */
public class RawPropertyReadsTest {

    private static final Pattern RAW = Pattern.compile(
            "(?:System\\.getProperty|Boolean\\.getBoolean|Integer\\.getInteger|Long\\.getLong)\\(\\s*\"(crystalgraphics\\.[^\"]*)\"");
    /** Read before the engine loads: the 1.7.10 hotswap plugin and integration test. */
    private static final List<String> ALLOWED = Arrays.asList("crystalgraphics.hotswap.", "crystalgraphics.integration.");
    private static final Set<String> SKIPPED_DIRS = new HashSet<>(Arrays.asList("build", ".gradle", "run", "runs", "out"));

    @Test
    public void everyEngineFlagIsAVariable() throws IOException {
        Path repo = DebugFlagsDocTest.findDoc().getParent().getParent();
        List<String> raw = new ArrayList<>();
        for (String module : new String[]{"platform", "core", "runtime"}) {
            Path root = repo.resolve(module);
            if (!Files.isDirectory(root)) continue;
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return SKIPPED_DIRS.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toString().endsWith(".java")) scan(repo, file, raw);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        assertTrue("read through a CgVar instead (com.crystalgraphics.variable):\n  " + String.join("\n  ", raw),
                raw.isEmpty());
    }

    private static void scan(Path repo, Path file, List<String> raw) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Matcher m = RAW.matcher(text);
        while (m.find()) {
            String id = m.group(1);
            if (ALLOWED.stream().anyMatch(id::startsWith)) continue;
            raw.add(repo.relativize(file) + ": " + id);
        }
    }
}
