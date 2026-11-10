package com.crystalgraphics.text.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.validate.CgValidation;

/** A text style built in Java, written, loaded and compiled back equal; inheritance by layer id; failures by path. */
public class CgTextStyleTest {

    private static final String FONT = "kakarot_kit:fonts/Art/UI/Xcmn_Font/CMN/FOT-RodinNTLGPro-EB.otf";

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgTextStyle gold() {
        return CgTextStyle.builder("rpgcore:text_styles/damage/gold").font(FONT).size(44f).letterSpacing(-120).skew(0.2f)
                .layer(CgTextLayer.shadow("shadow").outlined(true).offset(2f, 3f).paint(0xFF2A1406))
                .layer(CgTextLayer.outline("outline").width(3f).paint(0xFF3A1E08))
                .layer(CgTextLayer.fill("fill").paint(CgTextPaint.gradient(CgTextPaint.Space.GLYPH, 90f,
                        0f, 0xFFFFF6B0, 0.55f, 0xFFFFC21A, 1f, 0xFFE07800)))
                .layer(CgTextLayer.shadow("glow").spread(2f).blur(4f).paint(0x80FFD040).blend(CgTextLayer.Blend.ADDITIVE))
                .build();
    }

    private static CgAssetData data(String id, Map<?, ?> tree, CgAssetData parent) {
        return CgAssetData.of(id, CgLookKinds.TEXT_STYLE, tree, parent);
    }

    private static CgTextStyle compile(CgAssetData data) {
        return CgTextStyle.compile(data, (kind, id) -> null);
    }

    @Test
    public void aStyleBuiltInJavaRoundTripsThroughItsFile() {
        CgTextStyle built = gold();
        String yaml = CgAssetYaml.write(built.toTree(), CgLookKinds.TEXT_STYLE);
        CgAssetData read = data(built.id(), (Map<?, ?>) CgAssetYaml.read(yaml), null);
        CgValidation v = read.validate(CgValidation.Usecase.LOAD);
        assertTrue(yaml + "\n" + v.issues(), v.valid());
        assertEquals(yaml, built, compile(read));
    }

    @Test
    public void aChildChangesOneLayerByIdAndKeepsTheRest() {
        CgTextStyle gold = gold();
        CgAssetData parent = data(gold.id(), gold.toTree(), null);
        CgAssetData child = data("rpgcore:text_styles/damage/critical", (Map<?, ?>) CgAssetYaml.read("""
                _v: 1
                parent: rpgcore:text_styles/damage/gold
                layers:
                  - id: fill
                    paint: { gradient: { space: glyph, angle: 90, stops: [[0, 0xFFFFE0C0], [1, 0xFFFF3A10]] } }
                """), parent);
        CgTextStyle critical = compile(child);
        assertEquals("the parent's face", FONT, critical.font());
        assertEquals(4, critical.layers().size());
        CgTextLayer fill = critical.layers().get(2);
        assertEquals("fill", fill.id());
        assertEquals("its own gradient", 0xFFFF3A10, fill.paint().stopColour(1));
        assertEquals("the outline kept", gold.layers().get(1), critical.layers().get(1));
    }

    @Test
    public void noLayersDrawsOneWhiteFill() {
        CgTextStyle plain = compile(data("mymod:text_styles/plain", Map.of("_v", 1L, "font", FONT), null));
        assertEquals(1, plain.layers().size());
        assertEquals(CgTextLayer.Kind.FILL, plain.layers().get(0).kind());
        assertEquals(0xFFFFFFFF, plain.layers().get(0).paint().colour());
        assertEquals("24 points at 96 DPI", 32f, plain.px(), 1e-4f);
    }

    @Test
    public void aGradientOnAShadowFailsAtLoadByPath() {
        CgAssetData bad = data("mymod:text_styles/bad", (Map<?, ?>) CgAssetYaml.read("""
                _v: 1
                font: kakarot_kit:fonts/a.otf
                layers:
                  - { id: drop, kind: shadow, paint: { gradient: { stops: [[0, 0xFF000000], [1, 0xFFFFFFFF]] } } }
                """), null);
        CgAssetException e = assertThrows(CgAssetException.class, () -> compile(bad));
        assertTrue(e.getMessage(), e.getMessage().contains("mymod:text_styles/bad: layers.drop"));
    }

    @Test
    public void aFieldAnotherKindTakesFailsAtLoadByPath() {
        CgAssetData bad = data("mymod:text_styles/bad", (Map<?, ?>) CgAssetYaml.read("""
                _v: 1
                font: kakarot_kit:fonts/a.otf
                layers:
                  - { id: fill, kind: fill, width: 2 }
                """), null);
        CgAssetException e = assertThrows(CgAssetException.class, () -> compile(bad));
        assertTrue(e.getMessage(), e.getMessage().contains("layers.fill") && e.getMessage().contains("an outline's"));
    }

    @Test
    public void aGradientInterpolatesBetweenItsStopsAndHoldsPastThem() {
        CgTextPaint p = CgTextPaint.gradient(CgTextPaint.Space.GLYPH, 90f, 0.25f, 0xFF000000, 0.75f, 0xFFFFFFFF);
        assertEquals(0xFF000000, p.at(0f));
        assertEquals(0xFF808080, p.at(0.5f));
        assertEquals(0xFFFFFFFF, p.at(1f));
    }
}
