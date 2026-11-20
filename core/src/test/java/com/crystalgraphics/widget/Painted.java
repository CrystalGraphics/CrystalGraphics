package com.crystalgraphics.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.ui.element.CgUiElements;
import com.crystalgraphics.render.ui.property.CgEffectTree;
import com.crystalgraphics.render.ui.property.CgPalette;
import com.crystalgraphics.render.ui.property.CgSpatialTree;

/**
 * What a canvas recorded, read back without a GPU: each element in the order its passes record them, its texture, its
 * centre on the canvas (through its spatial nodes) and its colour. The widget tests' eyes.
 *
 * <pre>{@code
 * List<Painted.Draw> draws = Painted.of(c -> widget.paint(c));
 * assertEquals("a", draws.get(0).texture());
 * }</pre>
 */
final class Painted {

    /**
     * One element: {@code texture} its image's path ("layer" for a layer's picture, null for none), its centre and
     * size on the canvas, its colour as recorded, whether it was drawn into a layer rather than the target, and the
     * opacity its effect nodes multiply in.
     */
    record Draw(String texture, float cx, float cy, float width, float height, int argb, boolean inLayer,
                float opacity) {
    }

    private static final int SIZE = 1024;

    private Painted() {
    }

    /** {@code paint} recorded onto a {@value #SIZE}-pixel canvas at scale 1, its elements read back. */
    static List<Draw> of(Consumer<CgCanvas> paint) {
        // Materials compile against a device's capabilities: a null one, when no test installed its own
        CgNullRHI device = CgRHI.installed() ? null : new CgNullRHI(64, 64).withoutLog();
        if (device != null) CgRHI.install(device);
        try {
            return record(paint);
        } finally {
            if (device != null) {
                device.close();
                CgRHI.install(null);
            }
        }
    }

    private static List<Draw> record(Consumer<CgCanvas> paint) {
        CgRecording recording = new CgRecording();
        CgGraphTexture target = CgGraphTexture.requested("painted", CgTextureDesc.of(SIZE, SIZE, CgFormat.RGBA8_UNORM));
        CgCanvas canvas = new CgCanvas();
        canvas.begin(recording, target, CgLoad.CLEAR, new CgCamera().screenLens(SIZE, SIZE), 1f);
        paint.accept(canvas);
        canvas.end();
        List<Draw> draws = new ArrayList<>();
        CgSpatialTree nodes = recording.spatial();
        for (int p = 0; p < recording.passCount(); p++) {
            if (!(recording.pass(p) instanceof CgRasterPass raster)) continue;
            boolean inLayer = raster.target() != target;
            for (CgDrawChunk chunk : raster.chunks()) {
                for (int d = 0; d < chunk.draws(); d++) {
                    if (chunk.kind(d) != CgInstanceKind.ELEMENT) continue;
                    float[] data = chunk.data(CgInstanceKind.ELEMENT);
                    String texture = textureOf(chunk.binding(d).textures() > 0 ? chunk.binding(d).texture(0) : null);
                    for (int r = chunk.first(d), end = r + chunk.instances(d); r < end; r++) {
                        int o = r * CgUiElements.FLOATS;
                        float x0 = data[o + CgUiElements.RECT], y0 = data[o + CgUiElements.RECT + 1];
                        float x1 = data[o + CgUiElements.RECT + 2], y1 = data[o + CgUiElements.RECT + 3];
                        float node = data[o + CgUiElements.NODE];
                        float[] c = place(nodes, CgPalette.spatialOf(node), (x0 + x1) * 0.5f, (y0 + y1) * 0.5f);
                        draws.add(new Draw(texture, c[0], c[1], x1 - x0, y1 - y0,
                                Float.floatToRawIntBits(data[o + CgUiElements.COLOR]), inLayer,
                                opacity(recording.effects(), CgPalette.effectOf(node))));
                    }
                }
            }
        }
        return draws;
    }

    /** The draws with {@code texture}, in order. */
    static List<Draw> with(List<Draw> draws, String texture) {
        List<Draw> out = new ArrayList<>();
        for (Draw d : draws) if (texture == null ? d.texture() == null : texture.equals(d.texture())) out.add(d);
        return out;
    }

    private static String textureOf(CgTexture t) {
        if (t == null) return null;
        if (t instanceof CgTexture2D t2) return t2.getSourcePath();
        return t instanceof CgGraphTexture ? "layer" : t.toString();
    }

    /** Effect node {@code n}'s opacity times its parents'. */
    private static float opacity(CgEffectTree effects, int n) {
        float f = 1f;
        for (; n != 0; n = effects.parent(n)) f *= effects.opacity(n);
        return f;
    }

    /** A point in node {@code n}'s space on the canvas: each node's affine, child to root. */
    private static float[] place(CgSpatialTree nodes, int n, float x, float y) {
        for (; n != 0; n = nodes.parent(n)) {
            float a = nodes.local(n, 0), b = nodes.local(n, 1), c = nodes.local(n, 2), d = nodes.local(n, 3);
            float nx = a * x + c * y + nodes.local(n, 4), ny = b * x + d * y + nodes.local(n, 5);
            x = nx;
            y = ny;
        }
        return new float[]{x, y};
    }
}
