package com.crystalgraphics.sequence.condition;

import static org.junit.Assert.assertEquals;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.definition.CgSequenceCondition;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgAttachment;

/** Sections chosen by the role's variant, one row each under exclusive conditions: the Kakarot cut's face cases. */
public class CgConditionKindsTest {

    /** The cut's five cases, each writing its own value: base 1, SS3 2, SKL005 3, SSG 4, any other form 5. */
    private static final String CUT = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 800]\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
            + "tracks:\n  - id: t1\n    kind: property\n    binding: { role: caster }\n    property: face\n    sections:\n"
            + "      - { id: s1, range: [0, 800], row: 0, value: { keys: [[0, 1]] },"
            + " condition: { not: { kind: crystalgraphics:variant, role: caster, is: any } } }\n"
            + "      - { id: s2, range: [0, 800], row: 1, value: { keys: [[0, 2]] },"
            + " condition: { kind: crystalgraphics:variant, role: caster, is: [C001A_S_SKL003_3] } }\n"
            + "      - { id: s3, range: [0, 800], row: 2, value: { keys: [[0, 3]] },"
            + " condition: { kind: crystalgraphics:variant, role: caster, is: [C001A_S_SKL005] } }\n"
            + "      - { id: s4, range: [0, 800], row: 3, value: { keys: [[0, 4]] },"
            + " condition: { kind: crystalgraphics:variant, role: caster, is: [C001A_S_SKL014] } }\n"
            + "      - { id: s5, range: [0, 800], row: 4, value: { keys: [[0, 5]] },"
            + " condition: { all: [ { kind: crystalgraphics:variant, role: caster, is: any },"
            + " { not: { kind: crystalgraphics:variant, role: caster, is: [C001A_S_SKL003_3, C001A_S_SKL005, C001A_S_SKL014] } } ] } }\n";

    /** A role in a given form, keeping the {@code face} it was last given and how many times. */
    private static final class Fighter implements CgSequenceRole {
        final List<String> forms;
        float face;
        int writes;

        Fighter(String... forms) {
            this.forms = List.of(forms);
        }

        @Override
        public Collection<String> variants() {
            return forms;
        }

        @Nullable
        @Override
        public CgAttachment attachment(@Nullable String socket) {
            return null;
        }

        @Override
        public int slot(String name) {
            return name.equals("face") ? 0 : -1;
        }

        @Override
        public void set(int slot, float[] values, int mask) {
            face = values[0];
            writes++;
        }

        @Override
        public void get(int slot, float[] out) {
            out[0] = face;
        }
    }

    private static CgSequenceDefinition compile() {
        CgAssetCompiler.Dependencies d = (CgType kind, String id) -> null;
        return CgSequenceDefinition.COMPILER.compile(
                CgAssetData.of("mymod:cut", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(CUT), null), d);
    }

    private static float played(boolean server, String... forms) {
        CgSequencer sequencer = new CgWorld(server).add(new CgSequencer());
        Fighter goku = new Fighter(forms);
        sequencer.play(compile(), Map.of("caster", goku));
        sequencer.evaluate(0.2f);
        return goku.writes == 0 ? 0f : goku.face;
    }

    @Test
    public void exactlyTheCaseItsFormNamesPlays() {
        assertEquals("the base form", 1f, played(false), 0f);
        assertEquals("a named form", 2f, played(false, "C001A_S_SKL003_3"), 0f);
        assertEquals("another named form", 4f, played(false, "C001A_S_SKL014"), 0f);
        assertEquals("a form no case names", 5f, played(false, "C001A_S_SKL099"), 0f);
    }

    @Test
    public void aServerPlaysNoConditionalSection() {
        assertEquals(0f, played(true), 0f);
    }

    @Test
    public void aKindNobodyRegisteredDoesNotHold() {
        CgConditionKinds.register("mymod:weather", (leaf, role) -> true);
        CgSequenceCondition.Leaf rain = new CgSequenceCondition.Leaf("mymod:weather", -1, List.of("rain"), false, Map.of());
        CgSequenceCondition.Leaf snow = new CgSequenceCondition.Leaf("mymod:unregistered", -1, List.of("snow"), false, Map.of());
        assertEquals(true, rain.test(CgConditionKinds.reader(i -> null)));
        assertEquals(false, snow.test(CgConditionKinds.reader(i -> null)));
    }
}
