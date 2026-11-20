package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.render.precache.CgPipelineList;
import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.paint.CgPaint;
import com.crystalgraphics.api.paint.effect.CgBackdropFilter;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.target.CgRenderTarget;
import com.crystalgraphics.widget.CgUserWidget;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.gpu.CgGpuScene;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;

/**
 * A rectangle in the world drawn with any surface material: a sign, a pane, an icon over a block, a billboard. Through
 * the mesh passes like any mesh: depth-tested, sorted when translucent, resolved by TSR. Facing the camera it is
 * Unreal's {@code UMaterialBillboardComponent}; turned, a plane.
 *
 * <pre>{@code
 * CgMaterial sign = CgSurfaceMaterial.of("mymod:sign", "mymod:textures/sign.png").shading(CgShadingModels.UNLIT).build();
 * CgQuadComponent board = scene.quad(2f, 1f, sign).at(x, y + 1, z).rotation(facingSouth).spawn();
 * CgQuadComponent marker = scene.quad(0.5f, 0.5f, icon).facing(CgQuadComponent.Facing.CAMERA).at(x, y + 2, z).spawn();
 * }</pre>
 *
 * <ul>
 *   <li>It is centred on its point, facing +Z before its rotation; its width runs along X, its height along Y.</li>
 *   <li>Its size is its scale on X and Y: {@code size(w, h)} is {@code scale(w, h, 1)}.</li>
 *   <li>Facing the camera it turns with each view, so it draws no velocity: as Unreal's billboards, a moving one
 *       relies on TSR's colour clamp. Set {@link #facing} before it spawns.</li>
 *   <li>A colour per quad without a material per quad: {@code customData(0, r, g, b, a)}, read as
 *       {@code CG_OBJECT_CUSTOM0} by a material of your own.</li>
 * </ul>
 *
 * <p>A picture painted by the 2D canvas instead of a material: a gradient, an SVG icon, anything a canvas draws. It is
 * painted at the size the quad stands on the screen, so it stays sharp up close, and again only when that size steps
 * or {@link #repaint} says it changed.</p>
 *
 * <pre>{@code
 * scene.quad(1f, 1f, null).fill(CgBrush.svg(CgSvg.of("mymod:ui/icons/quest.svg"))).facing(Facing.CAMERA).at(x, y, z).spawn();
 * scene.quad(3f, 1f, null).paint((canvas, w, h) -> {                         // w x h device pixels
 *     canvas.box(0, 0, w, h).fill(0xC0101418).radius(h * 0.2f).draw();
 *     canvas.text(title, h * 0.2f, h * 0.2f);
 * }).at(x, y + 2, z).spawn();
 * board.repaint();                                                             // what it paints changed
 * }</pre>
 *
 * <p>A user widget in the world, UMG's {@code UWidgetComponent} in world space: laid out at its design size and painted
 * into the picture, again only when something it shows changes.</p>
 *
 * <pre>{@code
 * CgUserWidget board = QUEST_BOARD.loaded().newInstance();
 * scene.quad(2f, 1.2f, null).widget(board, 500f, 300f).at(x, y + 2, z).rotation(facingSouth).spawn();
 * board.text("Title", "Gather 10 crystals");                                  // repainted next frame
 * }</pre>
 *
 * <ul>
 *   <li>Its picture is translucent, premultiplied, unlit and two-sided; painted, the quad's material is its own.</li>
 *   <li>A widget's design size is stretched over the quad: give the quad the same aspect.</li>
 *   <li>Glass ({@link #backdropFilter}) draws instead of either, reading the scene behind it as the frame's
 *       back-to-front pass has drawn it so far: a pane sees the panes behind it, never those in front.</li>
 *   <li>Painted before the frame's first view, on the render thread: a painter reads what it shows, nothing more.</li>
 * </ul>
 */
public final class CgQuadComponent extends CgPrimitiveComponent<CgQuadComponent> {

    /** How it turns toward a view. */
    public enum Facing {
        /** Its own rotation: a plane in the world. */
        NONE,
        /** Square to the screen: Unreal's sprite. */
        CAMERA,
        /** Turned about the world's Y axis toward the view, upright: a tree card, a name plate. */
        UPRIGHT
    }

