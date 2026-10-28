package com.crystalgraphics.sequence.spawn;

import com.crystalgraphics.world.CgAttachment;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/**
 * Makes one kind of spawnable ({@code system}, {@code prefab}, {@code post}...), registered on the
 * {@link CgSequencer} by that kind. Unreal's spawn register's {@code SpawnObject}, per object type.
 *
 * <pre>{@code
 * sequencer.spawner("light", (player, spawnable, at) -> new SequenceLight(spawnable.tree(), at));
 * }</pre>
 *
 * <ul>
 *   <li>A spawnable whose kind has no spawner fails the play, naming it, unless the sequencer runs on a dedicated server,
 *       where presentation is never spawned.</li>
 *   <li>{@code at} is where its {@code at} places it, answering every frame; follow it, or read it once where the
 *       spawnable's {@code follow} is false.</li>
 *   <li>Called on the frame thread in the spawn phase: a section boundary can spawn many at once, so take from pools.</li>
 *   <li>{@link #check} runs as the play starts, for this sequence's spawnables and those of every sequence it plays.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgSequenceSpawner {

    /** A new object for {@code spawnable} of {@code player}'s sequence, placed by {@code at}. */
    CgSequenceSpawned spawn(CgSequencePlayer player, CgSequenceDefinition.Spawnable spawnable, CgAttachment at);

    /**
     * Throws, naming {@code spawnable}, where it could never be made: asked of every spawnable of a sequence and of the
     * sequences it plays as the play starts, so it fails there rather than mid-frame. Nothing by default.
     */
    default void check(CgSequenceDefinition definition, CgSequenceDefinition.Spawnable spawnable) {
    }
}
