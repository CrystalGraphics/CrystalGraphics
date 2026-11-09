package com.crystalgraphics.sequence.binding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/** A widget animation's tracks on a widget's named children, as UMG's write a UWidget's render transform and colour. */
public class CgSequenceWidgetTest {

    /** Over a second: the banner turns from -6.5 to 0 degrees and fades in; its scale is never keyed. */
    private static final String POP = """
            _v: 1
            tick_resolution: 1000
            display_rate: 50
            range: [0, 1000]
            roles:
              - { id: r1, name: number, kind: caster }
            tracks:
              - { id: t1, kind: property, binding: { role: number, node: Img_Banner }, property: angle,
                  sections: [ { id: s1, range: [0, 1000], value: { keys: [[0, -6.5, linear], [1000, 0.0, linear]] } } ] }
              - { id: t2, kind: property, binding: { role: number, node: Img_Banner }, property: colour_and_opacity,
                  sections: [ { id: s2, range: [0, 1000], value: { a: { keys: [[0, 0.0, linear], [1000, 1.0, linear]] } } } ] }
            """;

    @Test
    public void aTrackWritesTheChildItNamesAndLeavesItsDesignerValuesAlone() {
        CgSequenceDefinition definition = CgSequenceDefinition.compile(
                CgAssetData.of("mymod:pop", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(POP), null));
        CgSequenceWidget banner = new CgSequenceWidget().scale(1.5f, 1f).colour(0xFFFF8000);
        CgSequenceWidget pop = new CgSequenceWidget().child("Img_Banner", banner);
        CgWorld world = new CgWorld();
        CgSequencer sequencer = world.add(new CgSequencer());
        sequencer.play(definition, Map.of("number", pop));
        sequencer.evaluate(0f);
        sequencer.evaluate(0.5f);
        assertEquals(-3.25f, banner.angle(), 1e-4f);
        assertEquals("half faded in, the brush's colour kept", 0x80FF8000, banner.argb());
        assertEquals("unkeyed: the designer's", 1.5f, banner.scaleX(), 0f);
        world.close();
    }

    /** Slate's order: scale, then rotation, then translation, about the pivot, in canvas pixels a unit. */
    @Test
    public void itsPoseScalesThenTurnsThenMovesAboutThePivot() {
        CgSequenceWidget w = new CgSequenceWidget().scale(2f, 1f).angle(90f).translation(10f, 0f);
        Vector3f corner = w.pose(100f, 50f, 0.5f, new Matrix4f()).transformPosition(new Vector3f(1f, 0f, 0f));
        // (1, 0) scaled to (2, 0), turned clockwise on a y-down canvas to (0, 2), moved to (10, 2), halved, pivoted.
        assertEquals(105f, corner.x, 1e-4f);
        assertEquals(51f, corner.y, 1e-4f);
    }

    /** A shear of 45 degrees moves a point by its other coordinate. */
    @Test
    public void aShearOf45DegreesSlidesXByY() {
        Vector3f p = new CgSequenceWidget().shear(45f, 0f).pose(0f, 0f, 1f, new Matrix4f())
                .transformPosition(new Vector3f(0f, 1f, 0f));
        assertEquals(1f, p.x, 1e-4f);
        assertEquals(1f, p.y, 1e-4f);
    }

    /** A point ahead of the camera lands on the rectangle, y down; one behind answers false. */
    @Test
    public void aViewProjectsAheadAndRefusesBehind() {
        CgHostView view = new CgHostView().set(10d, 64d, 0d, new Matrix4f(),
                new Matrix4f().perspective((float) Math.toRadians(90), 1f, 0.05f, 100f));
        Vector2f at = new Vector2f();
        assertTrue(view.project(10d, 64d, -5d, 200f, 100f, at));
        assertEquals("the centre", 100f, at.x, 1e-3f);
        assertEquals(50f, at.y, 1e-3f);
        assertTrue(view.project(10d, 66.5d, -5d, 200f, 100f, at));
        assertEquals("above is up the screen: a quarter of the way", 25f, at.y, 1e-3f);
        assertFalse(view.project(10d, 64d, 5d, 200f, 100f, at));
    }
}
