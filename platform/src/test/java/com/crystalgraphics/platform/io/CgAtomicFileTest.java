package com.crystalgraphics.platform.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** A file is replaced whole and leaves nothing beside it. */
public class CgAtomicFileTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void makesItsFoldersAndReplacesTheOldContents() throws IOException {
        Path file = folder.getRoot().toPath().resolve("a").resolve("b").resolve("x.bin");

        CgAtomicFile.write(file, new byte[]{1, 2, 3});
        CgAtomicFile.write(file, new byte[]{9});

        assertArrayEquals(new byte[]{9}, Files.readAllBytes(file));
    }

    @Test
    public void leavesNoTempFile() throws IOException {
        Path dir = folder.newFolder().toPath();
        CgAtomicFile.write(dir.resolve("x.bin"), new byte[]{1});

        try (Stream<Path> files = Files.list(dir)) {
            assertEquals("[x.bin]", files.map(p -> p.getFileName().toString()).collect(Collectors.toList()).toString());
        }
    }
}
