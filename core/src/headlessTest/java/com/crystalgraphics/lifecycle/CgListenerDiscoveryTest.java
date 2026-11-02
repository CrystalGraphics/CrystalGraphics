package com.crystalgraphics.lifecycle;

import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class CgListenerDiscoveryTest {

    public static final class Singleton implements CgLifecycleListener {
        public static final Singleton INSTANCE = new Singleton();
    }

    public static final class Constructed implements CgLifecycleListener {
    }

    public static final class NotAListener {
    }

    @Test
    public void aFileIsSideAndClassALineCommentsAndBadLinesSkipped() {
        List<CgListenerDiscovery.Entry> entries = CgListenerDiscovery.parse("""
                # a comment
                common a.B
                client c.D   # trailing

                server e.F
                lonely
                """, "test");
        assertEquals(2, entries.size());
        assertEquals(CgListenerDiscovery.Side.COMMON, entries.get(0).side());
        assertEquals("a.B", entries.get(0).className());
        assertEquals(CgListenerDiscovery.Side.CLIENT, entries.get(1).side());
        assertEquals("c.D", entries.get(1).className());
    }

    @Test
    public void anInstanceFieldIsUsedAsItIsElseTheConstructor() {
        assertSame(Singleton.INSTANCE, make(Singleton.class));
        CgLifecycleListener a = make(Constructed.class), b = make(Constructed.class);
        assertNotNull(a);
        assertNotSame(a, b);
    }

    @Test
    public void aClassThatIsNoListenerOrDoesNotLoadIsSkipped() {
        assertNull(make(NotAListener.class));
        assertNull(CgListenerDiscovery.instantiate(new CgListenerDiscovery.Entry(CgListenerDiscovery.Side.COMMON,
                "no.such.Listener", "test")));
    }

    private static CgLifecycleListener make(Class<?> type) {
        return CgListenerDiscovery.instantiate(new CgListenerDiscovery.Entry(CgListenerDiscovery.Side.COMMON,
                type.getName(), "test"));
    }
}
