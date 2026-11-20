package com.crystalgraphics.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.joml.Vector2f;
import org.junit.Test;

import com.crystalgraphics.api.paint.effect.CgFilter;
import com.crystalgraphics.api.text.CgText;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceBinding;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.text.style.CgTextLayer;
import com.crystalgraphics.text.style.CgTextStyle;
import com.crystalgraphics.widget.CgWidgetSlot.Align;

/** A widget tree laid out by Slate's rules, painted onto a canvas with UMG's tints, animated by name. */
public class CgUserWidgetTest {

    private static final String BANNER = "kk:textures/hud/damage_number/base_critical";
    private static final String WORD = "kk:textures/hud/damage_number/lang_critical";
    private static final String DIGIT = "kk:textures/hud/damage_number/digit";
    private static final String FONT = Paths.get("src/test/resources/fonts/IBMPlexSans-Regular.ttf").toAbsolutePath().toString();

    /** Kakarot's Battle_Dmg, its critical branch and its nine digits, at the values its widget tree holds. */
    private static CgWidgetTree battleDmg() {
        CgWidget.Builder digits = CgWidget.horizontalBox("Number_Center");
        for (int i = 0; i < 9; i++) {
            digits.child(CgWidget.image("Ins_Dmg00_0" + i)
                    .brush(CgWidgetBrush.texture(DIGIT, 64f, 60f))
                    .slot(CgWidgetSlot.builder().padding(-13f, 0f, -13f, 0f).vertical(Align.CENTRE).build()));
        }
        CgWidgetSlot centred = CgWidgetSlot.builder().anchors(0.5f, 0.5f).alignment(0.5f, 0.5f)
                .offsets(0f, 0f, 500f, 100f).autoSize(true).build();
        return CgWidgetTree.of(CgWidget.canvas("All_Nut")
                .child(CgWidget.canvas("All_Number").slot(centred)
                        .child(CgWidget.border("Pnl_All_Num_Dmg").slot(centred).brush(CgWidgetBrush.NONE).child(digits))
                        .child(CgWidget.canvas("Pnl_Lang_Critical")
                                .slot(CgWidgetSlot.builder().anchors(0f, 0.5f, 1f, 0.5f).alignment(0f, 0.5f)
                                        .offsets(0f, 0f, 0f, 60f).build())
                                .translation(45f, 0f)
                                .child(CgWidget.image("Img_Base_Critical")
                                        .slot(CgWidgetSlot.builder().anchors(1f, 1f).alignment(1f, 1f)
                                                .offsets(115f, 48f, 224f, 56f).build())
                                        .brush(CgWidgetBrush.texture(BANNER, 224f, 56f))
                                        .scale(1.5f, 1.5f).angle(2f).colour(new CgLinearColour(1f, 1f, 1f, 0f)))
                                .child(CgWidget.image("Ins_Lang_Critical")
                                        .slot(CgWidgetSlot.builder().anchors(1f, 1f).alignment(1f, 1f)
                                                .offsets(115f, 37f, 216f, 60f).autoSize(true).build())
                                        .brush(CgWidgetBrush.texture(WORD, 216f, 60f))
                                        .angle(-5f).colour(new CgLinearColour(1f, 1f, 1f, 0f)))))
                .build());
    }

    /** A four-digit hit: the other five digits collapsed, as the game hides them. */
    private static CgUserWidget fourDigits(CgWidgetTree tree) {
        CgUserWidget pop = tree.newInstance();
        for (int i = 4; i < 9; i++) pop.visibility("Ins_Dmg00_0" + i, CgWidget.Visibility.COLLAPSED);
        return pop;
    }

    /** The one draw of {@code texture}. */
    private static Painted.Draw only(List<Painted.Draw> draws, String texture) {
        List<Painted.Draw> found = Painted.with(draws, texture);
        assertEquals(texture + " drawn once", 1, found.size());
        return found.get(0);
    }

    /** Nine 64-unit cells overlapping 13 a side advance 38 each; the border pads (4, 2), UMG's default. */
    @Test
    public void digitsAdvance38AndTheBorderPadsByDefault() {
        CgUserWidget pop = fourDigits(battleDmg());
        Vector2f size = pop.layout(new Vector2f());
        assertEquals("4 x 38 + 2 x 4", 160f, size.x, 1e-4f);
        assertEquals("60 + 2 x 2", 64f, size.y, 1e-4f);
        float[] box = pop.box(pop.tree().index("Ins_Dmg00_01"), new float[4]);
        assertEquals("the second cell starts 38 on, 13 early", 38f - 13f, box[0], 1e-4f);
        assertEquals(64f, box[2], 1e-4f);
    }

