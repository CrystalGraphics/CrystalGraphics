package com.crystalgraphics.cue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.frame.CgWorldSubsystem;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.tag.CgGameplayTag;

/**
 * Transport controls over the last burst cue this client played, for a mod's dev tools: pause it, step it a frame
 * either way, fire it again, and the lines a dev HUD shows of it. Unreal's Niagara debug HUD and Sequencer's
 * transport, on a cue.
 *
 * <pre>{@code
 * CgCueInspector inspector = CgCueInspector.client();
 *
 * // dev keys, on each press and each held repeat
 * inspector.setPaused(!inspector.paused());
 * inspector.step(1);                          // a frame forward, simulated
 * inspector.step(-1);                         // a frame back, sought: the frame play showed, the same particles
 * inspector.refire();                         // the last cue again, where it last played
 * inspector.fire(MYMOD_CUE_HIT_HEAVY);        // another, at that same point
 * inspector.setGrades(!inspector.gradesEnabled());
 * }</pre>
 *
 * <pre>{@code
 * // a dev HUD, under its own lines, each frame
 * for (String line : inspector.lines(CgCueInspector.Keys.DEFAULT)) draw(line);
 * for (String line : inspector.lines(new CgCueInspector.Keys("[ ]", "R", "K", ", .", "X"))) draw(line);   // its own keys
 * }</pre>
 *
 * <ul>
 *   <li>Client only, on the render thread. It follows a burst played here, a server's included, and no looping cue.</li>
 *   <li>Pausing sets the client world's time dilation to 0, and unpausing puts back what it was. A speed set while
 *       paused is overwritten each frame: set it after unpausing.</li>
 *   <li>The frames run from 0 to the duration less a frame and round again: forward from the last comes to 0, from a
 *       cue played out to its first frame; back from 0 or the end goes to the duration less a frame. Steps forward
 *       queue and are simulated one a frame; steps back seek at once, every one keeping the cue's seeds.</li>
 *   <li>Only a fire draws new seeds. {@link #lines} hands back one list, reused at its next call.</li>
 * </ul>
 */
public final class CgCueInspector extends CgWorldSubsystem {

    /** A step, in the cue's seconds. */
    public static final double STEP = 1.0 / 60.0;
    private static final double EPSILON = 1e-6;

    /**
     * The keys a mod binds, as {@link #lines} names them.
     *
     * @param cue    choosing what {@link #fire} plays
     * @param fire   {@link #refire}
     * @param pause  {@link #setPaused}
     * @param step   {@link #step}, back and forward
     * @param grades {@link #setGrades}
     */
    public record Keys(String cue, String fire, String pause, String step, String grades) {
        /** N/B, R, P, {@code , .} and X: the GL debug harness's. */
        public static final Keys DEFAULT = new Keys("N/B", "R", "P", ", .", "X");
    }

    private final List<CgSequencePlayer> players = new ArrayList<>();
    private final CgCueParameters at = new CgCueParameters();
    private final List<String> lines = new ArrayList<>(4);
    @Nullable
    private CgGameplayTag current;
    private CgTickFunction begin;
    private boolean paused;
    private float resume = 1f;
    /** Steps forward asked for and not yet simulated. */
    private int steps;

    /** The client world's, made at its first ask. */
    public static CgCueInspector client() {
        CgWorld world = CgWorld.client();
        synchronized (world) {
            CgCueInspector i = world.subsystem(CgCueInspector.class);
            return i != null ? i : world.add(new CgCueInspector());
        }
    }

    @Override
    protected void initialize() {
        // Before PLAYS, SEQUENCES and SCALABILITY read the frame's dilation
        begin = world().schedule().add(CgTickGroup.BEGIN, "crystalgraphics:cue.inspector", this::begin).whilePaused(true);
        CgCueManager.of(world()).inspector = this;
    }

    @Override
    protected void deinitialize() {
        begin.close();
        CgCueManager manager = world().subsystem(CgCueManager.class);
        if (manager != null && manager.inspector == this) manager.inspector = null;
        if (paused) world().timeDilation(resume);
    }

    /** While paused, a frame of a queued step advances the world by exactly {@link #STEP}, and every other none. */
    private void begin(float dt) {
        if (!paused) return;
        boolean stepping = steps > 0 && dt > 0f;
        if (stepping) steps--;
        world().timeDilation(stepping ? (float) (STEP / dt) : 0f);
    }

