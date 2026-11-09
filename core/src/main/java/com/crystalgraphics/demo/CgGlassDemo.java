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
 * Every look of {@link CgCanvasGlass} on a rounded pane in the world: a row of seven side by side, fixed where the scene
 * was placed and facing where the camera stood then, each over the world itself, its name above. Drawn through the
 * transparent stage's canvas after the world renderer, as a mod draws glass; each pane captures and blurs what is
 * behind it ({@link CgCanvasBackdrop}).
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

    private final CgFont font;
    private final CgCanvasGlass[] panes = new CgCanvasGlass[LOOKS.length];
    private final CgCanvasGlass.Look[] looks = new CgCanvasGlass.Look[LOOKS.length];
    private final CgCanvasBackdrop backdrop = new CgCanvasBackdrop("demo.glass");
    private final CgRenderStage.Registration drawing;
    private final Matrix4f pose = new Matrix4f();

    private boolean active;
    /** The point the row was laid out for, and the turn every pane takes: set when the point moves, kept otherwise. */
    private double laidX = Double.NaN, laidZ = Double.NaN;
    private float yaw;
    /** Where pane {@code i} stands, absolute. */
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

    /**
     * The row this frame, centred on {@code (x, y, z)}, the ground under it. Laid out across the camera's line to the
     * point when the point moves (the demo moves it only for a new scene, a new level or a jump), and fixed there.
     */
    public void submit(CgWorldRenderer world, CgHostView view, double x, double y, double z, double seconds) {
        if (x != laidX || z != laidZ) {
            laidX = x;
            laidZ = z;
            double toX = view.x() - x, toZ = view.z() - z, length = Math.max(1e-6, Math.hypot(toX, toZ));
            yaw = (float) Math.atan2(toX, toZ);
            for (int i = 0; i < LOOKS.length; i++) {
                double offset = (i - (LOOKS.length - 1) * 0.5) * SPACING;
                at[i * 3] = x - toZ / length * offset;
                at[i * 3 + 2] = z + toX / length * offset;
            }
        }
        for (int i = 0; i < LOOKS.length; i++) {
            at[i * 3 + 1] = y + LIFT;
            world.text(NAMES[i]).at(at[i * 3], at[i * 3 + 1] + HEIGHT * 0.5 + 0.45, at[i * 3 + 2]).height(0.4f)
                    .font(font).style(CgDemoStyles.LABEL).submit();
        }
        active = true;
    }

    private void draw(CgStageFrame frame) {
        if (!active) return;
        CgCanvas canvas = frame.canvas();
        pose.rotationY(yaw);
        for (int i = 0; i < LOOKS.length; i++) {
            double px = at[i * 3], py = at[i * 3 + 1], pz = at[i * 3 + 2];
            CgQuadBatch.Quad quad = canvas.quad(panes[i].material()).world(px, py, pz).pose(pose)
                    .at(-WIDTH * 0.5f, -HEIGHT * 0.5f).size(WIDTH, HEIGHT);
            CgCanvasGlass.Look look = looks[i];
            panes[i].look(look).size(WIDTH, HEIGHT).radii(RADIUS)
                    .backdrop(backdrop.capture(canvas, quad, look.blur(), panes[i].reach()))
                    .apply();
            quad.submit();
        }
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
