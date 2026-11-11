package com.crystalgraphics.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4fc;
import org.junit.Test;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.text.style.CgTextStyle;
import com.crystalgraphics.widget.CgWidgetComponent.State;
import com.crystalgraphics.world.CgAttachment;

/** World widgets: queued then started at the layer's tick, pooled, placed by their pivot, nearer drawn last. */
public class CgWorldWidgetLayerTest {

    /** A root filled by one image: the image's centre is the component's box's centre. */
    private static final CgWidgetTree TREE = CgWidgetTree.of(CgWidget.canvas("Root")
            .child(CgWidget.image("Img").brush(CgWidgetBrush.texture("mymod:textures/pop", 10f, 10f))
                    .slot(CgWidgetSlot.builder().anchors(0f, 0f, 1f, 1f).offsets(0f, 0f, 0f, 0f).build()))
            .build());

    /** The image fades in over half a second. */
    private static final String FADE = """
            _v: 1
            tick_resolution: 1000
            display_rate: 50
            range: [0, 500]
            roles:
              - { id: r1, name: number, kind: caster }
            tracks:
              - { id: t1, kind: property, binding: { role: number, node: Img }, property: colour_and_opacity,
                  sections: [ { id: s1, range: [0, 500], value: { a: { keys: [[0, 0.0, linear], [500, 1.0, linear]] } } } ] }
            """;

    private static CgSequenceDefinition fade() {
        return CgSequenceDefinition.compile(
                CgAssetData.of("mymod:fade", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(FADE), null));
    }

    /** The image fades out by 0.4 s; its section runs to the sequence's end at 0.5 s. */
    private static CgSequenceDefinition fadeOut() {
        return CgSequenceDefinition.compile(CgAssetData.of("mymod:fade_out", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(
                FADE.replace("[[0, 0.0, linear], [500, 1.0, linear]]", "[[0, 1.0, linear], [400, 0.0, linear]]")), null));
    }

    private static CgAttachment at(double x, double y, double z) {
        return (point, turn) -> {
            point[0] = x;
            point[1] = y;
            point[2] = z;
            return true;
        };
    }

    /** Ten canvas pixels a block of x and y, centred on (500, 300); nothing at or behind z 0. */
    private static boolean project(double x, double y, double z, Vector2f out) {
        if (z <= 0) return false;
        out.set((float) (500 + 10 * x), (float) (300 + 10 * y));
        return true;
    }

    /** Each image's centre on the canvas, in paint order. */
    private static final class Centres implements CgWidgetPainter {
        final List<Vector3f> centres = new ArrayList<>();

        @Override
        public Object layout(CgTextStyle style, String text, float wrap, CgWidget.Justification justification,
                             Vector2f out) {
            out.set(0f, 0f);
            return null;
        }

        @Override
        public void image(CgWidgetBrush brush, Matrix4f pose, float width, float height, float r, float g, float b,
                          float a, Vector4fc parameters) {
            centres.add(pose.transformPosition(new Vector3f(width / 2f, height / 2f, 0f)));
        }