    /** A burst starting here: the cue followed from now on. */
    void started(CgGameplayTag tag, CgCueParameters params) {
        current = tag;
        at.set(params);
        players.clear();
        steps = 0;
    }

    /** A sequence the burst just started plays. */
    void played(@Nullable CgSequencePlayer player) {
        if (player != null && current != null) players.add(player);
    }

    /** The cue followed, the last burst played here; null before any. */
    @Nullable
    public CgGameplayTag current() {
        return current;
    }

    /** Its length in seconds, its longest sequence's; 0 before any, or for one that plays no sequence. */
    public double duration() {
        double d = 0;
        for (int i = 0; i < players.size(); i++) {
            CgSequenceDefinition s = players.get(i).definition();
            d = Math.max(d, s.seconds(s.end() - s.start()));
        }
        return d;
    }

    /** Its seconds since it started, held at {@link #duration} once it plays out. */
    public double age() {
        double age = 0;
        for (int i = 0; i < players.size(); i++) age = Math.max(age, players.get(i).seconds());
        return Math.min(age, duration());
    }

    public boolean paused() {
        return paused;
    }

    /** Holds the client world's clock at 0, or puts back the speed it had. */
    public CgCueInspector setPaused(boolean pause) {
        if (pause == paused) return this;
        paused = pause;
        steps = 0;
        if (pause) {
            resume = world().timeDilation();
            world().timeDilation(0f);
        } else {
            world().timeDilation(resume);
        }
        return this;
    }

    /**
     * Pauses and steps {@code frames} frames: forward when positive, simulated one a frame; back when negative, sought
     * at once. Round from either end, as the class says.
     */
    public CgCueInspector step(int frames) {
        double d = duration();
        if (frames == 0 || d <= 0) return this;
        setPaused(true);
        for (int n = Math.abs(frames); n > 0; n--) {
            double from = age() + steps * STEP;
            if (frames > 0) {
                if (from >= d - EPSILON) seek(STEP);
                else if (from >= d - STEP - EPSILON) seek(0);
                else steps++;
            } else {
                seek(from >= d - EPSILON || from <= EPSILON ? d - STEP : from - STEP);
            }
        }
        return this;
    }

    /** Each of its sequences at {@code seconds}, as a scrub lands: its systems drawn there this frame, seeds kept. */
    private void seek(double seconds) {
        steps = 0;
        for (int i = 0; i < players.size(); i++) players.get(i).seek(seconds);
    }

    /** The cue followed again, from its start where it last played, with new seeds; nothing before any. */
    public CgCueInspector refire() {
        if (current != null) fire(current);
        return this;
    }

    /** {@code tag}'s burst where the last cue played (the world's origin before any), ending that one at once. */
    public CgCueInspector fire(CgGameplayTag tag) {
        for (int i = 0; i < players.size(); i++) players.get(i).kill();
        players.clear();
        steps = 0;
        CgCueManager.of(world()).execute(tag, at);
        return this;
    }

    /** Whether each kit's draws are graded under its own look ({@link CgWorldRenderer#gradeDraws()}). */
    public boolean gradesEnabled() {
        return CgWorldRenderer.get().gradeDraws();
    }

    public CgCueInspector setGrades(boolean on) {
        CgWorldRenderer.get().gradeDraws(on);
        return this;
    }

    /**
     * Four lines in the HUD's {@code Name [key]: value} form, {@code keys} naming what a mod bound: the cue, the pause
     * and step, its age, its grades. One list, reused at the next call.
     */
    public List<String> lines(Keys keys) {
        double d = duration();
        lines.clear();
        lines.add("Cue [" + keys.cue() + "]: " + (current == null ? "none" : current.name()) + " | Fire [" + keys.fire() + "]");
        lines.add("Pause [" + keys.pause() + "]: " + (paused ? "paused" : "playing") + " | Step [" + keys.step() + "]");
        lines.add(String.format(Locale.ROOT, "Age: %.3f / %.3f s", age(), d));
        lines.add("Kit grades [" + keys.grades() + "]: " + (gradesEnabled() ? "on" : "off"));
        return lines;
    }
}
