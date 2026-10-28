package com.crystalgraphics.frame;

import java.util.Locale;

/**
 * The fixed, ordered stages of a world, after Unreal's {@code ETickingGroup}: four that run each game tick, at the
 * host's 20 Hz ({@link #NET_IN} to {@link #NET_OUT}, {@link CgWorld#tick}), and eleven that run each frame
 * ({@link #BEGIN} to {@link #SUBMIT}). A tick function is added to one ({@link CgTickSchedule#add}); a mod adds
 * functions, never groups.
 *
 * <pre>{@code
 * world.schedule().add(CgTickGroup.SEQUENCES, "mymod:cutscene", dt -> cutscene.evaluate(dt));
 * world.schedule().add(CgTickGroup.SUBMIT, "mymod:hud-world", dt -> hud.draw(world.view()));
 * CgWorld.serverWorld().schedule().add(CgTickGroup.GAMEPLAY, "mymod:states", dt -> states.tick(dt)).onServer(true);
 * }</pre>
 *
 * <ul>
 *   <li>A dedicated server runs only the tick groups; no frame group runs there.</li>
 *   <li>Keep a function's prerequisites on its own side: one after a frame function moves into that frame group.</li>
 *   <li>Everything before {@link #SUBMIT} touches no GPU state; {@code SUBMIT} hands draws to the renderers.</li>
 *   <li>A function runs in its group unless a prerequisite sits in a later one: then it runs there, after it
 *       ({@link CgTickFunction#after}).</li>
 * </ul>
 */
public enum CgTickGroup {
    /** Messages and networked objects received. A tick group. */
    NET_IN,
    /** Gameplay's states: character clocks advance or freeze, hitboxes, claims, prediction. A tick group. */
    GAMEPLAY,
    /** The server's say: authoritative effects, headless sequence evaluation. A tick group. */
    AUTHORITY,
    /** What changed, written to the networked objects that send it. A tick group. */
    NET_OUT,
    /** The frame's own host frame is filled and the clocks advance. */
    BEGIN,
    /** Plays are started: owners' queues, cue views, sequences. What starts here ticks this frame. */
    PLAYS,
    /** Sequence players evaluate their ranges: values to systems, spawnables, rigs, post. */
    SEQUENCES,
    /** Bone matrices, puppets' clips, springs. */
    POSE,
    /** Sockets and attachment points to world transforms. */
    ATTACH,
    /** The camera director and its rigs: the frame's view. */
    CAMERA,
    /** Culling, sleep, significance and level of detail against that view. */
    SCALABILITY,
    /** Fixed steps: effect logic, follow, simulations, emitter books, GPU steps queued. */
    EFFECTS,
    /** Camera shakes summed, handed to the host before it applies its camera. */
    SHAKE,
    /** The freshest attachment marks re-read for what draws exactly at a point. */
    LATE_ATTACH,
    /** Draws, rows, post weights, lights and sound positions are handed to the renderers. */
    SUBMIT;

    private final String zone = "frame." + name().toLowerCase(Locale.ROOT).replace('_', '-');

    /** Whether it runs each game tick ({@link CgWorld#tick}) rather than each frame. */
    public boolean tick() {
        return ordinal() < BEGIN.ordinal();
    }

    /** This group's trace zone: {@code frame.<group>}. */
    public String zone() {
        return zone;
    }
}
