package com.crystalgraphics.sequence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.event.CgSequenceEventListener.Side;

/**
 * Subsequences on Unreal's outer-to-inner time ({@code FMovieSceneSectionTimingParametersFrames::MakeTransform}): a
 * nested sequence's keys land on the outer ticks its section's start, time scale, tick resolutions, offsets and loop
 * give. On a server, so its gameplay events alone show where each key lands.
 */
public class CgSequenceSubsequenceTest {

    private static final String ROLES = "roles:\n  - { id: b1, name: caster, kind: caster }\n";

    /** Half a second at 24,000 ticks a second, its events at 0.1 s and 0.4 s of its own time. */
    private static final String INNER = "_v: 1\ntick_resolution: 24000\ndisplay_rate: 24\nrange: [0, 12000]\n" + ROLES
            + "tracks:\n  - { id: t1, kind: event, side: rpg, binding: { role: caster }, sections: [\n"
            + "      { id: a, keys: [[2400, mymod:early], [9600, mymod:late]] } ] }\n";

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

    /** An outer sequence at 1,000 ticks a second playing {@code section} of the inner one. */
    private static CgSequenceDefinition outer(String section) {
        String yaml = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 2000]\n" + ROLES
                + "tracks:\n  - { id: t1, kind: subsequence, sections: [ { id: s1, sequence: mymod:inner, " + section + " } ] }\n";
        return compile("mymod:outer", yaml, Map.of("mymod:inner", compile("mymod:inner", INNER, Map.of())));
    }

    private void play(CgSequenceDefinition d, double until) {
        CgSequencer s = new CgWorld(true).add(new CgSequencer());
        s.listen(Side.RPG, (p, phase, event, args) -> heard.add(event + "@" + Math.round(now * 1000)));
        s.play(d, Map.of("caster", CASTER));
        while (now < until - 1e-9) {
            now += 0.025;
            s.evaluate(0.025f);
        }
    }

    @Test
    public void itsKeysLandWhereItsSectionStarts_acrossTickResolutions() {
        play(outer("range: [200, 1200]"), 1.3);
        assertEquals(List.of("mymod:early@300", "mymod:late@600"), heard);
    }

    @Test
    public void aStartOffsetAndATimeScaleMoveItsKeys() {
        // inner = (t - 0.2) * 2 + 0.1: early at 0.2, late at 0.35
        play(outer("range: [200, 1200], start_offset: 2400, time_scale: 2"), 1.3);
        assertEquals(List.of("mymod:early@200", "mymod:late@350"), heard);
    }

    @Test
    public void aLoopingSectionPlaysItAgainEachLap() {
        play(outer("range: [0, 1000], loop: true"), 1.0);
        assertEquals(List.of("mymod:early@100", "mymod:late@400", "mymod:early@600", "mymod:late@900"), heard);
    }

    @Test
    public void nestedTwoDeepTheOffsetsCompose() {
        CgSequenceDefinition inner = compile("mymod:inner", INNER, Map.of());
        String middle = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 1000]\n" + ROLES
                + "tracks:\n  - { id: t1, kind: subsequence, sections: [ { id: s1, range: [100, 600], sequence: mymod:inner } ] }\n";
        CgSequenceDefinition mid = compile("mymod:middle", middle, Map.of("mymod:inner", inner));
        String top = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 2000]\n" + ROLES
                + "tracks:\n  - { id: t1, kind: subsequence, sections: [ { id: s1, range: [200, 1200], sequence: mymod:middle } ] }\n";
        play(compile("mymod:top", top, Map.of("mymod:middle", mid)), 1.3);
        // 0.2 into the top, then 0.1 into the middle: early at 0.4, late at 0.7
        assertEquals(List.of("mymod:early@400", "mymod:late@700"), heard);
    }

    @Test
    public void itsRolesMapOntoTheOutersRoles() {
        String inner = INNER.replace("name: caster, kind: caster", "name: user, kind: caster").replace("role: caster", "role: user");
        String yaml = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 2000]\n" + ROLES
                + "tracks:\n  - { id: t1, kind: subsequence, sections: [ { id: s1, sequence: mymod:inner, range: [200, 1200], roles: { user: caster } } ] }\n";
        play(compile("mymod:outer", yaml, Map.of("mymod:inner", compile("mymod:inner", inner, Map.of()))), 1.3);
        assertEquals(List.of("mymod:early@300", "mymod:late@600"), heard);
    }

    @Test
    public void aTimeScaleThatChangesFailsAtLoadNamingIt() {
        try {
            outer("range: [0, 1000], time_scale: { curve: { keys: [[0, 1, linear], [1, 2, linear]] } }");
            fail("compiled a changing time scale");
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("sections.s1.time_scale"));
        }
    }
}