    /**
     * The critical lands where Slate puts it: the banner's centre (+48, +50) and the word's (+52, +37) from the
     * number's right edge and middle, the place measured from the game.
     */
    @Test
    public void theCriticalLandsWhereSlatePutsIt() {
        CgUserWidget pop = fourDigits(battleDmg());
        for (String name : new String[]{"Img_Base_Critical", "Ins_Lang_Critical"}) {
            pop.part(CgSequenceBinding.Part.NODE, name).set(5, new float[]{1f, 1f, 1f, 1f}, 0b1000);
        }
        List<Painted.Draw> draws = Painted.of(pop::paint);
        float right = 160f, middle = 32f;
        Painted.Draw banner = only(draws, BANNER);
        assertEquals(48f, banner.cx() - right, 1e-2f);
        assertEquals(50f, banner.cy() - middle, 1e-2f);
        Painted.Draw word = only(draws, WORD);
        assertEquals(52f, word.cx() - right, 1e-2f);
        assertEquals(37f, word.cy() - middle, 1e-2f);
    }

    /** A collapsed widget takes no space; a hidden one keeps it and draws nothing. */
    @Test
    public void collapsedTakesNoSpaceHiddenKeepsIt() {
        CgUserWidget pop = fourDigits(battleDmg());
        pop.visibility("Ins_Dmg00_03", CgWidget.Visibility.HIDDEN);
        assertEquals("hidden still counts", 160f, pop.layout(new Vector2f()).x, 1e-4f);
        assertEquals("three digits drawn", 3, Painted.with(Painted.of(pop::paint), DIGIT).size());
        pop.visibility("Ins_Dmg00_03", CgWidget.Visibility.COLLAPSED);
        assertEquals(3 * 38f + 8f, pop.layout(new Vector2f()).x, 1e-4f);
    }

    /**
     * Render opacity and a border's content colour multiply into everything inside, in linear, encoded to sRGB only
     * at the draw; alpha is never encoded.
     */
    @Test
    public void opacityAndContentColourFlowDownInLinear() {
        CgWidgetTree tree = CgWidgetTree.of(CgWidget.canvas("Root").renderOpacity(0.5f)
                .child(CgWidget.border("Glow").brush(CgWidgetBrush.NONE)
                        .contentColour(new CgLinearColour(0.5f, 0.1f, 0f, 0.5f))
                        .child(CgWidget.image("Cell")))
                .build());
        int argb = Painted.of(tree.newInstance()::paint).get(0).argb();
        assertEquals("0.5 x 0.5 alpha", 0x40, argb >>> 24, 1);
        assertEquals("linear 0.5 is sRGB 188", 188, argb >>> 16 & 0xFF, 1);
        assertEquals("linear 0.1 is sRGB 89", 89, argb >>> 8 & 0xFF, 1);
        assertEquals(0, argb & 0xFF);
    }

    /** A border with no background set draws Slate's white box; NONE draws nothing. */
    @Test
    public void aBorderDrawsAWhiteBoxUnlessToldNot() {
        List<Painted.Draw> draws = Painted.of(CgWidgetTree.of(CgWidget.border("B").build()).newInstance()
                .allot(40f, 20f)::paint);
        assertEquals(1, draws.size());
        assertNull("no texture: a box of its colour", draws.get(0).texture());
        assertEquals(0xFFFFFFFF, draws.get(0).argb());
        assertEquals(0, Painted.of(CgWidgetTree.of(CgWidget.border("B").brush(CgWidgetBrush.NONE).build())
                .newInstance().allot(40f, 20f)::paint).size());
    }

    /** Fill children share what auto children leave, by coefficient. */
    @Test
    public void fillChildrenShareTheRest() {
        CgWidgetTree tree = CgWidgetTree.of(CgWidget.horizontalBox("Row")
                .child(CgWidget.image("A").brush(CgWidgetBrush.texture("a", 40f, 10f)))
                .child(CgWidget.image("B").slot(CgWidgetSlot.builder().size(CgWidgetSlot.Size.FILL, 1f).build()))
                .child(CgWidget.image("C").slot(CgWidgetSlot.builder().size(CgWidgetSlot.Size.FILL, 3f).build()))
                .build());
        CgUserWidget row = tree.newInstance().allot(200f, 10f);
        row.layout(new Vector2f());
        float[] box = new float[4];
        assertEquals(40f, row.box(tree.index("B"), box)[0], 1e-4f);
        assertEquals("a quarter of 160", 40f, box[2], 1e-4f);
        assertEquals(120f, row.box(tree.index("C"), box)[2], 1e-4f);
    }

