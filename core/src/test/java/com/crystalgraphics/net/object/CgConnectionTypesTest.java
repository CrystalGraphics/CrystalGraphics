package com.crystalgraphics.net.object;

import org.junit.After;
import org.junit.Test;

import com.crystalgraphics.asset.tree.CgAssetException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A type declared for one connection: what a server's {@code net_type} file is on a client without its mod. */
public class CgConnectionTypesTest {

    /** Two servers' versions of one type, here under two names, as two servers would each name it {@code shop:pet}. */
    static final CgNetField<Integer> A = CgNetField.ofInt("a"), B = CgNetField.ofInt("b"), C = CgNetField.ofInt("c");
    static final CgNetType<CgNetObject> ONE = CgNetType.define("test:connection/one").fields(A, B).build();
    static final CgNetType<CgNetObject> TWO = CgNetType.define("test:connection/two").fields(A, C).build();

    private CgNetTestRig rig;

    @After
    public void tearDown() {
        if (rig != null) rig.close();
    }

    private static int intOf(CgNetObject obj, String field) {
        @SuppressWarnings("unchecked") CgNetField<Integer> f = (CgNetField<Integer>) obj.type().field(field);
        return obj.getInt(f);
    }

    @Test
    public void twoServersTypesOfOneNameEachReadRightAndNothingIsLeftBehind() {
        rig = CgNetTestRig.of(1);
        CgNetTestRig.Client client = rig.client(0);
        client.readsAs("test:connection/one", "shop:pet");
        CgNetTypeCompiler.declareFor(client.connection(), "shop:pet", "fields:\n  a: { kind: int }\n  b: { kind: int }\n");
        rig.tick(3);
        ONE.spawn(CgAnchor.global()).setInt(A, 1).setInt(B, 2).start();
        rig.tick(3);
        assertTrue("the client reads it as its own connection's type, not the process's", client.visible(ONE).isEmpty());
        CgNetObject seen = client.visible("shop:pet").get(0);
        assertEquals("shop:pet", seen.type().name());
        assertEquals(1, intOf(seen, "a"));
        assertEquals(2, intOf(seen, "b"));

        // the second server: the same name, other fields
        client.disconnect();
        rig.tick(2);
        client.connect();
        client.readsAs("test:connection/two", "shop:pet");
        CgNetTypeCompiler.declareFor(client.connection(), "shop:pet", "fields:\n  a: { kind: int }\n  c: { kind: int }\n");
        rig.tick(3);
        TWO.spawn(CgAnchor.global()).setInt(A, 5).setInt(C, 7).start();
        rig.tick(3);
        CgNetObject second = client.visible("shop:pet").get(0);
        assertEquals(5, intOf(second, "a"));
        assertEquals(7, intOf(second, "c"));
        assertTrue("its own type, not the first connection's", second.type() != seen.type());
        assertNull("nothing reached the process's registry", CgNetType.named("shop:pet"));
    }

    @Test
    public void aTypeOfANameTheProcessDeclaresIsRefused() {
        rig = CgNetTestRig.of(1);
        try {
            CgNetTypeCompiler.declareFor(rig.client(0).connection(), "test:connection/one", "fields:\n  a: { kind: int }\n");
            fail("declared over the process's type");
        } catch (CgAssetException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("declared by this process"));
        }
    }
}
