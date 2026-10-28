package com.crystalgraphics.sequence.condition;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.definition.CgSequenceCondition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The condition kinds by id: what answers a section's {@code condition} leaves. Built in: {@code crystalgraphics:character}
 * and {@code crystalgraphics:variant}, read from the role ({@link CgSequenceRole#character()},
 * {@link CgSequenceRole#variants()}); a mod adds its own.
 *
 * <pre>{@code
 * // condition: { not: { kind: crystalgraphics:variant, role: caster, is: any } }   // the base form
 * CgConditionKinds.register("mymod:weather", (leaf, role) -> leaf.is().contains(weather.name()));
 * boolean holds = section.condition().test(CgConditionKinds.reader(player::role));
 * }</pre>
 *
 * <ul>
 *   <li>A leaf of a kind nobody registered does not hold, reported once per kind: its section is not played.</li>
 *   <li>Registering an id a second time replaces its reader.</li>
 * </ul>
 */
public final class CgConditionKinds {

    private static final Logger LOGGER = LogManager.getLogger("CgConditionKinds");
    public static final String CHARACTER = "crystalgraphics:character", VARIANT = "crystalgraphics:variant";

    private static final Map<String, CgConditionKind> KINDS = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /** A role by its index in the definition's roles, or null where it was skipped. */
    @FunctionalInterface
    public interface Roles {
        @Nullable
        CgSequenceTarget role(int index);
    }

    static {
        register(CHARACTER, (leaf, role) -> role instanceof CgSequenceRole r && r.character() != null
                && (leaf.anyValue() || leaf.is().contains(r.character())));
        register(VARIANT, (leaf, role) -> role instanceof CgSequenceRole r && matches(leaf, r.variants()));
    }

    private CgConditionKinds() {
    }

    public static void register(String kind, CgConditionKind reader) {
        KINDS.put(Objects.requireNonNull(kind, "kind"), Objects.requireNonNull(reader, "reader"));
    }

    /** What answers {@code roles}' leaves. */
    public static CgSequenceCondition.Reader reader(Roles roles) {
        return leaf -> {
            CgConditionKind kind = KINDS.get(leaf.kind());
            if (kind == null) {
                if (REPORTED.add(leaf.kind())) LOGGER.warn("no condition kind {}: its sections are not played", leaf.kind());
                return false;
            }
            return kind.holds(leaf, leaf.role() < 0 ? null : roles.role(leaf.role()));
        };
    }

    /** {@code is: any} while any is reported, else one of {@code is} reported. */
    private static boolean matches(CgSequenceCondition.Leaf leaf, Collection<String> reported) {
        if (leaf.anyValue()) return !reported.isEmpty();
        for (Object value : leaf.is()) if (reported.contains(String.valueOf(value))) return true;
        return false;
    }
}
