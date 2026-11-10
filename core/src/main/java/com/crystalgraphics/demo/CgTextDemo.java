package com.crystalgraphics.demo;

import com.crystalgraphics.api.font.CgDefaultFont;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.text.render.CgTextDraw;
import com.crystalgraphics.text.style.CgTextLayer;
import com.crystalgraphics.text.style.CgTextLayer.Blend;
import com.crystalgraphics.text.style.CgTextPaint;
import com.crystalgraphics.text.style.CgTextPaint.Space;
import com.crystalgraphics.text.style.CgTextStyle;
import org.joml.Matrix4f;
import org.joml.Quaternionf;


/**
 * Seventeen text styles in the world, a station each: a 4×4 grid and Kakarot's "Critical!" left of its front row,
 * fixed where it was placed and facing where the camera stood then, a caption under each word naming what it shows: gradients over a glyph and a line; outlines under a fill,
 * over it and alone; stacked outlines; hard, soft, outlined, inset and additive shadows; skew, letter spacing and case;
 * a counting damage number; and seven shader stations, each a Text-domain material (one shader, {@code text_fx},
 * its effect a value): galaxy, fire, an HSV rainbow, electricity, plasma, a hologram and a glitch. Drawn through the
 * transparent stage's canvas, as a mod draws world text whose style takes an additive or material layer.
 *
 * <pre>{@code
 * CgTextDemo text = new CgTextDemo(labelFont);
 * text.precache();                                      // then ready() each frame until true
 * text.submit(world, view, x, y, z, seconds);           // each frame, its point on the ground
 * text.clear();                                         // draws nothing until the next submit
 * }</pre>
 *
 * <p>{@code CgRenderDemo}'s {@code text}.</p>
 */
public final class CgTextDemo {

    /** Every style is sized at the font's 64 px, so its lengths read as pixels of it. */
    private static final int PX = 64;
    private static final float SIZE = PX * 72f / 96f;
    /** Blocks a pixel of the font is: a line about 1.4 blocks tall. */
    private static final float BLOCKS_PER_PX = 1.4f / PX;
    private static final int COLUMNS = 4;
    /** Hard shadows an extruded outline is drawn with: under a pixel a step at the damage number's 4.7 px. */
    private static final int EXTRUSION_STEPS = 5;
    /** The 4x4 grid, and the critical's word beside the damage number, left of the front row. */
    private static final int STATIONS = 17, CRITICAL_STATION = 16;
    private static final float SPACING = 7.5f, DEPTH = 5.5f, RISE = 1.4f, LIFT = 2.2f;

    /**
     * A critical hit's digits, measured from Kakarot's own atlas (Num_Dmg00_02.png): Arial Black, whose digits carry
     * the atlas's ink weight, sized so they stand its 46 px; a 2.2 px outline, its shape extruded 4.7 px down as the
     * atlas's dark mass is (one offset copy leaves a step at every corner), both (53, 12, 1); the fill's yellow-to-red
     * sampled down the digits from their top row; a slant of 0.57; a 38 px advance. The gradient's stops sit where the face's digits stand
     * in its line (0.26 to 0.79 of it).
     */
    private static final CgTextStyle DAMAGE = extruded(style("damage").font("system:Arial Black").size(46.6f)
            .skew(0.57f).letterSpacing(-55), 0.6f, 4.7f, 0xFF350C01)
            .layer(CgTextLayer.outline("outline").width(2.2f).paint(0xFF350C01))
            .layer(CgTextLayer.fill("fill").paint(CgTextPaint.gradient(Space.LINE, 90f,
                    0.264f, 0xFFFECE30, 0.317f, 0xFFFFC530, 0.527f, 0xFFFF7124, 0.684f, 0xFFFF2F13, 0.789f, 0xFFFE120C)))
            .build();

    private static final CgTextStyle NEON = style("neon")
            .layer(CgTextLayer.shadow("haze").spread(3f).blur(12f).blend(Blend.ADDITIVE).paint(0xC0FF2AA0))
            .layer(CgTextLayer.shadow("tube").spread(1f).blur(3f).blend(Blend.ADDITIVE).paint(0xFFFF60C0))
            .layer(CgTextLayer.fill("core").paint(0xFFFFE6F6))
            .build();

    /** The same tube unlit, as it shows for a beat of a stutter: dim pink glass, its glow all but gone. */
    private static final CgTextStyle NEON_OFF = style("neon_off")
            .layer(CgTextLayer.shadow("haze").spread(3f).blur(12f).blend(Blend.ADDITIVE).paint(0x14FF2AA0))
            .layer(CgTextLayer.fill("glass").paint(0xFF7A5868))
            .build();

