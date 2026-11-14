package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.sort.CgSortLayer;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;

import javax.annotation.Nullable;

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
    private final CgAABB frameBounds = new CgAABB();

    CgDynamicComponent(CgScenePrimitives scene, CgDynamicPrimitive primitive) {
        super(scene);
        this.primitive = primitive;
    }

    public CgDynamicPrimitive primitive() {
        return primitive;
    }

    /** Its rows change frame to frame: their commands are built per view, never kept. */
    @Override
    boolean keepsCommands() {
        return false;
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

    /** This frame's bounds from its primitive where it gives them, absolute; else its component's, as any primitive's. */
    @Override
    @Nullable
    CgAABBc worldBounds() {
        return primitive.bounds(frameBounds) ? frameBounds : super.worldBounds();
    }

    @Override
    void gather(CgView view, CgCamera camera, CgMeshBatches out, CgSceneGather gather) {
        collector.begin(out, this, camera, gather);
        primitive.collect(view, collector);
    }
}
