// docs:begin whole
package com.example.zombiemod.client;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.lifecycle.CgLifecycleListener;
import com.crystalgraphics.mc.model.CgVanillaPoses;
import com.crystalgraphics.platform.input.CgKeyBindings;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.world.CgEntityQueries;

public final class ZombieModClient implements CgLifecycleListener {

    @Override
    public void onPreInit() {
        // G runs wave(), and the player can change it in Controls
        CgKeyBindings.declare("zombiemod:wave", CgKeyCodes.KEY_G, "zombiemod:keys", ZombieModClient::wave);
    }

    static void wave() {
        CgAnimSequence wave = CgMeshLoader.sequences("zombiemod:anims/wave.glb").get(0);

        // where you are standing: the game's facts about you, read into an array
        CgEntityQuery entities = CgEntityQueries.client();
        double[] me = new double[CgEntityQuery.POSE_LENGTH];
        if (!entities.pose(entities.localPlayer(), 1f, me)) return;
        double x = me[CgEntityQuery.X], y = me[CgEntityQuery.Y], z = me[CgEntityQuery.Z];

        // every zombie within 16 blocks of you
        entities.within(x - 16, y - 16, z - 16, x + 16, y + 16, z + 16, id -> {
            // ignoring case: before 1.11 a zombie is "minecraft:Zombie"
            if (!"minecraft:zombie".equalsIgnoreCase(entities.type(id))) return;
            if (CgVanillaPoses.posing(id)) {
                CgVanillaPoses.stop(id);                 // already waving: back to its own walk
            } else {
                CgVanillaPoses.play(id).play(wave);      // waves, looping, until you press G again
            }
        });
    }
}
// docs:end whole