    /** Shared by every quad: a unit square, so its scale is its size; v from its top, as an image's rows run. */
    private static final CgMesh QUAD = CgMesh.build(CgVertexFormat.SPATIAL, m -> {
        int a = m.vertex().position(-0.5f, -0.5f, 0f).uv(0f, 1f).normal(0f, 0f, 1f).color(0xFFFFFFFF).end();
        int b = m.vertex().position(0.5f, -0.5f, 0f).uv(1f, 1f).normal(0f, 0f, 1f).color(0xFFFFFFFF).end();
        int c = m.vertex().position(0.5f, 0.5f, 0f).uv(1f, 0f).normal(0f, 0f, 1f).color(0xFFFFFFFF).end();
        int d = m.vertex().position(-0.5f, 0.5f, 0f).uv(0f, 0f).normal(0f, 0f, 1f).color(0xFFFFFFFF).end();
        m.quad(a, b, c, d);
    });

    private CgMaterialInterface material;
    private Facing facing = Facing.NONE;
    private int slot = -1;
    private final CgMeshCollector collector = new CgMeshCollector();
    private final CgAABB faced = new CgAABB();
    private final Quaternionf turn = new Quaternionf();
    private final Matrix3f basis = new Matrix3f();

    /** What paints its picture: a canvas {@code width} by {@code height} device pixels, the quad's whole face. */
    @FunctionalInterface
    public interface Painter {
        void paint(CgCanvas canvas, int width, int height);
    }

    /** A pane's material: the scene behind it through a backdrop filter. */
    public static final String GLASS_SHADER = "crystalgraphics:shaders/materials/surface/glass.shader";
    /** A picture's material: what the canvas painted, premultiplied and unlit. */
    public static final String PICTURE_SHADER = "crystalgraphics:shaders/materials/surface/picture.shader";
    /** The heights a picture is painted at, in device pixels: a power of two between these. */
    private static final int MIN_PICTURE = 16, MAX_PICTURE = 2048;

    @Nullable
    private CgMaterialInstance glass;
    private float radius;
    /** The size {@code glass} was last given, so a move that keeps it writes nothing. */
    private float glassWidth = Float.NaN, glassHeight = Float.NaN;
    @Nullable
    private Painter painter;
    /** What a widget it shows had changed to when last painted: {@link CgUserWidget#changes}. */
    @Nullable
    private CgUserWidget widget;
    private long widgetChanges;
    private long revision;
    private long paintedRevision = -1;
    @Nullable
    private CgRenderTarget picture;
    @Nullable
    private CgMaterialInstance pictureMaterial;
    /** The tallest it stood on any view since it was last painted, device pixels; 0 before any view saw it. */
    private float seenPx;

    CgQuadComponent(CgScenePrimitives scene, float width, float height, @Nullable CgMaterialInterface material) {
        super(scene);
        this.material = material;
        size(width, height);
    }

    /** Its width and height, in blocks: its scale on X and Y. */
    public CgQuadComponent size(float width, float height) {
        return scale(width, height, 1f);
    }

    /** What draws it: any surface material. A painted quad draws its picture instead. */
    public CgQuadComponent material(CgMaterialInterface material) {
        requireLive();
        this.material = Objects.requireNonNull(material, "material");
        return this;
    }

    /**
     * A pane of glass: the scene behind it through {@code filter}, other panes included. Its lens and rim are in the
     * quad's units, blocks in Minecraft, so a look tuned in pixels is {@code scaled}; its blur stays in screen pixels.
     */
    public CgQuadComponent backdropFilter(CgBackdropFilter filter) {
        requireLive();
        Objects.requireNonNull(filter, "filter");
        if (glass == null) glass = CgMaterial.load(GLASS_SHADER).instance();
        glass.set1f("_Blur", filter.blur());
        filter.writeTo(glass);
        return radii(radius);
    }

    /** Its corners rounded by {@code radius}, in its units: a pane's outline and lens. */
    public CgQuadComponent radii(float radius) {
        this.radius = Math.max(0f, radius);
        if (glass != null) glass.vec4("_CornerRadius", this.radius, this.radius, this.radius, this.radius);
        return this;
    }

