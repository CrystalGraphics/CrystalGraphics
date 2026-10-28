package com.crystalgraphics.sequence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.event.CgSequenceEventListener.Side;

/**
 * An animation section's clip notifies: a sequence of its own, played on the clip's time while the section plays.
 * On a server, where no animation binds, so its gameplay events alone show its clock.
 */
public class CgSequenceNotifiesTest {

    private static final String ROLES = "roles:\n  - { id: b1, name: caster, kind: caster }\n"
            + "  - { id: b2, name: target, kind: target, absent: skip }\n";

    /** A clip's notifies: a second long, its events at clip times 0.02, 0.1, 0.3 and 0.9. */
    private static final String NOTIFIES = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 1000]\n" + ROLES
            + "tracks:\n  - { id: t1, kind: event, side: rpg, binding: { role: caster }, sections: [\n"
            + "      { id: a, keys: [[20, mymod:before], [100, mymod:early], [300, mymod:mid], [900, mymod:late]] } ] }\n";

    /** A move playing the clip from 0.2 s to 0.8 s at twice its rate, 50 ticks into it. */
    private static final String MOVE = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 1000]\n"
            + "roles:\n  - { id: b1, name: caster, kind: caster }\n"
            + "tracks:\n  - { id: t1, kind: animation, binding: { role: caster }, sections: [\n"
            + "      { id: s1, range: [200, 800], clip: mymod:clip, play_rate: 2, start_offset: 50, notifies: mymod:notifies } ] }\n";

    /** A caster that is no skeleton: a server binds no animation. */
    private static final CgSequenceTarget CASTER = new CgSequenceTarget() {
        @Override public int slot(String name) {
            return 0;
        }

        @Override public void set(int slot, float[] v, int mask) {
        }

        @Override public void get(int slot, float[] out) {
        }
    };

    private final List<String> heard = new ArrayList<>();
    private double now;

    private static CgSequenceDefinition compile(String id, String yaml, Map<String, Object> deps) {
        CgAssetCompiler.Dependencies d = (CgType kind, String ref) -> deps.get(ref);
        return CgSequenceDefinition.COMPILER.compile(CgAssetData.of(id, CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null), d);
    }

    private CgSequencer sequencer() {
        CgSequencer s = new CgWorld(true).add(new CgSequencer());
        s.listen(Side.RPG, (p, phase, event, args) -> heard.add(event + "@" + Math.round(now * 1000)));
        return s;
    }

    private void run(CgSequencer s, double until) {
        while (now < until - 1e-9) {
            now += 0.025;
            s.evaluate(0.025f);
        }
    }

    private static CgSequenceDefinition move(String yaml, String notifies) {
        return compile("mymod:move", yaml, Map.of("mymod:notifies", compile("mymod:notifies", notifies, Map.of())));
    }

    @Test
    public void itsEventsFireOnTheClipsTime_fromItsOffset_untilTheSectionEnds() {
        CgSequencer s = sequencer();
        s.play(move(MOVE, NOTIFIES), Map.of("caster", CASTER));
        run(s, 0.75);
        // Clip time 0.05 + 2 (t - 0.2): early at 0.225 s, mid at 0.325 s, late at 0.625 s; before lies under the offset.
        assertEquals(List.of("mymod:early@225", "mymod:mid@325", "mymod:late@625"), heard);
        assertEquals("the move and its clip's notifies", 2, s.players().size());
        run(s, 0.85);
        assertEquals("gone with its section", 1, s.players().size());
    }

    @Test
    public void aJumpLandsItWhereTheSectionIs_firingNothingItSkipped() {
        CgSequencer s = sequencer();
        CgSequencePlayer move = s.play(move(MOVE, NOTIFIES), Map.of("caster", CASTER));
        run(s, 0.1);
        move.jumpTo(0.5);
        run(s, 0.75);
        assertFalse("early and mid were jumped over: " + heard, heard.stream().anyMatch(h -> h.startsWith("mymod:mid")));
        assertTrue(heard.toString(), heard.stream().anyMatch(h -> h.startsWith("mymod:late")));
    }

    @Test
    public void aLoopingClipFiresItsNotifiesEachLap() {
        String clip = NOTIFIES.replace("range: [0, 1000]", "range: [0, 400]").replace("[20, mymod:before], ", "")
                .replace(", [300, mymod:mid], [900, mymod:late]", "");
        String loop = MOVE.replace("range: [200, 800], clip: mymod:clip, play_rate: 2, start_offset: 50", "range: [0, 1000], clip: mymod:clip, loop: true");
        CgSequencer s = sequencer();
        s.play(move(loop, clip), Map.of("caster", CASTER));
        run(s, 0.975);
        assertEquals(List.of("mymod:early@100", "mymod:early@500", "mymod:early@900"), heard);
    }
}
