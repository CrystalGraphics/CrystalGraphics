package com.crystalgraphics.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Matrix4f;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.material.CgTranslucencyPass;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.render.post.CgPost;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.view.CgView;

/**
 * Depth of field to play with: checkered pillars stepping away from the eye, a row of small lights far out and a few
 * just in front of it that blur into the aperture's shape, and three rings of light far out, one a translucency pass
 * ({@code BeforeDOF} blurs, {@code AfterDOF} and {@code AfterMotionBlur} stay sharp). The lens is the client chain's
 * camera settings ({@code CgPost.client().camera()}), as a camera rig's would be. {@code CgRenderDemo}'s {@code dof}
 * and the harness's {@code dof-lab}.
 *
 * <pre>{@code
 * CgDepthOfFieldDemo dof = new CgDepthOfFieldDemo();
 * dof.precache();                                   // then ready() each frame until true
 * dof.submit(CgScene.client(), view, x, y, z);      // laid out from the eye through (x, y, z), the first time
 * dof.press(CgKeyCodes.KEY_UP, false);              // one of KEYS, or the arrows, F and B
 * dof.clear();                                      // removed, and the lens back to the volumes'
 * }</pre>
 *
 * <pre>
 * Up / Down       focus farther / nearer        F         focus on what the centre of the view looks at
 * Left / Right    f-stop wider / narrower       Home      pull focus between the nearest pillar and the rings
 * B / Shift+B     a blade more / fewer          Insert    the lens's widest f-stop, f/1.2 or f/4 (round or bladed)
 * PgUp / PgDn     a larger / smaller format     End       depth of field on or off
 * </pre>
 *
 * <ul>
 *   <li>A larger format at the same field of view is a longer lens: it blurs more, as a wider view blurs less. It is
 *       the view's own FOV the blur follows, so a host's FOV option changes it too.</li>
 *   <li>Laid out once, along the eye's direction to the anchor when first submitted; {@link #clear} lets the next
 *       submit lay it out again.</li>
 * </ul>
 */
public final class CgDepthOfFieldDemo {

    /** Its keys beyond the arrows, F and B, which a host's other demos list already. */
    public static final int[] KEYS = {CgKeyCodes.KEY_HOME, CgKeyCodes.KEY_END, CgKeyCodes.KEY_PRIOR, CgKeyCodes.KEY_NEXT,
            CgKeyCodes.KEY_INSERT};

    private static final String DIR = "crystalgraphics:shaders/demo/";
    /** Unreal's full stops from the lens's widest, f/1.2. */
    private static final float[] STOPS = {1.2f, 1.4f, 2f, 2.8f, 4f, 5.6f, 8f, 11f, 16f, 22f};
    /** Sensor widths in mm: Super 35 (the settings' default), full frame, medium format, 4x5 and 8x10 sheet film. */
    private static final float[] FORMATS = {CgPostSettings.DEFAULT_SENSOR_WIDTH, 36f, 54f, 102f, 203f};
    private static final String[] FORMAT_NAMES = {"Super 35", "full frame", "medium format", "4x5", "8x10"};
    /** How far down the view each pillar stands from the anchor, and the rings and lights. */
    private static final double[] PILLARS = {0, 2, 5, 9, 14, 22, 34};
    private static final double RINGS = 28, LIGHTS = 38, BOARD = 43;
    private static final float NEAR_FOCUS = 3f, FAR_FOCUS = 34f, PULL_SECONDS = 1.5f;

    private CgMaterialInstance solid, light;
    private final CgMaterialInstance[] rings = new CgMaterialInstance[3];
    private final List<CgMeshComponent> spawned = new ArrayList<>();
    /** Every solid's box in the layout's frame (across, up, along the view from the anchor): min, then max. */
    private final List<double[]> boxes = new ArrayList<>();
    private float focus = 8f;
    private int stop = 2, blades = 5, format = 1;
    private boolean on = true, roundOpen = true;
    /** A pull under way: from, to, and its seconds so far; negative, none. */
    private float pullFrom, pullTo, pulled = -1f;
    private double lastSeconds = Double.NaN;
    /** The eye and its forward when last submitted: what F focuses along. */
    private double eyeX, eyeY, eyeZ, lookX, lookY, lookZ = -1;
    private final Matrix4f rotation = new Matrix4f();
    /** The layout's frame: its anchor and its forward along the ground, its right across. */
    private double originX, originY, originZ, fx, fz = -1, sx = 1, sz;

    /** Loads its materials and starts their programs building. */
    public void precache() {
        if (solid != null) return;
        solid = CgMaterial.load(DIR + "dof_solid.shader").instance();
        light = CgMaterial.load(DIR + "dof_light.shader").instance();
        CgTranslucencyPass[] passes = CgTranslucencyPass.values();
        for (int k = 0; k < 3; k++) {
            CgMaterialState state = CgMaterialState.builder().translucencyPass(passes[k]).build();
            rings[k] = CgMaterial.load(DIR + "dof_ring.shader", state).instance();
        }
        ready();
    }

