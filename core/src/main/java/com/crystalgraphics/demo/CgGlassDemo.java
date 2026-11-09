package com.crystalgraphics.demo;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.canvas.CgCanvasBackdrop;
import com.crystalgraphics.render.canvas.CgCanvasGlass;
import com.crystalgraphics.render.canvas.CgCanvasMaterials;
import com.crystalgraphics.render.canvas.CgQuadBatch;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;

/**
 * Every look of {@link CgCanvasGlass} on a rounded pane in the world: a row of seven across the view, each turned to
 * the camera and swaying, over a rainbow banner scrolling behind it, its name above. Drawn through the transparent
 * stage's canvas after the world renderer, as a mod draws glass; each pane captures and blurs what is behind it
 * ({@link CgCanvasBackdrop}), the banner, the world and the panes before it.
 *
 * <pre>{@code
 * CgGlassDemo glass = new CgGlassDemo(font);
 * glass.precache();                                      // then ready() each frame until true
 * glass.submit(world, view, x, y, z, seconds);           // each frame, its point on the ground
 * glass.clear();                                         // draws nothing until the next submit
 * }</pre>
 *
 * <p>{@code CgRenderDemo}'s {@code glass}.</p>
 */
public final class CgGlassDemo {

    private static final CgCanvasGlass.Look[] LOOKS = {CgCanvasGlass.DEFAULT, CgCanvasGlass.CLEAR, CgCanvasGlass.FROSTED,
            CgCanvasGlass.LIQUID, CgCanvasGlass.ACRYLIC, CgCanvasGlass.MICA, CgCanvasGlass.PRISM};
    private static final String[] NAMES = {"Default", "Clear", "Frosted", "Liquid", "Acrylic", "Mica", "Prism"};
    /** A block is 40 of a look's pixels. */
    private static final float UNITS_PER_PIXEL = 1f / 40f;
    private static final float WIDTH = 2.4f, HEIGHT = 1.6f, RADIUS = 0.3f, SPACING = 3.2f, LIFT = 2.2f;
    private static final float BANNER_WIDTH = 3.1f, BANNER_HEIGHT = 2.4f, BANNER_BACK = 0.7f;
    private static final int STRIPES = 14;

    private final CgFont font;
    private final CgCanvasGlass[] panes = new CgCanvasGlass[LOOKS.length];
    private final CgCanvasGlass.Look[] looks = new CgCanvasGlass.Look[LOOKS.length];
    private final CgCanvasBackdrop backdrop = new CgCanvasBackdrop("demo.glass");
    private final CgRenderStage.Registration drawing;
    private final Matrix4f pose = new Matrix4f();

    private boolean active;
    private double x, y, z;
    private double seconds;
    /** Where pane {@code i} stands, absolute, refreshed each submit. */
    private final double[] at = new double[LOOKS.length * 3];

    public CgGlassDemo(CgFont font) {
        this.font = font;
        for (int i = 0; i < LOOKS.length; i++) {
            panes[i] = new CgCanvasGlass(CgCanvasMaterials.WORLD);
            looks[i] = LOOKS[i].scaled(UNITS_PER_PIXEL);
        }
        // After the world renderer's draws and before the post stack: the glass sees the world and lands in the scene.
        drawing = CgRenderStage.WORLD_TRANSPARENT.register(CgWorldRenderer.ORDER + 10, this::draw);
    }

    /** Starts compiling every look's program. */
    public void precache() {
        ready();
    }

    /** Whether every look's program is built: it draws nothing until then. */
    public boolean ready() {
        boolean all = true;
        for (int i = 0; i < panes.length; i++) all &= panes[i].look(looks[i]).precache();
        return all;
    }

    /** The row this frame, centred on {@code (x, y, z)}, the ground under it, across the camera's line to it. */
    public void submit(CgWorldRenderer world, CgHostView view, double x, double y, double z, double seconds) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.seconds = seconds;
        double toX = view.x() - x, toZ = view.z() - z, length = Math.max(1e-6, Math.hypot(toX, toZ));
        double acrossX = -toZ / length, acrossZ = toX / length;
        for (int i = 0; i < LOOKS.length; i++) {
            double offset = (i - (LOOKS.length - 1) * 0.5) * SPACING;
            at[i * 3] = x + acrossX * offset;
            at[i * 3 + 1] = y + LIFT;
            at[i * 3 + 2] = z + acrossZ * offset;
            world.text(NAMES[i]).at(at[i * 3], at[i * 3 + 1] + HEIGHT * 0.5 + 0.45, at[i * 3 + 2]).height(0.4f)
                    .font(font).stroke(0.08f, 0xFF000000).submit();
        }
        active = true;
    }

    private void draw(CgStageFrame frame) {
        if (!active) return;
        CgHostView view = frame.host().view();
        CgCanvas canvas = frame.canvas();
        for (int i = 0; i < LOOKS.length; i++) {
            double px = at[i * 3], py = at[i * 3 + 1], pz = at[i * 3 + 2];
            float yaw = (float) Math.atan2(view.x() - px, view.z() - pz)
                    + 0.35f * (float) Math.sin(seconds * 0.7 + i * 0.9);
            pose.rotationY(yaw);
            banner(canvas, px, py, pz, i);
            CgQuadBatch.Quad quad = canvas.quad(panes[i].material()).world(px, py, pz).pose(pose)
                    .at(-WIDTH * 0.5f, -HEIGHT * 0.5f).size(WIDTH, HEIGHT);
            CgCanvasGlass.Look look = looks[i];
            panes[i].look(look).size(WIDTH, HEIGHT).radii(RADIUS)
                    .backdrop(backdrop.capture(canvas, quad, look.blur(), panes[i].reach()))
                    .apply();
            quad.submit();
        }
    }

    /** Rainbow stripes scrolling across a panel behind pane {@code i}, turned as it is. */
    private void banner(CgCanvas canvas, double px, double py, double pz, int i) {
        float stripe = BANNER_WIDTH / STRIPES;
        for (int s = 0; s < STRIPES; s++) {
            float hue = (float) ((s / (double) STRIPES + seconds * 0.08 + i / 7.0) % 1.0);
            canvas.quad(CgCanvasMaterials.WORLD.image()).world(px, py, pz).pose(pose)
                    .at(-BANNER_WIDTH * 0.5f + s * stripe, -BANNER_HEIGHT * 0.5f, -BANNER_BACK)
                    .size(stripe, BANNER_HEIGHT).color(hue(hue)).submit();
        }
    }

    /** A saturated colour around the wheel, opaque ARGB. */
    private static int hue(float h) {
        float r = channel(h + 1f / 3f), g = channel(h), b = channel(h - 1f / 3f);
        return 0xFF000000 | Math.round(r * 255f) << 16 | Math.round(g * 255f) << 8 | Math.round(b * 255f);
    }

    private static float channel(float h) {
        float t = ((h % 1f) + 1f) % 1f;
        return Math.max(0f, Math.min(1f, Math.abs(t * 6f - 3f) - 1f));
    }

    /** Draws nothing until the next submit. */
    public void clear() {
        active = false;
    }

    /** Stops drawing and frees its backdrop. */
    public void delete() {
        active = false;
        drawing.close();
        backdrop.delete();
    }
}
