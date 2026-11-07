package com.crystalgraphics.serialization.format.nbt;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.format.CgFiles;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

/**
 * The host's own tags, as the few operations {@link CgFiles} builds every codec format over: what
 * {@link CgFiles#toNbt} writes into an entity's or item's saved data, where only Minecraft's tags will do. Each era
 * spells them its own way; the rules of the format (typed numbers, booleans, text keys) are {@link HostTagOps}'.
 *
 * <pre>{@code
 * CgDynamicOps<Object> nbt = CgHostFormats.ops();             // what CgFiles.nbt() answers
 *
 * // a host, once, on both sides
 * CgPlatform.provide(CgHostFormats.HOST, new HostFormatsModern());
 * // a test
 * try (CgTestTags tags = CgTestTags.install()) { ... }
 * }</pre>
 *
 * <ul>
 *   <li>Tags are the host's objects, passed as {@code Object}; a reader answers null, or false, for a tag of another
 *       kind.</li>
 *   <li>A list takes one kind of tag, as Minecraft's do before 1.21.5: a codec's list of maps or of one number type
 *       always is.</li>
 *   <li>Without a host (a test, the harness) {@link #ops()} throws, naming the slot.</li>
 * </ul>
 */
public final class CgHostFormats {

    /** What a host answers, both sides. One class per era: {@code HostFormatsModern}, {@code HostFormatsLegacy}, {@code HostFormats1710}. */
    public interface Host {

        /** A new, empty compound. */
        Object compound();

        void put(Object compound, String key, Object value);

        /** A compound's keys, in its own order; null for a tag that is not a compound. */
        @Nullable
        Collection<String> keys(Object tag);

        @Nullable
        Object get(Object compound, String key);

        /** A new, empty list. */
        Object list();

        void add(Object list, Object value);

        /** A list's tags, in order, to read once and drop: it may be the list itself. Null for a tag that is not a list. */
        @Nullable
        List<?> elements(Object tag);

        Object string(String value);

        /** A string tag's text; null for any other tag. */
        @Nullable
        String stringOf(Object tag);

        /**
         * A number's tag, of its own width: a {@code Byte} a byte tag, a {@code Short} a short's, an {@code Integer} an
         * int's, a {@code Long} a long's, a {@code Float} a float's, anything else a double's.
         */
        Object number(Number value);

        /** A numeric tag's value, boxed as its width; null for any other tag. */
        @Nullable
        Number numberOf(Object tag);

        Object bytes(byte[] value);

        /** A byte array tag's bytes; null for any other tag. */
        @Nullable
        byte[] bytesOf(Object tag);

        /** No tags: every operation throws. */
        Host NONE = new Host() {
            @Override public Object compound() { throw absent(); }
            @Override public void put(Object compound, String key, Object value) { throw absent(); }
            @Override public Collection<String> keys(Object tag) { throw absent(); }
            @Override public Object get(Object compound, String key) { throw absent(); }
            @Override public Object list() { throw absent(); }
            @Override public void add(Object list, Object value) { throw absent(); }
            @Override public List<?> elements(Object tag) { throw absent(); }
            @Override public Object string(String value) { throw absent(); }
            @Override public String stringOf(Object tag) { throw absent(); }
            @Override public Object number(Number value) { throw absent(); }
            @Override public Number numberOf(Object tag) { throw absent(); }
            @Override public Object bytes(byte[] value) { throw absent(); }
            @Override public byte[] bytesOf(Object tag) { throw absent(); }
        };
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:host_formats", Host.NONE);

    private CgHostFormats() {
    }

    /** Whether the host has tags: false in a test without {@link CgTestTags}, and in the harness. */
    public static boolean present() {
        return CgPlatform.get(HOST) != Host.NONE;
    }

    /**
     * The host's tags as a codec format.
     *
     * @throws IllegalStateException without a host
     */
    public static CgDynamicOps<Object> ops() {
        Host tags = CgPlatform.get(HOST);
        if (tags == Host.NONE) throw absent();
        return new HostTagOps(tags);
    }

    private static IllegalStateException absent() {
        return new IllegalStateException("no host tags here: crystalgraphics:host_formats has none (a test, the"
                + " harness)");
    }
}
