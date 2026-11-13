package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.sort.CgSortLayer;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;

/**
 * A {@link CgDynamicPrimitive} in a scene, with a primitive's settings: what {@code scene.dynamic(primitive)} makes. It
 * keeps no GPU record; what it collects is written into the frame's records by the pass that draws it.
 *
 * <pre>{@code
 * CgDynamicComponent trail = scene.dynamic(new Trail()).layer(CgSortLayer.EFFECTS).at(x, y, z).spawn();
 * }</pre>
 */
public final class CgDynamicComponent extends CgPrimitiveComponent<CgDynamicComponent> {

    private final CgDynamicPrimitive primitive;
    private final CgMeshCollector collector = new CgMeshCollector();

    CgDynamicComponent(CgScenePrimitives scene, CgDynamicPrimitive primitive) {
        super(scene);
        this.primitive = primitive;
    }

    public CgDynamicPrimitive primitive() {
        return primitive;
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
        collector.begin(out, this, camera);
        primitive.collect(view, collector);
    }
}
