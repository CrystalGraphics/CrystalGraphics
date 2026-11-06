package com.crystalgraphics.platform.input;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A point where a host's input reaches code that is not the host's. A mod registers a {@link CgInputListener} on a
 * stage; the host fires the stage for each event, and a listener that consumes it keeps everything after it, the game
 * included, from seeing it. The input counterpart of {@code CgRenderStage}.
 *
 * <pre>{@code
 * // A mod: take what lands on its panel, from whatever screen is in front
 * CgInputStage.Registration taking = CgInputStage.SCREEN.register(0, listener);
 * taking.close();   // stops it
 *
 * // A stage of your own, fired from a hook of your own
 * CgInputStage RADIAL = CgInputStage.define("mymod:radial");
 * if (RADIAL.mouseButton(event)) return;   // a listener took it
 * }</pre>
 *
 * <ul>
 *   <li>{@link #SCREEN}: an event heading for the screen in front, before the screen sees it.</li>
 *   <li>Lower {@code order} first, ties in registration order. The first listener to consume stops the event; a move
 *       reaches all of them.</li>
 *   <li>A listener that throws is logged and counts as not having consumed.</li>
 *   <li>Fire on the thread the host delivers input on. A stage with no listener costs one array read.</li>
 * </ul>
 */
public final class CgInputStage {

    /** What only the host can answer. One class per era, {@code InputHost<Era>}. */
    public interface Host {

        /** Whether the game holds the cursor, so a pointer event is not over anything. */
        boolean pointerGrabbed();

        Host NONE = new Host() {
            @Override public boolean pointerGrabbed() { return false; }
        };
    }

    /** The slot a host fills, on a client. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:input_host", Host.NONE);

    /** A listener's place on a stage; {@link #close} removes it. */
    public interface Registration extends AutoCloseable { @Override void close();}

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    private static final Map<String, CgInputStage> DEFINED = new LinkedHashMap<>();
    // Before SCREEN: a static initialiser runs in order, and a stage built earlier would read this as null.
    private static final Entry[] NONE = new Entry[0];

    /** The screen in front, offered each event before it acts. */
    public static final CgInputStage SCREEN = define("crystalgraphics:screen");

    /** Defines a stage of your own. An id is {@code namespace:path}, defined once; a second definition throws. */
    public static CgInputStage define(String id) {
        synchronized (DEFINED) {
            if (DEFINED.containsKey(id)) throw new IllegalStateException("input stage already defined: " + id);
            CgInputStage stage = new CgInputStage(id);
            DEFINED.put(id, stage);
            return stage;
        }
    }

    private record Entry(int order, CgInputListener listener) {
    }

    private final String id;
    private volatile Entry[] entries = NONE;

    private CgInputStage(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Adds {@code listener} at {@code order}; lower runs first. */
    public Registration register(int order, CgInputListener listener) {
        Entry entry = new Entry(order, listener);
        synchronized (this) {
            Entry[] next = Arrays.copyOf(entries, entries.length + 1);
            int at = next.length - 1;
            while (at > 0 && next[at - 1].order > order) {
                next[at] = next[at - 1];
                at--;
            }
            next[at] = entry;
            entries = next;
        }
        return () -> {
            synchronized (this) {
                Entry[] now = entries;
                int index = -1;
                for (int i = 0; i < now.length; i++) if (now[i] == entry) index = i;
                if (index < 0) return;
                Entry[] next = new Entry[now.length - 1];
                System.arraycopy(now, 0, next, 0, index);
                System.arraycopy(now, index + 1, next, index, now.length - index - 1);
                entries = next;
            }
        };
    }

    /** Whether any listener is registered: a host that must do work to build an event can skip it. */
    public boolean listening() {
        return entries.length != 0;
    }

    /** Whether any listener {@link CgInputListener#wants() wants} input now: what a host asks before producing it. */
    public boolean wanted() {
        for (Entry entry : entries) {
            try {
                if (entry.listener.wants()) return true;
            } catch (RuntimeException | LinkageError failed) {
                LOGGER.error("[{}] an input listener failed on wants", id, failed);
            }
        }
        return false;
    }

    // -- Fired by the host. Each answers whether a listener consumed the event. --------------------------------------

    /** Delivered to every listener; returns whether any consumed it, which a host may ignore. */
    public boolean mouseMoved(CgSystemInput.Mouse.Event event) {
        boolean taken = false;
        for (Entry entry : entries) {
            try {
                taken |= entry.listener.mouseMoved(event);
            } catch (RuntimeException | LinkageError failed) {
                LOGGER.error("[{}] an input listener failed on a move", id, failed);
            }
        }
        return taken;
    }

    public boolean mouseButton(CgSystemInput.Mouse.Event event) {
        for (Entry entry : entries) {
            try {
                if (entry.listener.mouseButton(event)) return true;
            } catch (RuntimeException | LinkageError failed) {
                LOGGER.error("[{}] an input listener failed on a button", id, failed);
            }
        }
        return false;
    }

    public boolean scrolled(CgSystemInput.Mouse.Event event) {
        for (Entry entry : entries) {
            try {
                if (entry.listener.scrolled(event)) return true;
            } catch (RuntimeException | LinkageError failed) {
                LOGGER.error("[{}] an input listener failed on a scroll", id, failed);
            }
        }
        return false;
    }

    public boolean key(CgSystemInput.Keyboard.Event event) {
        for (Entry entry : entries) {
            try {
                if (entry.listener.key(event)) return true;
            } catch (RuntimeException | LinkageError failed) {
                LOGGER.error("[{}] an input listener failed on a key", id, failed);
            }
        }
        return false;
    }

    public boolean character(CgSystemInput.Keyboard.Event event) {
        for (Entry entry : entries) {
            try {
                if (entry.listener.character(event)) return true;
            } catch (RuntimeException | LinkageError failed) {
                LOGGER.error("[{}] an input listener failed on a character", id, failed);
            }
        }
        return false;
    }

    public boolean composition(String text, int caret) {
        for (Entry entry : entries) {
            try {
                if (entry.listener.composition(text, caret)) return true;
            } catch (RuntimeException | LinkageError failed) {
                LOGGER.error("[{}] an input listener failed on a composition", id, failed);
            }
        }
        return false;
    }

    // -- Answered by the host --------------------------------------------------------------------------------------

    /** Whether the game holds the cursor. */
    public static boolean pointerGrabbed() {
        return CgPlatform.get(HOST).pointerGrabbed();
    }
}
