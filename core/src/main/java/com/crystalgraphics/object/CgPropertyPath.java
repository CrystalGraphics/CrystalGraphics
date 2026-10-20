package com.crystalgraphics.object;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A node of an asset by name: the asset's id, then segments, each a map key, an element's id or a
 * plain list's index. Element ids keep a path pointed at its element when the list is reordered, so an undo entry, a
 * selection or a message survives a drag.
 *
 * <pre>{@code
 * CgPropertyPath p = CgPropertyPath.of("dragonforge:kamehameha/charge")
 *         .key("emitters").item("h1").key("emitter").key("particle_spawn").item("k3q9x2ab").key("inputs").key("sprite_size");
 * p.toString();   // dragonforge:kamehameha/charge /emitters[h1]/emitter/particle_spawn[k3q9x2ab]/inputs/sprite_size
 * CgPropertyPath.parse(p.toString()).equals(p);   // true
 * }</pre>
 *
 * <ul>
 *   <li>An {@link #item} is an element's id in an id list and an index in a plain list: the node it walks says which.</li>
 *   <li>Immutable: every builder call answers a new path.</li>
 * </ul>
 */
public final class CgPropertyPath {

    /** One step: a key, or an item in brackets. */
    public static final class Segment {
        private final String name;
        private final boolean item;

        private Segment(String name, boolean item) {
            this.name = name;
            this.item = item;
        }

        /** The key, the element's id, or the index as text. */
        public String name() {
            return name;
        }

        /** Whether it is an item of a list rather than a map key. */
        public boolean item() {
            return item;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Segment && ((Segment) o).item == item && ((Segment) o).name.equals(name);
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 2 + (item ? 1 : 0);
        }

        @Override
        public String toString() {
            return item ? "[" + name + "]" : "/" + name;
        }
    }

    private final String asset;
    private final List<Segment> segments;

    private CgPropertyPath(String asset, List<Segment> segments) {
        this.asset = asset;
        this.segments = Collections.unmodifiableList(segments);
    }

    /** The asset itself: its root. */
    public static CgPropertyPath of(String asset) {
        return new CgPropertyPath(asset, new ArrayList<Segment>());
    }

    /**
     * A path as {@link #toString()} writes it: the asset id, a space, then {@code /key} and {@code [item]} segments.
     *
     * @throws IllegalArgumentException if it is not one
     */
    public static CgPropertyPath parse(String text) {
        int at = text.indexOf(" /");
        CgPropertyPath p = of(at < 0 ? text : text.substring(0, at));
        if (at < 0) return p;
        String rest = text.substring(at + 1);
        int i = 0;
        while (i < rest.length()) {
            char c = rest.charAt(i);
            int end;
            if (c == '/') {
                end = i + 1;
                while (end < rest.length() && rest.charAt(end) != '/' && rest.charAt(end) != '[') end++;
                p = p.key(rest.substring(i + 1, end));
            } else if (c == '[') {
                end = rest.indexOf(']', i);
                if (end < 0) throw new IllegalArgumentException("an unclosed [ in " + text);
                p = p.item(rest.substring(i + 1, end));
                end++;
            } else {
                throw new IllegalArgumentException("not a path: " + text);
            }
            i = end;
        }
        return p;
    }

    /** This path, then the map key {@code key}. */
    public CgPropertyPath key(String key) {
        return with(new Segment(key, false));
    }

    /** This path, then the element {@code id} of an id list, or the index {@code id} of a plain list. */
    public CgPropertyPath item(String id) {
        return with(new Segment(id, true));
    }

    /** This path, then index {@code i} of a plain list. */
    public CgPropertyPath index(int i) {
        return item(Integer.toString(i));
    }

    /** The asset's id. */
    public String asset() {
        return asset;
    }

    /** The segments from the asset's root. */
    public List<Segment> segments() {
        return segments;
    }

    /** The path one segment up; the root's parent is the root. */
    public CgPropertyPath parent() {
        return segments.isEmpty() ? this : new CgPropertyPath(asset, new ArrayList<>(segments.subList(0, segments.size() - 1)));
    }

    /** The last segment, or null at the root. */
    public Segment last() {
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }

    private CgPropertyPath with(Segment s) {
        List<Segment> more = new ArrayList<>(segments);
        more.add(s);
        return new CgPropertyPath(asset, more);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgPropertyPath && ((CgPropertyPath) o).asset.equals(asset)
                && ((CgPropertyPath) o).segments.equals(segments);
    }

    @Override
    public int hashCode() {
        return asset.hashCode() * 31 + segments.hashCode();
    }

    @Override
    public String toString() {
        if (segments.isEmpty()) return asset;
        StringBuilder b = new StringBuilder(asset).append(' ');
        for (Segment s : segments) b.append(s);
        return b.toString();
    }
}