    /**
     * A scale box takes its content's size times its scale, so a row places it at that size, and draws its content
     * scaled about its own corner, centred in it: SScaleBox at a user scale.
     */
    @Test
    public void aScaleBoxTakesAndDrawsItsScaledSize() {
        CgWidgetTree tree = CgWidgetTree.of(CgWidget.horizontalBox("Row")
                .child(CgWidget.image("A").brush(CgWidgetBrush.texture("a", 40f, 10f)))
                .child(CgWidget.scaleBox("Box", 0.5f).child(CgWidget.image("B").brush(CgWidgetBrush.texture("b", 40f, 20f))))
                .child(CgWidget.image("C").brush(CgWidgetBrush.texture("c", 10f, 10f)))
                .build());
        CgUserWidget row = tree.newInstance();
        assertEquals("40 + 40 x 0.5 + 10", 70f, row.layout(new Vector2f()).x, 1e-4f);
        List<Painted.Draw> draws = Painted.of(row::paint);
        assertEquals("B's centre: 40 on, plus its half-scaled middle", 50f, only(draws, "b").cx(), 1e-4f);
        assertEquals(5f, only(draws, "b").cy(), 1e-4f);
        assertEquals("drawn at half its size", 20f, only(draws, "b").width(), 1e-4f);
        assertEquals("C after the scaled size", 65f, only(draws, "c").cx(), 1e-4f);
    }

    /** A text block measures its text with its style; changing the text relays out. */
    @Test
    public void textRelaysOut() {
        CgTextStyle style = CgTextStyle.builder("t").font(FONT).size(20f).build();
        CgWidgetTree tree = CgWidgetTree.of(CgWidget.text("Num").style(style).text("12").build());
        CgUserWidget w = tree.newInstance();
        assertEquals(CgText.of("12").style(style).measure().totalWidth(), w.layout(new Vector2f()).x, 1e-3f);
        w.text("Num", "1,250");
        assertEquals(CgText.of("1,250").style(style).measure().totalWidth(), w.layout(new Vector2f()).x, 1e-3f);
    }

    /** Over a second the banner turns from -6.5 to 0 degrees and fades in; its scale is never keyed. */
    private static final String POP = """
            _v: 1
            tick_resolution: 1000
            display_rate: 50
            range: [0, 1000]
            roles:
              - { id: r1, name: number, kind: caster }
            tracks:
              - { id: t1, kind: property, binding: { role: number, node: Img_Base_Critical }, property: angle,
                  sections: [ { id: s1, range: [0, 1000], value: { keys: [[0, -6.5, linear], [1000, 0.0, linear]] } } ] }
              - { id: t2, kind: property, binding: { role: number, node: Img_Base_Critical }, property: colour_and_opacity,
                  sections: [ { id: s2, range: [0, 1000], value: { a: { keys: [[0, 0.0, linear], [1000, 1.0, linear]] } } } ] }
            """;

    private static CgSequenceDefinition sequence(String yaml) {
        return CgSequenceDefinition.compile(
                CgAssetData.of("mymod:pop", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null));
    }

    @Test
    public void aSequenceFindsItsPartsByName() {
        CgWidgetTree tree = battleDmg();
        CgUserWidget pop = fourDigits(tree);
        CgWorld world = new CgWorld();
        CgSequencer sequencer = world.add(new CgSequencer());
        sequencer.play(sequence(POP), Map.of("number", pop));
        sequencer.evaluate(0f);
        sequencer.evaluate(0.5f);
        int banner = tree.index("Img_Base_Critical");
        assertEquals(-3.25f, pop.transform(banner, new float[7])[6], 1e-4f);
        assertEquals("half faded in", 0.5f, pop.colour(banner).a(), 1e-4f);
        assertEquals("unkeyed: the designer's", 1.5f, pop.transform(banner, new float[7])[2], 0f);
        Painted.Draw drawn = only(Painted.of(pop::paint), BANNER);
        assertEquals("faded in its node, its colour whole", 0xFF, drawn.argb() >>> 24);
        assertEquals("drawn at half its alpha", 0.5f, drawn.opacity(), 1e-4f);
        world.close();
    }

