package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.service.CgModLoader;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The undeclared-access check, Unreal's RDG validation ({@code bAllowRHIAccess}): while a step executes, the graph
 * textures and buffers it recorded (its declarations, and what its draws named when added) may be bound, and no other.
 * A binding that resolves one outside them is refused. On in a dev run, the harness and tests; off in a shipped jar.
 *
 * <pre>
 * -Dcrystalgraphics.graph.check=throw   // refuse: the harness, tests, a dev client
 * -Dcrystalgraphics.graph.check=log     // name each pass and resource once, and go on
 * -Dcrystalgraphics.graph.check=off
 * </pre>
 *
 * <ul>
 *   <li>A binding asks, as it resolves a graph texture ({@code rhiView()}) or buffer ({@code boundId()}). The
 *       executor's own resolution (barriers, copies, storage) is not a binding and is not asked.</li>
 * </ul>
 */
final class CgGraphCheck {

    private static final Logger LOGGER = LogManager.getLogger("CgGraphCheck");
    private static final CgVar.Text MODE = CgVar.text("crystalgraphics.graph.check", "", CgVar.DEV | CgVar.RESTART,
            "A pass binding a graph texture or buffer it did not record is refused: throw, log, or off; a dev run throws");
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private static volatile int state = -1;
    /** The step executing, named in a refusal; render thread. */
    private static CgPass executing;

    private CgGraphCheck() {}

    /** Whether bindings are checked. */
    static boolean on() {
        return mode() != 0;
    }

    /** Refuses, or logs once, a binding of {@code texture} its step did not record. */
    static void bound(CgGraphTexture texture) {
        if (texture.resource().allowed == 0) refuse(texture);
    }

    /** Refuses, or logs once, a binding of {@code buffer} its step did not record. */
    static void bound(CgGraphBuffer buffer) {
        if (buffer.resource().allowed == 0) refuse(buffer);
    }

    /** Allows step {@code s}'s recorded accesses to be bound ({@code by} 1), or ends it (-1). Render thread. */
    static CgPass allow(CgFrame frame, int s, int by, CgPass before) {
        for (int i = frame.accessFrom[s]; i < frame.accessFrom[s + 1]; i++) {
            CgGraphResource.resourceOf(frame.accessView[i]).allowed += by;
        }
        CgPass was = executing;
        executing = by > 0 ? frame.steps[s] : before;
        return was;
    }

    private static void refuse(CgGraphResource resource) {
        String what = (executing == null ? "a binding outside any step" : executing.toString()) + " binds " + resource
                + ", which it neither declares nor names in a draw: reads(...) it";
        if (mode() == 2) throw new IllegalStateException(what);
        if (LOGGED.add(what)) LOGGER.warn("undeclared: {}", what);
    }

    private static int mode() {
        int s = state;
        if (s < 0) {
            String m = MODE.get().isEmpty() ? (CgModLoader.development() ? "throw" : "off") : MODE.get();
            state = s = m.equals("throw") ? 2 : m.equals("log") ? 1 : 0;
        }
        return s;
    }
}
