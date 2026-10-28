package com.crystalgraphics.sequence.event;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.sequence.event.CgSequenceEventListener.Side;
import com.crystalgraphics.world.CgAttachment;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/** Event tracks heard by their side as play crosses them, and sections the gameplay's events start. */
public class CgSequenceEventTest {

    /** 1000 ticks a second: a key at 150 sits inside a 200 ms frame. */
    private static final String HEAD = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 2000]\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n";
    private static final String EVENTS = HEAD + "tracks:\n"
            + "  - { id: t1, kind: event, side: rpg, binding: { role: caster }, sections: [\n"
            + "      { id: a, keys: [[150, { event: mymod:release, args: { power: 3 } }], [1500, mymod:late]] },\n"
            + "      { id: b, range: [300, 700], event: mymod:hit_window, args: { damage: 40 } } ] }\n"
            + "  - { id: t2, kind: event, side: ours, binding: { role: caster }, sections: [ { id: c, keys: [[150, mymod:rumble]] } ] }\n";

    /** A role taking any slot, keeping each value written. */
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
    }

    private final List<String> heard = new ArrayList<>();

    private CgSequencer sequencer(boolean server) {
        CgSequencer s = new CgWorld(server).add(new CgSequencer());
        s.listen(Side.RPG, (p, phase, event, args) -> heard.add("rpg " + phase + " " + event + (args == null ? "" : " " + args)));
        s.listen(Side.OURS, (p, phase, event, args) -> heard.add("ours " + phase + " " + event));
        return s;
    }

    private static CgSequenceDefinition compile(String yaml) {
        return CgSequenceDefinition.compile(CgAssetData.of("mymod:move", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null));
    }

    @Test
    public void anEventInsideALongFrameFiresOnceAndSpansBeginAndEnd() {
        CgSequencer sequencer = sequencer(false);
        CgSequencePlayer player = sequencer.play(compile(EVENTS), Map.of("caster", new Caster()));
        sequencer.evaluate(0.2f);                                     // ticks 0 to 200: the key at 150, inside the frame
        assertEquals(List.of("rpg INSTANT mymod:release {power=3}", "ours INSTANT mymod:rumble"), heard);
        heard.clear();
        sequencer.evaluate(0.2f);                                     // to 400: nothing again; the span begins
        assertEquals(List.of("rpg BEGIN mymod:hit_window {damage=40}"), heard);
        heard.clear();
        sequencer.evaluate(0.4f);                                     // to 800: it ends
        assertEquals(List.of("rpg END mymod:hit_window {damage=40}"), heard);
        heard.clear();
        player.jumpTo(1.6);                                           // past the key at 1500: skipped, never fired
        sequencer.evaluate(0f);
        sequencer.evaluate(0.2f);
        assertEquals(List.of(), heard);
    }

    @Test
    public void aServerHearsTheGameplaysAlone() {
        CgSequencer server = sequencer(true);
        CgSequencePlayer player = server.play(compile(EVENTS), Map.of("caster", new Caster()));
        server.evaluate(0.4f);
        assertEquals(List.of("rpg INSTANT mymod:release {power=3}", "rpg BEGIN mymod:hit_window {damage=40}"), heard);
        heard.clear();
        player.stop();
        server.evaluate(0f);                                          // a stop ends what is open
        assertEquals(List.of("rpg END mymod:hit_window {damage=40}"), heard);
    }

    @Test
    public void aTriggeredSectionPlaysFromItsEventAndRestartsOnASecondOne() {
        CgSequencer sequencer = sequencer(false);
        Caster caster = new Caster();
        CgSequencePlayer player = sequencer.play(compile(HEAD + "tracks:\n"
                + "  - { id: t1, kind: event, side: ours, binding: { role: caster }, sections: [\n"
                + "      { id: a, start: { event: mymod:charge }, range: [0, 500], keys: [[0, mymod:glow], [300, mymod:flash]] } ] }\n"
                + "  - { id: t2, kind: property, binding: { role: caster }, property: opacity, sections: [\n"
                + "      { id: b, start: { event: mymod:charge }, range: [0, 500], value: { keys: [[0, 0.0, linear], [400, 1.0, linear]] } } ] }\n"),
                Map.of("caster", caster));
        sequencer.evaluate(0.1f);                                     // tick 100: nothing has started it
        assertEquals(List.of(), heard);
        assertTrue(player.event("mymod:charge"));
        sequencer.evaluate(0.2f);                                     // tick 300, 200 into it
        assertEquals(List.of("ours INSTANT mymod:glow"), heard);
        assertEquals(0.5f, caster.values.get(caster.slots.indexOf("opacity"))[0], 1e-5f);
        heard.clear();
        assertTrue(player.event("mymod:charge"));                     // again: it starts over from this tick
        assertEquals(2, player.trigger(player.definition().tracks().get(0), player.definition().tracks().get(0).sections().get(0)).count());
        sequencer.evaluate(0.1f);                                     // 100 into the second run: its first key again
        assertEquals(List.of("ours INSTANT mymod:glow"), heard);
        assertEquals(0.25f, caster.values.get(caster.slots.indexOf("opacity"))[0], 1e-5f);
        heard.clear();
        sequencer.evaluate(0.3f);                                     // 400 into it: the flash at 300, once
        assertEquals(List.of("ours INSTANT mymod:flash"), heard);
        assertTrue(!player.event("mymod:other"));
    }

    @Test
    public void anEventTrackOfNoSideFailsAtPlayNamingIt() {
        try {
            sequencer(false).play(compile(EVENTS.replace("side: ours, ", "")), Map.of("caster", new Caster()));
            fail("played an event track of no side");
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("tracks.t2") && e.getMessage().contains("rpg or ours"));
        }
    }
}