    private static final CgTextStyle HOLLOW = style("hollow")
            .layer(CgTextLayer.shadow("glow").blur(6f).blend(Blend.ADDITIVE).paint(0x6040E0FF))
            .layer(CgTextLayer.outline("ring").width(3f).align(CgTextLayer.Align.CENTRE).paint(0xFF60E8FF))
            .build();

    private static final CgTextStyle EMBOSSED = style("embossed")
            .layer(CgTextLayer.shadow("cast").offset(2f, 3f).blur(2f).paint(0x70000000))
            .layer(CgTextLayer.fill("stone").paint(0xFF9AA0A8))
            .layer(CgTextLayer.shadow("light").offset(2f, 2f).blur(1f).inset(true).paint(0xD0FFFFFF))
            .layer(CgTextLayer.shadow("shade").offset(-2f, -2f).blur(1f).inset(true).paint(0xC0202428))
            .build();

    private static final CgTextStyle COMIC = style("comic").skew(-0.12f)
            .layer(CgTextLayer.shadow("pop").offset(9f, 9f).outlined(true).paint(0xFFE01E3C))
            .layer(CgTextLayer.outline("ink").width(6f).paint(0xFF000000))
            .layer(CgTextLayer.fill("fill").paint(0xFFFFE030))
            .build();

    private static final CgTextStyle STACKED = style("stacked")
            .layer(CgTextLayer.outline("violet").width(16f).paint(0xFF5020A0))
            .layer(CgTextLayer.outline("blue").width(12f).paint(0xFF2060E0))
            .layer(CgTextLayer.outline("cyan").width(8f).paint(0xFF20D0F0))
            .layer(CgTextLayer.outline("white").width(4f).paint(0xFFFFFFFF))
            .layer(CgTextLayer.fill("fill").paint(0xFF101830))
            .build();

    private static final CgTextStyle UPPER = style("level_up").transform(CgTextStyle.Transform.UPPER).letterSpacing(300)
            .layer(CgTextLayer.shadow("aura").spread(2f).blur(9f).blend(Blend.ADDITIVE).paint(0x90FFC030))
            .layer(CgTextLayer.outline("gild").width(2f).paint(0xFF6A4000))
            .layer(CgTextLayer.fill("gold").paint(CgTextPaint.gradient(Space.LINE, 90f,
                    0f, 0xFFFFF8D0, 0.5f, 0xFFFFC840, 0.52f, 0xFFD08A10, 1f, 0xFFFFE070)))
            .build();

    private static final CgTextStyle SPEED = style("speed").skew(0.35f)
            .layer(CgTextLayer.shadow("trail3").offset(-30f, 0f).opacity(0.18f).paint(0xFF3080FF))
            .layer(CgTextLayer.shadow("trail2").offset(-20f, 0f).opacity(0.35f).paint(0xFF3080FF))
            .layer(CgTextLayer.shadow("trail1").offset(-10f, 0f).opacity(0.6f).paint(0xFF50A0FF))
            .layer(CgTextLayer.outline("edge").width(2f).paint(0xFF0A1A40))
            .layer(CgTextLayer.fill("fill").paint(0xFFFFFFFF))
            .build();

    private static final CgTextStyle EXTRUDE = extrude();

    private static final String[] CAPTIONS = {
            "Kakarot's critical hit; 1 to 9.9t and back, in units",
            "Additive glow layers; stutters to an unlit style",
            "An outline alone: the ring, centred on the edge",
            "Shader: nebula and twinkling stars, additive halo",
            "Shader: flames rising through the letters",
            "Shader: HSV hue cycling across the word",
            "Shader: bolts crackling over a dark charge",
            "Inset light and shade over a flat fill",
            "Hard offset shadow casting the outline",
            "Four stacked outlines under one fill",
            "Shader: plasma flowing over the run",
            "Shader, additive: scanlines, sweep and flicker",
            "Case transform and tracking: written \"level up!\"",
            "Skew and a motion trail of offset shadows",
            "Shader: torn bands under a static RGB split",
            "Twelve hard shadows: an extruded 3D face",
            "Kakarot's \"Critical!\": live text, not word art",
    };

