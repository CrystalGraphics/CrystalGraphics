package com.crystalgraphics.widget;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector2f;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.text.CgTextAlign;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.canvas.CgCanvasMaterials;
import com.crystalgraphics.render.canvas.CgCanvasRetainer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.text.style.CgTextStyle;

/**
 * Paints widgets onto a {@link CgCanvas}: an image as a textured quad or its UI material's, a text block as a canvas
 * text draw in its style, a retainer box's content into a region of one retained image drawn as one quad. One per
 * HUD, kept.
 *
 * <pre>{@code
 * static final CgCanvasWidgetPainter PAINTER = new CgCanvasWidgetPainter("mymod.hud", CgCanvasMaterials.SCREEN)
 *         .retaining(1024f, 1024f, 8f);                            // a retained image of 1024 x 1024 units
 *
 * PAINTER.prepare(POP.loaded());                                   // a tree loaded as an asset is precached already
 *
 * // each frame, at a GUI stage
 * PAINTER.begin(frame.canvas(), frame.recording(), scale * 1.5f); // texels a unit: the most it is drawn at
 * for (CgUserWidget pop : live) pop.retain(PAINTER);
 * for (CgUserWidget pop : live) pop.paint(PAINTER, placement(pop));
 * }</pre>
 *
 * <ul>
 *   <li>Client, the render thread: textures load at their first draw, fonts at a text block's first layout, both
 *       kept. A widget asset's UI materials and its styles' faces are built before it is READY
 *       ({@link CgWidgetPreparer}); a tree built in Java is built by {@link #prepare}, else at its first draw.</li>
 *   <li>Every retainer box is retained before anything paints onto the frame's canvas: the retained image's pass is
 *       recorded first. A region opened after a draw onto the canvas fails.</li>
 *   <li>The retained image only grows; a frame's regions past its size fail, naming the size to give
 *       {@link #retaining}.</li>
 *   <li>A brush with no texture or material draws a box of its tint, as Slate's.</li>
 * </ul>
 */
public final class CgCanvasWidgetPainter implements CgWidgetPainter {

    /** A wrapped or justified block's paragraph, laid out once per change. */
    private record Laid(CgTextLayout layout, CgFont font, float wrap) {
    }

    private final CgCanvasMaterials materials;
    private final CgCanvasRetainer retainer;
    /** Measures text: a canvas of its own, recording nothing, as a draw is measured under its canvas's camera. */
    private final CgCanvas measuring = new CgCanvas();
    private final Map<String, CgTexture2D> textures = new HashMap<>();
    private final Map<CgMaterialDefinition, CgMaterial> ui = new HashMap<>();
    private final Matrix4f inRegion = new Matrix4f();
    private float retainedWidth = 512f;
    private float retainedHeight = 512f;
    private float margin = 8f;

    private CgCanvas canvas;
    private CgRecording recording;
    private float texelsPerUnit = 1f;
    @Nullable
    private CgCanvas retainedCanvas;
    private boolean retainedClosed;
    private int target = -1;
    private float[] regions = new float[16 * 4];
    private int count;
    private float shelfX;
    private float shelfY;
    private float shelfHigh;

    /**
     * A painter drawing under {@code materials}: {@code SCREEN} for a HUD, {@code WORLD} for a canvas in the world;
     * {@code name} names its retained image in a trace.
     */
    public CgCanvasWidgetPainter(String name, CgCanvasMaterials materials) {
        this.materials = Objects.requireNonNull(materials, "materials");
        this.retainer = new CgCanvasRetainer(name);
    }

    /**
     * Its retained image: {@code width} x {@code height} units, each region given {@code margin} units of room on every
     * side for what draws past its box (an outline, a slant). 512 x 512 and 8 unless set.
     */
    public CgCanvasWidgetPainter retaining(float width, float height, float margin) {
        this.retainedWidth = width;
        this.retainedHeight = height;
        this.margin = margin;
        return this;
    }

