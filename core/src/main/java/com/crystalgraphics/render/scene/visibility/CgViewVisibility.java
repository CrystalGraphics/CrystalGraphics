package com.crystalgraphics.render.scene.visibility;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.sort.CgSortKey;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * What one view sees of one scene this frame, made once and read by every pass of the frame: Unreal's per-view
 * visibility and relevance ({@code FViewInfo}, made in {@code InitViews}). The scene's primitives are culled against the
 * view's frustum and reach, a mesh with levels takes the level its screen size picks, a dynamic primitive is asked only
 * once in view, and every row is keyed for sorting ({@code CgSortKey}): opaque front to back, transparent back to front.
 *
 * <pre>{@code
 * CgViewVisibility seen = CgViewVisibility.of(frame.view(), CgScene.client(), frame.camera());
 * CgMeshBatches batches = seen.batches();
 * for (int b = 0; b < batches.size(); b++) {
 *     long key = batches.key(b);                                  // what a pass sorts its draws by
 * }
 * culling.cull(frame.recording(), CgScene.client(), seen, frame.camera(), pyramid);   // its instances, on the GPU
 * }</pre>
 *
 * <pre>{@code
 * // A second ask in the same frame, from a later firing, is the same work, not done again
 * CgViewVisibility again = CgViewVisibility.of(frame.view(), CgScene.client(), frame.camera());   // seen, as it was
 * }</pre>
 *
 * <ul>
 *   <li>The frame thread's. Made at the first ask of a host frame ({@code CgFrameRing.frame()}) through that ask's
 *       camera; asks after it in the same frame answer it unchanged, whatever camera they pass.</li>
 *   <li>Each view keeps one per scene, kept as long as the scene is.</li>
 *   <li>Counted on the {@code crystalgraphics.world} trace channel: {@code scene.visibility.computed} once per view
 *       and scene a frame, {@code .reused} for every later ask, {@code .tested}, {@code .culled} and {@code .rows}.</li>
 * </ul>
 */
public final class CgViewVisibility {

    private static final CgViewState.Key<Map<CgScene, CgViewVisibility>> SEEN =
            new CgViewState.Key<>("crystalgraphics.scene.visibility", WeakHashMap::new);

    private static final int ZONE = CgTrace.name("scene.visibility"), COMPUTED = CgTrace.name("scene.visibility.computed"),
            REUSED = CgTrace.name("scene.visibility.reused"), TESTED = CgTrace.name("scene.visibility.tested"),
            CULLED = CgTrace.name("scene.visibility.culled"), ROWS = CgTrace.name("scene.visibility.rows");

    private final CgView view;
    private final CgMeshBatches batches = new CgMeshBatches();
    private long frame = Long.MIN_VALUE;
    private int computations, tested, culled;

    private CgViewVisibility(CgView view) {
        this.view = view;
    }

    /** What {@code view} sees of {@code scene} this frame: made now through {@code camera} at the frame's first ask. */
    public static CgViewVisibility of(CgView view, CgScene scene, CgCamera camera) {
        Map<CgScene, CgViewVisibility> seen = view.state().get(SEEN);
        CgViewVisibility v = seen.get(scene);
        if (v == null) seen.put(scene, v = new CgViewVisibility(view));
        long now = CgFrameRing.frame();
        if (v.frame == now) {
            CgTrace.add(CgChannels.WORLD, REUSED, 1);
            return v;
        }
        v.compute(scene, camera, now);
        return v;
    }

    private void compute(CgScene scene, CgCamera camera, long now) {
        try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, ZONE)) {
            scene.primitives().gather(view, camera, batches);
            for (int b = 0, n = batches.size(); b < n; b++) batches.key(b, key(b));
        }
        frame = now;
        computations++;
        tested = scene.primitives().tested();
        culled = scene.primitives().culled();
        CgTrace.add(CgChannels.WORLD, COMPUTED, 1);
        CgTrace.add(CgChannels.WORLD, TESTED, tested);
        CgTrace.add(CgChannels.WORLD, CULLED, culled);
        CgTrace.add(CgChannels.WORLD, ROWS, batches.size());
    }

    /**
     * Row {@code b}'s key: its material's queue, its layer, its sort priority as the key's order ({@code priority +
     * 128}, a byte), then by material and mesh near to far when opaque, far to near when transparent. A transparent
     * row is its own sort group until scenes have groups.
     */
    private long key(int b) {
        CgMaterialInterface material = batches.material(b);
        int queue = material.renderQueue(), layer = batches.layer(b).rank();
        int order = Math.max(0, Math.min(255, batches.sortPriority(b) + 128));
        float distance = batches.distance(b);
        if (queue >= CgRenderQueue.TRANSPARENT_THRESHOLD) {
            return CgSortKey.transparent(queue, layer, distance, material.writesDepth(), order, 0, distance);
        }
        return CgSortKey.opaque(queue, layer, order, material.getMaterialId(), System.identityHashCode(batches.mesh(b)), distance);
    }

    public CgView view() {
        return view;
    }

    /** What it sees: a row per mesh batch, each with its distance and key. */
    public CgMeshBatches batches() {
        return batches;
    }

    /** The host frame it was made in. */
    public long frame() {
        return frame;
    }

    /** How many times it has been made: once for every frame a view asked. */
    public int computations() {
        return computations;
    }

    /** Primitives tested against the frustum when it was made, and of them the ones left out. */
    public int tested() {
        return tested;
    }

    public int culled() {
        return culled;
    }
}
