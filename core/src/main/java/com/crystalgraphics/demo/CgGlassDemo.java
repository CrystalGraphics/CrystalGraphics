package com.crystalgraphics.demo;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.paint.effect.CgBackdropFilter;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgQuadComponent;
import org.joml.Quaternionf;

/**
 * Every {@link CgBackdropFilter} preset on a rounded pane in the world: a row of seven side by side, fixed where the
 * scene was placed and facing where the camera stood then, each over the world itself, its name above. Scene quads with
 * a backdrop filter, as a mod draws glass: each reads the scene behind it, the panes behind it included.
 *
 * <pre>{@code
 * CgGlassDemo glass = new CgGlassDemo(font);
 * glass.submit(scene, view, x, y, z, seconds);           // each frame, its point on the ground
 * glass.clear();                                         // the panes gone until the next submit
 * }</pre>
 *
 * <p>{@code CgRenderDemo}'s {@code glass}.</p>
 */
public final class CgGlassDemo {

    private static final CgBackdropFilter[] FILTERS = {CgBackdropFilter.DEFAULT, CgBackdropFilter.CLEAR,
            CgBackdropFilter.FROSTED, CgBackdropFilter.LIQUID, CgBackdropFilter.ACRYLIC, CgBackdropFilter.MICA,
            CgBackdropFilter.PRISM};
    private static final String[] NAMES = {"Default", "Clear", "Frosted", "Liquid", "Acrylic", "Mica", "Prism"};
    /** A block is 40 of a look's pixels. */
    private static final float UNITS_PER_PIXEL = 1f / 40f;
    private static final float WIDTH = 2.4f, HEIGHT = 1.6f, RADIUS = 0.3f, SPACING = 3.2f, LIFT = 2.2f;

    private final CgFont font;
    private final CgQuadComponent[] panes = new CgQuadComponent[FILTERS.length];
    private final Quaternionf turn = new Quaternionf();
    /** The scene the panes are in. */
    private CgScene in;
    /** The point the row was laid out for, and the turn every pane takes: set when the point moves, kept otherwise. */
    private double laidX = Double.NaN, laidY = Double.NaN, laidZ = Double.NaN;
    /** Where pane {@code i} stands, absolute. */
    private final double[] at = new double[FILTERS.length * 3];

    public CgGlassDemo(CgFont font) {
        this.font = font;
    }

    /**
     * The row this frame, centred on {@code (x, y, z)}, the ground under it. Laid out across the camera's line to the
     * point when the point moves (the demo moves it only for a new scene, a new level or a jump), and fixed there.
     */
    public void submit(CgScene scene, CgCamera view, double x, double y, double z, double seconds) {
        if (scene != in) clear();
        boolean moved = x != laidX || y != laidY || z != laidZ;
        if (moved) {
            laidX = x;
            laidY = y;
            laidZ = z;
            double toX = view.x() - x, toZ = view.z() - z, length = Math.max(1e-6, Math.hypot(toX, toZ));
            turn.rotationY((float) Math.atan2(toX, toZ));
            for (int i = 0; i < FILTERS.length; i++) {
                double offset = (i - (FILTERS.length - 1) * 0.5) * SPACING;
                at[i * 3] = x - toZ / length * offset;
                at[i * 3 + 1] = y + LIFT;
                at[i * 3 + 2] = z + toX / length * offset;
            }
        }
        for (int i = 0; i < FILTERS.length; i++) {
            if (panes[i] == null) {
                panes[i] = scene.quad(WIDTH, HEIGHT, null).backdropFilter(FILTERS[i].scaled(UNITS_PER_PIXEL))
                        .radii(RADIUS).at(at[i * 3], at[i * 3 + 1], at[i * 3 + 2]).rotation(turn).spawn();
            } else if (moved) {
                panes[i].at(at[i * 3], at[i * 3 + 1], at[i * 3 + 2]).rotation(turn);
            }
            scene.labelOnce(NAMES[i]).at(at[i * 3], at[i * 3 + 1] + HEIGHT * 0.5 + 0.45, at[i * 3 + 2]).height(0.4f)
                    .font(font).style(CgDemoStyles.LABEL).submit();
        }
        in = scene;
    }

    /** Removes the panes until the next submit. */
    public void clear() {
        for (int i = 0; i < panes.length; i++) {
            if (panes[i] != null) panes[i].remove();
            panes[i] = null;
        }
        in = null;
        laidX = laidY = laidZ = Double.NaN;
    }
}
