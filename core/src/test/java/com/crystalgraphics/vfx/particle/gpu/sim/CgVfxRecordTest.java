package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import org.junit.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord.Tail.FLAGS;
import static com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord.Tail.PREVIOUS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CgVfxRecordTest {

    @Test
    public void theCoreAloneIsFourVectors() {
        CgVfxRecord core = CgVfxRecord.of(List.of());
        assertSame(CgVfxRecord.CORE, core);
        assertEquals(16, core.words());
        assertEquals(64, core.bytes());
        assertEquals(-1, core.word(PREVIOUS));
        assertEquals(-1, core.word(FLAGS));
        assertEquals("struct FxRecord { uvec4 w0; uvec4 w1; uvec4 w2; uvec4 w3; };", core.glsl());
    }

    @Test
    public void aTailLaysOutInDeclarationOrderPaddedToAVector() {
        CgVfxRecord both = CgVfxRecord.of(List.of(PREVIOUS, FLAGS));
        assertEquals(16, both.word(PREVIOUS));
        assertEquals(19, both.word(FLAGS));
        assertEquals(20, both.words());

        CgVfxRecord flagsFirst = CgVfxRecord.of(List.of(FLAGS, PREVIOUS));
        assertEquals(16, flagsFirst.word(FLAGS));
        assertEquals(17, flagsFirst.word(PREVIOUS));

        CgVfxRecord flags = CgVfxRecord.of(List.of(FLAGS, FLAGS));
        assertEquals(List.of(FLAGS), flags.tail());
        assertEquals(20, flags.words());
        assertSame(flags, CgVfxRecord.of(List.of(FLAGS)));
    }

    @Test
    public void aShapeStoresOnlyWhatItsCodeReads() {
        CgVfxShape drifting = CgVfxShape.of(emitter("drifting")
                .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Drag(0.3f, 0.15f)).build());
        assertSame(CgVfxRecord.CORE, drifting.record());
        String step = CgVfxEmitterCompiler.source(drifting);
        assertTrue(step.contains("p.previous = p.position;"));
        assertTrue(step.contains("p.resting = false;"));

        CgVfxShape landing = CgVfxShape.of(emitter("landing")
                .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Ground(0.3f, 0.5f, 0.8f)).build());
        assertEquals(List.of(FLAGS, PREVIOUS), landing.record().tail());   // fx_ground rests a particle; Look's floor
        assertEquals(20, landing.record().words());
        CgVfxEmitterCompiler.compile(landing).kernel("Step").check();
    }

    @Test
    public void theSharedKernelsReadTheCoreWhereEveryLayoutPutsIt() {
        String lib = CgIO.loadSource("crystalgraphics:shaders/lib/vfx/fx_record.glsl");
        assertEquals(CgVfxRecord.POSITION, 4 * vector(lib, "FX_RECORD_POSITION_AGE"));
        assertEquals(CgVfxRecord.AGE, 4 * vector(lib, "FX_RECORD_POSITION_AGE") + 3);
        assertEquals(CgVfxRecord.VELOCITY, 4 * vector(lib, "FX_RECORD_VELOCITY_SIZE"));
        assertEquals(CgVfxRecord.SIZE, 4 * vector(lib, "FX_RECORD_VELOCITY_SIZE") + 3);
        int lifeSeed = 4 * vector(lib, "FX_RECORD_LIFE_SEED");
        assertEquals(List.of(lifeSeed, lifeSeed + 1, lifeSeed + 2, lifeSeed + 3),
                List.of(CgVfxRecord.LIFE, CgVfxRecord.SEED, CgVfxRecord.SPIN, CgVfxRecord.SPIN_RATE));
        int heatId = 4 * vector(lib, "FX_RECORD_HEAT_ID");
        assertEquals(List.of(heatId, heatId + 1, heatId + 2, heatId + 3),
                List.of(CgVfxRecord.HEAT, CgVfxRecord.ID, CgVfxRecord.SLOT, CgVfxRecord.PARAM_ROW));
    }

    private static CgVfxEmitter.Builder emitter(String name) {
        return CgVfxEmitter.builder(name).state(CgVfxEmitterState.ONCE)
                .capacity(64).burst(0f, 64).life(1f, 2f);
    }

    private static int vector(String lib, String name) {
        Matcher m = Pattern.compile("const int " + name + " = (\\d+);").matcher(lib);
        assertTrue(name, m.find());
        return Integer.parseInt(m.group(1));
    }
}
