package com.crystalgraphics.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetTestRig;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.tag.CgTagCounts;

/** A stored entity field: a mod's per-holder data set on the server, read through its binding on each side, a late joiner included. */
public class CgEntityNetFieldTest {

    private static final CgNetField<Float> CURRENT = CgNetField.ofFloat("current");
    private static final CgNetField<Boolean> CHARGING = CgNetField.ofBool("charging");
    private static final CgNetType MANA = CgNetType.define("netfieldtest:mana").fields(CURRENT, CHARGING).perHolder().build();
    private static final CgEntityNetField KI = CgEntityNetField.of(MANA, CURRENT);
    private static final CgEntityNetField CHARGE = (CgEntityNetField) CgEntityFields.binding("netfieldtest:mana#charging");

    private CgNetTestRig rig;

    @Before
    public void setUp() {
        rig = CgNetTestRig.of(1);
        rig.tick(4);
    }

    @After
    public void tearDown() {
        rig.close();
    }

    /** The entity as one side's reader sees it. */
    private static CgEntityFields.Source side(Object serverEntity, int id) {
        CgEntityState state = new CgEntityState();
        CgTagCounts tags = new CgTagCounts();
        return new CgEntityFields.Source() {
            @Override public CgEntityState state() { return state; }
            @Override public CgTagCounts tags() { return tags; }
            @Override public Object entity() { return serverEntity; }
            @Override public int id() { return id; }
            @Override public boolean onServer() { return serverEntity != null; }
        };
    }

    private static float read(CgEntityNetField field, CgEntityFields.Source source) {
        CgNetObject o = field.find(source);
        return field.read(o);
    }

    @Test
    public void theServersValueIsReadOnBothSides() {
        CgNetTestRig.Entity e = rig.entity(0, 64, 0);
        CgEntityFields.Source server = side(e, e.id()), client = side(null, e.id());
        rig.client(0).local();
        assertNull("nothing made by reading", KI.find(server));
        assertEquals(0f, read(KI, client), 0f);

        MANA.of(e).setFloat(CURRENT, 42f);
        MANA.of(e).setBool(CHARGING, true);
        rig.tick(2);
        assertEquals(42f, read(KI, server), 0f);
        assertEquals(42f, read(KI, client), 0f);
        assertEquals("a bool reads 1", 1f, read(CHARGE, client), 0f);

        MANA.of(e).setFloat(CURRENT, 7.5f);
        rig.tick(2);
        assertEquals(7.5f, read(KI, client), 0f);

        rig.client(0).disconnect();
        rig.tick(2);
        rig.client(0).connect();
        rig.tick(6);
        assertEquals("a late joiner is sent the value as it stands", 7.5f, read(KI, client), 0f);
    }
}
