package com.crystalgraphics.variable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every {@link CgVar} declared so far, by id: what a console lists and sets, what a remote tool reads, and what
 * {@code docs/DEBUG_FLAGS.md} is generated from.
 *
 * <pre>{@code
 * CgVar<?> v = CgVars.get("crystalgraphics.vfx.mergeParticles");
 * v.setText(CgVar.Source.CONSOLE, "false");         // false: no such value, or read-only
 * v.clear(CgVar.Source.CONSOLE);                    // back to what -D, the file or the default says
 *
 * for (CgVar<?> each : CgVars.all()) System.out.println(each);   // sorted by id
 * }</pre>
 *
 * <ul>
 *   <li>A variable registers when its declaring class initialises: one never loaded is not listed.</li>
 *   <li>{@link #unregister} is for variables made at run time (a look's parameters while a tool shows them); a
 *       static one stays for the run.</li>
 * </ul>
 */
public final class CgVars {

    private static final ConcurrentHashMap<String, CgVar<?>> BY_ID = new ConcurrentHashMap<>();

    private CgVars() {
    }

    /** The variable {@code id}; null when none is declared. */
    public static CgVar<?> get(String id) {
        return BY_ID.get(id);
    }

    /** Every variable, sorted by id. */
    public static List<CgVar<?>> all() {
        List<CgVar<?>> all = new ArrayList<>(BY_ID.values());
        all.sort(Comparator.comparing(CgVar::id));
        return Collections.unmodifiableList(all);
    }

    /** Every variable whose id starts with {@code prefix}, sorted by id. */
    public static List<CgVar<?>> under(String prefix) {
        List<CgVar<?>> found = new ArrayList<>();
        for (CgVar<?> v : BY_ID.values()) {
            if (v.id().startsWith(prefix)) found.add(v);
        }
        found.sort(Comparator.comparing(CgVar::id));
        return found;
    }

    /** Drops a variable made at run time. Its readers keep the object; nothing lists or sets it any more. */
    public static void unregister(CgVar<?> v) {
        BY_ID.remove(v.id(), v);
    }

    static <V extends CgVar<?>> V register(V v) {
        if (BY_ID.putIfAbsent(v.id(), v) != null) throw new IllegalStateException("variable " + v.id() + " is declared twice");
        v.readProperty();
        return v;
    }
}