    /** Whether every program it draws with is built. */
    public boolean ready() {
        if (solid == null) return false;
        boolean all = solid.precache() & light.precache();
        for (CgMaterialInstance r : rings) all &= r.precache();
        return all;
    }

    /** Lays the demo out the first time, from {@code view}'s eye through the anchor; then keeps the lens on it. */
    public void submit(CgScene scene, CgCamera view, double x, double y, double z, double seconds) {
        eyeX = view.x();
        eyeY = view.y();
        eyeZ = view.z();
        rotation.set(view.view());
        lookX = -rotation.m02();
        lookY = -rotation.m12();
        lookZ = -rotation.m22();
        if (spawned.isEmpty()) layOut(scene, x, y, z);
        double dt = Double.isNaN(lastSeconds) ? 0 : Math.max(0, seconds - lastSeconds);
        lastSeconds = seconds;
        if (pulled >= 0f) {
            pulled += (float) dt;
            float t = Math.min(1f, pulled / PULL_SECONDS);
            t = t * t * (3f - 2f * t);
            focus = pullFrom * (float) Math.pow(pullTo / pullFrom, t);   // even in stops of distance, as a puller turns it
            if (pulled >= PULL_SECONDS) pulled = -1f;
        }
        CgPost.client().camera().depthOfField(focus, STOPS[stop]).depthOfFieldBladeCount(blades)
                .depthOfFieldMinFstop(roundOpen ? 1.2f : 4f).depthOfFieldSensorWidth(FORMATS[format]);
        CgView.main().showFlags().depthOfField(on);
    }

    /** Pillars, lights and rings down the eye's horizontal direction to the anchor, standing on its height. */
    private void layOut(CgScene scene, double x, double y, double z) {
        fx = x - eyeX;
        fz = z - eyeZ;
        double len = Math.sqrt(fx * fx + fz * fz);
        if (len < 1e-3) {
            fx = lookX;
            fz = lookZ;
            len = Math.max(1e-6, Math.sqrt(fx * fx + fz * fz));
        }
        fx /= len;
        fz /= len;
        sx = -fz;   // to the right of the view
        sz = fx;
        originX = x;
        originY = y;
        originZ = z;
        float yaw = (float) Math.atan2(fx, fz);
        CgMesh cube = CgMeshShapes.cube(), ball = CgMeshShapes.sphere(16, 24);
        float[][] colours = {{0.9f, 0.3f, 0.25f}, {0.95f, 0.75f, 0.3f}, {0.4f, 0.85f, 0.4f}, {0.3f, 0.7f, 0.95f},
                {0.65f, 0.45f, 0.95f}, {0.95f, 0.5f, 0.8f}, {0.85f, 0.85f, 0.85f}};
        for (int i = 0; i < PILLARS.length; i++) {
            double side = i % 2 == 0 ? -1.4 : 1.4;
            double px = x + fx * PILLARS[i] + sx * side, pz = z + fz * PILLARS[i] + sz * side;
            float[] c = colours[i];
            spawned.add(scene.mesh(cube, solid).at(px, y + 1.2, pz).transform(new Matrix4f().rotateY(yaw).scale(0.6f, 2.4f, 0.6f))
                    .customData(0, c[0], c[1], c[2], 1f).spawn());
            boxes.add(new double[]{side - 0.3, 0, PILLARS[i] - 0.3, side + 0.3, 2.4, PILLARS[i] + 0.3});
        }
        double bx = x + fx * BOARD, bz = z + fz * BOARD;
        spawned.add(scene.mesh(cube, solid).at(bx, y + 2.5, bz).transform(new Matrix4f().rotateY(yaw).scale(16f, 5f, 0.2f))
                .customData(0, 0.6f, 0.6f, 0.65f, 1f).spawn());
        boxes.add(new double[]{-8, 0, BOARD - 0.1, 8, 5, BOARD + 0.1});
        // Points of light: a far row the bokeh shows on, and a few just in front of the eye
        for (int i = -8; i <= 8; i++) {
            float hue = (i + 8) / 16f;
            double lx = x + fx * LIGHTS + sx * i * 0.9, lz = z + fz * LIGHTS + sz * i * 0.9;
            spawned.add(scene.mesh(ball, light).at(lx, y + 3.2 + 0.4 * Math.sin(i), lz).scale(0.09f)
                    .customData(0, 1f, 0.55f + 0.45f * hue, 0.3f + 0.7f * (1f - hue), 24f).spawn());
        }
        for (int i = -2; i <= 2; i++) {
            double lx = eyeX + fx * 1.3 + sx * i * 0.5, lz = eyeZ + fz * 1.3 + sz * i * 0.5;
            spawned.add(scene.mesh(ball, light).at(lx, eyeY - 0.7 + 0.15 * i, lz).scale(0.03f).customData(0, 0.6f, 0.9f, 1f, 20f).spawn());
        }
        // The rings, one a pass, left to right, facing the eye
        CgMesh quad = CgMeshShapes.quad(CgVertexFormat.SPATIAL, 1f, 1f);
        float[][] ringColours = {{1f, 0.4f, 0.2f}, {0.3f, 1f, 0.5f}, {0.4f, 0.6f, 1f}};
        for (int k = 0; k < 3; k++) {
            double rx = x + fx * RINGS + sx * (k - 1) * 4.5, rz = z + fz * RINGS + sz * (k - 1) * 4.5;
            float[] c = ringColours[k];
            spawned.add(scene.mesh(quad, rings[k]).at(rx, y + 3, rz).transform(new Matrix4f().rotateY(yaw).scale(3.5f))
                    .customData(0, c[0], c[1], c[2], 6f).spawn());
        }
    }

