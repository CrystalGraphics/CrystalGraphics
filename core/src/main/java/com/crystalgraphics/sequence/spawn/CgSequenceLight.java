package com.crystalgraphics.sequence.spawn;

import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.render.stage.CgDirectionalLights;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgAttachment;

/**
 * A sequence's {@code light} spawnable: a directional light added to {@link CgDirectionalLights} while it lives,
 * travelling along the forward axis (Unreal's +X) of the node it rides. The sequencer's default {@code light} spawner.
 *
 * <pre>{@code
 * spawnables:
 *   - { id: k1, kind: light, at: { spawnable: dmy, socket: EffPoint_01 },
 *       light: { type: directional, colour: [1, 1, 1], intensity: 10, priority: 1 } }
 * }</pre>
 *
 * <pre>{@code
 * sequencer.spawner("light", CgSequenceLight.SPAWNER);   // already the default; a host replaces it like any spawner
 * }</pre>
 *
 * <ul>
 *   <li>{@code priority} is Unreal's {@code ForwardShadingPriority}: above the sun's 0 it lights the world in its
 *       place; at 0 only while brighter. Unwritten, colour is white, intensity 10 and priority 0, Unreal's defaults.</li>
 *   <li>A point, spot or sky light fails as it spawns, naming the spawnable: not played yet.</li>
 *   <li>Its direction is read each frame, from where its node is turned; it never fades in or out.</li>
 * </ul>
 */
public final class CgSequenceLight implements CgSequenceSpawned {

    /** Makes a {@code light} spawnable's light. */
    public static final CgSequenceSpawner SPAWNER = CgSequenceLight::spawn;

    private final CgDirectionalLights.Light light;
    private final CgAttachment at;
    private final double[] point = new double[3];
    private final Quaternionf turn = new Quaternionf();
    private final Vector3f forward = new Vector3f();

    private CgSequenceLight(CgDirectionalLights.Light light, CgAttachment at) {
        this.light = light;
        this.at = at;
        update();
    }

    private static CgSequenceSpawned spawn(CgSequencePlayer player, CgSequenceDefinition.Spawnable spawnable, CgAttachment at) {
        String path = player.definition().id() + ": spawnables." + spawnable.id() + ".light";
        Map<?, ?> tree = spawnable.tree().get("light") instanceof Map<?, ?> m ? m : Map.of();
        Object type = tree.get("type");
        if (!"directional".equals(type)) throw new CgAssetException(path + ": a " + type + " light is not played yet: directional is");
        int priority = tree.get("priority") instanceof Number n ? n.intValue() : 0;
        if (priority < 0) throw new CgAssetException(path + ".priority: 0 or more, not " + priority);
        float intensity = tree.get("intensity") instanceof Number n ? n.floatValue() : 10f;
        float r = 1f, g = 1f, b = 1f;
        if (tree.get("colour") instanceof List<?> c && c.size() >= 3) {
            r = ((Number) c.get(0)).floatValue();
            g = ((Number) c.get(1)).floatValue();
            b = ((Number) c.get(2)).floatValue();
        }
        return new CgSequenceLight(CgDirectionalLights.get().add(priority).colour(r, g, b, intensity), at);
    }

    @Override
    public void update() {
        if (!at.place(point, turn)) return;
        turn.transform(forward.set(1f, 0f, 0f));
        light.travel(forward.x, forward.y, forward.z);
    }

    @Override
    public void destroy(boolean graceful) {
        light.close();
    }

    @Override
    public String toString() {
        return "directional light, priority " + light.priority();
    }
}