    /** Builds {@code tree}'s UI materials, nested trees' too: from a load task, so no first draw builds one. */
    public CgCanvasWidgetPainter prepare(CgWidgetTree tree) {
        for (int i = 0; i < tree.size(); i++) {
            CgWidget w = tree.widget(i);
            if (w.brush().material() != null) material(w.brush().material());
            if (w.effect() != null) material(w.effect());
            if (w.widget() != null) prepare(w.widget());
        }
        return this;
    }

    /**
     * Starts a frame: draws go onto {@code canvas}; the retained image is painted into {@code recording} at
     * {@code texelsPerUnit}, the largest a retained region is drawn at, so it is never magnified.
     */
    public CgCanvasWidgetPainter begin(CgCanvas canvas, CgRecording recording, float texelsPerUnit) {
        this.canvas = Objects.requireNonNull(canvas, "canvas");
        this.recording = Objects.requireNonNull(recording, "recording");
        this.texelsPerUnit = texelsPerUnit;
        retainedCanvas = null;
        retainedClosed = false;
        target = -1;
        count = 0;
        shelfX = shelfY = shelfHigh = 0f;
        return this;
    }

    /** Releases the retained image into {@code recording}: when the HUD is done with it. */
    public void release(CgRecording recording) {
        retainer.release(recording);
    }

    @Nullable
    @Override
    public Object layout(CgTextStyle style, String text, float wrap, CgWidget.Justification justification,
                         Vector2f out) {
        if (wrap <= 0f && justification == CgWidget.Justification.LEFT) {
            measuring.text().style(style).text(text).measureSize(out);
            return null;
        }
        // Lines justified across the wrap width, as STextBlock's; the box is the widest line's.
        CgFont font = style.font(Math.max(1, Math.round(style.px())));
        CgTextAlign align = switch (justification) {
            case CENTRE -> CgTextAlign.CENTER;
            case RIGHT -> CgTextAlign.RIGHT;
            case LEFT -> CgTextAlign.LEFT;
        };
        CgTextLayout.Request request = CgTextLayout.of(text, font).align(align);
        if (wrap > 0f) request.maxWidth(wrap);
        CgTextLayout layout = request.build();
        out.set(layout.totalWidth(), layout.totalHeight());
        return new Laid(layout, font, wrap);
    }

    @Override
    public void image(CgWidgetBrush brush, Matrix4f pose, float width, float height, float r, float g, float b,
                      float a) {
        if (a <= 0f) return;
        CgCanvas on = canvas();
        Matrix4f at = pose(pose);
        if (brush.material() != null) {
            // A UI material takes its tint in linear, as Slate's material shaders read the vertex colour.
            on.quad(material(brush.material())).at(0f, 0f).size(width, height).pose(at).color(linear(r, g, b, a)).submit();
            return;
        }
        var quad = on.quad(materials.image()).at(0f, 0f).size(width, height).pose(at)
                     .color(CgLinearColour.argb(r, g, b, a));
        if (brush.texture() != null) quad.texture(0, texture(brush.texture()));
        quad.submit();
    }

    @Override
    public void text(CgTextStyle style, String text, @Nullable Object laidOut, CgWidget.Justification justification,
                     Matrix4f pose, float width, float r, float g, float b, float a) {
        if (a <= 0f || text.isEmpty()) return;
        CgCanvas on = canvas();
        Matrix4f at = pose(pose);
        int argb = CgLinearColour.argb(r, g, b, a);
        if (!(laidOut instanceof Laid laid)) {
            on.text().style(style).text(text).at(0f, 0f).pose(at).color(argb).submit();
            return;
        }
        CgTextLayout layout = laid.layout();
        float f = switch (justification) {
            case CENTRE -> 0.5f;
            case RIGHT -> 1f;
            case LEFT -> 0f;
        };
        // The layout justifies its lines across the wrap width; place the widest line's box in the widget's.
        float inset = laid.wrap() > 0f ? (laid.wrap() - layout.totalWidth()) * f : 0f;
        float x = (width - layout.totalWidth()) * f - inset;
        on.text().style(style).layout(layout).font(laid.font()).contained(false).at(x, 0f).pose(at).color(argb).submit();
    }

