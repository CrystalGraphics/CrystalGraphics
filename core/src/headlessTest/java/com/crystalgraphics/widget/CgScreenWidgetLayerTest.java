package com.crystalgraphics.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector2f;
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

/** Screen widgets by z-order, off at a level change; a widget's own animations: one player each, heard at each end. */
public class CgScreenWidgetLayerTest {

    /** One image of {@code width}, so a painted widget is known by its width. */
    private static CgWidgetTree tree(float width) {
        return CgWidgetTree.of(CgWidget.canvas("Root")
                .child(CgWidget.image("Img").brush(CgWidgetBrush.texture("mymod:textures/pop", width, 10f))
                        .slot(CgWidgetSlot.builder().anchors(0f, 0f, 0f, 0f).offsets(0f, 0f, width, 10f).build()))
                .build());
    }

    /** The image fades in over half a second. */
    private static final String FADE = """
            _v: 1
            tick_resolution: 1000
            display_rate: 50
            range: [0, 500]
            roles:
              - { id: r1, name: counter, kind: caster }
            tracks:
              - { id: t1, kind: property, binding: { role: counter, node: Img }, property: colour_and_opacity,
                  sections: [ { id: s1, range: [0, 500], value: { a: { keys: [[0, 0.0, linear], [500, 1.0, linear]] } } } ] }
            """;

    private static CgSequenceDefinition fade() {
        return sequence("mymod:fade", FADE);
    }

    private static CgSequenceDefinition sequence(String id, String yaml) {
        return CgSequenceDefinition.compile(
                CgAssetData.of(id, CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null));
    }

    /** Each image's width, in paint order. */
    private static final class Widths implements CgWidgetPainter {
        final List<Float> widths = new ArrayList<>();

        @Override
        public Object layout(CgTextStyle style, String text, float wrap, CgWidget.Justification justification,
                             Vector2f out) {
            out.set(0f, 0f);
            return null;
        }