    /** Its picture filled with {@code paint}: a colour, a gradient, a brush, an SVG. */
    public CgQuadComponent fill(CgPaint paint) {
        Objects.requireNonNull(paint, "paint");
        return paint((canvas, w, h) -> canvas.fill(0f, 0f, w, h, paint));
    }

    /** Its picture painted by {@code painter} through the 2D canvas, at the size it stands on the screen. */
    public CgQuadComponent paint(Painter painter) {
        requireLive();
        this.painter = Objects.requireNonNull(painter, "painter");
        if (spawned()) scene.pictures.add(this);
        return repaint();
    }

    /**
     * {@code widget} on it, laid out at {@code designWidth} by {@code designHeight} widget units and stretched over the
     * quad; painted again whenever anything it shows changes. Render thread, as the widget.
     */
    public CgQuadComponent widget(CgUserWidget widget, float designWidth, float designHeight) {
        Objects.requireNonNull(widget, "widget");
        if (!(designWidth > 0f && designHeight > 0f)) {
            throw new IllegalArgumentException("design size " + designWidth + " x " + designHeight + ": both above 0");
        }
        widget.allot(designWidth, designHeight);
        paint((canvas, w, h) -> {
            canvas.save().scale(w / designWidth, h / designHeight);
            widget.paint(canvas);
            canvas.restore();
        });
        this.widget = widget;
        widgetChanges = widget.changes();
        return this;
    }

    /** What its painter paints changed: painted again before the next frame's first view. */
    public CgQuadComponent repaint() {
        revision++;
        return this;
    }

    /** Its own material; null for a painted quad given none. */
    @Nullable
    public CgMaterialInterface material() {
        return material;
    }

    /** How it turns toward each view; {@link Facing#NONE} by default. Before it spawns. */
    public CgQuadComponent facing(Facing facing) {
        if (spawned()) throw new IllegalStateException("a quad's facing is set before it spawns");
        this.facing = Objects.requireNonNull(facing, "facing");
        return this;
    }

    public Facing facing() {
        return facing;
    }

    /** A turned quad's draw is kept; a facing one is built per view. */
    @Override
    boolean keepsCommands() {
        return facing == Facing.NONE;
    }

    @Override
    public boolean keepsPrevious() {
        return facing == Facing.NONE;
    }

    @Override
    void onSpawn() {
        slot = scene.gpu().add();
        scene.writeRecord(this, slot);
        if (painter != null) scene.pictures.add(this);
    }

    @Override
    void onRemove() {
        scene.gpu().remove(slot);
        slot = -1;
        scene.pictures.remove(this);
        if (picture != null) {
            picture.release();
            picture = null;
        }
    }

    /** What draws it this frame: its glass, else its picture once painted, else its own material. */
    @Nullable
    private CgMaterialInterface drawn(Matrix4d w) {
        if (glass != null) {
            float width = (float) columnLength(w, 0), height = (float) columnLength(w, 1);
            if (width != glassWidth || height != glassHeight) {
                glass.vec2("_BoxSize", width, height);
                glassWidth = width;
                glassHeight = height;
            }
            return glass;
        }
        return painter != null ? pictureMaterial : material;
    }

    /**
     * Engine side, before the frame's first view: paints its picture where it changed or the size it stands at stepped
     * to another power of two. Render thread, outside a recording.
     */
    void updatePicture(CgCanvas canvas) {
        if (painter == null) return;
        if (widget != null && widget.changes() != widgetChanges) {
            widgetChanges = widget.changes();
            revision++;
        }
        int height = tier(seenPx > 0f ? seenPx : 256f);
        double aspect = columnLength(world(), 0) / Math.max(1e-6, columnLength(world(), 1));
        int width = Math.max(1, Math.min(MAX_PICTURE * 2, (int) Math.round(height * aspect)));
        boolean resized = picture == null || picture.height() != height || picture.width() != width;
        if (!resized && paintedRevision == revision) return;
        if (resized) {
            if (picture != null) picture.release();
            picture = CgRenderTarget.create("quad_picture", width, height, CgRenderTarget.Format.RGBA16F)
                    .clearColor(0f, 0f, 0f, 0f);
            if (pictureMaterial == null) pictureMaterial = CgMaterial.load(PICTURE_SHADER).instance();
            pictureMaterial.sampler("_Picture", 0, picture.texture(), CgSampler.BILINEAR_CLAMP);
        }
        boolean degraded;
        try (CgExecutor.Into ignored = CgExecutor.into(picture.beginDraw(false))) {
            canvas.beginNow(width, height);
            long before = canvas.degradedDraws();
            painter.paint(canvas, width, height);
            degraded = canvas.degradedDraws() != before;
            canvas.end();
        }
        picture.drawn();
        // Text drawn below its tier while its glyphs generate: kept, it would stay that way, so painted again next frame
        paintedRevision = degraded ? -1 : revision;
        seenPx = 0f;
    }