    /** One of its keys pressed; true when it took it. */
    public boolean press(int key, boolean shift) {
        switch (key) {
            case CgKeyCodes.KEY_UP -> focus = Math.min(300f, focus * 1.12f);
            case CgKeyCodes.KEY_DOWN -> focus = Math.max(0.3f, focus / 1.12f);
            case CgKeyCodes.KEY_LEFT -> stop = Math.max(0, stop - 1);
            case CgKeyCodes.KEY_RIGHT -> stop = Math.min(STOPS.length - 1, stop + 1);
            case CgKeyCodes.KEY_F -> focus = autofocus();
            case CgKeyCodes.KEY_B -> blades = shift ? Math.max(4, blades - 1) : Math.min(16, blades + 1);
            case CgKeyCodes.KEY_PRIOR -> format = Math.min(FORMATS.length - 1, format + 1);
            case CgKeyCodes.KEY_NEXT -> format = Math.max(0, format - 1);
            case CgKeyCodes.KEY_INSERT -> roundOpen = !roundOpen;
            case CgKeyCodes.KEY_END -> on = !on;
            case CgKeyCodes.KEY_HOME -> {
                pullFrom = focus;
                pullTo = Math.abs(focus - NEAR_FOCUS) < Math.abs(focus - FAR_FOCUS) ? FAR_FOCUS : NEAR_FOCUS;
                pulled = 0f;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** The eye distance to the nearest solid the centre of the view looks at, else where the view meets the ground. */
    private float autofocus() {
        // The eye and its look in the layout's frame: a turn about y, so distances along the ray hold
        double ex = eyeX - originX, ez = eyeZ - originZ;
        double[] o = {ex * sx + ez * sz, eyeY - originY, ex * fx + ez * fz};
        double[] d = {lookX * sx + lookZ * sz, lookY, lookX * fx + lookZ * fz};
        double best = Double.MAX_VALUE;
        for (double[] b : boxes) {
            double near = 0, far = Double.MAX_VALUE;
            boolean hit = true;
            for (int a = 0; a < 3 && hit; a++) {
                if (Math.abs(d[a]) < 1e-9) {
                    hit = o[a] >= b[a] && o[a] <= b[a + 3];
                    continue;
                }
                double t1 = (b[a] - o[a]) / d[a], t2 = (b[a + 3] - o[a]) / d[a];
                near = Math.max(near, Math.min(t1, t2));
                far = Math.min(far, Math.max(t1, t2));
                hit = near <= far;
            }
            if (hit && near > 0) best = Math.min(best, near);
        }
        if (best == Double.MAX_VALUE && d[1] < -1e-6) best = -o[1] / d[1];
        return best == Double.MAX_VALUE ? FAR_FOCUS : (float) Math.max(0.3, best);
    }

    /** Its HUD lines, each starting with a line break. */
    public String hud() {
        return String.format(Locale.ROOT, "%nDepth of field [End]: %s | Focus [Up/Down, F, Home pull]: %.2f m | f/%s [Left/Right]"
                        + "%nFormat [PgUp/PgDn]: %s | Blades [B]: %d | Widest [Insert]: f/%s"
                        + "%nRings, left to right: BeforeDOF, AfterDOF, AfterMotionBlur",
                on ? "on" : "off", focus, stopName(STOPS[stop]), FORMAT_NAMES[format], blades, roundOpen ? "1.2" : "4");
    }

    private static String stopName(float f) {
        return f == (int) f ? Integer.toString((int) f) : Float.toString(f);
    }

    /** Removes what it laid out and gives the lens back to the volumes; the next submit lays it out again. */
    public void clear() {
        for (CgMeshComponent c : spawned) c.remove();
        spawned.clear();
        boxes.clear();
        pulled = -1f;
        lastSeconds = Double.NaN;
        CgPost.client().camera().reset();
        CgView.main().showFlags().depthOfField(true);
    }

    public void delete() {
        clear();
    }
}