    private final CgFont font = CgDefaultFont.bold(PX);
    private final CgFont labelFont;
    /** One program for every shader station: each its definition of text_fx.shader with its own effect. */
    private final CgMaterial warm;
    private final CgTextStyle[] styles;
    /** Kakarot's "Critical!" as live text: an asset, so an edit to its file draws on the next frame (hot reload). */
    private static final CgAssetRef<CgTextStyle> CRITICAL_WORD =
            CgVfxAssets.ref(CgLookKinds.TEXT_STYLE, "crystalgraphics:demo/critical");
    /** The definition of {@link #CRITICAL_WORD} whose face was last read, so each reload reads its own once. */
    private CgTextStyle criticalFaced;
    private final String[] words = {"12.3k", "NEON", "HOLLOW", "GALAXY", "INFERNO", "RAINBOW", "ELECTRIC", "Embossed",
            "POW!", "STACKED", "PLASMA", "HOLOGRAM", "level up!", "SPEED", "GLITCH", "EXTRUDE", "Critical!"};
    private final CgRenderStage.Registration drawing;
    private final Matrix4f pose = new Matrix4f();

    private boolean active;
    private double seconds;
    /** The point the grid was last laid out round, and the turn it faces: fixed until the point moves. */
    private double laidX = Double.NaN, laidY, laidZ;
    private float yaw;
    private final Quaternionf facing = new Quaternionf();
    /** Where station {@code i}'s word stands, absolute, refreshed each submit. */
    private final double[] at = new double[STATIONS * 3];
    private long shownDamage = -1;

    public CgTextDemo(CgFont labelFont) {
        this.labelFont = labelFont;
        CgMaterialDefinition plasma = fx("plasma", 0), hologram = fx("hologram", 1), rainbow = fx("rainbow", 2),
                fire = fx("fire", 3), electric = fx("electric", 4), glitch = fx("glitch", 5), galaxy = fx("galaxy", 6);
        CgTextStyle galaxyStyle = style("galaxy").letterSpacing(60)
                .layer(CgTextLayer.shadow("halo").spread(3f).blur(12f).blend(Blend.ADDITIVE).paint(0x907040FF))
                .layer(CgTextLayer.outline("rim").width(2.5f).paint(0xFFE8D8FF))
                .layer(CgTextLayer.fill("space").paint(CgTextPaint.material(galaxy)))
                .build();
        CgTextStyle infernoStyle = style("inferno")
                .layer(CgTextLayer.shadow("heat").offset(0f, -8f).spread(2f).blur(10f).blend(Blend.ADDITIVE)
                        .paint(0xC0FF4A00))
                .layer(CgTextLayer.outline("char").width(2.5f).paint(0xFF2A0600))
                .layer(CgTextLayer.fill("flames").paint(CgTextPaint.material(fire)))
                .build();
        CgTextStyle rainbowStyle = style("rainbow")
                .layer(CgTextLayer.shadow("drop").offset(3f, 4f).blur(3f).paint(0x90000000))
                .layer(CgTextLayer.outline("outline").width(3f).paint(0xFF101010))
                .layer(CgTextLayer.fill("spectrum").paint(CgTextPaint.material(rainbow)))
                .build();
        CgTextStyle electricStyle = style("electric").skew(0.08f)
                .layer(CgTextLayer.shadow("charge").spread(3f).blur(10f).blend(Blend.ADDITIVE).paint(0xA040A0FF))
                .layer(CgTextLayer.outline("insulation").width(2f).paint(0xFF020818))
                .layer(CgTextLayer.fill("bolts").paint(CgTextPaint.material(electric)))
                .build();
        CgTextStyle plasmaStyle = style("plasma")
                .layer(CgTextLayer.shadow("drop").offset(3f, 4f).blur(4f).paint(0xA0000000))
                .layer(CgTextLayer.outline("outline").width(3.5f).paint(0xFF120820))
                .layer(CgTextLayer.fill("plasma").paint(CgTextPaint.material(plasma)))
                .build();
        CgTextStyle hologramStyle = style("hologram").letterSpacing(80)
                .layer(CgTextLayer.shadow("bloom").spread(2f).blur(10f).blend(Blend.ADDITIVE).paint(0x6020C0FF))
                .layer(CgTextLayer.fill("projection").blend(Blend.ADDITIVE).paint(CgTextPaint.material(hologram)))
                .build();
        CgTextStyle glitchStyle = style("glitch").letterSpacing(40)
                .layer(CgTextLayer.shadow("red").offset(-5f, 0f).blend(Blend.ADDITIVE).paint(0xC0FF2040))
                .layer(CgTextLayer.shadow("blue").offset(5f, 1f).blend(Blend.ADDITIVE).paint(0xC02080FF))
                .layer(CgTextLayer.fill("signal").paint(CgTextPaint.material(glitch)))
                .build();
        styles = new CgTextStyle[]{DAMAGE, NEON, HOLLOW, galaxyStyle, infernoStyle, rainbowStyle, electricStyle, EMBOSSED,
                COMIC, STACKED, plasmaStyle, hologramStyle, UPPER, SPEED, glitchStyle, EXTRUDE, null};
        warm = CgMaterial.newInstance(plasma, null);
        warm.toggleKeyword("MSDF_MODE", true);
        // After the world renderer's draws and before the post stack, as world text with additive layers is drawn.
        drawing = CgRenderStage.WORLD_TRANSPARENT.register(CgWorldRenderer.ORDER + 10, this::draw);
    }

