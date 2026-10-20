package com.crystalgraphics.vfx.system;

import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What a system plays: its name, its look (emitters, the layers on them, its parameters) and its native behaviour, if
 * any. Unreal's {@code UNiagaraSystem} as it compiles; a system with no behaviour plays its emitters once and completes.
 *
 * <pre>{@code
 * static final CgVfxSystemDefinition PUFF = CgVfxSystemDefinition.of("mymod:puff",
 *         CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).with(PUFF_KIT).build());
 * VFX.play(new CgVfxSystemInstance(PUFF, x, y, z));
 *
 * static final CgVfxSystemDefinition BEAM = CgVfxSystemDefinition.of("mymod:beam", BEAM_LOOK).withBehaviour(Beam::new);
 * }</pre>
 *
 * <ul>
 *   <li>Held by identity: it is the simulation's key, so make each once, as a constant.</li>
 * </ul>
 *
 * @param name      what tools and logs call it
 * @param look      its emitters, layers and parameters
 * @param behaviour its native behaviour's factory, or null for none
 */
public record CgVfxSystemDefinition(String name, CgVfxLook look, @Nullable CgVfxBehaviour.Factory behaviour) {

    /** A system with no parameters of its own reads none: its kits' layers read theirs. */
    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:system");

    public CgVfxSystemDefinition {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("a system needs a name");
        if (look == null) throw new IllegalArgumentException(name + " needs a look");
    }

    /** {@code look}'s emitters played once, with no behaviour. */
    public static CgVfxSystemDefinition of(String name, CgVfxLook look) {
        return new CgVfxSystemDefinition(name, look, null);
    }

    /** Each look's burst definition, made once: a definition is its simulation's key. */
    private static final Map<CgVfxLook, CgVfxSystemDefinition> BURSTS = Collections.synchronizedMap(new WeakHashMap<>());

    /** {@code look}'s emitters played once, with no behaviour: the same definition for the same look. */
    public static CgVfxSystemDefinition of(CgVfxLook look) {
        CgVfxSystemDefinition known = BURSTS.get(look);
        if (known != null) return known;
        CgVfxSystemDefinition made = new CgVfxSystemDefinition("crystalgraphics:burst", look, null);
        CgVfxSystemDefinition raced = BURSTS.putIfAbsent(look, made);
        return raced != null ? raced : made;
    }

    /** This definition carrying {@code behaviour}. */
    public CgVfxSystemDefinition withBehaviour(CgVfxBehaviour.Factory behaviour) {
        return new CgVfxSystemDefinition(name, look, behaviour);
    }
}