    /** A tree whose one image a test moves and fades: centred 40 x 20 at (100, 60) under a canvas scale of 2. */
    private static CgUserWidget card() {
        return CgWidgetTree.of(CgWidget.canvas("Root")
                .child(CgWidget.image("Img").brush(CgWidgetBrush.texture("a", 40f, 20f))
                        .slot(CgWidgetSlot.builder().anchors(0f, 0f, 0f, 0f).offsets(80f, 50f, 40f, 20f).build()))
                .build()).newInstance();
    }

    private static List<Painted.Draw> placed(CgUserWidget w) {
        return Painted.of(c -> {
            c.save().translate(30f, 10f).scale(2f, 2f);
            w.paint(c);
            c.restore();
        });
    }

    /**
     * Once a widget has moved and faded, later moves and fades replay its kept drawing and write only its nodes'
     * values: no repaint, and each draw lands where a fresh paint of the same state lands.
     */
    @Test
    public void aMoveAndAFadeReplayWithoutRepainting() {
        CgUserWidget w = card();
        int img = w.tree().index("Img");
        placed(w);
        w.translation(img, 5f, 0f);
        w.colourAndOpacity("Img", new CgLinearColour(1f, 1f, 1f, 0.2f));
        placed(w);
        long repaints = w.repaints();
        float[][] frames = {{30f, -4f, 0.6f, 12f}, {-8f, 9f, 0.05f, -30f}};
        for (float[] f : frames) {
            w.translation(img, f[0], f[1]);
            w.colourAndOpacity("Img", new CgLinearColour(1f, 1f, 1f, f[2]));
            w.part(CgSequenceBinding.Part.NODE, "Img").set(w.slot("angle"), new float[]{f[3], 0f, 0f, 0f}, 1);
            Painted.Draw replayed = only(placed(w), "a");
            assertEquals("replayed, not repainted", repaints, w.repaints());

            CgUserWidget fresh = card();
            fresh.translation(img, f[0], f[1]);
            fresh.colourAndOpacity("Img", new CgLinearColour(1f, 1f, 1f, f[2]));
            fresh.part(CgSequenceBinding.Part.NODE, "Img").set(w.slot("angle"), new float[]{f[3], 0f, 0f, 0f}, 1);
            Painted.Draw painted = only(placed(fresh), "a");
            assertEquals(painted.cx(), replayed.cx(), 1e-3f);
            assertEquals(painted.cy(), replayed.cy(), 1e-3f);
            assertEquals("faded by its node", f[2], replayed.opacity() * (replayed.argb() >>> 24) / 255f, 2e-3f);
        }
        assertEquals("its last translation: (100 - 8) x 2 + 30", 214f, only(placed(w), "a").cx(), 1e-3f);
    }

    /** Its colour is drawn into its records: a change repaints. So do a layout and the canvas scale it is baked at. */
    @Test
    public void aColourOrScaleChangeRepaints() {
        CgUserWidget w = card();
        placed(w);
        placed(w);
        long repaints = w.repaints();
        assertEquals("unchanged: replayed", 1L, repaints);
        w.colourAndOpacity("Img", new CgLinearColour(1f, 0.5f, 1f, 1f));
        placed(w);
        assertEquals(repaints + 1, w.repaints());
        Painted.of(w::paint);
        assertEquals("another scale under it", repaints + 2, w.repaints());
        Painted.of(c -> {
            c.save().translate(500f, 20f);
            w.paint(c);
            c.restore();
        });
        assertEquals("moved as a whole: its root node", repaints + 2, w.repaints());
    }

    @Test
    public void materialParametersAreKeyed() {
        CgWidgetTree tree = battleDmg();
        CgUserWidget pop = fourDigits(tree);
        pop.materialParameters("Img_Base_Critical", 0f, 2f, 0f, 0f);
        CgWorld world = new CgWorld();
        CgSequencer sequencer = world.add(new CgSequencer());
        sequencer.play(sequence("""
                _v: 1
                tick_resolution: 1000
                display_rate: 50
                range: [0, 1000]
                roles:
                  - { id: r1, name: number, kind: caster }
                tracks:
                  - { id: t1, kind: property, binding: { role: number, node: Img_Base_Critical }, property: material_parameters,
                      sections: [ { id: s1, range: [0, 1000], value: { x: { keys: [[0, 0.0, linear], [1000, 1.0, linear]] } } } ] }
                """), Map.of("number", pop));
        sequencer.evaluate(0f);
        sequencer.evaluate(0.5f);
        float[] p = pop.materialParameters(tree.index("Img_Base_Critical"), new float[4]);
        assertEquals("keyed: half way", 0.5f, p[0], 1e-4f);
        assertEquals("unkeyed: as set", 2f, p[1], 0f);
        world.close();
    }

