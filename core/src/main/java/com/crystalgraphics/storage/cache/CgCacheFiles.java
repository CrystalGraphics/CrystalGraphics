package com.crystalgraphics.storage.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Getting a file into a cache directory without leaving a half-written one behind.
 *
 * <pre>{@code
 * Path file = dir.resolve("mappings.tsv");
 * if (!CgCacheFiles.isValid(file, digest)) CgCacheFiles.install(file, stream, digest);   // atomic, verified
 * }</pre>
 *
 * <ul>
 *   <li>Missing and invalid are the same: existence alone would let a truncated file persist.</li>
 *   <li>A digest is checked where one is known, never a size.</li>
 *   <li>Bytes go to {@code <name>.part} beside the target, are verified there, then moved, so nothing
 *       incomplete is visible under the real name.</li>
 *   <li>A failed check deletes the {@code .part}, so the next attempt starts clean.</li>
 * </ul>
 */
public final class CgCacheFiles {

    private CgCacheFiles() {
    }

    /**
     * Whether {@code file} is present and, if {@code md5} is given, matches it. A null digest accepts any non-empty file;
     * an empty one always fails, as an interrupted write leaves exactly that.
     */
    public static boolean isValid(Path file, String md5) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) == 0) return false;
            return md5 == null || matches(file, md5);
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Checks a digest that may name its algorithm: {@code gitblob:<sha1>}, {@code sha1:<hex>}, or a bare MD5.
     * The algorithm travels in the value so a pin and its algorithm cannot be separated.
     */
    private static boolean matches(Path file, String digest) throws IOException {
        int tag = digest.indexOf(':');
        if (tag < 0) return digest.equalsIgnoreCase(digestOf(file));
        String kind = digest.substring(0, tag);
        String expected = digest.substring(tag + 1);
        if (kind.equalsIgnoreCase("gitblob")) return expected.equalsIgnoreCase(gitBlobSha1(file));
        if (kind.equalsIgnoreCase("md5")) return expected.equalsIgnoreCase(digestOf(file));
        // Plain SHA-1, as upstream publishes beside a download
        if (kind.equalsIgnoreCase("sha1")) return expected.equalsIgnoreCase(sha1Of(file));
        // An unknown algorithm fails: a typo in a pin must not pass as unverified
        return false;
    }

    /**
     * Git's hash of a file's contents, {@code sha1("blob " + length + NUL + bytes)}: what GitHub's API reports for a blob.
     */
    public static String gitBlobSha1(Path file) throws IOException {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is required of every JVM", impossible);
        }
        sha1.update(("blob " + Files.size(file) + (char) 0).getBytes(StandardCharsets.US_ASCII));
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                sha1.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(40);
        for (byte value : sha1.digest()) hex.append(String.format("%02x", value));
        return hex.toString();
    }

    /**
     * Installs {@code contents} at {@code target} atomically after verifying {@code md5}. Consumes and closes the stream.
     * Returns false when the bytes did not match and nothing was installed.
     */
    public static boolean install(Path target, InputStream contents, String md5) throws IOException {
        return install(target, contents, md5, false);
    }

    /**
     * The in-progress copy of {@code target}: a sibling, so the final move can be atomic.
     */
    public static Path partOf(Path target) {
        return target.resolveSibling(target.getFileName() + ".part");
    }

    /**
     * How much of {@code target} an interrupted attempt left on disk, or zero.
     */
    public static long partialSize(Path target) {
        try {
            Path part = partOf(target);
            return Files.isRegularFile(part) ? Files.size(part) : 0L;
        } catch (IOException unreadable) {
            return 0L;
        }
    }

    /**
     * The same, appending to the {@code .part} already held, for a transfer the server agreed to resume. The digest is
     * still checked over the whole file, so a server that ignored the range fails the check and the {@code .part} goes.
     */
    public static boolean install(Path target, InputStream contents, String md5, boolean append)
            throws IOException {
        Path directory = target.getParent();
        if (directory != null) Files.createDirectories(directory);
        Path part = partOf(target);

        try {
            try (InputStream in = contents;
                 OutputStream out = append
                         ? Files.newOutputStream(part, StandardOpenOption.CREATE,
                                 StandardOpenOption.APPEND)
                         : Files.newOutputStream(part)) {
                byte[] buffer = new byte[8192];
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                }
            }
            if (!isValid(part, md5)) {
                // Deleted: a failed .part looks like a crashed one, and the next attempt may overwrite either
                Files.deleteIfExists(part);
                return false;
            }
            move(part, target);
            return true;
        } catch (IOException failed) {
            Files.deleteIfExists(part);
            throw failed;
        }
    }

    /**
     * Atomic where the filesystem allows it, a plain replace where it does not.
     */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Lowercase hex SHA-1 of a file's bytes, as Mojang's version manifest pins each download.
     */
    public static String sha1Of(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is required of every JVM", impossible);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(40);
        for (byte value : digest.digest()) hex.append(String.format("%02x", value));
        return hex.toString();
    }

    /** Lowercase hex MD5 of a file. */
    public static String digestOf(Path file) throws IOException {
        MessageDigest digest = md5();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(32);
        for (byte value : digest.digest()) hex.append(String.format("%02x", value));
        return hex.toString();
    }

    /**
     * MD5, for detecting a truncated or corrupted file. Not a security measure.
     */
    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM ships MD5
            throw new IllegalStateException("MD5 is unavailable", impossible);
        }
    }
}