    /** Starts compiling the shader stations' program and loading the critical's style, and reads the damage's face. */
    public void precache() {
        DAMAGE.font(Math.round(DAMAGE.px()));
        CRITICAL_WORD.request(CgLoadPriority.PLAYING);
        ready();
    }

    /** Whether the shader stations' program is built: it draws nothing until then. */
    public boolean ready() {
        return warm.precache();
    }

    /** The critical's style as last loaded, its face read once per definition; null until it has loaded. */
    private CgTextStyle critical() {
        CgTextStyle style = CRITICAL_WORD.loaded();
        if (style != null && style != criticalFaced) {
            style.font(Math.round(style.px()));
            criticalFaced = style;
        }
        return style;
    }

    /** {@code text_fx.shader} as effect {@code effect}: a station's Text-domain material. */
    private static CgMaterialDefinition fx(String name, int effect) {
        return CgMaterialDefinition.of("crystalgraphics:materials/demo_text_" + name,
                "crystalgraphics:shaders/demo/text_fx.shader").value("_Effect", effect).build();
    }

    /**
     * The grid this frame, its front row's middle on {@code (x, y, z)}, rows stepping away from the camera. Laid out
     * facing where the camera stood when the point moved (the demo moves it only for a new scene, a new level or a
     * jump), and fixed there: the words never turn after the camera.
     */
    public void submit(CgWorldRenderer world, CgHostView view, double x, double y, double z, double seconds) {
        this.seconds = seconds;
        if (x != laidX || y != laidY || z != laidZ) {
            laidX = x;
            laidY = y;
            laidZ = z;
            double toX = view.x() - x, toZ = view.z() - z, length = Math.max(1e-6, Math.hypot(toX, toZ));
            double backX = -toX / length, backZ = -toZ / length, acrossX = toZ / length, acrossZ = -toX / length;
            yaw = (float) Math.atan2(toX, toZ);
            facing.rotationY(yaw);
            for (int i = 0; i < STATIONS; i++) {
                int row = i == CRITICAL_STATION ? 0 : i / COLUMNS, column = i == CRITICAL_STATION ? -1 : i % COLUMNS;
                double offset = (column - (COLUMNS - 1) * 0.5) * SPACING;
                at[i * 3] = x + acrossX * offset + backX * row * DEPTH;
                at[i * 3 + 1] = y + LIFT + row * RISE;
                at[i * 3 + 2] = z + acrossZ * offset + backZ * row * DEPTH;
            }
        }
        for (int i = 0; i < STATIONS; i++) {
            world.text(CAPTIONS[i]).at(at[i * 3], at[i * 3 + 1] - 1.05, at[i * 3 + 2]).height(0.28f).rotation(facing)
                    .font(labelFont).style(CgDemoStyles.LABEL).submit();
        }
        // A hit landing four times a second, climbing from single digits to the trillions, three random hits to each
        // order of magnitude, then back down: live text in a style, laid out only as it changes.
        long hit = (long) (seconds * 4.0);
        int step = (int) (hit % (2 * CLIMB)), order = (step < CLIMB ? step : 2 * CLIMB - 1 - step) / 3;
        long damage = POWERS[order] + (long) (unit(hit) * 9.0 * POWERS[order]);
        if (damage != shownDamage) {
            shownDamage = damage;
            words[0] = Long.toString(damage);
        }
        active = true;
    }

    private void draw(CgStageFrame frame) {
        if (!active) return;
        CgHostView view = frame.host().view();
        CgCanvas canvas = frame.canvas();
        // Back row first: blended text covers what is behind it.
        for (int row = 3; row >= 0; row--) {
            for (int column = 0; column < COLUMNS; column++) word(canvas, view, row * COLUMNS + column);
        }
        word(canvas, view, CRITICAL_STATION);
    }

