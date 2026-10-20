package com.crystalgraphics.asset.load;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A load of one or more primary assets' bundles toward {@link CgAssetState#READY}: Unreal's {@code FStreamableHandle}
 * (StreamableManager.h:196), from {@code UAssetManager::LoadPrimaryAssets} (AssetManager.h:308).
 *
 * <pre>{@code
 * CgLoadHandle h = CgVfxAssets.instance().load(List.of(SHURIKEN, LUNGE), CgLoadPriority.MOVE);
 * h.ready();                   // every asset of every bundle READY
 * h.progress();                // 0..1, by bytes
 * h.onReady(() -> enable());   // on the frame thread, at the drain it became ready; now if it is
 * }</pre>
 *
 * <ul>
 *   <li>A handle tracks readiness and priority, not lifetime: dropping it releases nothing.</li>
 *   <li>An asset in two bundles is one load; it runs at the higher of their priorities.</li>
 *   <li>One failure in the bundle fails the handle; {@link #failures()} names each.</li>
 * </ul>
 */
public final class CgLoadHandle {

    private final List<CgAssetRef<?>> assets;
    private final List<CgAssetRef<?>> primaries;
    CgLoadPriority priority;
    private final List<Runnable> onReady = new ArrayList<>(1);
    boolean fired;
    /** Whether the bundle's peaks reached the pool sizer. */
    boolean pooled;

    CgLoadHandle(List<CgAssetRef<?>> primaries, List<CgAssetRef<?>> assets, CgLoadPriority priority) {
        this.primaries = Collections.unmodifiableList(primaries);
        this.assets = Collections.unmodifiableList(assets);
        this.priority = priority;
    }

    /** Whether every asset of every bundle is READY. */
    public boolean ready() {
        if (!pooled) return false;
        for (int i = 0, n = assets.size(); i < n; i++) if (!assets.get(i).ready) return false;
        return true;
    }

    /** Whether an asset of the bundle failed. */
    public boolean failed() {
        for (int i = 0, n = assets.size(); i < n; i++) if (assets.get(i).state() == CgAssetState.FAILED) return true;
        return false;
    }

    /** Each failed asset with its reason. */
    public List<String> failures() {
        List<String> out = new ArrayList<>();
        for (CgAssetRef<?> a : assets) {
            if (a.state() == CgAssetState.FAILED) out.add(a.kind().id() + " " + a.id() + ": " + a.failure());
        }
        return out;
    }

    /** How far along, 0..1, weighted by each asset's upload bytes (an asset with none weighs one byte). */
    public float progress() {
        double done = 0, total = 0;
        for (int i = 0, n = assets.size(); i < n; i++) {
            CgAssetRef<?> a = assets.get(i);
            long w = Math.max(1, a.weight());
            total += w;
            done += a.ready ? w : Math.min(w, a.doneBytes());
        }
        return total == 0 ? 1f : (float) (done / total);
    }

    /** Runs {@code r} on the frame thread once ready; at once if it is. */
    public CgLoadHandle onReady(Runnable r) {
        if (fired) r.run();
        else onReady.add(r);
        return this;
    }

    /** Its priority. */
    public CgLoadPriority priority() {
        return priority;
    }

    /** The primary assets it was asked for. */
    public List<CgAssetRef<?>> primaries() {
        return primaries;
    }

    /** Every asset of their bundles, primaries first. */
    public List<CgAssetRef<?>> assets() {
        return assets;
    }

    /** Fires the ready listeners once; answers whether it did. */
    boolean fireIfReady() {
        if (fired || !ready()) return false;
        fired = true;
        for (int i = 0; i < onReady.size(); i++) onReady.get(i).run();
        onReady.clear();
        return true;
    }
}
