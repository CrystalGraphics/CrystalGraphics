package com.crystalgraphics.demo.anim;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgClipModifiers;
import com.crystalgraphics.api.animation.CgWarpWindow;
import com.crystalgraphics.api.font.CgDefaultFont;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.collision.debug.CgCollisionDebug;
import com.crystalgraphics.demo.CgDemoStyles;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.animation.CgSequenceSkeletal;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/**
 * Goku's back attack landing on an opponent wherever they stand. His clip dives a fixed path; its first second is a
 * warp window toward "opponent", so each round, with the opponent somewhere new (nearer or further than the clip
 * reaches, left or right of its line), the dive is bent to end on them, its pacing kept. The clip's travel is on his
 * pelvis; it is moved onto his root at load (encode root bone), so it is root motion to warp.
 *
 * <ul>
 *   <li>Grey: where the clip alone would take him from where he starts. Cyan: where he went. A ring: the opponent.</li>
 *   <li>Moving: the opponent sidesteps while he dives, and the window re-aims every frame.</li>
 * </ul>
 *
 * <p>Keys: Left/Right how far the opponent stands, Up the opponent sidestepping, Down warping on/off, ` the paths,
 * \ a round now.</p>
 */
final class MotionWarpingStation implements AnimDemoStation {

    /** The window: the dive is over by then. */
    private static final float WINDOW_END = 1.0f;
    /** The opponent's distance, as the clip's reach: random between the first two, or one of the last two. */
    private static final String[] RANGES = {"anywhere", "near", "far"};
    private static final int TRAIL = 512;

    private final CgFont font = CgDefaultFont.bold(36);
    private final Matrix4f step = new Matrix4f();
    private final Vector3f t = new Vector3f();
    private final Quaternionf q = new Quaternionf();
    private final double[] trail = new double[TRAIL * 3], own = new double[64 * 3];
    @Nullable
    private Goku goku;
    @Nullable
    private AnimDemoCast.Body opponent;
    @Nullable
    private CgSequenceDefinition warped;
    @Nullable
    private CgSequencePlayer playing;
    private CgAnimSequence clip, plainClip;
    private double x, y, z, startX, startZ, oppX, oppZ, side;
    private float startYaw, roundClock, roundLength, reach, fitScale;
    private int range, trailCount, ownCount;
    private boolean moving, warping = true, paths = true;
    private String status = "";

    /** Goku as a sequence's caster: its rush is the station's clip whatever the section names. */
    private final class Goku implements CgSequenceSkeletal {
        final AnimDemoCast.Body body;

        Goku(AnimDemoCast.Body body, Matrix4d fit) {
            this.body = body;
            body.instance.placedBy(out -> out.translation(body.x, body.y, body.z).rotateY(body.yaw).mul(fit));
        }

        @Override public CgAnimInstance instance() {
            return body.instance;
        }

        @Override public CgAnimSequence clip(String id) {
            return warping ? clip : plainClip;
        }

        @Override public int slot(String name) {
            return -1;
        }

        @Override public void set(int slot, float[] values, int mask) {
        }

        @Override public void get(int slot, float[] out) {
        }
    }

    @Override
    public String name() {
        return "Motion warping";
    }

