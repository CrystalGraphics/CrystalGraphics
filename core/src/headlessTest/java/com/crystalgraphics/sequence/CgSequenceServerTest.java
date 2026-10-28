package com.crystalgraphics.sequence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Assume;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.event.CgSequenceEventListener;
import com.crystalgraphics.sequence.event.CgSequenceEventListener.Side;
import com.crystalgraphics.world.CgAttachment;

/** A sequence on a dedicated server: its gameplay events heard at their tick, its presentation never bound. */
public class CgSequenceServerTest {

    /** A caster that is no skeleton: a server binds no animation, so none is needed. */
    private static final class Caster implements CgSequenceRole {
        @Override public CgAttachment attachment(String socket) {
            return (point, turn) -> {
                point[0] = point[1] = point[2] = 0;
                if (turn != null) turn.identity();
                return true;
            };
        }

        @Override public int slot(String name) {
            return 0;
        }

        @Override public void set(int slot, float[] v, int mask) {
        }

        @Override public void get(int slot, float[] out) {
        }
    }

    private static CgSequenceDefinition compile(String id, String yaml) {
        return CgSequenceDefinition.compile(CgAssetData.of(id, CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null));
    }

    /** Ticks a server world until {@code event}'s span begins; the tick it began, or -1. */
    private static int tickOfBegin(CgSequenceDefinition definition, String event, List<String> heard) {
        CgWorld server = new CgWorld(true);
        CgSequencer sequencer = server.add(new CgSequencer());
        int[] tick = {0};
        int[] began = {-1};
        sequencer.listen(Side.RPG, (p, phase, e, args) -> {
            heard.add(tick[0] + " " + phase + " " + e);
            if (e.equals(event) && phase == CgSequenceEventListener.Phase.BEGIN && began[0] < 0) began[0] = tick[0];
        });
        sequencer.listen(Side.OURS, (p, phase, e, args) -> heard.add("ours " + e));
        server.tick();   // a running world: a tick function's first run advances nothing
        sequencer.play(definition, Map.of("caster", new Caster()));
        for (tick[0] = 1; tick[0] <= 40 && began[0] < 0; tick[0]++) server.tick();
        return began[0];
    }

    @Test
    public void anAnimationTrackIsNeverBoundOnAServerAndItsEventsAreHeard() {
        CgSequenceDefinition d = compile("mymod:shot", "_v: 1\ntick_resolution: 1000\ndisplay_rate: 20\nrange: [0, 1000]\n"
                + "roles:\n  - { id: b1, name: caster, kind: caster }\ntracks:\n"
                + "  - { id: t1, kind: animation, binding: { role: caster }, sections: [ { id: a, range: [0, 1000], clip: mymod:shot_clip, slot: DefaultSlot } ] }\n"
                + "  - { id: t2, kind: event, side: rpg, binding: { role: caster }, sections: [ { id: b, range: [220, 260], event: mymod:beam } ] }\n"
                + "  - { id: t3, kind: event, side: ours, binding: { role: caster }, sections: [ { id: c, keys: [[100, mymod:flash]] } ] }\n");
        List<String> heard = new ArrayList<>();
        assertEquals("0.22 s: the fifth tick at 20 Hz", 5, tickOfBegin(d, "mymod:beam", heard));
        assertTrue("presentation is not the server's: " + heard, heard.stream().noneMatch(h -> h.startsWith("ours")));
    }

    /** {@code -Pkits=kakarot_kit=...}: the Kamehameha's shot, from its file, fires its beam at its tick. */
    @Test
    public void theKamehamehasBeamIsHeardOnTheServerAtItsTick() throws IOException {
        Path kit = null;
        for (String k : System.getProperty("crystalgraphics.kits", "").split(";")) {
            if (k.startsWith("kakarot_kit=")) kit = Paths.get(k.substring("kakarot_kit=".length()));
        }
        Assume.assumeTrue("no -Pkits=kakarot_kit=...", kit != null);
        Path file = kit.resolve("sequences/Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001_02.yaml");
        CgSequenceDefinition d = compile("kakarot_kit:Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001_02",
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        List<String> heard = new ArrayList<>();
        // [13459, 15459] at 60000 a second: from 0.2243 s, so the fifth tick.
        assertEquals(String.valueOf(heard), 5, tickOfBegin(d, "kakarot:AnimNotify_SpawnProjectileBeam", heard));
    }
}
