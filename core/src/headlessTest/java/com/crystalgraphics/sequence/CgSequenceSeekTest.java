package com.crystalgraphics.sequence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.sequence.CgSequenceEventListener.Side;
import com.crystalgraphics.world.CgAttachment;

/** Seeks landing on the state playing reaches, montage sections looping and jumping, and late joiners. */
public class CgSequenceSeekTest {

    /** 100 ticks a second: the one-shot window is 20 ticks. */
    private static final String SEQUENCE = "_v: 1\ntick_resolution: 100\ndisplay_rate: 10\nrange: [0, 100]\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
            + "spawnables:\n  - { id: s1, name: aura, at: { role: caster, socket: root }, system: mymod:aura }\n"
            + "tracks:\n"
            + "  - { id: t1, kind: property, binding: { role: caster }, property: opacity, sections: [\n"
            + "      { id: a, range: [0, 100], value: { keys: [[0, 0.0, linear], [100, 1.0, linear]] } } ] }\n"
            + "  - { id: t2, kind: system, binding: { spawnable: s1 }, sections: [ { id: b, range: [0, 100], keys: [[10, activate], [80, deactivate]] } ] }\n"
            + "  - { id: t3, kind: event, side: ours, binding: { role: caster }, sections: [\n"
            + "      { id: c, keys: [[30, mymod:early], [45, mymod:late]] },\n"
            + "      { id: d, range: [20, 60], event: mymod:window } ] }\n";

    private static final String MONTAGE = "_v: 1\ntick_resolution: 100\ndisplay_rate: 10\nrange: [0, 100]\n"
            + "composite_sections:\n"
            + "  - { id: q1, name: charge, range: [0, 40], next: q2 }\n"
            + "  - { id: q2, name: loop, range: [40, 80], next: q2 }\n"
            + "  - { id: q3, name: release, range: [80, 100] }\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
            + "tracks:\n"
            + "  - { id: t1, kind: event, side: ours, binding: { role: caster }, sections: [ { id: a, keys: [[50, mymod:pulse], [90, mymod:burst]] } ] }\n";

    private static final class Caster implements CgSequenceRole {
        final List<String> slots = new ArrayList<>();
        final List<float[]> values = new ArrayList<>();

        @Override public CgAttachment attachment(String socket) {
            return (point, turn) -> {
                point[0] = point[1] = point[2] = 0;
                if (turn != null) turn.identity();
                return true;
            };
        }

        @Override public int slot(String name) {
            if (!slots.contains(name)) {
                slots.add(name);
                values.add(new float[4]);
            }
            return slots.indexOf(name);
        }

        @Override public void set(int slot, float[] v, int mask) {
            for (int i = 0; i < 4; i++) if ((mask & 1 << i) != 0) values.get(slot)[i] = v[i];
        }

        @Override public void get(int slot, float[] out) {
            System.arraycopy(values.get(slot), 0, out, 0, Math.min(4, out.length));
        }

        float opacity() {
            return values.get(slots.indexOf("opacity"))[0];
        }
    }

    /** A system recording whether it runs and the age a seek gave it. */
    private static final class Aura implements CgSequenceSystem {
        boolean active;
        float age = -1f;

        @Override public void activate(boolean reset) { active = true; }
        @Override public void deactivate() { active = false; }
        @Override public void kill() { active = false; }
        @Override public void detach() { }
        @Override public boolean active() { return active; }
        @Override public void age(float seconds) { age = seconds; }
        @Override public void destroy(boolean graceful) { active = false; }
    }

    private final List<Aura> auras = new ArrayList<>();
    private final List<String> heard = new ArrayList<>();

    private CgSequencer sequencer() {
        CgSequencer s = new CgWorld().add(new CgSequencer());
        s.spawner("system", (p, spawnable, at) -> {
            Aura a = new Aura();
            auras.add(a);
            return a;
        });
        s.listen(Side.OURS, (p, phase, event, args) -> heard.add(phase + " " + event));
        return s;
    }

    private static CgSequenceDefinition compile(String yaml) {
        return CgSequenceDefinition.compile(CgAssetData.of("mymod:move", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null));
    }

    @Test
    public void aSeekLandsOnTheStatePlayingReaches() {
        CgSequencer played = sequencer();
        Caster a = new Caster();
        played.play(compile(SEQUENCE), Map.of("caster", a));
        for (int i = 0; i < 5; i++) played.evaluate(0.1f);              // played to tick 50
        assertEquals(List.of("BEGIN mymod:window", "INSTANT mymod:early", "INSTANT mymod:late"), heard);
        assertTrue(auras.get(0).active);
        heard.clear();

        CgSequencer late = sequencer();
        Caster b = new Caster();
        late.play(compile(SEQUENCE), Map.of("caster", b)).seek(0.5);   // a late joiner at tick 50
        late.evaluate(0f);
        assertEquals(a.opacity(), b.opacity(), 1e-6f);                  // state: evaluated at its time
        Aura aura = auras.get(1);
        assertTrue(aura.active);                                        // lasting: started at its age
        assertEquals(0.4f, aura.age, 1e-5f);                            // 40 ticks after its activate key
        // A one-shot within the window (45, 5 ticks before) fires; the one before it (30) is dropped; the span begins.
        assertEquals(List.of("INSTANT mymod:late", "BEGIN mymod:window"), heard);
    }

    @Test
    public void aMontageSectionLoopsUntilReLinkedAndAJumpFiresNothingItSkips() {
        CgSequencer sequencer = sequencer();
        CgSequencePlayer player = sequencer.play(compile(MONTAGE), Map.of("caster", new Caster()));
        for (int i = 0; i < 9; i++) sequencer.evaluate(0.1f);           // 0.9 s: charge, then loop, then loop again
        assertEquals("loop", player.section());
        assertEquals(List.of("INSTANT mymod:pulse", "INSTANT mymod:pulse"), heard);   // its key, once a lap
        heard.clear();
        player.nextSection("loop", "release");                          // the key let go: the loop runs out
        for (int i = 0; i < 6; i++) sequencer.evaluate(0.1f);         // from 50: the lap's end at 80, then release
        assertEquals("release", player.section());
        assertEquals(List.of("INSTANT mymod:burst"), heard);
        assertTrue(player.finished());                                   // release has no next: it holds at its end
        heard.clear();

        CgSequencePlayer jumped = sequencer.play(compile(MONTAGE), Map.of("caster", new Caster()));
        sequencer.evaluate(0.1f);
        jumped.jumpToSection("release");                                // from the charge, over the loop's key
        sequencer.evaluate(0f);
        assertEquals("release", jumped.section());
        assertEquals(List.of(), heard);
        sequencer.evaluate(0.15f);                                       // then plays on, crossing the burst at 90
        assertEquals(List.of("INSTANT mymod:burst"), heard);
    }

    @Test
    public void aFrameLongerThanALapPlaysEachLapsKey() {
        CgSequencer sequencer = sequencer();
        CgSequencePlayer player = sequencer.play(compile(MONTAGE), Map.of("caster", new Caster()));
        sequencer.evaluate(0.35f);                                       // to tick 35
        sequencer.evaluate(1.0f);                                        // 100 ticks: 5 to the loop, then 2.4 laps of it
        assertEquals("loop", player.section());
        assertEquals(List.of("INSTANT mymod:pulse", "INSTANT mymod:pulse", "INSTANT mymod:pulse"), heard);
    }
}
