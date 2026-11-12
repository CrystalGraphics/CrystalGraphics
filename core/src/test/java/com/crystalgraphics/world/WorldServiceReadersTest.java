package com.crystalgraphics.world;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * The wiring standard's second rule: each world SPI's slot ({@code platform.world}) is read in core by its facade
 * alone, or by the one class a single-reader SPI serves. Everything else asks the facade.
 */
public class WorldServiceReadersTest {

    private static final Pattern GET = Pattern.compile("CgPlatform\\.get\\((Cg\\w+)\\.(?:SERVICE|HOST)\\)");
    private static final Map<String, List<String>> READERS = new HashMap<>();

    static {
        // Static owners in platform: core asks their statics, never their slot
        READERS.put("CgLevels", Collections.emptyList());
        READERS.put("CgPlayers", Collections.emptyList());
        READERS.put("CgHostShapes", Collections.emptyList());
        READERS.put("CgProcessExit", Collections.emptyList());
        // Static owners in core: the owner alone, and a test's provider putting back what it replaced
        READERS.put("CgMirrorHost", Arrays.asList("collision/mirror/CgMirrorHost.java"));
        READERS.put("CgHostFormats", Arrays.asList("serialization/format/nbt/CgHostFormats.java",
                "serialization/format/nbt/CgTestTags.java"));
        // The older shape: one facade in core
        READERS.put("CgHostCamera", Arrays.asList("world/CgCameraControl.java"));
        READERS.put("CgModelSpace", Arrays.asList("world/CgEntityAttachments.java"));
        READERS.put("CgHostDebug", Arrays.asList("collision/debug/CgCollisionShow.java"));
        READERS.put("CgWorldStimulus", Arrays.asList("probe/CgWorldProbe.java", "probe/CgCollisionProbe.java",
                "net/object/CgNetProbe.java"));
    }

    @Test
    public void eachWorldSlotIsReadByItsFacadeAlone() throws IOException {
        Path root = sources();
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String name = root.relativize(file).toString().replace('\\', '/');
                Matcher m = GET.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                while (m.find()) {
                    List<String> allowed = READERS.get(m.group(1));
                    if (allowed != null && !allowed.contains(name)) bad.add(name + ": " + m.group(1) + " -> " + allowed);
                }
            }
        }
        assertTrue("ask the owner or facade, never the slot:\n  " + String.join("\n  ", bad), bad.isEmpty());
    }

    private static Path sources() {
        Path here = Paths.get("src/main/java/com/crystalgraphics");
        return Files.isDirectory(here) ? here : Paths.get("core/src/main/java/com/crystalgraphics");
    }
}