    @Test
    public void aNameTheTreeLacksFails() {
        CgUserWidget pop = battleDmg().newInstance();
        CgWorld world = new CgWorld();
        CgSequencer sequencer = world.add(new CgSequencer());
        assertThrows(RuntimeException.class,
                () -> sequencer.play(sequence(POP.replace("Img_Base_Critical", "Img_Nope")), Map.of("number", pop)));
        assertThrows(IllegalArgumentException.class, () -> pop.text("Img_Nope", "x"));
        world.close();
    }

    @Test
    public void twoWidgetsOfOneNameFail() {
        assertThrows(IllegalArgumentException.class, () -> CgWidgetTree.of(CgWidget.canvas("A")
                .child(CgWidget.image("B")).child(CgWidget.image("B")).build()));
    }

    /**
     * A nested user widget takes its tree's desired size; an outer track on it writes the nested instance's own
     * transform, as its own sequences do, never a widget inside it.
     */
    @Test
    public void aNestedUserWidgetIsReachedWhole() {
        CgWidgetTree digit = CgWidgetTree.of("mymod:widgets/digit", CgWidget.image("Cell")
                .brush(CgWidgetBrush.texture("d", 30f, 40f)).build());
        CgWidgetTree row = CgWidgetTree.of(CgWidget.horizontalBox("Row")
                .child(CgWidget.userWidget("A", digit)).child(CgWidget.userWidget("B", digit)).build());
        CgUserWidget w = row.newInstance();
        assertEquals(60f, w.layout(new Vector2f()).x, 1e-4f);
        CgSequenceTarget b = w.part(CgSequenceBinding.Part.NODE, "B");
        assertSame(w.nested("B"), b);
        assertNull("its insides are its own", b.part(CgSequenceBinding.Part.NODE, "Nope"));
        b.set(b.slot("translation"), new float[]{0f, 5f, 0f, 0f}, 0b11);
        List<Painted.Draw> draws = Painted.of(w::paint);
        assertEquals("A's cell centre", 15f, draws.get(0).cx(), 1e-4f);
        assertEquals("B sits after A, moved down 5", 45f, draws.get(1).cx(), 1e-4f);
        assertEquals(25f, draws.get(1).cy(), 1e-4f);
    }

    /** A retainer box's blur axis picks which sigma its layer blurs by: none across for a vertical blur. */
    @Test
    public void aRetainerBoxBlursAlongItsAxis() {
        CgUserWidget w = CgWidgetTree.of(CgWidget.retainerBox("Ret").blur(3f).blurAxis(CgWidget.BlurAxis.VERTICAL)
                .child(CgWidget.image("Cell")).build()).newInstance();
        Painted.of(w::paint);
        float[] sigmas = w.retainerFilter("Ret").ops().get(0).values();
        assertEquals("across", 0f, sigmas[0], 0f);
        assertEquals("down", 3f, sigmas[1], 0f);
    }

    /**
     * A retainer box blurs what it holds by its blur, the designer's, set from Java or keyed by a sequence as
     * {@code blur}, as CSS's filter: blur(); only a retainer box blurs.
     */
    @Test
    public void aRetainerBoxBlursWhatItHolds() {
        CgWidgetTree tree = CgWidgetTree.of(CgWidget.retainerBox("Ret").blur(2f).child(CgWidget.image("Cell")).build());
        CgUserWidget w = tree.newInstance();
        Painted.of(w::paint);
        assertEquals("the designer's", 2f, blurOf(w), 0f);
        w.blur("Ret", 5f);
        Painted.of(w::paint);
        assertEquals("set from Java", 5f, blurOf(w), 0f);

        CgWorld world = new CgWorld();
        CgSequencer sequencer = world.add(new CgSequencer());
        sequencer.play(sequence("""
                _v: 1
                tick_resolution: 1000
                display_rate: 50
                range: [0, 1000]
                roles:
                  - { id: r1, name: number, kind: caster }
                tracks:
                  - { id: t1, kind: property, binding: { role: number, node: Ret }, property: blur,
                      sections: [ { id: s1, range: [0, 1000], value: { x: { keys: [[0, 8.0, linear], [1000, 0.0, linear]] } } } ] }
                """), Map.of("number", w));
        sequencer.evaluate(0f);
        sequencer.evaluate(0.5f);
        Painted.of(w::paint);
        assertEquals("keyed: half way", 4f, blurOf(w), 1e-4f);
        world.close();

        assertThrows(IllegalArgumentException.class, () -> CgWidget.image("Img").blur(1f).build());
        assertThrows(IllegalArgumentException.class, () -> CgWidgetTree.of(CgWidget.canvas("Root")
                .child(CgWidget.image("Img")).build()).newInstance().blur("Img", 1f));
    }

