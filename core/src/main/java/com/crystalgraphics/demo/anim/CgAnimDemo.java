package com.crystalgraphics.demo.anim;

import com.crystalgraphics.api.font.CgDefaultFont;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.asset.load.CgAssetMounts;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.demo.CgDemoStyles;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.world.actor.kit.CgKitActor;
import com.crystalgraphics.world.actor.kit.CgKitActorFile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The animation demo: a station per feature of the engine's animation, each on its own set, standing in a row along
 * +x from where it is placed. Its characters are the engine's bundled humanoid bodies and any kit character it is
 * given, each with its own clips. In Minecraft it is {@code CgRenderDemo}'s {@code anim} scene, its kit characters
 * {@code actors.yaml}'s; the harness's {@code anim-demo} plays it too.
 *
 * <pre>{@code
 * CgAnimDemo demo = new CgAnimDemo(List.of(heroSpec));   // kit characters, with their clips folders
 * demo.precache();                                        // starts loading; render thread
 * // each frame, once demo.ready():
 * demo.submit(view, x, y, z, seconds);                    // placed at (x, y, z), y the ground
 * if (pressed) demo.press(key, shift);                    // a key of CgAnimDemo.KEYS
 * hudText += demo.hud();
 * }</pre>
 *
 * <ul>
 *   <li>The station nearest the camera is the one in view: it is updated and takes the keys.</li>
 *   <li>Placed somewhere else, every station is built again there; {@link #clear} removes them all.</li>
 *   <li>A kit character whose files or materials fail is logged and left out; the rest still play.</li>
 * </ul>
 */
public final class CgAnimDemo {

    private static final Logger LOGGER = LogManager.getLogger(CgAnimDemo.class);

    /** How high over each station's origin its name stands, in blocks. */
    private static final double LABEL_Y = 4;
    /** Blocks between stations along x. */
    public static final int SPACING = 14;
    /** The stations' keys, which neither Minecraft nor {@code CgRenderDemo} uses. */
    public static final int[] KEYS = {CgKeyCodes.KEY_LEFT, CgKeyCodes.KEY_RIGHT, CgKeyCodes.KEY_UP, CgKeyCodes.KEY_DOWN,
            CgKeyCodes.KEY_GRAVE, CgKeyCodes.KEY_BACKSLASH};

    private final List<CgKitActor.Spec> kitCharacters;
    private final List<AnimDemoStation> stations = List.of(new ClipsStation(), new IkStation(), new ReachStation(), new RetargetStation(),
            new LocomotionStation(), new MotionWarpingStation());
    private final CgFont labelFont = CgDefaultFont.bold(48);
    /** Each station's name over it: its number, the key that brings the harness's camera to it, then its name. */
    private final String[] titles = new String[stations.size()];
    private AnimDemoCasts casts;
    private AnimDemoTime time;
    private AnimDemoSet set;
    private boolean built;
    private double x, y, z, lastSeconds = Double.NaN;
    private int active;

    /** A demo with {@code kitCharacters} beside the humanoids, each with the clips of its spec's {@code clips} folders. */
    public CgAnimDemo(List<CgKitActor.Spec> kitCharacters) {
        this.kitCharacters = List.copyOf(kitCharacters);
        for (int i = 0; i < titles.length; i++) titles[i] = (i + 1) + "  " + stations.get(i).name();
    }

    /**
     * The characters of every mounted namespace carrying an {@code actor.yaml} at its root ({@code CgKitActorFile.readActor}):
     * the engine's own {@code kk} in a dev run (Goku), and a mod's kit the same way. The shipped jar leaves {@code kk}
     * out, so a release has none of its own.
     *
     * <pre>{@code
     * List<CgKitActor.Spec> all = new ArrayList<>(CgKitActorFile.read().values());
     * all.addAll(CgAnimDemo.demoKits());
     * CgAnimDemo demo = new CgAnimDemo(all);
     * }</pre>
     */
    public static List<CgKitActor.Spec> demoKits() {
        List<CgKitActor.Spec> out = new ArrayList<>();
        for (String ns : CgAssetMounts.namespaces()) {
            Path folder = CgAssetMounts.folder(ns);
            if (folder == null || !Files.isRegularFile(folder.resolve("actor.yaml"))) continue;
            CgKitActor.Spec spec = CgKitActorFile.readActor(folder);
            if (spec != null) out.add(spec);
        }
        LOGGER.info("[anim-demo] {} kit characters mounted", out.size());
        return out;
    }

    /** Starts loading its characters. Render thread; once. */
    public void precache() {
        if (casts == null) {
            casts = AnimDemoCasts.load(kitCharacters);
            time = new AnimDemoTime(casts);
        }
    }

    /** Whether its characters have loaded or failed: it draws nothing until then. Render thread. */
    public boolean ready() {
        return casts != null && casts.settled();
    }

    /**
     * Places the demo with its first station at ({@code x}, {@code y}, {@code z}), {@code y} the ground, building
     * every station there when it moved, and runs the station nearest {@code view}. {@code seconds} is the demo's clock.
     */
    public void submit(CgCamera view, double x, double y, double z, double seconds) {
        if (!ready()) return;
        if (!built || x != this.x || y != this.y || z != this.z) {
            clear();
            this.x = x;
            this.y = y;
            this.z = z;
            set = new AnimDemoSet();
            for (int i = 0; i < stations.size(); i++) stations.get(i).build(set, casts, x + i * SPACING, y, z);
            built = true;
            LOGGER.info("[anim-demo] {} stations at ({}, {}, {}): {}", stations.size(), x, y, z, names());
        }
        active = Math.max(0, Math.min(stations.size() - 1, (int) Math.round((view.x() - x) / SPACING)));
        float dt = Double.isNaN(lastSeconds) ? 0f : (float) Math.max(0.0, seconds - lastSeconds);
        lastSeconds = seconds;
        stations.get(active).update(dt);
        time.tickSteps();
        for (int i = 0; i < stations.size(); i++) {
            CgScene.client().label(titles[i]).at(x + i * SPACING, y + LABEL_Y, z).height(0.6f)
                    .font(labelFont).style(CgDemoStyles.LABEL).submit();
        }
    }

    /**
     * This frame's demo seconds from {@code frameSeconds} of the host's, under the demo's rate and pause; sets the
     * client world's time dilation to match. A host passing its own clock to {@link #submit} needs none of it.
     *
     * <pre>{@code
     * seconds += demo.advance(frame.getDeltaTime());
     * demo.submit(view, x, y, z, seconds);
     * }</pre>
     */
    public float advance(float frameSeconds) {
        return time == null ? frameSeconds : time.advance(frameSeconds);
    }

    /** P: pauses or plays. */
    public void togglePause() {
        if (time != null) time.togglePause();
    }

    /** U/I: the rate {@code by} steps along 0.1x to 2x. */
    public void faster(int by) {
        if (time != null) time.faster(by);
    }

    /** , .: pauses, then {@code frames} frames of 1/30 s on or back; a step back shows a recorded frame. */
    public void step(int frames) {
        if (time != null) time.step(frames);
    }

    /** A key of {@link #KEYS}, for the station in view; true when it took it. */
    public boolean press(int key, boolean shift) {
        return built && stations.get(active).press(key, shift);
    }

    /** The HUD's lines, each starting with a line break: the stations, the one in view marked, then its own. */
    public String hud() {
        if (!ready()) return "\nLoading the characters";
        StringBuilder line = new StringBuilder("\nStations (walk to one):");
        for (int i = 0; i < stations.size(); i++) {
            String name = stations.get(i).name();
            line.append(i == active ? "  [" + (i + 1) + " " + name + "]" : "  " + (i + 1) + " " + name);
        }
        if (time != null) line.append(time.hud());
        return built ? line.append(stations.get(active).hud()).toString() : line.toString();
    }

    public int stations() {
        return stations.size();
    }

    /**
     * Where a camera stands to look at station {@code i}, as it is placed now: x, y, z, then yaw and pitch in degrees
     * (yaw 0 looks along -z).
     */
    public float[] view(int i) {
        float[] v = stations.get(i).view().clone();
        v[0] += (float) (x + i * SPACING);
        v[1] += (float) y;
        v[2] += (float) z;
        return v;
    }

    /** Removes every station's bodies and blocks; the next {@link #submit} builds them again. */
    public void clear() {
        if (!built) return;
        for (AnimDemoStation s : stations) s.delete();
        set.delete();
        set = null;
        built = false;
        lastSeconds = Double.NaN;
    }

    public void delete() {
        clear();
        if (time != null) time.delete();
    }

    private String names() {
        StringBuilder out = new StringBuilder();
        for (AnimDemoStation s : stations) out.append(out.length() == 0 ? "" : ", ").append(s.name().toLowerCase(Locale.ROOT));
        return out.toString();
    }
}
