package com.crystalgraphics.sequence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;

import javax.annotation.Nullable;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.object.validate.CgValidation;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.sequence.binding.CgFigureDefinition;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgAttachment;

/**
 * A locator takes its role's figure: the record's key transform composed under its own, a key the record lacks
 * falling back to the record's, a record or a figure that is not there leaving it as it is.
 */
public class CgSequenceFigureTest {

    private static final String FIGURE = "_v: 1\nrecords:\n"
            + "  - id: r1\n    name: LSG_Hit\n    transform: { scale: [1, 1, 1] }\n"
            + "    keys: [ { id: k1, name: Hit_01, transform: { location: [0, 1, 0], scale: [2, 2, 2] } } ]\n";

    private static String sequence(String record, @Nullable String key) {
        return "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 800]\n"
                + "roles:\n  - { id: b1, name: target, kind: target }\n"
                + "locators:\n  - { id: j1, name: joint, parent: { role: target }, location: [1, 0, 0],"
                + " figure: { role: target, record: " + record + (key == null ? "" : ", key: " + key) + " } }\n";
    }

    /** A target standing at the origin, playing with {@code figure}. */
    private static final class Target implements CgSequenceRole {
        final CgFigureDefinition figure;

        Target(@Nullable CgFigureDefinition figure) {
            this.figure = figure;
        }

        @Override
        public CgAttachment attachment(@Nullable String socket) {
            return (point, turn) -> {
                point[0] = point[1] = point[2] = 0;
                if (turn != null) turn.identity();
                return true;
            };
        }

        @Override
        public CgFigureDefinition figure() {
            return figure;
        }

        @Override
        public int slot(String name) {
            return -1;
        }

        @Override
        public void set(int slot, float[] values, int mask) {
        }

        @Override
        public void get(int slot, float[] out) {
        }
    }

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgFigureDefinition figure() {
        return CgFigureDefinition.compile("mymod:figures/large", (Map<?, ?>) CgAssetYaml.read(FIGURE), CgUnits.HOST);
    }

    private static CgSequenceDefinition compile(String yaml) {
        return CgSequenceDefinition.COMPILER.compile(CgAssetData.of("mymod:s", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null),
                (kind, id) -> null);
    }

    /** Where locator 0 stands and how large it is, the role playing with {@code figure}. */
    private static float[] placed(String yaml, @Nullable CgFigureDefinition figure) {
        CgSequencer sequencer = new CgWorld().add(new CgSequencer());
        CgSequencePlayer player = sequencer.play(compile(yaml), Map.of("target", new Target(figure)));
        sequencer.evaluate(0.1f);
        CgAttachment joint = player.locatorPlacement(0);
        double[] p = new double[3];
        assertTrue(joint.place(p, new Quaternionf()));
        Vector3f s = joint.scale(null, new Vector3f());
        return new float[] {(float) p[0], (float) p[1], (float) p[2], s.x};
    }

    @Test
    public void theKeysTransformComposesUnderTheLocatorsOwn() {
        float[] at = placed(sequence("LSG_Hit", "Hit_01"), figure());
        assertEquals("its own offset, then the key's", 1f, at[0], 1e-6f);
        assertEquals(1f, at[1], 1e-6f);
        assertEquals("the key's scale", 2f, at[3], 1e-6f);
    }

    @Test
    public void aKeyTheRecordLacksTakesTheRecords() {
        float[] at = placed(sequence("LSG_Hit", "Hit_09"), figure());
        assertEquals(0f, at[1], 1e-6f);
        assertEquals("the record's scale", 1f, at[3], 1e-6f);
    }

    @Test
    public void noRecordAndNoFigureLeaveTheLocatorAsItIs() {
        for (float[] at : new float[][] {placed(sequence("LSG_Guard", "Hit_01"), figure()), placed(sequence("LSG_Hit", "Hit_01"), null)}) {
            assertEquals(1f, at[0], 1e-6f);
            assertEquals(0f, at[1], 1e-6f);
            assertEquals(1f, at[3], 1e-6f);
        }
    }

    @Test
    public void aFigureOfARoleTheSequenceLacksFailsByName() {
        try {
            compile(sequence("LSG_Hit", "Hit_01").replace("figure: { role: target", "figure: { role: caster"));
            fail("compiled");
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("caster"));
        }
    }

    @Test
    public void bothFilesAreValidAgainstTheirKinds() {
        CgAssetData figure = CgAssetData.of("mymod:figures/large", CgSequenceKinds.FIGURE, (Map<?, ?>) CgAssetYaml.read(FIGURE), null);
        CgValidation v = figure.validate(CgValidation.Usecase.LOAD);
        assertTrue(v.issues().toString(), v.valid());
        CgAssetData seq = CgAssetData.of("mymod:s", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(sequence("LSG_Hit", "Hit_01")), null);
        v = seq.validate(CgValidation.Usecase.LOAD);
        assertTrue(v.issues().toString(), v.valid());
        assertEquals("the writer keeps the figure", sequence("LSG_Hit", "Hit_01").contains("figure"),
                CgAssetYaml.write(seq.delta(), CgSequenceKinds.SEQUENCE).contains("figure: { role: target, record: LSG_Hit, key: Hit_01 }"));
    }
}
