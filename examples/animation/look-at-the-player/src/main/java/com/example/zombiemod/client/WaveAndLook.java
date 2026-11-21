// docs:begin whole
package com.example.zombiemod.client;

import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgSequencePlayerNode;
import com.crystalgraphics.animation.ik.CgIkTarget;
import com.crystalgraphics.animation.ik.CgLookAtNode;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.mc.model.CgVanillaHumanoid;

import static com.example.zombiemod.client.ZombieModClient.LOOK_X;
import static com.example.zombiemod.client.ZombieModClient.LOOK_Y;
import static com.example.zombiemod.client.ZombieModClient.LOOK_Z;

/** wave_and_look.yaml, built in Java instead of loaded from the file. */
final class WaveAndLook {

    private WaveAndLook() {
    }

    static CgAnimGraph graph() {
        CgSequencePlayerNode wave = CgSequencePlayerNode.of(CgMeshLoader.sequences("zombiemod:anims/wave.glb").get(0)).loop();
        return CgAnimGraph.builder("zombiemod:wave_and_look", CgVanillaHumanoid.SKELETON)
                .variables(LOOK_X, LOOK_Y, LOOK_Z)
                .output(CgLookAtNode.of(wave, "head", CgIkTarget.world(LOOK_X, LOOK_Y, LOOK_Z))   // the variables, set each frame
                        .lookAtAxis(0f, 0f, -1f)     // the way a vanilla mob's face looks: -z
                        .lookUpAxis(0f, 1f, 0f)      // keeps the head level
                        .clamp(70f))                 // never more than 70 degrees: no owl necks
                .build();
    }
}
// docs:end whole