    private static float blurOf(CgUserWidget w) {
        CgFilter.Op op = w.retainerFilter("Ret").ops().get(0);
        assertEquals(CgFilter.Kind.BLUR, op.kind());
        return op.values()[0];
    }

    /**
     * A retainer box's picture reaches as far as its text draws past its box: the widest outline, the shadows' offset,
     * spread and three of their blur, the slant over the size, under any scale box between.
     */
    @Test
    public void aRetainerBoxReachesAsFarAsItsText() {
        CgTextStyle style = CgTextStyle.builder("s").font(FONT).size(100f).skew(0.1f)
                .layer(CgTextLayer.shadow("drop").offset(4f, 12f).blur(2f).outlined(true))
                .layer(CgTextLayer.outline("outline").width(10f))
                .layer(CgTextLayer.fill("fill")).build();
        assertEquals("12 + 10 + 3 x 2, then 0.1 x 100", 38f, style.reach(), 1e-4f);
        CgUserWidget w = CgWidgetTree.of(CgWidget.retainerBox("Ret")
                .child(CgWidget.scaleBox("Small", 0.5f).child(CgWidget.text("Num").style(style).text("7"))).build())
                .newInstance();
        Vector2f size = w.layout(new Vector2f());
        // Away from the target's edge, which would cut the picture
        Painted.Draw picture = only(Painted.of(c -> {
            c.save().translate(200f, 200f);
            w.paint(c);
            c.restore();
        }), "layer");
        assertTrue("halved by the scale box: 19 a side past " + size.x + ", got " + picture.width(),
                picture.width() >= size.x + 2f * 19f - 1f);
    }

    /** A retainer box inside another is not drawn: its picture would need a second picture's. */
    @Test
    public void aRetainerBoxInsideAnotherFails() {
        CgUserWidget w = CgWidgetTree.of(CgWidget.retainerBox("Outer")
                .child(CgWidget.retainerBox("Inner").child(CgWidget.image("Cell"))).build()).newInstance();
        assertThrows(IllegalStateException.class, () -> Painted.of(w::paint));
    }

    /**
     * A retainer box's content is drawn at full opacity into a picture of its own, then laid over under everything
     * above it: a fade above it fades one picture, never its layers apart.
     */
    @Test
    public void aRetainerBoxFadesItsContentAsOneImage() {
        CgWidgetTree tree = CgWidgetTree.of(CgWidget.border("Fade").brush(CgWidgetBrush.NONE)
                .contentColour(new CgLinearColour(1f, 1f, 1f, 0.5f))
                .child(CgWidget.retainerBox("Ret").slot(CgWidgetSlot.builder().padding(0f, 0f, 0f, 0f).build())
                        .child(CgWidget.image("Cell").brush(CgWidgetBrush.texture("c", 40f, 20f))
                                .colour(new CgLinearColour(1f, 1f, 1f, 0.8f))))
                .build());
        CgUserWidget w = tree.newInstance();
        List<Painted.Draw> draws = Painted.of(c -> {
            c.save().translate(100f, 0f);
            w.paint(c);
            c.restore();
        });
        Painted.Draw cell = only(draws, "c"), picture = only(draws, "layer");
        assertTrue("its content in its picture", cell.inLayer());
        assertEquals("its own colour only: nothing above it", 0xCC, cell.argb() >>> 24, 1);
        assertEquals(120f, cell.cx(), 1e-4f);
        assertEquals("the fade above it, on the one picture", 0x80, picture.argb() >>> 24, 1);
        assertEquals(120f, picture.cx(), 1e-4f);
    }
}
