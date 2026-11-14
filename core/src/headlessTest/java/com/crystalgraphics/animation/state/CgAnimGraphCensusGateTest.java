package com.crystalgraphics.animation.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;
import org.junit.Assume;
import org.junit.Test;

import com.crystalgraphics.animation.blend.CgInertializationNode;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimGraphCodec;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.tree.CgAssetData;

/**
 * An imported {@code anim_graph} file against the census it was imported from: every machine, its initial state, each
 * state's conduit and reset-on-entry, and each transition's ends, crossfade and blend, exactly and in order.
 *
 * <ul>
 *   <li>{@code -PanimGraphFile=<the imported yaml>} {@code -PanimGraphCensus=<census json>}: skipped without them;
 *       {@code -PanimGraphParent=<its parent's yaml>} for a child graph.</li>
 *   <li>The census is the importer's: {@code machines[] { name, initial, states[] { name, conduit, reset_on_entry },
 *       transitions[] { from, to, duration, blend } }}, machines and blends as Unreal names them ({@code BrakingSub},
 *       {@code HermiteCubic}); the file's are snake_case.</li>
 * </ul>
 */
public class CgAnimGraphCensusGateTest {

    @Test
    public void theImportedGraphIsTheCensus() throws IOException {
        String file = System.getProperty("crystalgraphics.animGraph.file"), census = System.getProperty("crystalgraphics.animGraph.census");
        Assume.assumeTrue("-PanimGraphFile and -PanimGraphCensus name the files", file != null && census != null);
        CgAssetKinds.register();
        CgAnimGraphCodec.registerKind("state_machine", CgStateMachineNode.KIND);
        CgAnimGraphCodec.registerKind("inertialization", CgInertializationNode.KIND);

        Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(Files.readString(Path.of(file), StandardCharsets.UTF_8));
        String parentFile = System.getProperty("crystalgraphics.animGraph.parent");
        CgAssetData parent = null;
        if (parentFile != null) {
            Map<?, ?> p = (Map<?, ?>) CgAssetYaml.read(Files.readString(Path.of(parentFile), StandardCharsets.UTF_8));
            parent = CgAssetData.of(String.valueOf(tree.get("parent")), CgBodyKinds.ANIM_GRAPH, p, null);
        }
        CgSkeleton bone = CgSkeleton.builder().bone("root", null, new Vector3f()).build();
        CgAnimSequence clip = CgAnimSequence.builder("clip", 30, 1).build();
        CgAnimGraph graph = CgAnimGraphCodec.compile(CgAssetData.of("gate:imported", CgBodyKinds.ANIM_GRAPH, tree, parent), null)
                .graph(bone, id -> clip);

        List<CgStateMachine> machines = new ArrayList<>();
        collect(graph.output(), machines);
        for (CgAnimNode saved : graph.saves().values()) collect(saved, machines);

        Map<?, ?> c = (Map<?, ?>) CgAssetYaml.read(Files.readString(Path.of(census), StandardCharsets.UTF_8));
        List<?> expected = (List<?>) c.get("machines");
        assertEquals("machines", expected.size(), machines.size());
        int states = 0, transitions = 0;
        for (Object o : expected) {
            Map<?, ?> em = (Map<?, ?>) o;
            // The format's names are snake_case (BrakingSub is braking_sub); states keep the game's
            String name = ((String) em.get("name")).replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
            CgStateMachine m = machines.stream().filter(x -> x.name().equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError("no machine " + name));
            assertEquals(name + " initial", em.get("initial"), m.states().get(m.initial()).name());
            List<?> es = (List<?>) em.get("states");
            assertEquals(name + " states", es.size(), m.states().size());
            for (int i = 0; i < es.size(); i++) {
                Map<?, ?> s = (Map<?, ?>) es.get(i);
                CgAnimState st = m.states().get(i);
                assertEquals(name + " state " + i, s.get("name"), st.name());
                assertEquals(st.name() + " conduit", s.get("conduit"), st.conduit());
                if (!st.conduit()) assertEquals(st.name() + " reset on entry", s.get("reset_on_entry"), st.resetsOnEntry());
                states++;
            }
            List<?> et = (List<?>) em.get("transitions");
            assertEquals(name + " transitions", et.size(), m.transitions().size());
            for (int i = 0; i < et.size(); i++) {
                Map<?, ?> t = (Map<?, ?>) et.get(i);
                CgAnimTransition tr = m.transitions().get(i);
                String at = name + " transition " + i + " " + tr.from() + " -> " + tr.to();
                assertEquals(at, t.get("from"), tr.from());
                assertEquals(at, t.get("to"), tr.to());
                assertEquals(at + " crossfade", ((Number) t.get("duration")).floatValue(), tr.crossfade(), 0f);
                String blend = ((String) t.get("blend")).replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
                assertEquals(at + " blend", blend, tr.blend().id());
                transitions++;
            }
        }
        assertTrue(states > 0 && transitions > 0);
        System.out.println("[census] " + machines.size() + " machines, " + states + " states, " + transitions + " transitions match");
    }

    /** Every machine under {@code node}, a state's pose included. */
    private static void collect(CgAnimNode node, List<CgStateMachine> out) {
        if (node instanceof CgStateMachineNode m && !out.contains(m.machine())) {
            out.add(m.machine());
            for (CgAnimState s : m.machine().states()) if (s.pose() != null) collect(s.pose(), out);
            return;
        }
        node.forEachChild(child -> collect(child, out));
    }
}