    @Override
    public void build(AnimDemoSet set, AnimDemoCasts casts, double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        AnimDemoCast cast = null;
        for (AnimDemoCast c : casts.kitCasts()) {
            if (c.clip("BTL_BackAtk01_Atk") != null && c.mesh.skeleton().indexOf("pelvis") >= 0) cast = c;
        }
        if (cast == null || CgSequencer.of(CgWorld.client()) == null) {
            status = "\nNo kit character with Kakarot's back attack loaded";
            return;
        }
        CgAnimSequence encoded = CgClipModifiers.encodeRootBone(cast.clip("BTL_BackAtk01_Atk").sequence(), cast.mesh.skeleton(), "root", "pelvis");
        clip = encoded.with(encoded.settings().withWarpWindows(List.of(CgWarpWindow.of("opponent", 0f, WINDOW_END))));
        plainClip = encoded;
        Vector3f s = cast.fit.getScale(new Vector3f());
        fitScale = s.y;
        Matrix4f path = new Matrix4f();
        Vector3f end = clip.extractRootMotion(cast.mesh.skeleton(), 0f, WINDOW_END, false, path).getTranslation(new Vector3f());
        reach = (float) Math.hypot(end.x, end.z) * fitScale;
        // the clip's own path, in its mesh's space scaled to the world, a point every 1/60 s
        ownCount = Math.min(64, (int) (WINDOW_END * 60) + 1);
        for (int i = 0; i < ownCount; i++) {
            Vector3f p = clip.extractRootMotion(cast.mesh.skeleton(), 0f, i * WINDOW_END / (ownCount - 1), false, path).getTranslation(new Vector3f());
            own[i * 3] = p.x * fitScale;
            own[i * 3 + 2] = p.z * fitScale;
        }
        // he starts in front, his clip's own dive aimed away from the camera
        startX = x;
        startZ = z + 4;
        startYaw = (float) (Math.PI - Math.atan2(end.x, end.z));
        goku = new Goku(cast.spawn(), new Matrix4d(cast.fit));
        goku.body.instance.play(cast.clip("BTL_Guard_02LP") != null ? cast.clip("BTL_Guard_02LP").sequence() : clip, true);
        AnimDemoCast foe = casts.humanoid();
        if (foe != null) {
            opponent = foe.spawn();
            if (!foe.clips.isEmpty()) opponent.instance.play(foe.clips.get(0).sequence(), true);
        }
        warped = sequence("demo:motion_warping", clip.duration());
        roundLength = clip.duration() + 0.8f;
        round();
        for (int i = 0; i < 40; i++) {
            double a = i * Math.PI * 2 / 40;
            set.box(startX + 0.6 * Math.cos(a) - 0.04, y - 0.01, startZ + 0.6 * Math.sin(a) - 0.04, startX + 0.6 * Math.cos(a) + 0.04, y + 0.01,
                    startZ + 0.6 * Math.sin(a) + 0.04, 0xFF6A7A8A);
        }
        status = "";
    }

    /** A sequence of one section, its clip the caster's rush. */
    private static CgSequenceDefinition sequence(String id, float seconds) {
        int ms = Math.round(seconds * 1000);
        return CgSequenceDefinition.compile(CgAssetData.of(id, CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(
                "_v: 1\ntick_resolution: 1000\ndisplay_rate: 60\nrange: [0, " + ms + "]\nroles:\n  - { id: b1, name: caster, kind: caster }\n"
                        + "tracks:\n  - { id: t1, kind: animation, binding: { role: caster }, sections: [ { id: s1, range: [0, " + ms
                        + "], clip: kk:rush, ease: { in: 100, out: 250 } } ] }\n"), null));
    }

    /** A round: Goku back at his start, the opponent somewhere new, the dive played. */
    private void round() {
        roundClock = 0f;
        if (playing != null) playing.stop();
        double far = switch (RANGES[range]) {
            case "near" -> 0.2;
            case "far" -> 0.6;
            default -> 0.2 + 0.4 * Math.random();
        };
        double angle = Math.PI + Math.toRadians(-35 + 70 * Math.random());
        oppX = startX + Math.sin(angle) * reach * far;
        oppZ = startZ + Math.cos(angle) * reach * far;
        side = Math.random() < 0.5 ? -1 : 1;
        goku.body.at(startX, y, startZ, startYaw);
        place();
        trailCount = 0;
        playing = CgSequencer.of(CgWorld.client()).play(warped, Map.of("caster", goku));
    }

    /** The opponent where it stands, facing Goku, and his warp target there. */
    private void place() {
        float facing = (float) Math.atan2(oppX - startX, oppZ - startZ);
        if (opponent != null) opponent.at(oppX, y, oppZ, facing + (float) Math.PI);
        // he ends turned along his line of attack
        goku.body.instance.motionWarping().target("opponent", oppX, y, oppZ, facing);
    }

    @Override
    public float[] view() {
        return new float[]{0f, 9f, 16f, 0f, -22f};
    }

