package com.crystalgraphics.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4fc;
import org.junit.Test;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.validate.CgValidation;
import com.crystalgraphics.text.style.CgTextStyle;

/** Widget files: read over a parent by widget name, written back equal, refused by name where they are wrong. */
public class CgWidgetCodecTest {

    static {
        CgAssetKinds.register();
    }

    private static final CgTextStyle GOLD = CgTextStyle.builder("kk:text_styles/hud/gold").font("kk:fonts/x.ttf").build();

    /** Kakarot's damage pop as its importer writes it: the digits material instances, the critical a texture. */
    private static final String BATTLE_DMG = """
            _v: 1
            root: All_Nut
            widgets:
              All_Nut: { kind: canvas, children: [All_Number] }
              All_Number:
                kind: canvas
                slot: { anchors: [0.5, 0.5], offsets: [0, 0, 500, 100], alignment: [0.5, 0.5], auto_size: true }
                children: [Pnl_All_Num_Dmg, Pnl_Lang_Critical]
              Pnl_All_Num_Dmg:
                kind: border
                slot: { anchors: [0.5, 0.5], offsets: [0, 0, 500, 100], alignment: [0.5, 0.5], auto_size: true }
                content: Number_Center
                background: { draw_as: none }
              Number_Center: { kind: horizontal_box, children: [Ins_Dmg00_01, Ins_Dmg00_00] }
              Ins_Dmg00_01:
                kind: image
                slot: { padding: [-13, 0, -13, 0], vertical_alignment: centre }
                brush: { material: kk:materials/Art/UI/Num_S/Material/Ins_Dmg00_00, image_size: [64, 60] }
              Ins_Dmg00_00:
                kind: image
                slot: { padding: [-13, 0, -13, 0], vertical_alignment: centre }
                brush: { material: kk:materials/Art/UI/Num_S/Material/Ins_Dmg00_02, image_size: [64, 60] }
              Pnl_Lang_Critical:
                kind: canvas
                slot: { anchors: [[0, 0.5], [1, 0.5]], offsets: [0, 0, 0, 60], alignment: [0, 0.5] }
                transform: { translation: [45, 0] }
                children: [Img_Base_Critical]
              Img_Base_Critical:
                kind: image
                slot: { anchors: [1, 1], offsets: [115, 48, 224, 56], alignment: [1, 1] }
                transform: { scale: [1.5, 1.5], angle: 2 }
                colour_and_opacity: [1, 1, 1, 0]
                brush: { texture: kk:textures/hud/damage_number/base_critical.png, image_size: [224, 56] }
            """;

    /** A mod's pop: the digits one text block in a style, the images it held removed. */
    private static final String POP = """
            _v: 1
            parent: kk:widgets/hud/damage_number/battle_dmg
            widgets:
              Number_Center: { kind: text_block, style: kk:text_styles/hud/gold, text: "1,250" }
            remove: [Ins_Dmg00_01, Ins_Dmg00_00]
            """;

    private static CgAssetData asset(String id, String yaml, CgAssetData parent) {
        return CgAssetData.of(id, CgLookKinds.WIDGET, (Map<?, ?>) CgAssetYaml.read(yaml), parent);
    }

    private static final CgAssetCompiler.Dependencies DEPS = (kind, id) -> kind == CgLookKinds.TEXT_STYLE
            && id.equals(GOLD.id()) ? GOLD : null;

    /** 10 units a character, 20 high. */
    private static final class Measure implements CgWidgetPainter {
        @Override
        public Object layout(CgTextStyle style, String text, float wrap, CgWidget.Justification justification,
                             Vector2f out) {
            out.set(10f * text.length(), 20f);
            return null;
        }

        @Override
        public void image(CgWidgetBrush brush, Matrix4f pose, float width, float height, float r, float g, float b,
                          float a, Vector4fc parameters) {
        }

        @Override
        public void text(CgTextStyle style, String text, Object laidOut, CgWidget.Justification justification,
                         Matrix4f pose, float width, float r, float g, float b, float a) {
        }

