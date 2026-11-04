package com.crystalgraphics.tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Every gameplay tag of a process, interned: each is declared by what owns it, and looked up by name afterwards.
 * Mirrors Unreal's {@code UGameplayTagsManager}: registering {@code a.b.c} makes {@code a} and {@code a.b} too, and
 * names compare without case.
 *
 * <pre>{@code
 * // from an asset: the registry's scan declares a cue's `tag` and a rig's `tags`, before anything compiles
 * //   assets/mymod/cues/kamehameha/fire.yaml:  tag: mymod:cue.kamehameha.fire
 *
 * // from code, at init: a tag no asset owns, such as a gameplay state
 * CgGameplayTag charging = CgTagRegistry.global().register("mymod:state.charging");
 *
 * // anything that only names a tag: unknown fails by name
 * CgGameplayTag fire = CgTagRegistry.global().require("mymod:cue.kamehameha.fire");
 * }</pre>
 *
 * <p>There is no list of tags beside the assets: a tag no cue, rig or code declares does not exist.</p>
 *
 * <p>Easy to get wrong: the tags a client has differ from the server's when their mod sets differ, so indices never
 * cross the wire as {@link CgGameplayTag#index()}; {@link #table()} and {@link CgTagLink} agree them.</p>
 */
public final class CgTagRegistry {

    /** {@code namespace:segment.segment}: a lowercase namespace, segments of letters, digits, {@code _} and {@code -}. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+:[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");

    private static final CgTagRegistry GLOBAL = new CgTagRegistry();

    private final Map<String, CgGameplayTag> byKey = new ConcurrentHashMap<>();
    private final List<CgGameplayTag> all = new ArrayList<>();
    private CgTagTable table;

    /** The process's registry. */
    public static CgTagRegistry global() {
        return GLOBAL;
    }

    /** A registry of its own, for a test or a tool. */
    public CgTagRegistry() {
    }

    /**
     * Makes {@code name} and each of its ancestors, or returns the existing tag. Registering an ancestor later marks it
     * explicit.
     *
     * @throws CgTagException if {@code name} is not {@code namespace:a.b.c}
     */
    public synchronized CgGameplayTag register(String name) {
        validate(name);
        CgGameplayTag parent = null;
        int dot = -1;
        while (true) {
            int next = name.indexOf('.', dot + 1);
            String path = next < 0 ? name : name.substring(0, next);
            boolean last = next < 0;
            String key = path.toLowerCase(Locale.ROOT);
            CgGameplayTag tag = byKey.get(key);
            if (tag == null) {
                tag = new CgGameplayTag(this, path, all.size(), parent, last);
                all.add(tag);
                byKey.put(key, tag);
                table = null;
            } else if (last && !tag.explicit()) {
                tag.explicit(true);
            }
            if (last) return tag;
            parent = tag;
            dot = next;
        }
    }

    /** The tag called {@code name}, or null. */
    @Nullable
    public CgGameplayTag find(String name) {
        return byKey.get(name.toLowerCase(Locale.ROOT));
    }

    /** The tag called {@code name}; unknown fails at load by name. */
    public CgGameplayTag require(String name) {
        CgGameplayTag tag = find(name);
        if (tag == null) throw new CgTagException("unknown gameplay tag '" + name + "'");
        return tag;
    }

    /** How many tags, ancestors included. */
    public synchronized int size() {
        return all.size();
    }

    /** Every tag in registration order: a copy. */
    public synchronized List<CgGameplayTag> all() {
        return Collections.unmodifiableList(new ArrayList<>(all));
    }

    /**
     * The index table of this registry as it stands: every tag, sorted by name as Unreal's
     * {@code ConstructNetIndex} sorts them, so two registries with the same tags agree. Rebuilt after a registration.
     */
    public synchronized CgTagTable table() {
        if (table == null) {
            List<CgGameplayTag> sorted = new ArrayList<>(all);
            Collections.sort(sorted);
            List<String> names = new ArrayList<>(sorted.size());
            for (CgGameplayTag tag : sorted) names.add(tag.name());
            table = new CgTagTable(names);
        }
        return table;
    }

    private static void validate(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new CgTagException("malformed gameplay tag '" + name + "': expected namespace:a.b.c");
        }
    }
}
