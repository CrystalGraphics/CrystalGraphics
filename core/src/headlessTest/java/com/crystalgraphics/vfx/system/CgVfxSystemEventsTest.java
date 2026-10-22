package com.crystalgraphics.vfx.system;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A system file's events: handlers resolved onto their sources, written back the same, and what fails by name. */
public class CgVfxSystemEventsTest {

    private static final String EMITTER = "    emitter:\n      properties: { capacity: 40 }\n"
            + "      emitter_update:\n"
            + "        - { id: s0, module: crystalgraphics:emitter_state, inputs: { loop: once, duration: 1 } }\n"
            + "        - { id: s1, module: crystalgraphics:spawn_burst, inputs: { time: 0, count: 20 } }\n";

    private static CgVfxSystemDefinition compile(String yaml) {
        CgAssetData asset = CgAssetData.of("mymod:sparks", CgParticleKinds.SYSTEM, (Map<?, ?>) CgAssetYaml.read(yaml), null);
        return CgVfxSystemAssets.compile(asset, (kind, ref) -> null);
    }

    /** Debris (h1), its chips (h2) spawned on collision, and their glints (h3) spawned as each chip dies. */
    private static final String SPARKS = "_v: 1\nemitters:\n"
            + "  - id: h1\n    name: debris\n" + EMITTER
            + "  - id: h2\n    name: chips\n" + EMITTER
            + "      events:\n"
            + "        - { id: e1, trigger: collision, source: h1, spawn: { emitter: h2, count: { range: [3, 5] }, "
            + "inherit_velocity: [0.3, 0.3, 0.3] }, max_per_frame: 32 }\n"
            + "  - id: h3\n    name: glints\n" + EMITTER
            + "      events:\n"
            + "        - { id: e2, trigger: death, source: h2, spawn: { emitter: h3, count: 2, use_emitter_location: true } }\n";

    @Test
    public void handlersLandOnTheirSourcesSpawningTheResolvedEmitters() {
        CgVfxSystemDefinition d = compile(SPARKS);
        List<CgVfxEmitter> emitters = d.emitters();
        CgVfxEmitter debris = emitters.get(0), chips = emitters.get(1), glints = emitters.get(2);
        CgVfxEvent collision = debris.events().get(0);
        assertEquals(CgVfxEvent.Trigger.COLLISION, collision.trigger());
        assertSame(chips, collision.child());
        assertEquals(3, collision.countMin());
        assertEquals(5, collision.count());
        assertEquals(0.3f, collision.inherit(), 0f);
        assertEquals(32, collision.maxPerFrame());
        CgVfxEvent death = chips.events().get(0);
        assertSame(glints, death.child());
        assertTrue(death.atEmitter());
        assertEquals(CgVfxEvent.MAX_PER_FRAME, death.maxPerFrame());
        assertEquals(0, glints.events().size());
    }

    @Test
    public void aSystemWritesItsEventsAsTheHandlersItRead() {
        CgVfxSystemDefinition d = compile(SPARKS);
        Map<String, Object> tree = CgVfxSystemAssets.write(d);
        CgVfxSystemDefinition again = CgVfxSystemAssets.compile(
                CgAssetData.of("mymod:sparks", CgParticleKinds.SYSTEM, tree, null), (kind, ref) -> null);
        assertEquals(tree, CgVfxSystemAssets.write(again));
        CgVfxEvent collision = again.emitters().get(0).events().get(0);
        assertSame(again.emitters().get(1), collision.child());
        assertEquals(3, collision.countMin());
    }

    @Test
    public void aLoopAKillAndAMissingHandleFailNamingThem() {
        String loop = "_v: 1\nemitters:\n"
                + "  - id: h1\n" + EMITTER + "      events:\n"
                + "        - { id: e1, trigger: death, source: h2, spawn: { emitter: h1 } }\n"
                + "  - id: h2\n" + EMITTER + "      events:\n"
                + "        - { id: e2, trigger: death, source: h1, spawn: { emitter: h2 } }\n";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> compile(loop));
        assertTrue(e.getMessage(), e.getMessage().contains("loop"));
        String kill = "_v: 1\nemitters:\n  - id: h1\n" + EMITTER + "      events:\n"
                + "        - { id: e1, trigger: death, source: h1, spawn: { emitter: h1 }, kill: true }\n";
        e = assertThrows(IllegalArgumentException.class, () -> compile(kill));
        assertTrue(e.getMessage(), e.getMessage().contains("kill"));
        String missing = "_v: 1\nemitters:\n  - id: h1\n" + EMITTER + "      events:\n"
                + "        - { id: e1, trigger: burst, source: h1, spawn: { emitter: h9 } }\n";
        e = assertThrows(IllegalArgumentException.class, () -> compile(missing));
        assertTrue(e.getMessage(), e.getMessage().contains("h9"));
    }
}
