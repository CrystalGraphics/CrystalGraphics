package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;

/**
 * A place in a scene that draws nothing, what other primitives attach to: Unreal's {@code DefaultSceneRoot}, an
 * actor's root. Moving it moves everything riding it.
 *
 * <pre>{@code
 * CgSceneRoot root = scene.root().at(x, y, z).spawn();
 * CgSkeletalMeshComponent body = scene.skeletal(mesh).materials(skins::get).attach(root, null).spawn();
 * root.at(x + 1, y, z).rotation(facing);     // the body follows in ATTACH
 * }</pre>
 */
public final class CgSceneRoot extends CgPrimitiveComponent<CgSceneRoot> {

    CgSceneRoot(CgScenePrimitives scene) {
        super(scene);
    }

    @Override
    void onSpawn() {
    }

    @Override
    void onRemove() {
    }

    @Override
    void onMoved() {
    }

    @Override
    void onLooked() {
    }

    @Override
    boolean kindBounds(CgAABB out) {
        return false;
    }

    @Override
    void gather(CgView view, CgCamera camera, CgMeshBatches out, CgSceneGather gather) {
    }

    @Override
    public String toString() {
        return "CgSceneRoot";
    }
}
