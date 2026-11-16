package com.crystalgraphics.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.net.object.CgNetTestRig;

/** A mod's replicated entity variable: read on the server, its value on the clients that see the entity, a late joiner included. */
public class CgEntityVariablesNetTest {

    /** The mod's own state the server knows, by entity: what its reader reads. */
    private static final Map<Object, Float> KI = new HashMap<>();
    private static final CgEntityVariable KI_VAR = CgEntityVariables.register("netvartest:ki", CgAnimVariable.Type.FLOAT,
            (state, entity) -> KI.getOrDefault(entity, 0f)).replicated();

    private CgNetTestRig rig;

    @Before
    public void setUp() {
        CgEntityVariables.resetForTesting();
        rig = CgNetTestRig.of(1);
        rig.tick(4);
    }

    @After
    public void tearDown() {
        rig.close();
        CgEntityVariables.resetForTesting();
        KI.clear();
    }

    @Test
    public void theServersValueReachesTheClientThatSeesTheEntity() {
        CgNetTestRig.Entity e = rig.entity(0, 64, 0);
        CgEntityState state = new CgEntityState();
        float[] held = CgEntityVariables.hold(e.id());
        KI.put(e, 42f);
        CgEntityVariables.serve(e, state);
        rig.tick(2);
        assertEquals(42f, CgEntityVariables.client(e.id(), KI_VAR), 0f);
        assertEquals("the actor's held array is the one written", 42f, held[KI_VAR.replicatedIndex], 0f);

        KI.put(e, 7.5f);
        CgEntityVariables.serve(e, state);
        rig.tick(2);
        assertEquals(7.5f, CgEntityVariables.client(e.id(), KI_VAR), 0f);

        rig.client(0).disconnect();
        rig.tick(2);
        rig.client(0).connect();
        rig.tick(6);
        assertEquals("a late joiner is sent the value as it stands", 7.5f, CgEntityVariables.client(e.id(), KI_VAR), 0f);
        assertSame(held, CgEntityVariables.hold(e.id()));
        CgEntityVariables.release(e.id());
        CgEntityVariables.release(e.id());
    }

    @Test
    public void namesAreAModsAndOnce() {
        assertThrows(IllegalArgumentException.class, () -> CgEntityVariables.register("ki", CgAnimVariable.Type.FLOAT, (s, e) -> 0f));
        assertThrows(IllegalArgumentException.class, () -> CgEntityVariables.register("netvartest:ki", CgAnimVariable.Type.FLOAT, (s, e) -> 0f));
        assertThrows("the engine's are read on each side", IllegalArgumentException.class, CgEntityVariables.SPEED::replicated);
    }
}
