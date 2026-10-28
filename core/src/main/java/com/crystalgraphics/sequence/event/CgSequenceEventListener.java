package com.crystalgraphics.sequence.event;

import javax.annotation.Nullable;
import com.crystalgraphics.sequence.CgSequencePlayer;

/**
 * Hears a sequence's {@code event} tracks of one side: the gameplay hears side {@code rpg}, the host's presentation
 * side {@code ours} (a caption, rumble, a camera reset). Unreal's event track calling its event contexts' functions,
 * with the events a frame swept heard after the frame's evaluation.
 *
 * <pre>{@code
 * sequencer.listen(CgSequenceEventListener.Side.RPG, (player, phase, event, args) -> {
 *     if (event.equals("kakarot:ATAnimNotifyState_SuperArmor")) armour(player, phase == Phase.BEGIN);
 * });
 * }</pre>
 *
 * <pre>{@code
 * # what it hears: an instant at a key, a span begun and ended
 * - { id: t9, kind: event, side: rpg, binding: { role: caster }, sections: [
 *     { id: a, keys: [[1200, { event: mymod:release }]] },
 *     { id: b, range: [1200, 3000], event: mymod:hit_window, args: { damage: 40 } } ] }
 * }</pre>
 *
 * <ul>
 *   <li>A key fires once, in the frame whose range crosses it, however long the frame; a jump fires nothing it
 *       skips. A span begins in the frame play enters it, a jump landing inside it included, and ends as play leaves
 *       it or stops.</li>
 *   <li>{@code args} are the file's, untouched: a {@code Map} as written, or null.</li>
 *   <li>A dedicated server hears side {@code rpg} alone; a client hears both.</li>
 *   <li>It runs on the frame thread, after the frame's tracks are applied: allocate nothing it does not keep.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgSequenceEventListener {

    /** Who an event track is for. */
    enum Side {
        /** The gameplay: a cancel window, super armour, a hit. */
        RPG,
        /** The host's presentation. */
        OURS
    }

    /** What happened: a key crossed, or a span begun or ended. */
    enum Phase { INSTANT, BEGIN, END }

    void heard(CgSequencePlayer player, Phase phase, String event, @Nullable Object args);
}