    private void word(CgCanvas canvas, CgHostView view, int i) {
        double px = at[i * 3], py = at[i * 3 + 1], pz = at[i * 3 + 2];
        float scale = BLOCKS_PER_PX * (i == 0 ? 1f + 0.12f * punch(seconds) : 1f);
        pose.translation((float) (px - view.x()), (float) (py - view.y()), (float) (pz - view.z()))
                .rotateY(yaw).scale(scale, -scale, scale);
        CgTextStyle style = i == CRITICAL_STATION ? critical() : i == 1 && neonOut() ? NEON_OFF : styles[i];
        if (style == null) return;
        CgTextDraw draw = canvas.text().style(style).text(words[i]).contained(false);
        if (style.font() == null) draw.font(font);
        CgTextLayout laid = draw.measure();
        draw.at(-laid.totalWidth() * 0.5f, -laid.totalHeight() * 0.5f).pose(pose).submit();
    }

    /**
     * Whether the neon tube is out this moment. Lit steadily, it stutters once every {@link #NEON_CYCLE} seconds at a
     * point that varies by cycle: out, back, out again, and in some cycles a longer drop before it catches. By time,
     * not frames, so it reads alike at any frame rate.
     */
    private boolean neonOut() {
        long cycle = (long) Math.floor(seconds / NEON_CYCLE);
        double into = seconds - cycle * NEON_CYCLE - (0.8 + 2.6 * unit(cycle));
        if (into < 0.0 || into > 0.4) return false;
        return into < 0.05 || (into > 0.09 && into < 0.13) || (into > 0.2 && into < 0.34 && unit(cycle + 7) > 0.4);
    }

    private static final double NEON_CYCLE = 4.7;

    /** A repeatable 0 to 1 from {@code n}. */
    private static double unit(long n) {
        return ((n * 0x9E3779B97F4A7C15L) >>> 40) / (double) (1 << 24);
    }

    /** 10 to the 0th through 12th: each order of magnitude the damage climbs through, single digits to trillions. */
    private static final long[] POWERS = {1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L,
            100_000_000L, 1_000_000_000L, 10_000_000_000L, 100_000_000_000L, 1_000_000_000_000L};

    /** Hits from single digits to the top order: three to each. */
    private static final int CLIMB = 3 * POWERS.length;

    /** 1 at each hit, easing back to 0 over a quarter second. */
    private static float punch(double seconds) {
        double f = (seconds * 4.0) % 1.0;
        return (float) Math.max(0.0, 1.0 - f * 4.0);
    }

    /** A style at the demo's size, every length in pixels of its 64 px font. */
    private static CgTextStyle.Builder style(String name) {
        return CgTextStyle.builder("crystalgraphics:demo_text_" + name).size(SIZE);
    }

    /** {@code b} with its outline's shape swept to ({@code dx}, {@code dy}): a hard shadow every fifth of the way. */
    private static CgTextStyle.Builder extruded(CgTextStyle.Builder b, float dx, float dy, int argb) {
        for (int step = EXTRUSION_STEPS; step >= 1; step--) {
            float t = step / (float) EXTRUSION_STEPS;
            b.layer(CgTextLayer.shadow("extrusion" + step).offset(dx * t, dy * t).outlined(true).paint(argb));
        }
        return b;
    }

    private static CgTextStyle extrude() {
        CgTextStyle.Builder b = style("extrude");
        b.layer(CgTextLayer.shadow("ground").offset(16f, 18f).blur(6f).paint(0x80000000));
        for (int d = 12; d >= 1; d--) {
            int shade = 0x30 + d * 6;
            b.layer(CgTextLayer.shadow("depth" + d).offset(d, d).paint(0xFF000000 | shade << 16 | (shade / 3) << 8));
        }
        return b.layer(CgTextLayer.outline("bevel").width(1.5f).paint(0xFFFFE0D0))
                .layer(CgTextLayer.fill("face").paint(CgTextPaint.gradient(Space.GLYPH, 90f,
                        0f, 0xFFFF8A60, 1f, 0xFFE03A20)))
                .build();
    }

    /** Draws nothing until the next submit. */
    public void clear() {
        active = false;
    }

    /** Stops drawing and frees the material it warmed. */
    public void delete() {
        active = false;
        drawing.close();
        warm.delete();
    }
}
