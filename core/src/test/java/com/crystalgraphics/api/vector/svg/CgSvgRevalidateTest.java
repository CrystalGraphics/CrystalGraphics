package com.crystalgraphics.api.vector.svg;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/**
 * A resource reload re-parses only the icons whose file changed. The kept ones keep their IDENTITY, which is
 * what the path cache keys its rasters on, so an unchanged icon is neither parsed nor rasterised again.
 */
public class CgSvgRevalidateTest {

    private static final String SQUARE =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><rect width=\"8\" height=\"8\"/></svg>";
    private static final String WIDER =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><rect width=\"12\" height=\"8\"/></svg>";

    private Path file;

    @After
    public void cleanUp() throws IOException {
        CgSvg.invalidateCache();
        if (file != null) Files.deleteIfExists(file);
    }

    @Test
    public void anUnchangedIconKeepsItsDocumentAndAnEditedOneIsReplaced() throws IOException {
        file = Files.createTempFile("icon", ".svg");
        Files.write(file, SQUARE.getBytes(StandardCharsets.UTF_8));
        String path = file.toAbsolutePath().toString();

        CgSvg first = CgSvg.of(path);
        assertNotNull("the fixture loads", first);

        assertEquals("nothing changed, nothing dropped", 0, CgSvg.revalidate());
        assertSame("the same document, so its raster survives", first, CgSvg.of(path));

        Files.write(file, WIDER.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, CgSvg.revalidate());
        assertNotSame("the edit is picked up", first, CgSvg.of(path));
    }
}
