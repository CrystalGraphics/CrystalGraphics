package com.crystalgraphics.mc.render.world;

import java.util.Map;

import org.joml.Quaternionf;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.mc.entity.CgEntityData;
import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.render.entity.CgEntityPose;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawned;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawner;
import com.crystalgraphics.world.CgAttachment;

/**
 * A sequence's {@code entity} spawnable: one of Minecraft's entities, a {@link CgChildEntityComponent} of the client
 * scene while it lives, standing where its node is keyed. Unreal's spawnable actor. The sequencer's default
 * {@code entity} spawner.
 *
 * <pre>{@code
 * spawnables:
 *   - { id: z1, kind: entity, at: { spawnable: mark }, entity: { data: "minecraft:zombie{IsBaby:1b}" } }
 * }</pre>
 *
 * <ul>
 *   <li>Its feet at the node, facing the node's +Z, as Minecraft's entities face south at yaw 0.</li>
 *   <li>On a server's sequencer it makes nothing: only the client draws it.</li>
 *   <li>{@code entity.data} is entity data as {@code /summon} takes it; missing, the spawnable fails as it spawns.</li>
 * </ul>
 */
public final class CgSequenceEntity implements CgSequenceSpawned {

    /** Makes an {@code entity} spawnable's entity. */
    public static final CgSequenceSpawner SPAWNER = CgSequenceEntity::spawn;

    private static final CgSequenceSpawned NOTHING = new CgSequenceSpawned() {
        @Override public void update() { }
        @Override public void destroy(boolean graceful) { }
    };

    private final CgChildEntityComponent entity;
    private final CgAttachment at;
    private final double[] point = new double[3];
    private final Quaternionf turn = new Quaternionf();

    private CgSequenceEntity(CgChildEntityComponent entity, CgAttachment at) {
        this.entity = entity;
        this.at = at;
        update();
        entity.spawn();
    }

    private static CgSequenceSpawned spawn(CgSequencePlayer player, CgSequenceDefinition.Spawnable spawnable, CgAttachment at) {
        String path = player.definition().id() + ": spawnables." + spawnable.id() + ".entity";
        Map<?, ?> tree = spawnable.tree().get("entity") instanceof Map<?, ?> m ? m : Map.of();
        if (!(tree.get("data") instanceof String data)) throw new CgAssetException(path + ".data: an entity names its data");
        CgEntityData parsed;
        try {
            parsed = CgEntityData.parse(data);
        } catch (IllegalArgumentException e) {
            throw new CgAssetException(path + ".data: " + e.getMessage());
        }
        if (player.server()) return NOTHING;
        return new CgSequenceEntity(CgChildEntityComponent.of(CgScene.client(), CgEntityRef.of(parsed))
                .pose(CgEntityPose.FRONT), at);
    }

    @Override
    public void update() {
        if (!at.place(point, turn)) return;
        entity.at(point[0], point[1], point[2]).rotation(turn);
    }

    @Override
    public void destroy(boolean graceful) {
        entity.remove();
    }

    @Override
    public String toString() {
        return "entity " + entity.ref();
    }
}
