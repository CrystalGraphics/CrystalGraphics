package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.device.recording.CgRecordingDevice;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.state.CgGlStateShadow;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.Assert.*;

/** The scope framework on the tracked backend: the same code as on GL, so the same guarantees (spec §4.2). */
public class CgTrackedScopeSuiteTest {

    private CgTrackedGLBackend gl;
    private CgTrackedStateProvider provider;

    @Before
    public void setUp() {
        gl = new CgTrackedGLBackend(new CgRecordingDevice(32, 32), FakeGlslCompiler.EMPTY, true);
        provider = new CgTrackedStateProvider(gl);
        CgGL.init(gl);
        CgGlState.reset();
        CgGlState.setProvider(provider);
    }

    @After
    public void tearDown() {
        CgGlState.reset();
    }

    /** What each slot's test writes, through CgGL. */
    private Map<CgGlSlot, Runnable> changes() {
        int program = CgGL.glCreateProgram(), fbo = CgGL.glGenFramebuffers(), texture = CgGL.glGenTextures();
        int vao = CgGL.glGenVertexArrays(), buffer = CgGL.glGenBuffers();
        Map<CgGlSlot, Runnable> m = new EnumMap<>(CgGlSlot.class);
        m.put(CgGlSlot.BLEND, () -> {
            CgGL.glEnable(CgGL.GL_BLEND);
            CgGL.glBlendFuncSeparate(CgGL.GL_SRC_ALPHA, CgGL.GL_ONE_MINUS_SRC_ALPHA, CgGL.GL_ONE, CgGL.GL_ZERO);
            CgGL.glBlendEquationSeparate(CgGL.GL_FUNC_SUBTRACT, CgGL.GL_MAX);
        });
        m.put(CgGlSlot.DEPTH, () -> {
            CgGL.glEnable(CgGL.GL_DEPTH_TEST);
            CgGL.glDepthFunc(CgGL.GL_GEQUAL);
            CgGL.glDepthMask(false);
        });
        m.put(CgGlSlot.CULL, () -> {
            CgGL.glEnable(CgGL.GL_CULL_FACE);
            CgGL.glCullFace(CgGL.GL_FRONT);
            CgGL.glFrontFace(CgGL.GL_CW);
        });
        m.put(CgGlSlot.STENCIL, () -> {
            CgGL.glEnable(CgGL.GL_STENCIL_TEST);
            CgGL.glStencilFunc(CgGL.GL_EQUAL, 3, 0x0F);
            CgGL.glStencilOp(CgGL.GL_REPLACE, CgGL.GL_INCR, CgGL.GL_INVERT);
            CgGL.glStencilMask(0x0F);
        });
        m.put(CgGlSlot.ALPHA_TEST, () -> CgGL.glAlphaFunc(CgGL.GL_GREATER, 0.5f));
        m.put(CgGlSlot.COLOR_MASK, () -> CgGL.glColorMask(false, true, false, true));
        m.put(CgGlSlot.VIEWPORT, () -> CgGL.glViewport(1, 2, 3, 4));
        m.put(CgGlSlot.SCISSOR, () -> {
            CgGL.glEnable(CgGL.GL_SCISSOR_TEST);
            CgGL.glScissor(1, 2, 3, 4);
        });
        m.put(CgGlSlot.POLYGON_OFFSET, () -> {
            CgGL.glEnable(CgGL.GL_POLYGON_OFFSET_FILL);
            CgGL.glPolygonOffset(1, 2);
        });
        m.put(CgGlSlot.POLYGON_MODE, () -> CgGL.glPolygonMode(CgGL.GL_FRONT_AND_BACK, CgGL.GL_LINE));
        m.put(CgGlSlot.LINE_WIDTH, () -> CgGL.glLineWidth(1.5f));
        m.put(CgGlSlot.POINT_SIZE, () -> CgGL.glPointSize(4));
        m.put(CgGlSlot.PROGRAM, () -> CgGL.glUseProgram(program));
        m.put(CgGlSlot.FBO, () -> CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fbo));
        m.put(CgGlSlot.TEXTURES, () -> {
            CgGL.glActiveTexture(CgGL.GL_TEXTURE3);
            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, texture);
        });
        m.put(CgGlSlot.VERTEX_INPUT, () -> {
            CgGL.glBindVertexArray(vao);
            CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, buffer);
        });
        m.put(CgGlSlot.STORAGE_BUFFERS, () -> CgGL.glBindBufferRange(CgGL.GL_SHADER_STORAGE_BUFFER, 2, buffer, 16, 64));
        m.put(CgGlSlot.IMAGES,
                () -> CgGL.glBindImageTexture(1, texture, 0, false, 0, CgGL.GL_READ_WRITE, CgGL.GL_RGBA8));
        m.put(CgGlSlot.INDIRECT_BUFFERS, () -> CgGL.glBindBuffer(CgGL.GL_DRAW_INDIRECT_BUFFER, buffer));
        return m;
    }

    @Test
    public void everySlotIsRestored() {
        Map<CgGlSlot, Runnable> changes = changes();
        assertEquals("a change for every slot", CgGlSlot.values().length, changes.size());
        for (CgGlSlot slot : CgGlSlot.values()) {
            CgGlStateShadow before = snapshot();
            try (CgGlScope ignored = CgGlState.save(slot)) {
                changes.get(slot).run();
                assertNotEquals(slot + " changed nothing: the test proves nothing", describe(before), snapshotString());
            }
            assertShadowsEqual(slot, before, snapshot());
        }
    }

    @Test
    public void nestedScopesUnwindInOrder() {
        try (CgGlScope outer = CgGlState.save(CgGlSlot.DEPTH)) {
            CgGL.glDepthFunc(CgGL.GL_GREATER);
            try (CgGlScope inner = CgGlState.save(CgGlSlot.DEPTH)) {
                CgGL.glDepthFunc(CgGL.GL_ALWAYS);
            }
            assertEquals(CgGL.GL_GREATER, gl.renderState().depthFunc);
        }
        assertEquals(CgGL.GL_LESS, gl.renderState().depthFunc);
    }

    @Test
    public void foreignCodeIsUndoneForTheDeclaredDomainAndDistrustedForTheRest() {
        CgGL.glEnable(CgGL.GL_BLEND);
        CgGL.glDepthFunc(CgGL.GL_LESS);
        try (CgGlScope ignored = CgGlState.hostForeign(CgGlSlot.BLEND)) {
            gl.glDisable(CgGL.GL_BLEND);                 // Minecraft's renderer, behind CgGL's back
            gl.glDepthFunc(CgGL.GL_GREATER);
        }
        assertTrue("the declared domain is re-asserted for real", gl.renderState().blend);
        CgGL.glDepthFunc(CgGL.GL_LESS);
        assertEquals("an undeclared domain is untrusted, so the write reaches the backend", CgGL.GL_LESS,
                gl.renderState().depthFunc);
    }

    @Test
    public void closingOutOfOrderIsRefused() {
        CgGlScope blend = CgGlState.save(CgGlSlot.BLEND);
        CgGlScope depth = CgGlState.save(CgGlSlot.DEPTH);
        try {
            blend.close();
            fail("expected an out-of-order close to be refused");
        } catch (IllegalStateException expected) {
        }
        depth.close();
    }

    // ── snapshots ──────────────────────────────────────────────────────────────

    private CgGlStateShadow snapshot() {
        CgGlStateShadow s = new CgGlStateShadow();
        for (CgGlSlot slot : CgGlSlot.values()) provider.read(slot, s);
        return s;
    }

    private String snapshotString() {
        return describe(snapshot());
    }

    private static void assertShadowsEqual(CgGlSlot slot, CgGlStateShadow expected, CgGlStateShadow actual) {
        assertEquals(slot + " was not restored", describe(expected), describe(actual));
    }

    /** Every public field, arrays included: two shadows describe the same state exactly when these agree. */
    private static String describe(CgGlStateShadow s) {
        StringBuilder b = new StringBuilder();
        for (Field f : CgGlStateShadow.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                Object v = f.get(s);
                b.append(f.getName()).append('=')
                        .append(v instanceof int[] a ? Arrays.toString(a)
                                : v instanceof long[] l ? Arrays.toString(l) : Objects.toString(v)).append(' ');
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        return b.toString();
    }
}
