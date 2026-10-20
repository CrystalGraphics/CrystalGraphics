package com.crystalgraphics.tag;

import com.crystalgraphics.serialization.CgBinaryFormat;
import com.crystalgraphics.serialization.CgPlainOps;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;

public class CgTagsTest {

    private final CgTagRegistry registry = new CgTagRegistry();

    private CgTagContainer of(String... names) {
        CgTagContainer out = new CgTagContainer();
        for (String name : names) out.add(registry.register(name));
        return out;
    }

    private static void rejects(Runnable body) {
        try {
            body.run();
            fail("expected CgTagException");
        } catch (CgTagException expected) {
        }
    }

    @Test
    public void aTagMatchesItselfAndItsAncestorsNeverTheReverse() {
        CgGameplayTag fire = registry.register("mod:cue.beam.fire");
        CgGameplayTag beam = registry.require("mod:cue.beam");
        CgGameplayTag cue = registry.require("mod:cue");
        assertTrue(fire.matches(fire));
        assertTrue(fire.matches(beam));
        assertTrue(fire.matches(cue));
        assertFalse(beam.matches(fire));
        assertFalse(cue.matches(beam));
        assertFalse(fire.matches(registry.register("mod:cue.blast")));
        assertEquals(3, fire.depth());
        assertSame(beam, fire.parent());
        assertFalse(beam.explicit());
        registry.register("mod:cue.beam");
        assertTrue(beam.explicit());
    }

    @Test
    public void namesAreInternedWithoutCase() {
        CgGameplayTag a = registry.register("mod:Cue.Fire");
        assertSame(a, registry.register("mod:cue.fire"));
        assertSame(a, registry.find("MOD:CUE.FIRE"));
        assertEquals("mod:Cue.Fire", a.name());
        assertEquals("mod", a.namespace());
    }

