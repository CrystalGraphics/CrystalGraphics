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
import com.crystalgraphics.api.animation.CgSkeleton;
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
 * Goku's lunging strike landing on an opponent wherever they stand. Two lanes side by side, each with an opponent at
 * the same distance and angle: on the left his lunge is motion warped toward the opponent, stopping at arm's length;
 * on the right it plays as drawn, so it falls short, overshoots or misses wide. Each round the opponents stand
 * somewhere new.
 *
 * <p>The clip is Kakarot's {@code CMB_S_Range05_Cut}: a crouch, a 2.6 m lunge in 0.37 s, the strike held. Its travel
 * is on the pelvis, so it is moved onto the root at load (encode root bone); the lunge is its warp window, the target
 * stood off 0.9 m (where the fist meets the opponent), and he turns to face them as he goes.</p>
 *
 * <p>Keys: Left/Right how far the opponents stand, Up the left opponent sidestepping mid-lunge, Down the stand-off,
 * ` the paths, \ a round now.</p>
 */
final class MotionWarpingStation implements AnimDemoStation {

    private static final String CLIP = "CMB_S_Range05_Cut";
    /** The lunge, the clip's seconds: its travel, then the strike connects. */
    private static final float LUNGE_START = 0.2f, LUNGE_END = 0.6f;
    /** Where the fist meets the opponent, from their centre, metres. */
    private static final float STAND_OFF = 0.9f;
    /** Standing before the lunge, and holding after the clip, seconds. */
    private static final float LEAD = 0.6f, HOLD = 1.0f;
    /**
     * The opponents' distances, metres; 0 for a random one. The clip reaches 2.4 m at 16 m/s at its fastest; past about
     * 1.5 times that a lunge reads as a teleport (measured: 4.2 m peaks at 22 m/s, 5.5 m at 29), and a game dashes in first.
     */
    private static final float[] DISTANCES = {0f, 1.6f, 3.0f, 4.2f};
    private static final double LANE = 1.9;
    private static final int TRAIL = 256;

    private final CgFont font = CgDefaultFont.bold(36);
    private final Matrix4f step = new Matrix4f();
    private final Vector3f t = new Vector3f();
    private final Quaternionf q = new Quaternionf();
    private final double[] own = new double[32 * 3];
    @Nullable
    private Lane warped, plain;
    @Nullable
    private CgSequenceDefinition lunge;
    private CgAnimSequence clip, plainClip, stand;
    private double x, y, z;
    private float roundClock, roundLength, reach, fitScale;
    private int distance, ownCount;
    private boolean moving, standOff = true, paths = true;
    private String status = "";

    /** One lane: a Goku, his opponent, where each stands, and his path. */
    private final class Lane implements CgSequenceSkeletal {
        final AnimDemoCast.Body goku;
        @Nullable
        final AnimDemoCast.Body opponent;
        final boolean warps;
        final double laneX;
        final double[] trail = new double[TRAIL * 3];
        int trailCount;
        double oppX, oppZ, hitX, hitZ;
        float knock, flash;
        boolean struck;
        String verdict = "";
        @Nullable
        CgSequencePlayer playing;

        Lane(AnimDemoCast cast, @Nullable AnimDemoCast foe, Matrix4d fit, boolean warps, double laneX) {
            this.warps = warps;
            this.laneX = laneX;
            goku = cast.spawn();
            goku.instance.placedBy(out -> out.translation(goku.x, goku.y, goku.z).rotateY(goku.yaw).mul(fit));
            goku.instance.play(stand, true);
            opponent = foe == null ? null : foe.spawn();
            if (opponent != null && !foe.clips.isEmpty()) opponent.instance.play(foe.clips.get(0).sequence(), true);
        }

        @Override public CgAnimInstance instance() {
            return goku.instance;
        }

        @Override public CgAnimSequence clip(String id) {
            return warps ? clip : plainClip;
        }

        @Override public int slot(String name) {
            return -1;
        }

        @Override public void set(int slot, float[] values, int mask) {
        }

        @Override public void get(int slot, float[] out) {
        }

        double startX() {
            return laneX;
        }

        double startZ() {
            return z + 3;
        }

        void reset(double dist, double angle) {
            if (playing != null) playing.stop();
            playing = null;
            oppX = startX() + Math.sin(angle) * dist;
            oppZ = startZ() + Math.cos(angle) * dist;
            goku.at(startX(), y, startZ(), (float) Math.PI);
            trailCount = 0;
            knock = flash = 0f;
            struck = false;
            verdict = "";
            aim();
        }

        /** The opponent where it stands, facing where Goku started, and Goku's warp target there. */
        void aim() {
            float facing = (float) Math.atan2(startX() - oppX, startZ() - oppZ);
            if (opponent != null) opponent.at(oppX, y, oppZ, facing);
            goku.instance.motionWarping().target("opponent", oppX, y, oppZ, facing + (float) Math.PI)
                    .standOff("opponent", standOff ? STAND_OFF : 0f);
        }

        void lunge() {
            playing = CgSequencer.of(CgWorld.client()).play(lunge, Map.of("caster", this));
        }

        void update(float dt, float clipTime) {
            if (warps && moving && goku.instance.motionWarping().open() != null) {
                oppX += 2.5 * dt;
                aim();
            }
            if (goku.instance.montages().consumeRootMotion(1f, step)) {
                step.getTranslation(t).mul(fitScale).rotateY(goku.yaw);
                step.getNormalizedRotation(q);
                goku.at(goku.x + t.x, goku.y + t.y, goku.z + t.z, goku.yaw + (float) (2 * Math.atan2(q.y, q.w)));
            }
            if (playing != null && trailCount < TRAIL) {
                trail[trailCount * 3] = goku.x;
                trail[trailCount * 3 + 1] = y + 0.05;
                trail[trailCount * 3 + 2] = goku.z;
                trailCount++;
            }
            // the strike: where the fist is as the lunge ends
            if (!struck && clipTime >= LUNGE_END) {
                struck = true;
                double fx = goku.x + Math.sin(goku.yaw) * STAND_OFF, fz = goku.z + Math.cos(goku.yaw) * STAND_OFF;
                double miss = Math.hypot(fx - oppX, fz - oppZ);
                hitX = fx;
                hitZ = fz;
                if (miss < 0.45) {
                    verdict = "HIT";
                    knock = 1f;
                    flash = 0.35f;
                } else {
                    verdict = String.format(Locale.ROOT, "MISS by %.1f m", miss);
                }
            }
            // knocked back along the blow, easing out
            if (knock > 0f && opponent != null) {
                float before = knock;
                knock = Math.max(0f, knock - dt / 0.35f);
                double push = 1.4 * (before * before - knock * knock);
                oppX += Math.sin(goku.yaw) * push;
                oppZ += Math.cos(goku.yaw) * push;
                opponent.at(oppX, y, oppZ, goku.yaw + (float) Math.PI);
            }
            flash = Math.max(0f, flash - dt);
        }

        void draw() {
            int ring = verdict.equals("HIT") ? 0xFFFF4040 : 0xFFFFB040;
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI * 2 / 24, c = Math.PI * 2 / 24;
                CgCollisionDebug.line(oppX + 0.5 * Math.cos(a), y + 0.03, oppZ + 0.5 * Math.sin(a), oppX + 0.5 * Math.cos(a + c), y + 0.03,
                        oppZ + 0.5 * Math.sin(a + c), ring);
            }
            if (flash > 0f) {
                double r = 0.3 + (0.35 - flash) * 3;
                for (int i = 0; i < 16; i++) {
                    double a = i * Math.PI * 2 / 16;
                    CgCollisionDebug.line(hitX, y + 1.2, hitZ, hitX + r * Math.cos(a), y + 1.2 + r * Math.sin(a) * 0.6, hitZ + r * Math.sin(a),
                            0xFFFFF0A0);
                }
            }
            String title = warps ? (standOff ? "warped, stops at arm's length" : "warped onto the target") : "as drawn";
            CgScene.client().label(title).at(startX(), y + 2.6, startZ()).height(0.24f).font(font).style(CgDemoStyles.LABEL).submit();
            if (!verdict.isEmpty()) {
                CgScene.client().label(verdict).at(oppX, y + 2.3, oppZ).height(0.32f).font(font).style(CgDemoStyles.LABEL).submit();
            }
            if (!paths) return;
            for (int i = 1; i < ownCount; i++) {
                CgCollisionDebug.line(startX() - own[(i - 1) * 3], y + 0.04, startZ() - own[(i - 1) * 3 + 2], startX() - own[i * 3], y + 0.04,
                        startZ() - own[i * 3 + 2], 0xFF9AA0A8);
            }
            for (int i = 1; i < trailCount; i++) {
                CgCollisionDebug.line(trail[(i - 1) * 3], trail[(i - 1) * 3 + 1], trail[(i - 1) * 3 + 2], trail[i * 3], trail[i * 3 + 1],
                        trail[i * 3 + 2], warps ? 0xFF40E0FF : 0xFFFFB040);
            }
        }

        void remove() {
            if (playing != null) playing.stop();
            goku.remove();
            if (opponent != null) opponent.remove();
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
            if (exact(c, CLIP) != null && c.clip("FLD_Stand") != null && c.mesh.skeleton().indexOf("pelvis") >= 0) cast = c;
        }
        if (cast == null || CgSequencer.of(CgWorld.client()) == null) {
            status = "\nNo kit character with Kakarot's lunging strike loaded";
            return;
        }
        CgSkeleton s = cast.mesh.skeleton();
        CgAnimSequence encoded = CgClipModifiers.encodeRootBone(exact(cast, CLIP), s, "root", "pelvis");
        clip = encoded.with(encoded.settings().withWarpWindows(List.of(CgWarpWindow.of("opponent", LUNGE_START, LUNGE_END)
                .facing(true).rotation(CgWarpWindow.Rotation.SLERP_WITH_CLAMPED_RATE, 0.6f, 540f))));
        plainClip = encoded;
        stand = cast.clip("FLD_Stand").sequence();
        fitScale = cast.fit.getScale(new Vector3f()).y;
        Matrix4f m = new Matrix4f();
        Vector3f p = new Vector3f();
        reach = encoded.extractRootMotion(s, 0f, LUNGE_END, false, m).getTranslation(p).length() * fitScale;
        // the clip's own path from a start facing -z, a point every 1/30 s
        ownCount = Math.min(32, (int) (LUNGE_END * 30) + 1);
        for (int i = 0; i < ownCount; i++) {
            encoded.extractRootMotion(s, 0f, i * LUNGE_END / (ownCount - 1), false, m).getTranslation(p);
            own[i * 3] = p.x * fitScale;
            own[i * 3 + 2] = p.z * fitScale;
        }
        Matrix4d fit = new Matrix4d(cast.fit);
        AnimDemoCast foe = casts.humanoid();
        warped = new Lane(cast, foe, fit, true, x - LANE);
        plain = new Lane(cast, foe, fit, false, x + LANE);
        lunge = sequence("demo:lunge", encoded.duration());
        roundLength = LEAD + encoded.duration() + HOLD;
        for (Lane l : new Lane[]{warped, plain}) {
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI * 2 / 24;
                set.box(l.startX() + 0.45 * Math.cos(a) - 0.04, y - 0.01, l.startZ() + 0.45 * Math.sin(a) - 0.04, l.startX() + 0.45 * Math.cos(a) + 0.04,
                        y + 0.01, l.startZ() + 0.45 * Math.sin(a) + 0.04, 0xFF6A7A8A);
            }
        }
        round();
        status = "";
    }

    /** The cast's clip named exactly {@code name}'s ending, not a longer one that contains it. */
    @Nullable
    static CgAnimSequence exact(AnimDemoCast cast, String name) {
        for (AnimDemoCast.Clip c : cast.clips) {
            String n = c.name();
            int dot = n.lastIndexOf('.');
            if ((dot < 0 ? n : n.substring(0, dot)).endsWith(name)) return c.sequence();
        }
        return null;
    }

    /** A sequence of one section, its clip the caster's lunge. */
    static CgSequenceDefinition sequence(String id, float seconds) {
        int ms = Math.round(seconds * 1000);
        return CgSequenceDefinition.compile(CgAssetData.of(id, CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(
                "_v: 1\ntick_resolution: 1000\ndisplay_rate: 60\nrange: [0, " + ms + "]\nroles:\n  - { id: b1, name: caster, kind: caster }\n"
                        + "tracks:\n  - { id: t1, kind: animation, binding: { role: caster }, sections: [ { id: s1, range: [0, " + ms
                        + "], clip: kk:lunge, ease: { in: 150, out: 300 } } ] }\n"), null));
    }

    /** A round: both Gokus back at their marks, the opponents somewhere new, at the same distance and angle. */
    private void round() {
        roundClock = 0f;
        double dist = DISTANCES[distance] > 0f ? DISTANCES[distance] : 1.4 + 2.8 * Math.random();
        double angle = Math.PI + Math.toRadians(-30 + 60 * Math.random());
        warped.reset(dist, angle);
        plain.reset(dist, angle);
    }

    @Override
    public float[] view() {
        return new float[]{0f, 3.2f, 8.5f, 0f, -14f};
    }

    @Override
    public void update(float dt) {
        if (warped == null) return;
        roundClock += dt;
        if (roundClock > roundLength) round();
        if (roundClock >= LEAD && warped.playing == null) {
            warped.lunge();
            plain.lunge();
        }
        float clipTime = roundClock - LEAD;
        for (Lane l : new Lane[]{warped, plain}) {
            l.update(dt, warped.playing == null ? -1f : clipTime);
            l.draw();
        }
    }

    @Override
    public boolean press(int key, boolean shift) {
        if (warped == null) return false;
        switch (key) {
            case CgKeyCodes.KEY_RIGHT -> distance = (distance + 1) % DISTANCES.length;
            case CgKeyCodes.KEY_LEFT -> distance = (distance + DISTANCES.length - 1) % DISTANCES.length;
            case CgKeyCodes.KEY_UP -> moving = !moving;
            case CgKeyCodes.KEY_DOWN -> standOff = !standOff;
            case CgKeyCodes.KEY_GRAVE -> paths = !paths;
            case CgKeyCodes.KEY_BACKSLASH -> round();
            default -> {
                return false;
            }
        }
        if (key == CgKeyCodes.KEY_RIGHT || key == CgKeyCodes.KEY_LEFT || key == CgKeyCodes.KEY_DOWN) round();
        return true;
    }

    @Override
    public String hud() {
        if (warped == null) return status;
        String at = DISTANCES[distance] > 0f ? String.format(Locale.ROOT, "%.1f m", DISTANCES[distance]) : "anywhere from 1.4 to 4.2 m";
        return String.format(Locale.ROOT, "\nGoku's lunging strike covers %.1f m on its own. Left: warped to the opponent. Right: as drawn", reach)
                + "\nLeft/Right: the opponents stand " + at
                + "\nUp: the left opponent " + (moving ? "sidesteps as he lunges (re-aimed every frame)" : "stands still")
                + "\nDown: stand-off " + (standOff ? String.format(Locale.ROOT, "%.1f m (the fist meets them)", STAND_OFF) : "off (onto their centre)")
                + "\n`: paths " + (paths ? "on (grey the clip's own, cyan warped, orange as drawn)" : "off") + "   \\: a round now";
    }

    @Override
    public void delete() {
        if (warped != null) warped.remove();
        if (plain != null) plain.remove();
        warped = plain = null;
    }
}