        @Override
        public void image(CgWidgetBrush brush, Matrix4f pose, float width, float height, float r, float g, float b,
                          float a, Vector4fc parameters) {
            widths.add(width);
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

    private static CgScreenWidgetLayer layer(CgWorld world) {
        return CgScreenWidgetLayer.builder("mymod:hud").world(world).detached();
    }

    private static List<Float> painted(CgScreenWidgetLayer layer) {
        Widths widths = new Widths();
        layer.paint(widths, 1920f, 1080f, 1f);
        return widths.widths;
    }

    @Test
    public void higherZOrdersPaintOverLowerAndEqualOnesInTheOrderAdded() {
        CgScreenWidgetLayer layer = layer(new CgWorld());
        layer.add(tree(30f).newInstance(), 5);
        layer.add(tree(10f).newInstance(), 0);
        layer.add(tree(20f).newInstance(), 0);
        assertEquals(List.of(10f, 20f, 30f), painted(layer));
    }

    @Test
    public void addedAgainItMovesAndRemovedItIsGone() {
        CgScreenWidgetLayer layer = layer(new CgWorld());
        CgUserWidget low = tree(10f).newInstance(), high = tree(20f).newInstance();
        layer.add(low, 0).add(high, 1);
        layer.add(low, 2);
        assertEquals("one entry, now on top", List.of(20f, 10f), painted(layer));
        assertTrue(layer.remove(high));
        assertFalse(layer.contains(high));
        assertFalse("removing twice is nothing", layer.remove(high));
        assertEquals(List.of(10f), painted(layer));
    }

    @Test
    public void aLevelChangeTakesEveryWidgetOff() {
        CgScreenWidgetLayer layer = layer(new CgWorld());
        layer.level(4);
        layer.add(tree(10f).newInstance(), 0);
        layer.level(4);
        assertEquals(1, layer.size());
        layer.level(5);
        assertEquals("OnLevelRemovedFromWorld", 0, layer.size());
    }

    @Test
    public void playedAgainAnAnimationRestartsOnItsOwnPlayer() {
        CgWorld world = new CgWorld();
        CgSequencer sequencer = CgSequencer.widgets(world);
        CgUserWidget widget = tree(10f).newInstance();
        layer(world).add(widget, 0);
        CgSequenceDefinition fade = fade();
        CgSequencePlayer first = widget.playAnimation(fade);
        sequencer.evaluate(0f);
        sequencer.evaluate(0.3f);
        assertEquals(0.3, first.seconds(), 1e-6);

        assertSame("GetOrAddPlayer: one player an animation", first, widget.playAnimation(fade));
        sequencer.evaluate(0f);
        assertEquals("restarted from its start", 0.0, first.seconds(), 1e-6);
        assertTrue(widget.isAnimationPlaying(fade));
    }

    @Test
    public void itsEndIsHeardOnceEachTimeItIsReached() {
        CgWorld world = new CgWorld();
        CgSequencer sequencer = CgSequencer.widgets(world);
        CgUserWidget widget = tree(10f).newInstance();
        layer(world).add(widget, 0);
        CgSequenceDefinition fade = fade();
        int[] heard = {0};
        widget.onAnimationFinished(fade, () -> heard[0]++);

        widget.playAnimation(fade);
        sequencer.evaluate(0f);
        sequencer.evaluate(1f);
        assertEquals(1, heard[0]);
        assertFalse(widget.isAnimationPlaying(fade));
        sequencer.evaluate(1f);
        assertEquals("retired at its end, it is not heard again", 1, heard[0]);

        widget.playAnimation(fade);
        sequencer.evaluate(0f);
        sequencer.evaluate(1f);
        assertEquals("a replay ends again", 2, heard[0]);
    }

    @Test
    public void aFinishedAnimationKeepsWhatItWroteAndLetsAnotherWriteAlone() {
        CgWorld world = new CgWorld();
        CgSequencer sequencer = CgSequencer.widgets(world);
        CgUserWidget widget = tree(10f).newInstance();
        layer(world).add(widget, 0);
        CgSequenceDefinition fade = fade();
        CgSequencePlayer in = widget.playAnimation(fade);
        sequencer.evaluate(0f);
        sequencer.evaluate(1f);
        sequencer.evaluate(0.1f);
        assertTrue("retired: UMG's finished player stops evaluating", in.stopped());
        int img = widget.tree().index("Img");
        assertEquals("what it wrote is kept", 1f, widget.colour(img).a(), 0f);

        CgSequenceDefinition out = sequence("mymod:fade_out", FADE.replace("[[0, 0.0, linear], [500, 1.0, linear]]",
                "[[0, 1.0, linear], [500, 0.0, linear]]"));
        widget.playAnimation(out);
        sequencer.evaluate(0f);
        sequencer.evaluate(0.25f);
        assertEquals("written alone, not blended with the finished fade", 0.5f, widget.colour(img).a(), 1e-4f);
    }

    @Test
    public void aStoppedAnimationNeverFinishes() {
        CgWorld world = new CgWorld();
        CgSequencer sequencer = CgSequencer.widgets(world);
        CgUserWidget widget = tree(10f).newInstance();
        layer(world).add(widget, 0);
        CgSequenceDefinition fade = fade();
        int[] heard = {0};
        widget.onAnimationFinished(fade, () -> heard[0]++);
        widget.playAnimation(fade);
        sequencer.evaluate(0f);
        widget.stopAnimation(fade);
        assertFalse(widget.isAnimationPlaying(fade));
        sequencer.evaluate(1f);
        assertEquals(0, heard[0]);
    }

    /** A stretch of one animation plays from its start to its end, finishes there and keeps that frame, as UMG's PlayTo. */
    @Test
    public void aStretchPlaysBetweenTwoTimesAndHoldsItsEnd() {
        CgWorld world = new CgWorld();
        CgSequencer sequencer = CgSequencer.widgets(world);
        CgUserWidget widget = tree(10f).newInstance();
        layer(world).add(widget, 0);
        CgSequenceDefinition fade = fade();
        int img = widget.tree().index("Img");
        int[] heard = {0};
        widget.onAnimationFinished(fade, () -> heard[0]++);
        widget.playAnimationTo(fade, 0.1, 0.3, 1f);
        sequencer.evaluate(0f);
        assertEquals("from its start", 0.2f, widget.colour(img).a(), 1e-4f);
        sequencer.evaluate(0.1f);
        assertEquals(0.2, widget.animationCurrentTime(fade), 1e-6);
        sequencer.evaluate(0.2f);
        assertEquals("stopped at its end, evaluated there", 0.6f, widget.colour(img).a(), 1e-4f);
        assertEquals(1, heard[0]);
        sequencer.evaluate(0.1f);
        assertEquals("kept once retired", 0.6f, widget.colour(img).a(), 1e-4f);
        assertEquals("not playing: 0, as UMG's", 0.0, widget.animationCurrentTime(fade), 0.0);

        widget.playAnimationTo(fade, 0.3, 0.5, 2f);
        sequencer.evaluate(0f);
        assertEquals("on from where it held", 0.6f, widget.colour(img).a(), 1e-4f);
        sequencer.evaluate(0.05f);
        assertEquals("at twice its speed", 0.8f, widget.colour(img).a(), 1e-4f);
    }
}
