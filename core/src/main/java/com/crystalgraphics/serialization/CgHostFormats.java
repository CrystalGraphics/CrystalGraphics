package com.crystalgraphics.serialization;

import com.crystalgraphics.platform.CgService;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

/**
 * The host's own tags, as the few operations {@link CgFormats} builds every codec format over: what
 * {@link CgFormats#toNbt} writes into an entity's or item's saved data, where only Minecraft's tags will do. Each era
 * spells them its own way; the rules of the format (typed numbers, booleans, text keys) are {@link CgFormats}'.
 *
 * <pre>{@code
 * // a host, once, on both sides
 * CgPlatform.provide(CgHostFormats.SERVICE, new HostFormatsModern());
 * }</pre>
 *
 * <ul>
 *   <li>Tags are the host's objects, passed as {@code Object}; a reader answers null, or false, for a tag of another
 *       kind.</li>
 *   <li>A list takes one kind of tag, as Minecraft's do before 1.21.5: a codec's list of maps or of one number type
 *       always is.</li>
 *   <li>{@link #NONE} has no tags (a test, the harness): {@link CgFormats#toNbt} throws there, naming the slot.</li>
 * </ul>
 */
public interface CgHostFormats {

    CgHostFormats NONE = new CgHostFormats() {
        @Override public boolean present() { return false; }
        @Override public Object compound() { throw absent(); }
        @Override public void put(Object compound, String key, Object value) { throw absent(); }
        @Override @Nullable public Collection<String> keys(Object tag) { throw absent(); }
        @Override @Nullable public Object get(Object compound, String key) { throw absent(); }
        @Override public Object list() { throw absent(); }
        @Override public void add(Object list, Object value) { throw absent(); }
        @Override @Nullable public List<?> elements(Object tag) { throw absent(); }
        @Override public Object string(String value) { throw absent(); }
        @Override @Nullable public String stringOf(Object tag) { throw absent(); }
        @Override public Object number(Number value) { throw absent(); }
        @Override @Nullable public Number numberOf(Object tag) { throw absent(); }
        @Override public Object bytes(byte[] value) { throw absent(); }
        @Override @Nullable public byte[] bytesOf(Object tag) { throw absent(); }

        private IllegalStateException absent() {
            return new IllegalStateException("no host tags here: crystalgraphics:host_formats has none (a test, the"
                    + " harness)");
        }
    };

    CgService<CgHostFormats> SERVICE = CgService.of("crystalgraphics:host_formats", NONE);

    /** Whether the host has tags; false for {@link #NONE}. */
    boolean present();

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
}
