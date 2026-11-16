package com.crystalgraphics.net.object;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.format.CgFiles;

/**
 * How the layer's save files are kept on disk: JSONC, so a person can open one in any editor, comment it and fix it,
 * their comments kept across our writes. Each write leaves the file it replaces as {@code .old}, as Minecraft keeps
 * {@code level.dat_old}; a file that no longer parses is moved aside as {@code .unreadable} rather than written over.
 */
final class SaveFiles {

    static final String EXTENSION = ".jsonc";

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private SaveFiles() {
    }

    /** {@code file}'s value; null when there is none, or it does not parse (then moved aside, and said). */
    @Nullable
    static <V> V read(Path file, CgCodec<V> codec) {
        try {
            return CgFiles.JSONC.read(file, codec);
        } catch (IOException | RuntimeException failed) {
            Path aside = file.resolveSibling(file.getFileName() + ".unreadable");
            LOGGER.error("[cg-net] {} does not read; moved to {}, fix it and move it back while the server is down",
                    file, aside, failed);
            try {
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // a later write still replaces it; keeping the broken one from being written over is what this guards
            }
            return null;
        }
    }

    /** Writes {@code value} over {@code file}, the last one kept as {@code .old}. False, said, where it could not. */
    static <V> boolean write(Path file, CgCodec<V> codec, V value) {
        try {
            if (Files.isRegularFile(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".old"), StandardCopyOption.REPLACE_EXISTING);
            }
            CgFiles.JSONC.write(file, codec, value);
            return true;
        } catch (IOException | RuntimeException failed) {
            // a value JSON cannot hold (NaN, an infinity) lands here too, naming its field
            LOGGER.error("[cg-net] could not write {}; its changes stay pending", file, failed);
            return false;
        }
    }

    /** {@code file} kept as {@code .unreadable} beside it, for a person to fix: one of its values did not decode. */
    static void keepCopy(Path file) {
        try {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".unreadable"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // the log already names the value that failed
        }
    }
}