        @Override
        public int beginRetained(float width, float height, float reach, float blurX, float blurY) {
            return 0;
        }

        @Override
        public void endRetained() {
        }

        @Override
        public void retained(int region, CgMaterialDefinition effect, String textureParameter, Matrix4f pose,
                             float width, float height, float r, float g, float b, float a) {
        }
    }

    @Test
    public void theImportersFileValidates() {
        CgValidation v = CgAssetData.validate("battle_dmg.yaml", CgLookKinds.WIDGET,
                (Map<?, ?>) CgAssetYaml.read(BATTLE_DMG), CgValidation.Usecase.LOAD);
        assertTrue(v.issues().toString(), v.valid());
    }

    /** Its digits' materials are hard references: one that does not compile fails the file, naming the widget. */
    @Test
    public void aMissingMaterialFailsAtLoadByName() {
        CgAssetException e = assertThrows(CgAssetException.class, () -> CgWidgetCodec.compile(
                asset("kk:widgets/hud/damage_number/battle_dmg", BATTLE_DMG, null), DEPS));
        assertTrue(e.getMessage(), e.getMessage().contains("Ins_Dmg00_01.brush.material"));
    }

    /** A child replaces a widget of another kind whole and removes what nothing reaches now; the critical keeps its place. */
    @Test
    public void aChildReplacesTheDigitsByName() {
        CgAssetData parent = asset("kk:widgets/hud/damage_number/battle_dmg", BATTLE_DMG, null);
        CgWidgetTree pop = CgWidgetCodec.compile(asset("mymod:widgets/pop", POP, parent), DEPS);
        assertEquals(-1, pop.index("Ins_Dmg00_00"));
        CgWidget number = pop.widget(pop.index("Number_Center"));
        assertEquals(CgWidget.Kind.TEXT_BLOCK, number.kind());
        assertEquals("1,250", number.text());
        CgUserWidget w = pop.newInstance();
        Vector2f size = w.layout(new Measure(), new Vector2f());
        assertEquals("50 of text and the border's (4, 2)", 58f, size.x, 1e-4f);
        assertEquals("the critical's 60-unit slot is taller than the text's 24", 60f, size.y, 1e-4f);
        float[] banner = w.box(pop.index("Img_Base_Critical"), new float[4]);
        assertEquals("anchored to the number's right edge: 58 + 115 - 224", -51f, banner[0], 1e-4f);
    }

    @Test
    public void builtInJavaWrittenAndReadIsEqual() {
        CgWidgetTree digit = CgWidgetTree.of("mymod:widgets/digit", CgWidget.image("Cell").build());
        CgWidgetTree built = CgWidgetTree.of("mymod:widgets/pop", CgWidget.canvas("Root")
                .child(CgWidget.border("Glow")
                        .slot(CgWidgetSlot.builder().anchors(0.5f, 0.5f).alignment(0.5f, 0.5f).autoSize(true).zOrder(2).build())
                        .brush(CgWidgetBrush.NONE).contentColour(new CgLinearColour(0.5f, 0.1f, 0f, 0.5f))
                        .child(CgWidget.text("Num").style(GOLD).text("kk:text/dmg").wrap(300f)
                                .justification(CgWidget.Justification.CENTRE)
                                .slot(CgWidgetSlot.builder().padding(2f, 0f, 2f, 0f).vertical(CgWidgetSlot.Align.BOTTOM).build())))
                .child(CgWidget.horizontalBox("Row")
                        .slot(CgWidgetSlot.builder().anchors(0f, 0.5f, 1f, 0.5f).offsets(0f, 0f, 0f, 60f).build())
                        .translation(45f, 0f).scale(1.5f, 1f).shear(10f, 0f).angle(-5f).pivot(0f, 0f)
                        .renderOpacity(0.25f).visibility(CgWidget.Visibility.HIDDEN)
                        .child(CgWidget.image("Img").brush(CgWidgetBrush.texture("kk:textures/a.png", 224f, 56f)
                                        .tint(new CgLinearColour(1f, 0.5f, 0.25f, 1f)))
                                .colour(new CgLinearColour(1f, 1f, 1f, 0f))
                                .slot(CgWidgetSlot.builder().size(CgWidgetSlot.Size.FILL, 2f).build()))
                        .child(CgWidget.userWidget("Digit", digit))
                        .child(CgWidget.scaleBox("Small", 0.418f).child(CgWidget.text("Hits").style(GOLD).text("HITS")))
                        .child(CgWidget.retainerBox("Soft").blur(2.5f).child(CgWidget.text("Ghost").style(GOLD).text("9"))))
                .build());
        String yaml = CgAssetYaml.write(CgWidgetCodec.toTree(built), CgLookKinds.WIDGET);
        CgAssetCompiler.Dependencies deps = (kind, id) -> kind == CgLookKinds.TEXT_STYLE ? GOLD
                : kind == CgLookKinds.WIDGET && id.equals(digit.id()) ? digit : null;
        CgValidation v = CgAssetData.validate("pop.yaml", CgLookKinds.WIDGET, (Map<?, ?>) CgAssetYaml.read(yaml),
                CgValidation.Usecase.LOAD);
        assertTrue(v.issues() + "\n" + yaml, v.valid());
        assertEquals(yaml, built, CgWidgetCodec.compile(asset("mymod:widgets/pop", yaml, null), deps));
    }

