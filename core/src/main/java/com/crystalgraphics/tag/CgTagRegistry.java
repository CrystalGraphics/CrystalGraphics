package com.crystalgraphics.tag;

import com.crystalgraphics.asset.codec.CgAssetYaml;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Every gameplay tag of a process, interned: a mod declares its tags at init, in code or in
 * {@code assets/<namespace>/tags.yaml}, and looks them up by name afterwards. Mirrors Unreal's
 * {@code UGameplayTagsManager}: registering {@code a.b.c} makes {@code a} and {@code a.b} too, and names compare
 * without case.
 *
 * <pre>{@code
 * // from code, at init
 * CgGameplayTag fire = CgTagRegistry.global().register("mymod:cue.kamehameha.fire");
 *
 * // from a file: assets/mymod/tags.yaml
 * //   _v: 1
 * //   tags: [ mymod:cue.kamehameha.fire, mymod:state.charging ]
 * CgTagRegistry.global().registerFile("mymod", yamlText, "assets/mymod/tags.yaml");
 *
 * // in a file that names a tag (a cue's `tag`): unknown fails by name
 * CgGameplayTag tag = CgTagRegistry.global().require("mymod:cue.kamehameha.fire");
 * }</pre>
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

    /**
     * Declares every tag of a {@code tags.yaml}, each in {@code namespace}.
     *
     * @param source the file's name, for the messages
     * @throws CgTagException on a malformed file, a tag in another namespace, or a malformed tag
     */
    public synchronized void registerFile(String namespace, String yaml, String source) {
        Object tree = CgAssetYaml.read(yaml);
        if (!(tree instanceof Map)) throw new CgTagException(source + ": expected a map with _v and tags");
        Map<?, ?> root = (Map<?, ?>) tree;
        for (Object key : root.keySet()) {
            if (!"_v".equals(key) && !"tags".equals(key) && !"editor".equals(key)) {
                throw new CgTagException(source + ": unknown key '" + key + "' (expected _v, tags)");
            }
        }
        Object version = root.get("_v");
        if (!(version instanceof Number) || ((Number) version).intValue() != 1) {
            throw new CgTagException(source + ": _v must be 1, not " + version);
        }
        Object tags = root.get("tags");
        if (!(tags instanceof List)) throw new CgTagException(source + ": tags must be a list");
        List<String> names = new ArrayList<>();
        for (Object entry : (List<?>) tags) {
            if (!(entry instanceof String)) throw new CgTagException(source + ": tag " + entry + " is not a string");
            String name = (String) entry;
            try {
                validate(name);
            } catch (CgTagException e) {
                throw new CgTagException(source + ": " + e.getMessage());
            }
            if (!name.startsWith(namespace + ":")) {
                throw new CgTagException(source + ": tag '" + name + "' is not in namespace '" + namespace + "'");
            }
            names.add(name);
        }
        for (String name : names) register(name);
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
