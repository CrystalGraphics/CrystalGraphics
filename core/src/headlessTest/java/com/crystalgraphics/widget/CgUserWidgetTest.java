package com.crystalgraphics.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.junit.Test;

import com.crystalgraphics.api.material.CgMaterialDefinition;
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

/** A widget tree laid out by Slate's rules, painted with UMG's tints, animated by name. */
public class CgUserWidgetTest {

    private static final String BANNER = "kk:textures/hud/damage_number/base_critical";
    private static final String WORD = "kk:textures/hud/damage_number/lang_critical";

    /** Kakarot's Battle_Dmg, its critical branch and its nine digits, at the values its widget tree holds. */
    private static CgWidgetTree battleDmg() {
        CgWidget.Builder digits = CgWidget.horizontalBox("Number_Center");
        for (int i = 0; i < 9; i++) {
            digits.child(CgWidget.image("Ins_Dmg00_0" + i)
                    .brush(CgWidgetBrush.texture("kk:textures/hud/damage_number/digit", 64f, 60f))
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

    /** Records each draw: its kind, its box's centre on the canvas and its colour. */
    private static final class Recorder implements CgWidgetPainter {
        final List<String> draws = new ArrayList<>();
        final List<Vector3f> centres = new ArrayList<>();
        final List<Integer> colours = new ArrayList<>();
        final List<Vector4f> parameters = new ArrayList<>();

        @Override
        public Object layout(CgTextStyle style, String text, float wrap, CgWidget.Justification justification,
                             Vector2f out) {
            out.set(10f * text.length(), 20f);
            return null;
        }

        @Override
        public void image(CgWidgetBrush brush, Matrix4f pose, float width, float height, float r, float g, float b,
                          float a, Vector4fc parameters) {
            draws.add(brush.texture());
            centres.add(pose.transformPosition(new Vector3f(width / 2f, height / 2f, 0f)));
            colours.add(CgLinearColour.argb(r, g, b, a));
            this.parameters.add(new Vector4f(parameters));
        }

        @Override
        public void text(CgTextStyle style, String text, Object laidOut, CgWidget.Justification justification,
                         Matrix4f pose, float width, float r, float g, float b, float a) {
            draws.add(text);
            centres.add(pose.transformPosition(new Vector3f()));
            colours.add(CgLinearColour.argb(r, g, b, a));
        }

        int regions;
        int open = -1;
        final List<Float> blurs = new ArrayList<>();
        final List<Float> reaches = new ArrayList<>();

        @Override
        public int beginRetained(float width, float height, float reach, float blur) {
            draws.add("region " + regions);
            blurs.add(blur);
            reaches.add(reach);
            centres.add(new Vector3f(width, height, 0f));
            colours.add(0);
            return open = regions++;
        }

        @Override
        public void endRetained() {
            open = -1;
        }

        @Override
        public void retained(int region, CgMaterialDefinition effect, String textureParameter, Matrix4f pose,
                             float width, float height, float r, float g, float b, float a) {
            draws.add("retained " + region);
            centres.add(pose.transformPosition(new Vector3f(width / 2f, height / 2f, 0f)));
            colours.add(CgLinearColour.argb(r, g, b, a));
        }

        Vector3f centreOf(String texture) {
            return centres.get(draws.indexOf(texture));
        }
    }

    /** Nine 64-unit cells overlapping 13 a side advance 38 each; the border pads (4, 2), UMG's default. */
    @Test
    public void digitsAdvance38AndTheBorderPadsByDefault() {
        CgUserWidget pop = fourDigits(battleDmg());
        Vector2f size = pop.layout(new Recorder(), new Vector2f());
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
            pop.part(CgSequenceBinding.Part.NODE, name)
               .set(5, new float[]{1f, 1f, 1f, 1f}, 0b1000);
        }
        Recorder r = new Recorder();
        pop.paint(r, new Matrix4f());
        float right = 160f, middle = 32f;
        Vector3f banner = r.centreOf(BANNER);
        assertEquals(48f, banner.x - right, 1e-3f);
        assertEquals(50f, banner.y - middle, 1e-3f);
        Vector3f word = r.centreOf(WORD);
        assertEquals(52f, word.x - right, 1e-3f);
        assertEquals(37f, word.y - middle, 1e-3f);
    }

    /** A collapsed widget takes no space; a hidden one keeps it and draws nothing. */
    @Test
    public void collapsedTakesNoSpaceHiddenKeepsIt() {
        CgUserWidget pop = fourDigits(battleDmg());
        pop.visibility("Ins_Dmg00_03", CgWidget.Visibility.HIDDEN);
        assertEquals("hidden still counts", 160f, pop.layout(new Recorder(), new Vector2f()).x, 1e-4f);
        Recorder r = new Recorder();
        pop.paint(r, new Matrix4f());
        assertEquals("three digits drawn; the banner and word are transparent and still listed", 3,
                r.draws.stream().filter(d -> d.endsWith("digit")).count());
        pop.visibility("Ins_Dmg00_03", CgWidget.Visibility.COLLAPSED);
        assertEquals(3 * 38f + 8f, pop.layout(new Recorder(), new Vector2f()).x, 1e-4f);
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
        Recorder r = new Recorder();
        tree.newInstance().paint(r, new Matrix4f());
        int argb = r.colours.get(0);
        assertEquals("0.5 x 0.5 alpha", 0x40, argb >>> 24, 1);
        assertEquals("linear 0.5 is sRGB 188", 188, argb >>> 16 & 0xFF, 1);
        assertEquals("linear 0.1 is sRGB 89", 89, argb >>> 8 & 0xFF, 1);
        assertEquals(0, argb & 0xFF);
    }

    /** A border with no background set draws Slate's white box; NONE draws nothing. */
    @Test
    public void aBorderDrawsAWhiteBoxUnlessToldNot() {
        Recorder r = new Recorder();
        CgWidgetTree.of(CgWidget.border("B").build()).newInstance().paint(r, new Matrix4f());
        assertEquals(1, r.draws.size());
        assertEquals("no texture: a box of its colour", null, r.draws.get(0));
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
        row.layout(new Recorder(), new Vector2f());
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
        assertEquals("40 + 40 x 0.5 + 10", 70f, row.layout(new Recorder(), new Vector2f()).x, 1e-4f);
        Recorder r = new Recorder();
        row.paint(r, new Matrix4f());
        assertEquals("B's centre: 40 on, plus its half-scaled middle", 50f, r.centreOf("b").x, 1e-4f);
        assertEquals(5f, r.centreOf("b").y, 1e-4f);
        assertEquals("C after the scaled size", 65f, r.centreOf("c").x, 1e-4f);
    }

    /** A text block measures its text; changing the text relays out. */
    @Test
    public void textRelaysOut() {
        CgTextStyle style = CgTextStyle.builder("t").font("mymod:fonts/x.ttf").build();
        CgWidgetTree tree = CgWidgetTree.of(CgWidget.text("Num").style(style).text("12").build());
        CgUserWidget w = tree.newInstance();
        Recorder r = new Recorder();
        assertEquals(20f, w.layout(r, new Vector2f()).x, 1e-4f);
        w.text("Num", "1,250");
        assertEquals(50f, w.layout(r, new Vector2f()).x, 1e-4f);
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
        world.close();
    }

    @Test
    public void materialParametersReachTheBrush() {
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
        Recorder painter = new Recorder();
        pop.paint(painter, new Matrix4f());
        Vector4f drawn = painter.parameters.get(painter.draws.indexOf(BANNER));
        assertEquals("keyed: half way", 0.5f, drawn.x, 1e-4f);
        assertEquals("unkeyed: as set", 2f, drawn.y, 0f);
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
        assertEquals(60f, w.layout(new Recorder(), new Vector2f()).x, 1e-4f);
        CgSequenceTarget b = w.part(CgSequenceBinding.Part.NODE, "B");
        assertSame(w.nested("B"), b);
        assertNull("its insides are its own", b.part(CgSequenceBinding.Part.NODE, "Nope"));
        b.set(b.slot("translation"), new float[]{0f, 5f, 0f, 0f}, 0b11);
        Recorder r = new Recorder();
        w.paint(r, new Matrix4f());
        assertEquals("A's cell centre", 15f, r.centres.get(0).x, 1e-4f);
        assertEquals("B sits after A, moved down 5", 45f, r.centres.get(1).x, 1e-4f);
        assertEquals(25f, r.centres.get(1).y, 1e-4f);
    }

    /**
     * A retainer box asks its painter to blur what it holds by its blur, the designer's, set from Java or keyed by a
     * sequence as {@code blur}, as CSS's filter: blur(); only a retainer box blurs.
     */
    @Test
    public void aRetainerBoxBlursWhatItHolds() {
        CgWidgetTree tree = CgWidgetTree.of(CgWidget.retainerBox("Ret").blur(2f).child(CgWidget.image("Cell")).build());
        CgUserWidget w = tree.newInstance();
        Recorder r = new Recorder();
        w.retain(r);
        assertEquals("the designer's", 2f, r.blurs.get(0), 0f);
        w.blur("Ret", 5f);
        w.retain(r);
        assertEquals("set from Java", 5f, r.blurs.get(1), 0f);

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
        w.retain(r);
        assertEquals("keyed: half way", 4f, r.blurs.get(2), 1e-4f);
        world.close();

        assertThrows(IllegalArgumentException.class, () -> CgWidget.image("Img").blur(1f).build());
        assertThrows(IllegalArgumentException.class, () -> CgWidgetTree.of(CgWidget.canvas("Root")
                .child(CgWidget.image("Img")).build()).newInstance().blur("Img", 1f));
    }

    /**
     * A retainer box pads its image by how far its text draws past its box: the widest outline, the shadows' offset,
     * spread and three of their blur, the slant over the size, under any scale box between.
     */
    @Test
    public void aRetainerBoxPadsByItsTextsReach() {
        CgTextStyle style = CgTextStyle.builder("s").font("mymod:fonts/x.ttf").size(100f).skew(0.1f)
                .layer(CgTextLayer.shadow("drop").offset(4f, 12f).blur(2f).outlined(true))
                .layer(CgTextLayer.outline("outline").width(10f))
                .layer(CgTextLayer.fill("fill")).build();
        assertEquals("12 + 10 + 3 x 2, then 0.1 x 100", 38f, style.reach(), 1e-4f);
        CgUserWidget w = CgWidgetTree.of(CgWidget.retainerBox("Ret")
                .child(CgWidget.scaleBox("Small", 0.5f).child(CgWidget.text("Num").style(style).text("7"))).build())
                .newInstance();
        Recorder r = new Recorder();
        w.retain(r);
        assertEquals("halved by the scale box", 19f, r.reaches.get(0), 1e-4f);
    }

    /** A retainer box paints from what was retained this frame: painting it unretained fails. */
    @Test
    public void aRetainerBoxPaintsOnlyRetained() {
        assertThrows(IllegalStateException.class, () -> {
            CgUserWidget w = CgWidgetTree.of(CgWidget.retainerBox("Ret").child(CgWidget.image("Cell")).build()).newInstance();
            w.paint(new Recorder(), new Matrix4f());
        });
    }

    /**
     * A retainer box's content is painted at full opacity into its region, then drawn as one quad under everything
     * above it: a fade above it fades one image, never its layers apart.
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
        Recorder r = new Recorder();
        w.retain(r);
        assertEquals(List.of("region 0", "c"), r.draws);
        assertEquals("its own colour only: nothing above it", 0xCC, r.colours.get(1) >>> 24, 1);
        assertEquals("at the region's origin", 20f, r.centres.get(1).x, 1e-4f);
        w.paint(r, new Matrix4f().translation(100f, 0f, 0f));
        assertEquals("retained 0", r.draws.get(2));
        assertEquals("the fade above it, on the one quad", 0x80, r.colours.get(2) >>> 24, 1);
        assertEquals(120f, r.centres.get(2).x, 1e-4f);
    }
}