    /** Engine side, the world's precache: a picture's and a pane's materials, in every pass the world draws them. */
    public static void precache(CgPipelineList list) {
        list.add(CgMaterial.load(PICTURE_SHADER), false);
        list.add(CgMaterial.load(GLASS_SHADER), false);
    }

    /** {@code px} rounded up to a power of two in the picture's range. */
    private static int tier(float px) {
        int h = MIN_PICTURE;
        while (h < px && h < MAX_PICTURE) h <<= 1;
        return h;
    }

    /** Its height on {@code camera}'s screen, device pixels: what its picture is painted at. */
    private void measure(CgCamera camera, Matrix4d w) {
        // The view is relative to the camera's origin: the camera itself for a host's
        Matrix4f v = camera.view();
        double px = w.m30() - camera.originX(), py = w.m31() - camera.originY(), pz = w.m32() - camera.originZ();
        double depth = -(v.m02() * px + v.m12() * py + v.m22() * pz + v.m32());
        if (depth <= 1e-3) return;
        float tall = (float) (columnLength(w, 1) * camera.projection().m11() * 0.5 * camera.height() / depth);
        if (tall > seenPx) seenPx = tall;
    }

    @Override
    void onMoved() {
        scene.gpu().transform(slot, world());
    }

    @Override
    void onLooked() {
        scene.writeValues(this, slot);
    }

    @Override
    boolean kindBounds(CgAABB out) {
        QUAD.bounds(out);
        return true;
    }

    /** Facing, it may turn any way about its point: a cube by its larger side. */
    @Override
    @Nullable
    public CgAABBc worldBounds() {
        if (facing == Facing.NONE) return super.worldBounds();
        Matrix4d w = world();
        double half = 0.5 * Math.max(columnLength(w, 0), columnLength(w, 1));
        return faced.setCenterHalf(w.m30(), w.m31(), w.m32(), half, half, half);
    }

    @Override
    void gather(CgView view, CgCamera camera, CgMeshBatches out, CgSceneGather gather) {
        Matrix4d w = world();
        CgMaterialInterface m = drawn(w);
        if (m == null) return;
        if (painter != null) measure(camera, w);
        if (facing == Facing.NONE) {
            out.object(batch(out, QUAD, m, -1), CgGpuScene.id(slot));
            return;
        }
        Matrix4f v = camera.view();
        // The view's axes in the world: its rows. Upright keeps Y and turns the forward axis level.
        float rx = v.m00(), ry = v.m10(), rz = v.m20();
        float ux = v.m01(), uy = v.m11(), uz = v.m21();
        float bx = v.m02(), by = v.m12(), bz = v.m22();
        if (facing == Facing.UPRIGHT) {
            float len = (float) Math.sqrt(bx * bx + bz * bz);
            if (len < 1e-6f) {
                bx = 0f;
                bz = 1f;
            } else {
                bx /= len;
                bz /= len;
            }
            by = 0f;
            ux = 0f;
            uy = 1f;
            uz = 0f;
            rx = bz;
            ry = 0f;
            rz = -bx;
        }
        basis.set(rx, ry, rz, ux, uy, uz, bx, by, bz);
        turn.setFromNormalized(basis);
        collector.begin(out, this, camera, gather);
        collector.mesh(QUAD, m).at(w.m30(), w.m31(), w.m32()).rotation(turn)
                .scale((float) columnLength(w, 0), (float) columnLength(w, 1), 1f).add();
    }

    private static double columnLength(Matrix4d m, int c) {
        double x = m.getRowColumn(0, c), y = m.getRowColumn(1, c), z = m.getRowColumn(2, c);
        return Math.sqrt(x * x + y * y + z * z);
    }
}
