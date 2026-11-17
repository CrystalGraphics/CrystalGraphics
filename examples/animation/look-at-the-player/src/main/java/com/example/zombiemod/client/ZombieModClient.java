// docs:begin whole
package com.example.zombiemod.client;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimGraphDefinition;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.lifecycle.CgLifecycleListener;
import com.crystalgraphics.mc.model.CgVanillaHumanoid;
import com.crystalgraphics.mc.model.CgVanillaPoses;
import com.crystalgraphics.platform.input.CgKeyBindings;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.world.CgEntityQueries;

public final class ZombieModClient implements CgLifecycleListener {

    // the graph file's three variables: the point in the world the head turns to
    static final CgAnimVariable<Float> LOOK_X = CgAnimVariable.floating("look_x");
    static final CgAnimVariable<Float> LOOK_Y = CgAnimVariable.floating("look_y");
    static final CgAnimVariable<Float> LOOK_Z = CgAnimVariable.floating("look_z");

    // the file assets/zombiemod/anim_graphs/wave_and_look.yaml
    static final CgAssetRef<CgAnimGraphDefinition> WAVE_AND_LOOK =
            CgVfxAssets.ref(CgBodyKinds.ANIM_GRAPH, "zombiemod:wave_and_look");
    static final double[] PLAYER = new double[CgEntityQuery.POSE_LENGTH];
    static CgAnimGraph graph;

    @Override
    public void onPreInit() {
        CgKeyBindings.declare("zombiemod:wave", CgKeyCodes.KEY_G, "zombiemod:keys", ZombieModClient::wave);
    }

    static void wave() {
        // docs:begin load
        if (graph == null) {
            // made once and shared by every zombie, for the bones of Minecraft's humanoid mobs;
            // the clip id zombiemod:anims/wave is the file anims/wave.glb
            graph = WAVE_AND_LOOK.get().graph(CgVanillaHumanoid.SKELETON,
                    clip -> CgMeshLoader.sequences(clip + ".glb").get(0));
        }
        // docs:end load

        // where you are standing: the game's facts about you, read into an array
        CgEntityQuery entities = CgEntityQueries.client();
        double[] me = new double[CgEntityQuery.POSE_LENGTH];
        if (!entities.pose(entities.localPlayer(), 1f, me)) return;
        double x = me[CgEntityQuery.X], y = me[CgEntityQuery.Y], z = me[CgEntityQuery.Z];

        entities.within(x - 16, y - 16, z - 16, x + 16, y + 16, z + 16, id -> {
            // ignoring case: before 1.11 a zombie is "minecraft:Zombie"
            if (!"minecraft:zombie".equalsIgnoreCase(entities.type(id))) return;
            if (CgVanillaPoses.posing(id)) {
                CgVanillaPoses.stop(id);
            } else {
                // docs:begin play
                CgVanillaPoses.play(id).graph(graph).onUpdate(ZombieModClient::lookAtPlayer);
                // docs:end play
            }
        });
    }

    // docs:begin feed
    /** Every frame, before the zombie is posed: your eyes are where its head should point. */
    static void lookAtPlayer(CgAnimInstance zombie, float seconds) {   // seconds since the last frame: unused here
        CgEntityQuery entities = CgEntityQueries.client();
        if (!entities.pose(entities.localPlayer(), 1f, PLAYER)) return;
        zombie.set(LOOK_X, (float) PLAYER[CgEntityQuery.X])
                .set(LOOK_Y, (float) (PLAYER[CgEntityQuery.Y] + PLAYER[CgEntityQuery.EYE_HEIGHT]))
                .set(LOOK_Z, (float) PLAYER[CgEntityQuery.Z]);
    }
    // docs:end feed
}
// docs:end whole
