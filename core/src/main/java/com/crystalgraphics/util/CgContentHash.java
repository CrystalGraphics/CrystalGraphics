package com.crystalgraphics.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Content hashing — turning a blob of text into a short, stable, collision-resistant name for it.
 *
 * <p>The engine keys caches on <b>what a thing is</b> rather than on what it was called, in the places
 * where the name is either absent or unreliable. Generated shader source is the first such case: a node
 * graph compiles to text with no file behind it, so the text itself has to be the identity, and two
 * graphs that emit identical GLSL must resolve to one compiled program rather than two.</p>
 *
 * <h3>Why not {@code String.hashCode()}</h3>
 * <p>32 bits, over a corpus of <em>machine-generated</em> sources that differ from one another by a
 * single constant, is a collision waiting to happen — and the consequence of one here is not a slow
 * lookup, it is one graph silently bound to another graph's compiled program. That renders something
 * plausible and wrong, which is the worst failure mode available. SHA-256 truncated to 64 bits makes it
 * something nobody will meet.</p>
 */
public final class CgContentHash {

    /** Hex characters kept from the digest. 64 bits: short enough to read in a log line, long enough
     * that a collision is not a thing that happens. */
    private static final int HEX_CHARS = 16;

    private CgContentHash() {
    }

    /**
     * A stable {@value #HEX_CHARS}-character lowercase hex digest of {@code content}.
     *
     * <p>Stable across runs and across JVMs — unlike {@code String.hashCode()}, which is only promised
     * to be stable within one specification, and unlike identity hashes, which are not stable at all.
     * That matters wherever a hash is written down rather than merely held in a map.</p>
     *
     * @throws IllegalArgumentException if {@code content} is null
     */
    public static String of(String content) {
        if (content == null) throw new IllegalArgumentException("content must not be null");
        return hex(digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            // Every conformant JRE is required to ship SHA-256, so this cannot happen — and if it
            // somehow did, silently falling back to a weaker digest would be far worse than failing.
            throw new IllegalStateException("SHA-256 is unavailable in this JRE", e);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(HEX_CHARS);
        for (int i = 0; i < HEX_CHARS / 2; i++) {
            out.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
            out.append(Character.forDigit(digest[i] & 0xF, 16));
        }
        return out.toString();
    }
}