    @Test
    public void malformedAndUnknownTagsFailByName() {
        rejects(() -> registry.register("no_namespace"));
        rejects(() -> registry.register("mod:a..b"));
        rejects(() -> registry.register("mod:a.b."));
        rejects(() -> registry.register("Mod:a"));
        rejects(() -> registry.register("mod:a b"));
        try {
            registry.require("mod:never.declared");
            fail();
        } catch (CgTagException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("mod:never.declared"));
        }
        assertNull(registry.find("mod:never.declared"));
    }

    @Test
    public void containerParentsAreMatchedExplicitOnesAreExact() {
        CgTagContainer owned = of("mod:cue.beam.fire", "mod:state.charging");
        CgGameplayTag beam = registry.require("mod:cue.beam");
        assertTrue(owned.has(beam));
        assertFalse(owned.hasExact(beam));
        assertTrue(owned.hasExact(registry.require("mod:cue.beam.fire")));
        assertFalse(owned.has(registry.register("mod:cue.blast")));
        assertFalse(owned.has(null));
        assertEquals(2, owned.size());
    }

    @Test
    public void hasAnyAndHasAllFollowUnrealsEmptyRules() {
        CgTagContainer owned = of("mod:a.b", "mod:c.d");
        CgTagContainer parents = of("mod:a", "mod:c");
        CgTagContainer partly = of("mod:a", "mod:z");
        CgTagContainer none = new CgTagContainer();
        assertTrue(owned.hasAny(parents));
        assertTrue(owned.hasAll(parents));
        assertTrue(owned.hasAny(partly));
        assertFalse(owned.hasAll(partly));
        assertFalse(parents.hasAny(owned));
        assertFalse(parents.hasAll(owned));
        assertFalse(owned.hasAny(none));
        assertTrue(owned.hasAll(none));
        assertFalse(owned.hasAnyExact(parents));
        assertTrue(owned.hasAllExact(of("mod:a.b")));
        assertTrue(owned.hasAllExact(none));
    }

    @Test
    public void removingATagKeepsAnAncestorAnotherProvides() {
        CgTagContainer owned = of("mod:a.b", "mod:a.c");
        CgGameplayTag a = registry.require("mod:a");
        assertTrue(owned.remove(registry.require("mod:a.b")));
        assertTrue(owned.has(a));
        assertTrue(owned.remove(registry.require("mod:a.c")));
        assertFalse(owned.has(a));
        assertFalse(owned.remove(registry.require("mod:a.c")));
        assertTrue(owned.isEmpty());
    }

    @Test
    public void containersSpanMoreThanOneWordAndCompareByTheirTags() {
        CgTagContainer low = new CgTagContainer();
        CgTagContainer high = new CgTagContainer();
        CgGameplayTag last = null;
        for (int i = 0; i < 150; i++) last = registry.register("mod:t" + i);
        low.add(registry.require("mod:t0"));
        high.add(last);
        assertTrue(high.has(last));
        assertFalse(low.has(last));
        assertFalse(high.has(registry.require("mod:t0")));
        low.add(last);
        assertTrue(low.hasAll(high));
        assertFalse(high.hasAll(low));
        assertNotEquals(low, high);
        CgTagContainer copy = low.copy();
        assertEquals(low, copy);
        assertEquals(low.hashCode(), copy.hashCode());
        copy.remove(last);
        assertNotEquals(low, copy);
        assertEquals(Arrays.asList(registry.require("mod:t0"), last), low.tags());
    }

    @Test
    public void aContainerRefusesAnotherRegistrysTag() {
        CgTagContainer owned = of("mod:a");
        CgTagRegistry other = new CgTagRegistry();
        rejects(() -> owned.add(other.register("mod:a")));
        assertFalse(owned.has(other.register("mod:a")));
    }

    @Test
    public void aTagsFileDeclaresItsTagsAndAncestors() {
        registry.registerFile("novamod", "_v: 1\ntags: [novamod:cue.nova_cannon, novamod:cue.nova_cannon.impact]\n",
                "tags.yaml");
        assertTrue(registry.require("novamod:cue.nova_cannon.impact").matches(registry.require("novamod:cue")));
        assertEquals(3, registry.size());
        assertTrue(registry.register("kakarot_kit:cue.C001A_S_SKL001.beam").matches(registry.require("kakarot_kit:cue")));
    }

    @Test
    public void aBadTagsFileFailsNamingTheFileAndTheTag() {
        rejects(() -> registry.registerFile("mod", "_v: 1\ntags: [other:cue.x]\n", "assets/mod/tags.yaml"));
        rejects(() -> registry.registerFile("mod", "_v: 2\ntags: [mod:a]\n", "f"));
        rejects(() -> registry.registerFile("mod", "_v: 1\ntags: [mod:a b]\n", "f"));
        rejects(() -> registry.registerFile("mod", "_v: 1\ntags: mod:a\n", "f"));
        rejects(() -> registry.registerFile("mod", "_v: 1\ntag: [mod:a]\n", "f"));
        try {
            registry.registerFile("mod", "_v: 1\ntags: [mod:ok, other:cue.x]\n", "assets/mod/tags.yaml");
            fail();
        } catch (CgTagException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("assets/mod/tags.yaml"));
            assertTrue(e.getMessage(), e.getMessage().contains("other:cue.x"));
        }
        assertNull("a failed file declares nothing", registry.find("mod:ok"));
    }

    @Test
    public void theTableIsSortedSoTheSameSetsAgreeInAnyRegistrationOrder() {
        CgTagRegistry other = new CgTagRegistry();
        for (String n : new String[]{"m:b.x", "m:a", "m:c"}) registry.register(n);
        for (String n : new String[]{"m:c", "m:a", "m:b.x"}) other.register(n);
        assertEquals(registry.table(), other.table());
        assertEquals(registry.table().hash(), other.table().hash());
        assertEquals(Arrays.asList("m:a", "m:b", "m:b.x", "m:c"), registry.table().names());
        registry.register("m:d");
        assertNotEquals(registry.table().hash(), other.table().hash());
    }

    @Test
    public void aTableRoundTripsAndACorruptHashFails() {
        registry.register("m:a.b");
        registry.register("m:c");
        CgTagTable table = registry.table();
        Object tree = CgTagTable.CODEC.encode(CgPlainOps.INSTANCE, table);
        Object wire = CgBinaryFormat.decode(CgBinaryFormat.encode(tree));
        CgTagTable back = CgTagTable.CODEC.decode(CgPlainOps.INSTANCE, wire);
        assertEquals(table, back);
        assertEquals(table.hash(), back.hash());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) wire;
        map.put("h", table.hash() ^ 1);
        rejects(() -> CgTagTable.CODEC.decode(CgPlainOps.INSTANCE, map));
    }

    @Test
    public void aClientWithAnotherModSetMapsTheServersIndices() {
        CgTagRegistry server = new CgTagRegistry();
        for (String n : new String[]{"a:one", "a:two.x", "b:three", "b:four"}) server.register(n);
        CgTagRegistry clientRegistry = new CgTagRegistry();
        CgGameplayTag two = clientRegistry.register("a:two.x");
        CgGameplayTag one = clientRegistry.register("a:one");
        CgGameplayTag mine = clientRegistry.register("c:only_here");

        CgTagLink toClient = CgTagLink.server(server);
        CgTagLink client = CgTagLink.client(clientRegistry);
        client.agree(toClient.table());

        int wireTwo = toClient.toWire(server.require("a:two.x"));
        assertSame(two, client.fromWire(wireTwo));
        assertSame(one, client.fromWire(toClient.toWire(server.require("a:one"))));
        assertNull("a tag only the server has", client.fromWire(toClient.toWire(server.require("b:four"))));
        assertSame(two, client.fromWire(client.toWire(two)));
        rejects(() -> client.toWire(mine));
        assertNull(client.fromWire(client.invalid()));
        rejects(() -> client.fromWire(client.invalid() + 1));
        rejects(() -> client.fromWire(-1));
        assertTrue("every index and the invalid one fit", (1 << client.bits()) > client.invalid());
    }

    @Test
    public void anIndexBeforeTheTableIsAgreedFailsNeverAliases() {
        registry.register("m:a");
        registry.register("m:b");
        CgTagLink client = CgTagLink.client(registry);
        assertFalse(client.agreed());
        rejects(() -> client.fromWire(0));
        rejects(() -> client.toWire(registry.require("m:a")));
        client.agree(registry.table());
        assertSame(registry.require("m:b"), client.fromWire(1));
    }

    @Test
    public void theInvalidIndexAlwaysFitsItsBits() {
        for (int n = 0; n <= 70; n++) {
            CgTagRegistry r = new CgTagRegistry();
            for (int i = 0; i < n; i++) r.register("m:t" + i);
            CgTagLink link = CgTagLink.server(r);
            assertTrue("n=" + n, link.invalid() < (1L << Math.max(link.bits(), 0)) || link.invalid() == 0);
            assertEquals(n, link.invalid());
        }
    }

    @Test
    public void aLateTagIsNotOnTheAgreedTable() {
        registry.register("m:a");
        CgTagLink link = CgTagLink.server(registry);
        CgGameplayTag late = registry.register("m:late");
        rejects(() -> link.toWire(late));
        assertEquals(Collections.singletonList("m:a"), link.table().names());
    }
}
