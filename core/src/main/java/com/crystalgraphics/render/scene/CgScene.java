package com.crystalgraphics.render.scene;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.CgAnimation;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.frame.CgWorldSubsystem;

import com.crystalgraphics.render.scene.component.CgDynamicComponent;
import com.crystalgraphics.render.scene.component.CgDynamicPrimitive;
import com.crystalgraphics.render.scene.component.CgInstancedMeshComponent;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.scene.component.CgScenePrimitives;
import com.crystalgraphics.render.scene.component.CgSceneRoot;
import com.crystalgraphics.render.scene.component.CgSkeletalMeshComponent;
import com.crystalgraphics.render.scene.gpu.CgGpuScene;

import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.world.CgWorldQueries;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A world of primitives kept until removed, Unreal's {@code FScene}: meshes, instanced meshes, skeletal meshes and
 * dynamic primitives, each a component made here, set up and spawned. Its {@link CgGpuScene} holds every persistent
 * primitive's record on the GPU; each frame, every view that draws it takes what it sees once, as its
 * {@code CgViewVisibility}, without a submit.
 *
 * <pre>{@code
 * CgScene scene = CgScene.client();                  // the client world's; cleared with the level
 * CgScene preview = new CgScene();                   // one of your own: a preview, an editor, a test
 *
 * CgMeshComponent rock = scene.mesh(rockMesh, stone).at(x, y, z).spawn();
 * CgSkeletalMeshComponent goku = scene.skeletal(gokuMesh).materials(skins::get).at(x, y, z).spawn();
 * CgInstancedMeshComponent grass = scene.instanced(blade, grassMaterial).spawn();
 * CgDynamicComponent trail = scene.dynamic(new Trail()).spawn();
 * scene.drawOnce(marker, debugMaterial, x, y, z);    // this frame only: Unreal's debug draw
 * }</pre>
 *
 * <ul>
 *   <li>The frame thread's: its components are made, changed and gathered there.</li>
 *   <li>A component is a handle: keep it to change or remove what it made.</li>
 *   <li>The client's scene poses its skeletal meshes with the client world's {@link CgAnimation} and places what rides
 *       another in {@code ATTACH}. A scene of your own does neither by itself: evaluate each skeletal mesh's
 *       {@code anim()} and call {@link #update()} each frame.</li>
 * </ul>
 */
public final class CgScene {

    private static final CgScene CLIENT = new CgScene("crystalgraphics.scene.client", new CgScenePrimitives.Driver() {
        @Override
        public void started() {
            Ticks.of(CgWorld.client());
        }

        @Override
        public void pose(CgAnimInstance anim, boolean on) {
            if (on) CgAnimation.client().add(anim);
            else CgAnimation.client().remove(anim);
        }
    });

    /** The client world's scene. */
    public static CgScene client() {
        return CLIENT;
    }

    private final CgGpuScene gpu;
    private final CgScenePrimitives primitives;

    public CgScene() {
        this("crystalgraphics.scene", null);
    }

    private CgScene(String name, @Nullable CgScenePrimitives.Driver driver) {
        gpu = new CgGpuScene(name);
        primitives = new CgScenePrimitives(gpu, driver);
    }

    /** Its records on the GPU. */
    public CgGpuScene gpu() {
        return gpu;
    }

    /** Its primitives: what spawns into it and what a view gathers. */
    public CgScenePrimitives primitives() {
        return primitives;
    }

    /** A mesh, every submesh drawn with {@code material} until {@code material(slot, ...)} says otherwise. */
    public CgMeshComponent mesh(CgMesh mesh, CgMaterialInterface material) {
        return primitives.mesh(mesh, material);
    }

    /** A mesh with levels of detail, each view drawing the level its screen size picks. */
    public CgMeshComponent mesh(CgMeshLods lods, CgMaterialInterface material) {
        return primitives.mesh(lods, material);
    }

    /** A skinned mesh posed by an animation instance of its own: give each slot a material before it spawns. */
    public CgSkeletalMeshComponent skeletal(CgSkeletalMesh mesh) {
        return primitives.skeletal(mesh);
    }

    /** A place that draws nothing, for others to attach to: an actor's root. */
    public CgSceneRoot root() {
        return primitives.root();
    }

    /** Many of one mesh, each instance its own record. */
    public CgInstancedMeshComponent instanced(CgMesh mesh, CgMaterialInterface material) {
        return primitives.instanced(mesh, material);
    }

    /** Geometry asked for per view each frame. */
    public CgDynamicComponent dynamic(CgDynamicPrimitive primitive) {
        return primitives.dynamic(primitive);
    }

    /** {@code mesh} drawn this frame only, at an absolute position: a prototype or a debug shape. */
    public void drawOnce(CgMesh mesh, CgMaterialInterface material, double x, double y, double z) {
        primitives.drawOnce(mesh, material, x, y, z);
    }

    /** This frame's placement: what rides another, then the skeletal meshes' palettes. The client's runs in {@code ATTACH}. */
    public void update() {
        primitives.update();
    }


    /** Removes every primitive: the level ended. */
    public void clear() {
        primitives.clear();
    }

    /**
     * The client scene's frame on the client world: placed in {@code ATTACH}, its skeletal meshes handed to the world
     * renderer in {@code SUBMIT}, cleared when the level changes. Added with its first skeletal mesh or attachment.
     */
    private static final class Ticks extends CgWorldSubsystem {

        private static final int UPDATE = CgTrace.name("scene.update"), SKELETAL = CgTrace.name("scene.skeletal");

        private CgTickFunction attach, submit;
        private int levelEpoch;

        static void of(CgWorld world) {
            if (world.subsystem(Ticks.class) == null) world.add(new Ticks());
        }

        @Override
        protected void initialize() {
            levelEpoch = CgWorldQueries.client().levelEpoch();
            attach = world().schedule().add(CgTickGroup.ATTACH, "crystalgraphics:scene.attach", dt -> update())
                    .whilePaused(true);
            submit = world().schedule().add(CgTickGroup.SUBMIT, "crystalgraphics:scene.skeletal", dt -> submit())
                    .whilePaused(true);
        }

        @Override
        protected void deinitialize() {
            attach.close();
            submit.close();
        }

        private void update() {
            int now = CgWorldQueries.client().levelEpoch();
            if (now != levelEpoch) {
                levelEpoch = now;
                CLIENT.clear();
            }
            try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, UPDATE)) {
                CLIENT.update();
            }
        }

        /** Each spawned skeletal mesh, through the world renderer's skinned draw. */
        private void submit() {
            List<CgSkeletalMeshComponent> skeletal = CLIENT.primitives.skeletal();
            if (skeletal.isEmpty()) return;
            CgWorldRenderer world = CgWorldRenderer.get();
            try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, SKELETAL)) {
                for (int i = 0, n = skeletal.size(); i < n; i++) world.draw(skeletal.get(i));
            }
        }
    }
}