        @Override
        public void text(CgTextStyle style, String text, Object laidOut, CgWidget.Justification justification,
                         Matrix4f pose, Matrix4f layoutPose, float width, float r, float g, float b, float a) {
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

    private static CgWorldWidgetLayer layer(CgWorld world, int live, int waiting) {
        return CgWorldWidgetLayer.builder("mymod:pops").world(world).most(live, waiting).detached();
    }

    @Test
    public void startsAtItsTickNewestFirstUnderItsCap() {
        CgWorld world = new CgWorld();
        CgWorldWidgetLayer layer = layer(world, 2, 2);
        CgWidgetComponent[] c = new CgWidgetComponent[5];
        for (int i = 0; i < 5; i++) c[i] = layer.add(TREE, at(0, 0, 1));
        assertEquals("nothing starts before the tick", 0, layer.live());
        assertEquals(State.RELEASED, c[0].state());
        assertEquals(State.RELEASED, c[1].state());
        assertEquals("past the waiting cap the oldest go", State.RELEASED, c[2].state());
        // Each one dropped is reused by the next added: three instances for five adds.
        assertEquals(2, layer.pool().active().size());
        assertEquals(1, layer.pool().inactive());

        layer.tick(0);
        assertEquals(2, layer.live());
        assertEquals(State.LIVE, c[3].state());
        assertEquals(State.LIVE, c[4].state());

        CgWidgetComponent sixth = layer.add(TREE, at(0, 0, 1));
        layer.tick(0);
        assertEquals("no place: it waits", State.QUEUED, sixth.state());
        c[4].remove();
        layer.tick(0);
        assertEquals(State.LIVE, sixth.state());
    }

    @Test
    public void releasedToThePoolWhenItsSequenceEnds() {
        CgWorld world = new CgWorld();
        CgSequencer sequencer = CgSequencer.widgets(world);
        CgWorldWidgetLayer layer = layer(world, 8, 8);
        CgWidgetComponent pop = layer.add(TREE, at(0, 0, 1)).play(fade());
        CgUserWidget widget = pop.widget();
        layer.tick(0);
        assertNotNull("its sequence started with it", pop.player());
        sequencer.evaluate(0f);
        layer.tick(0);
        assertEquals(State.LIVE, pop.state());

        sequencer.evaluate(1f);
        layer.tick(0);
        assertEquals(State.RELEASED, pop.state());
        assertEquals(0, layer.live());
        assertEquals(1, layer.pool().inactive());
        assertSame("the next one reuses its instance", widget, layer.add(TREE, at(0, 0, 1)).widget());
    }

    @Test
    public void aFinishedAnimationHoldsItsLastFrame() {
        CgSequencer sequencer = CgSequencer.widgets(new CgWorld());
        CgUserWidget widget = TREE.newInstance();
        CgSequencePlayer player = sequencer.play(fadeOut(), Map.of("number", widget));
        sequencer.evaluate(0f);
        sequencer.evaluate(1f);
        assertTrue(player.finished());
        // UMG's last valid frame: the fade's sections still in force, never the designer's opacity of 1 put back.
        assertEquals(0f, widget.colour(TREE.index("Img")).a(), 1e-4f);
    }

    @Test
    public void itsPivotSitsOnItsPointAndTheNearerPaintsLast() {
        CgWorld world = new CgWorld();
        CgWorldWidgetLayer layer = layer(world, 8, 8);
        layer.add(TREE, at(1, 0, 10)).drawSize(100f, 40f);
        layer.add(TREE, at(2, 0, 5)).drawSize(100f, 40f).pivot(0f, 0f);
        layer.tick(0);

        assertEquals(2, layer.gather(CgWorldWidgetLayerTest::project, 0, 0, 0));
        Centres painter = new Centres();
        layer.paint(painter, 2f);
        // Far, pivot (0.5, 0.5): its centre on its point (510, 300). Near, pivot (0, 0): its corner on (520, 300), so
        // its centre half its box on, 2 px a unit.
        assertEquals(new Vector3f(510f, 300f, 0f), painter.centres.get(0));
        assertEquals(new Vector3f(520f + 100f, 300f + 40f, 0f), painter.centres.get(1));
    }

    @Test
    public void behindTheCameraOrNeverPlacedIsNotDrawn() {
        CgWorld world = new CgWorld();
        CgWorldWidgetLayer layer = layer(world, 8, 8);
        layer.add(TREE, at(0, 0, -3));
        layer.add(TREE, (point, turn) -> false);
        boolean[] gone = {false};
        layer.add(TREE, (point, turn) -> {
            if (gone[0]) return false;
            point[0] = 3;
            point[1] = 0;
            point[2] = 4;
            return true;
        }).drawSize(100f, 40f);
        layer.tick(0);
        assertEquals(1, layer.gather(CgWorldWidgetLayerTest::project, 0, 0, 0));
        layer.paint(new Centres(), 1f);
        gone[0] = true;
        Centres painter = new Centres();
        assertEquals("gone, it stays where it was last seen", 1, layer.gather(CgWorldWidgetLayerTest::project, 0, 0, 0));
        layer.paint(painter, 1f);
        assertEquals(new Vector3f(530f, 300f, 0f), painter.centres.get(0));
    }

    @Test
    public void aLevelChangeReleasesEverything() {
        CgWorld world = new CgWorld();
        CgWorldWidgetLayer layer = layer(world, 8, 8);
        layer.add(TREE, at(0, 0, 1));
        layer.tick(4);
        CgWidgetComponent waiting = layer.add(TREE, at(0, 0, 1));
        layer.tick(5);
        assertEquals(0, layer.live());
        assertEquals(State.RELEASED, waiting.state());
        assertEquals(0, layer.pool().active().size());
    }
}