    @Override
    public int beginRetained(float width, float height) {
        if (retainedClosed) {
            throw new IllegalStateException("a retainer box was retained after a draw onto the canvas: retain every "
                    + "widget before painting any");
        }
        if (target >= 0) throw new IllegalStateException("a retainer box inside another is not drawn");
        if (retainedCanvas == null) retainedCanvas = retainer.paint(recording, retainedWidth, retainedHeight, texelsPerUnit);
        float w = width + 2f * margin, h = height + 2f * margin;
        if (shelfX + w > retainedWidth) {
            shelfX = 0f;
            shelfY += shelfHigh;
            shelfHigh = 0f;
        }
        if (w > retainedWidth || shelfY + h > retainedHeight) {
            throw new IllegalStateException("this frame's retainer boxes need more than " + retainedWidth + " x "
                    + retainedHeight + " units: raise retaining(...)");
        }
        if (count * 4 + 4 > regions.length) regions = Arrays.copyOf(regions, regions.length * 2);
        regions[count * 4] = shelfX;
        regions[count * 4 + 1] = shelfY;
        regions[count * 4 + 2] = w;
        regions[count * 4 + 3] = h;
        shelfX += w;
        shelfHigh = Math.max(shelfHigh, h);
        target = count;
        return count++;
    }

    @Override
    public void endRetained() {
        target = -1;
    }

    @Override
    public void retained(int region, @Nullable CgMaterialDefinition effect, String textureParameter, Matrix4f pose,
                         float width, float height, float r, float g, float b, float a) {
        closeRetained();
        if (a <= 0f) return;
        float x = regions[region * 4], y = regions[region * 4 + 1];
        float w = regions[region * 4 + 2], h = regions[region * 4 + 3];
        if (effect == null) {
            // SRetainerWidget: one premultiplied box; the colour is straight, the material premultiplies it.
            retainer.quad(canvas, materials.image(CgBlendMode.PREMULTIPLIED), x, y, w, h).at(-margin, -margin).pose(pose)
                    .color(CgLinearColour.argb(r, g, b, a)).submit();
            return;
        }
        CgMaterial m = material(effect);
        int unit = m.samplerUnit(textureParameter);
        if (unit < 0) throw new IllegalStateException(effect.id() + " has no sampler " + textureParameter);
        retainer.quad(canvas, m, unit, x, y, w, h).at(-margin, -margin).pose(pose).color(linear(r, g, b, a)).submit();
    }

    /** Where draws go: a retained region while one is open, else the canvas, closing the retained image first. */
    private CgCanvas canvas() {
        if (target >= 0) return retainedCanvas;
        closeRetained();
        return canvas;
    }

    private void closeRetained() {
        if (retainedCanvas != null && !retainedClosed) retainer.end();
        retainedClosed = true;
    }

    /** {@code pose} into the open region, its box {@link #margin} in from the region's corner. */
    private Matrix4f pose(Matrix4f pose) {
        if (target < 0) return pose;
        return inRegion.translation(regions[target * 4] + margin, regions[target * 4 + 1] + margin, 0f).mul(pose);
    }

    /** The shared material, which {@link CgWidgetPreparer} names to the precache: a draw never builds it. */
    private CgMaterial material(CgMaterialDefinition definition) {
        CgMaterial m = ui.get(definition);
        if (m == null) {
            m = CgMaterial.of(definition);
            m.precache();
            ui.put(definition, m);
        }
        return m;
    }

    private static int linear(float r, float g, float b, float a) {
        return byteOf(a) << 24 | byteOf(r) << 16 | byteOf(g) << 8 | byteOf(b);
    }

    private static int byteOf(float value) {
        return Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
    }

    private CgTexture2D texture(String path) {
        CgTexture2D t = textures.get(path);
        if (t == null) {
            t = CgTextures.of(path, CgTextureSpec.RGBA8_LINEAR);
            textures.put(path, t);
        }
        return t;
    }
}
