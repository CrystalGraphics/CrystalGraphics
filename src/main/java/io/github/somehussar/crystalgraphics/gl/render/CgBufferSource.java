package io.github.somehussar.crystalgraphics.gl.render;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered collection of typed render layers with dirty-aware flush.
 * Registration order in the builder determines painter's order (back-to-front).
 *
 * <h3>Ownership Model</h3>
 * <p>A {@code CgBufferSource} is a <strong>per-context owned</strong> instance, not a
 * global singleton. Each rendering context (UI, world overlay, etc.) creates and owns
 * its own buffer source. This unlike MC 1.20.1's pattern where
 * {@code GameRenderer.RenderBuffers#bufferSource()} owns one global singleton that contains all
 * RenderTypes.</p>
 *
 * <p>Typical ownership hierarchy in CrystalGUI:</p>
 * <pre>
 * UIContainer
 *   └─ CgUiRenderContext
 *        └─ CgBufferSource  (owns the ordered layer collection)
 *             ├─ CgRenderLayer "solid"
 *             ├─ CgRenderLayer "panel"
 *             ├─ CgDynamicTextureRenderLayer "text"
 *             └─ ...
 * </pre>
 *
 * <p>Multiple buffer sources can coexist (one for UI, one for world overlays).
 * Each owns its layers independently. {@link #delete()} disposes all owned layers.</p>
 *
 * <h3>Layer Lookup</h3>
 * <p>Layers are registered and retrieved by {@link CgLayer.Key}, which provides
 * type-safe access. Keys use name equality, not identity — two keys with the same
 * name address the same layer slot.</p>
 *
 * @see CgLayer
 * @see CgLayer.Key
 */
public final class CgBufferSource {

    private final CgLayer[] orderedLayers;
    private final Map<CgLayer.Key<?>, CgLayer> layerMap;

    private CgBufferSource(CgLayer[] orderedLayers, Map<CgLayer.Key<?>, CgLayer> layerMap) {
        this.orderedLayers = orderedLayers;
        this.layerMap = layerMap;
    }

    public void begin(Matrix4f projection) {
        for (CgLayer layer : orderedLayers) layer.begin(projection);
    }

    public void flushAll() {
        for (CgLayer layer : orderedLayers) 
            if (layer.isDirty()) layer.flush();
    }

    public void flush(CgLayer.Key<?> key) {
        CgLayer layer = layerMap.get(key);
        if (layer != null && layer.isDirty()) layer.flush();
    }

    public void end() {
        for (CgLayer layer : orderedLayers) layer.end();
    }

    public <T extends CgLayer> T get(CgLayer.Key<T> key) {
        return (T) layerMap.get(key);
    }

    public void delete() {
        for (CgLayer layer : orderedLayers) layer.delete();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<CgLayer> ordered = new ArrayList<>();
        private final Map<CgLayer.Key<?>, CgLayer> map = new LinkedHashMap<>();

        public <T extends CgLayer> Builder layer(CgLayer.Key<T> key, T layer) {
            ordered.add(layer);
            map.put(key, layer);
            return this;
        }

        public CgBufferSource build() {
            return new CgBufferSource(ordered.toArray(new CgLayer[0]), new LinkedHashMap<>(map));
        }
    }
}