    @Override
    public void update(float dt) {
        if (goku == null) return;
        roundClock += dt;
        if (roundClock > roundLength) round();
        if (moving && goku.body.instance.motionWarping().open() != null) {
            oppX += side * 3.0 * dt * Math.cos(startYaw);
            oppZ -= side * 3.0 * dt * Math.sin(startYaw);
            place();
        }
        AnimDemoCast.Body b = goku.body;
        if (b.instance.montages().consumeRootMotion(1f, step)) {
            step.getTranslation(t).mul(fitScale).rotateY(b.yaw);
            step.getNormalizedRotation(q);
            b.at(b.x + t.x, b.y + t.y, b.z + t.z, b.yaw + (float) (2 * Math.atan2(q.y, q.w)));
        }
        if (trailCount < TRAIL) {
            trail[trailCount * 3] = b.x;
            trail[trailCount * 3 + 1] = y + 0.05;
            trail[trailCount * 3 + 2] = b.z;
            trailCount++;
        }
        draw();
    }

    private void draw() {
        CgCollisionDebug.mark(oppX, y + 0.02, oppZ, 0, 1, 0, 0xFFFF5050);
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2 / 24, c = Math.PI * 2 / 24;
            CgCollisionDebug.line(oppX + 0.7 * Math.cos(a), y + 0.03, oppZ + 0.7 * Math.sin(a), oppX + 0.7 * Math.cos(a + c), y + 0.03,
                    oppZ + 0.7 * Math.sin(a + c), 0xFFFF5050);
        }
        CgScene.client().label("opponent").at(oppX, y + 2.4, oppZ).height(0.3f).font(font).style(CgDemoStyles.LABEL).submit();
        CgScene.client().label(warping ? "warped dive" : "the clip's own dive").at(goku.body.x, goku.body.y + 2.4, goku.body.z).height(0.3f)
                .font(font).style(CgDemoStyles.LABEL).submit();
        if (!paths) return;
        double c = Math.cos(startYaw), s = Math.sin(startYaw);
        for (int i = 1; i < ownCount; i++) {
            double ax = own[(i - 1) * 3], az = own[(i - 1) * 3 + 2], bx = own[i * 3], bz = own[i * 3 + 2];
            CgCollisionDebug.line(startX + ax * c + az * s, y + 0.04, startZ - ax * s + az * c, startX + bx * c + bz * s, y + 0.04,
                    startZ - bx * s + bz * c, 0xFF9AA0A8);
        }
        for (int i = 1; i < trailCount; i++) {
            CgCollisionDebug.line(trail[(i - 1) * 3], trail[(i - 1) * 3 + 1], trail[(i - 1) * 3 + 2], trail[i * 3], trail[i * 3 + 1],
                    trail[i * 3 + 2], 0xFF40E0FF);
        }
    }

    @Override
    public boolean press(int key, boolean shift) {
        if (goku == null) return false;
        switch (key) {
            case CgKeyCodes.KEY_RIGHT -> range = (range + 1) % RANGES.length;
            case CgKeyCodes.KEY_LEFT -> range = (range + RANGES.length - 1) % RANGES.length;
            case CgKeyCodes.KEY_UP -> moving = !moving;
            case CgKeyCodes.KEY_DOWN -> warping = !warping;
            case CgKeyCodes.KEY_GRAVE -> paths = !paths;
            case CgKeyCodes.KEY_BACKSLASH -> round();
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public String hud() {
        if (goku == null) return status;
        return String.format(Locale.ROOT, "\nGoku's back attack: a %.1f m dive in %.1f s, its first second a warp window", reach, WINDOW_END)
                + "\nLeft/Right: the opponent stands " + RANGES[range] + (range == 0 ? " (a fifth to three fifths of the dive)" : "")
                + "\nUp: the opponent " + (moving ? "sidesteps as he dives (re-aimed each frame)" : "stands still")
                + "\nDown: warping " + (warping ? "ON" : "OFF (the clip's own path)") + ", from the next round"
                + "\n`: paths " + (paths ? "on (grey the clip's, cyan his)" : "off") + "   \\: a round now";
    }

    @Override
    public void delete() {
        if (playing != null) playing.stop();
        if (goku != null) goku.body.remove();
        if (opponent != null) opponent.remove();
        goku = null;
        opponent = null;
        playing = null;
    }
}
