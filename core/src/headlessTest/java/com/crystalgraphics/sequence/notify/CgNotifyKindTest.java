package com.crystalgraphics.sequence.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypeKind;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/** A mod's notify kinds: spans begin, tick and end; triggers fire once; what a span set is restored as it ends. */
public class CgNotifyKindTest {

    private static final String HEAD = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 800]\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n";
    private static final List<String> CALLS = new ArrayList<>();

    /** A role with a tint, starting at 0.1 everywhere. */
    private static final class Role implements CgSequenceTarget {
        final float[] tint = {0.1f, 0.1f, 0.1f, 0.1f};

        @Override
        public int slot(String name) {
            return name.equals("tint") ? 0 : -1;
        }

        @Override
        public void set(int slot, float[] values, int mask) {
            for (int i = 0; i < 4; i++) if ((mask & 1 << i) != 0) tint[i] = values[i];
        }

        @Override
        public void get(int slot, float[] out) {
            System.arraycopy(tint, 0, out, 0, 4);
        }
    }

    @BeforeClass
    public static void kinds() {
        if (CgNotifyKind.get("sqtest:glow") != null) return;
        // A data type: it registers whether or not the engine's native types are frozen yet.
        CgType glow = CgTypes.global().declare("sqtest:notify.glow", CgTypeKind.STRUCT)
                .property("strength", CgValueType.FLOAT).required().register();
        CgNotifyKind.builder("sqtest:glow").properties(glow).writes("tint").behaviour(new CgNotifyKind.Behaviour() {
            @Override public void begin(CgNotifyContext c) {
                CALLS.add("begin");
                float k = ((Number) c.properties().get("strength")).floatValue();
                c.set(0, new float[] {k, k, k, 1}, 0b1111);
            }

            @Override public void tick(CgNotifyContext c, float dt) {
                CALLS.add("tick");
            }

            @Override public void end(CgNotifyContext c) {
                CALLS.add("end");
            }
        }).register();
        CgNotifyKind.builder("sqtest:puff").behaviour(new CgNotifyKind.Behaviour() {
            @Override public void notify(CgNotifyContext c) {
                CALLS.add("puff@" + c.section().from());
            }
        }).register();
    }

    private static CgSequenceDefinition compile(String sections) {
        String yaml = HEAD + "tracks:\n  - id: t1\n    kind: notify\n    binding: { role: caster }\n    sections:\n" + sections;
        return CgSequenceDefinition.compile(CgAssetData.of("mymod:s", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null));
    }

    private static final String SECTIONS = "      - { id: s1, range: [100, 300], kind: \"sqtest:glow\", properties: { strength: 2 } }\n"
            + "      - { id: s2, at: 400, kind: \"sqtest:puff\" }\n";

    @Test
    public void aSpanBeginsTicksAndEndsAndRestoresWhatItSet() {
        CALLS.clear();
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        Role caster = new Role();
        sequencer.play(compile(SECTIONS), Map.of("caster", caster));
        sequencer.evaluate(0.05f);
        assertEquals(List.of(), CALLS);
        sequencer.evaluate(0.1f);                          // tick 150: inside the span
        assertEquals(List.of("begin"), CALLS);
        sequencer.evaluate(0.05f);                         // tick 200: what begin set, written
        assertEquals(List.of("begin", "tick"), CALLS);
        assertEquals(2f, caster.tint[0], 0f);
        sequencer.evaluate(0.15f);                         // tick 350: past it
        assertEquals("end", CALLS.get(CALLS.size() - 1));
        assertEquals(0.1f, caster.tint[0], 0f);            // restored
        sequencer.evaluate(0.1f);                          // tick 450: crosses the trigger at 400, once
        sequencer.evaluate(0.1f);
        assertEquals(1, CALLS.stream().filter(c -> c.equals("puff@400")).count());
    }

    @Test
    public void aSeekBackPastASpanEndsItAndRestoresAndAJumpFiresNothing() {
        CALLS.clear();
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        Role caster = new Role();
        CgSequencePlayer player = sequencer.play(compile(SECTIONS), Map.of("caster", caster));
        sequencer.evaluate(0.15f);
        sequencer.evaluate(0.05f);
        assertEquals(2f, caster.tint[0], 0f);
        player.jumpTo(0.05);                               // back before the span
        sequencer.evaluate(0f);
        assertEquals("end", CALLS.get(CALLS.size() - 1));
        assertEquals(0.1f, caster.tint[0], 0f);
        player.jumpTo(0.5);                                // over the trigger: a jump crosses nothing
        sequencer.evaluate(0f);
        sequencer.evaluate(0.1f);
        assertTrue(CALLS.toString(), CALLS.stream().noneMatch(c -> c.startsWith("puff")));
    }

    @Test
    public void anUnknownKindOrItsWrongPropertiesFailAtLoadNamingThem() {
        try {
            compile("      - { id: s1, range: [100, 300], kind: \"sqtest:nope\" }\n");
            fail("compiled");
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("mymod:s: tracks.t1.sections.s1.kind: no notify kind sqtest:nope is registered"));
        }
        try {
            compile("      - { id: s1, range: [100, 300], kind: \"sqtest:glow\", properties: { stength: 2 } }\n");
            fail("compiled");
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("stength") || e.getMessage().contains("strength"));
        }
    }

    @Test
    public void aDedicatedServerRunsNoBehaviour() {
        CALLS.clear();
        CgSequencer sequencer = new CgWorld(true).add(new CgSequencer());
        sequencer.play(compile(SECTIONS), Map.of("caster", new Role()));
        for (int i = 0; i < 6; i++) sequencer.evaluate(0.1f);
        assertEquals(List.of(), CALLS);
    }

    @Test
    public void aTriggerWithNoChanceNeverFires() {
        CALLS.clear();
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        sequencer.play(compile("      - { id: s2, at: 400, kind: \"sqtest:puff\", chance: 0 }\n"), Map.of("caster", new Role()));
        for (int i = 0; i < 10; i++) sequencer.evaluate(0.1f);
        assertEquals(List.of(), CALLS);
    }
}