    @Test
    public void aWidgetOutOfPlaceFailsByName() {
        assertFails("Lost", """
                _v: 1
                root: A
                widgets:
                  A: { kind: canvas, children: [B] }
                  B: { kind: image }
                  Lost: { kind: image }
                """);
        assertFails("Missing", """
                _v: 1
                root: A
                widgets:
                  A: { kind: canvas, children: [Missing] }
                """);
        assertFails("B", """
                _v: 1
                root: A
                widgets:
                  A: { kind: canvas, children: [B, C] }
                  C: { kind: border, content: B }
                  B: { kind: image }
                """);
        assertFails("A.kind", """
                _v: 1
                root: A
                widgets:
                  A: { kind: vertical_box }
                """);
        assertFails("A.stretch", """
                _v: 1
                root: A
                widgets:
                  A: { kind: scale_box, user_specified_scale: 0.5 }
                """);
    }

    private static void assertFails(String naming, String yaml) {
        CgAssetException e = assertThrows(CgAssetException.class,
                () -> CgWidgetCodec.compile(asset("mymod:widgets/w", yaml, null), DEPS));
        assertTrue(e.getMessage(), e.getMessage().contains("mymod:widgets/w") && e.getMessage().contains(naming));
    }

    /** Kakarot's curve: 1 at 1080 on the shortest side, linear between keys, held past its ends. */
    @Test
    public void theUiScaleReadsTheCurveAtTheShortestSide() {
        CgUiScale ui = CgUiScale.of((Map<?, ?>) CgAssetYaml.read("""
                _v: 1
                rule: shortest_side
                curve: [[480, 0.444], [720, 0.666], [1080, 1], [8640, 8]]
                application_scale: 1
                """));
        assertEquals(1f, ui.scale(1920f, 1080f), 1e-4f);
        assertEquals("between 1080 and 8640", 1f + 360f * 7f / 7560f, ui.scale(2560f, 1440f), 1e-4f);
        assertEquals("held below its first key", 0.444f, ui.scale(400f, 300f), 1e-4f);
        assertEquals(1f, CgUiScale.DEFAULT.scale(3000f, 1080f), 1e-4f);
        assertEquals("Unreal's default shrinks with the window", 0.666f, CgUiScale.DEFAULT.scale(1280f, 720f), 1e-4f);
        CgUiScale kept = CgUiScale.of((Map<?, ?>) CgAssetYaml.read("_v: 1\napplication_scale: 0.75\n"));
        assertEquals("no curve: Unreal's, under its application scale", 1f, kept.scale(2560f, 1440f), 1e-4f);
        assertFalse("a default with no file", CgUiScale.of("no_such_kit") != CgUiScale.DEFAULT);
    }
}
