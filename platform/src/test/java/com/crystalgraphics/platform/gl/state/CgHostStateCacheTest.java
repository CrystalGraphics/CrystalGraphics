package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.CgGL;
import org.junit.Test;

import static com.crystalgraphics.platform.gl.state.CgCheckedProvider.bit;
import static org.junit.Assert.*;

/**
 * {@link CgHostStateCache} against classes shaped like each era's {@code GlStateManager}, their fields renamed as SRG
 * and intermediary rename them, so only shape finds them; and against 26.2's, found by Mojang's names.
 */
public class CgHostStateCacheTest {

    /** Legacy Forge to 1.16.5: a light table of BooleanStates beside the texture table, an alpha test, no scissor. */
    @SuppressWarnings("unused")
    static final class Legacy {
        static final class Bool { final int cap; boolean on; Bool(int cap) { this.cap = cap; } }
        static final class Alpha { final Bool test = new Bool(3008); int func = 516; float ref = 0.1f; }
        static final class Blend { final Bool on = new Bool(3042); int src = 770, dst = 771, srcA = 1, dstA = 0; }
        static final class Depth { final Bool test = new Bool(2929); boolean mask = true; int func = 515; }
        static final class Cull { final Bool on = new Bool(2884); int face = 1028; }
        static final class Offset { final Bool fill = new Bool(32823); final Bool line = new Bool(10754); float factor = 1f, units = 2f; }
        static final class Mask { boolean r = true, g = true, b = false, a = true; }
        static final class Tex { final Bool on = new Bool(3553); int name; }

        private static Alpha a1 = new Alpha();
        private static Bool[] a2 = lights();
        private static Blend a3 = new Blend();
        private static Depth a4 = new Depth();
        private static Cull a5 = new Cull();
        private static Offset a6 = new Offset();
        private static int a7;                       // the active unit
        private static Tex[] a8 = textures(8);
        private static int a9 = 7425;                // the shade model
        private static Mask a10 = new Mask();

        static Bool[] lights() {
            Bool[] lights = new Bool[8];
            for (int i = 0; i < 8; i++) lights[i] = new Bool(16384 + i);
            return lights;
        }

        static Tex[] textures(int n) {
            Tex[] t = new Tex[n];
            for (int i = 0; i < n; i++) t[i] = new Tex();
            return t;
        }
    }

    /** 1.17 to 1.19.3: a texture state of one boolean and one int, as a BooleanState is; a scissor; a viewport. */
    @SuppressWarnings("unused")
    static final class Core117 {
        static final class Bool { final int cap; boolean on; Bool(int cap) { this.cap = cap; } }
        static final class Blend { final Bool on = new Bool(3042); int src = 1, dst = 0, srcA = 1, dstA = 0; }
        static final class Depth { final Bool test = new Bool(2929); boolean mask = true; int func = 513; }
        static final class Cull { final Bool on = new Bool(2884); int face = 1029; }
        static final class Offset { final Bool fill = new Bool(32823); final Bool line = new Bool(10754); float factor, units; }
        static final class Scissor { final Bool on = new Bool(3089); }
        static final class Mask { boolean r = true, g = true, b = true, a = true; }
        static final class Tex { boolean enable; int binding; }
        enum Box { ONLY; int x, y, w, h; }

        private static Blend b1 = new Blend();
        private static Depth b2 = new Depth();
        private static Cull b3 = new Cull();
        private static Offset b4 = new Offset();
        private static Scissor b5 = new Scissor();
        private static int b6;
        private static Tex[] b7 = textures();
        private static Mask b8 = new Mask();

        static Tex[] textures() {
            Tex[] t = new Tex[12];
            for (int i = 0; i < 12; i++) t[i] = new Tex();
            return t;
        }
    }

    /** 26.2, unrenamed: blend and colour mask per draw buffer, the blend equation beside each factor pair. */
    @SuppressWarnings("unused")
    static final class Mojang262 {
        static final class BooleanState { final int cap; boolean on; BooleanState(int cap) { this.cap = cap; } }
        static final class BlendState {
            final BooleanState mode = new BooleanState(3042);
            int srcRgb = 770, dstRgb = 771, modeRgb = 32774, srcAlpha = 1, dstAlpha = 771, modeAlpha = 32776;
        }
        static final class DepthState { final BooleanState mode = new BooleanState(2929); boolean mask = true; int func = 518; }
        static final class CullState { final BooleanState enable = new BooleanState(2884); }
        static final class PolygonOffsetState { final BooleanState fill = new BooleanState(32823); final BooleanState line = new BooleanState(10754); float factor, units; }
        static final class ScissorState { final BooleanState mode = new BooleanState(3089); }
        static final class TextureState { int binding; }

        private static final BlendState[] BLEND = {new BlendState(), new BlendState()};
        private static final DepthState DEPTH = new DepthState();
        private static final CullState CULL = new CullState();
        private static final PolygonOffsetState POLY_OFFSET = new PolygonOffsetState();
        private static final ScissorState SCISSOR = new ScissorState();
        private static final int[] COLOR_MASK = {0xF, 0xF};
        private static final TextureState[] TEXTURES = {new TextureState(), new TextureState(), new TextureState(),
                new TextureState(), new TextureState(), new TextureState(), new TextureState(), new TextureState(),
                new TextureState(), new TextureState(), new TextureState(), new TextureState()};
        private static int activeTexture;
    }

    @Test
    public void aLegacyCacheIsFoundByShapeBesideItsLightTable() throws Exception {
        Legacy.a8[2].name = 77;
        CgHostStateCache cache = new CgHostStateCache(Legacy.class, 8,
                unit -> Legacy.a7 = unit - CgGL.GL_TEXTURE0, () -> CgGL.GL_TEXTURE0 + 2);

        assertEquals("legacy keeps no viewport", bit(CgGlSlot.VIEWPORT), cache.missing());
        CgGlStateShadow t = new CgGlStateShadow();
        for (CgGlSlot slot : new CgGlSlot[] {CgGlSlot.BLEND, CgGlSlot.DEPTH, CgGlSlot.CULL, CgGlSlot.ALPHA_TEST,
                CgGlSlot.POLYGON_OFFSET, CgGlSlot.COLOR_MASK, CgGlSlot.SCISSOR}) {
            assertTrue(slot.name(), cache.answer(slot, t));
        }
        assertEquals(770, t.blendSrcRgb);
        assertEquals(771, t.blendDstRgb);
        assertEquals(CgGL.GL_FUNC_ADD, t.blendEqRgb);
        assertEquals(515, t.depthFunc);
        assertEquals(1028, t.cullFace);
        assertEquals(516, t.alphaFunc);
        assertEquals(0.1f, t.alphaRef, 0f);
        assertEquals(2f, t.polygonOffsetUnits, 0f);
        assertEquals("red, green and alpha in every draw buffer", 0xBBBBBBBB, t.colorMaskPacked);
        assertFalse("no scissor kept: off, as at every hook", t.scissorTest);

        cache.textures(t, cache.unitsMask());
        assertEquals("the probe found the unit and put the driver's back", 2, t.activeTextureUnit);
        assertEquals(77, t.boundTexture2D[2]);
        assertFalse("PROGRAM is the host's to answer", cache.answer(CgGlSlot.PROGRAM, t));
    }

    @Test
    public void aTextureStateShapedLikeABooleanStateIsStillTheTextureTable() throws Exception {
        Core117.b7[5].binding = 41;
        Core117.Box.ONLY.w = 854;
        Core117.Box.ONLY.h = 480;
        CgHostStateCache cache = new CgHostStateCache(Core117.class, 12,
                unit -> Core117.b6 = unit - CgGL.GL_TEXTURE0, () -> CgGL.GL_TEXTURE0 + 5);

        assertEquals("from 1.17 there is no alpha test", bit(CgGlSlot.ALPHA_TEST), cache.missing());
        CgGlStateShadow t = new CgGlStateShadow();
        assertTrue(cache.answer(CgGlSlot.VIEWPORT, t));
        assertEquals(854, t.viewportW);
        assertEquals(480, t.viewportH);
        cache.textures(t, 1 << 5);
        assertEquals(5, t.activeTextureUnit);
        assertEquals(41, t.boundTexture2D[5]);
    }

    @Test
    public void aMojangNamedCacheReadsItsFirstDrawBuffer() throws Exception {
        Mojang262.activeTexture = 3;
        Mojang262.TEXTURES[3].binding = 9;
        CgHostStateCache cache = new CgHostStateCache(Mojang262.class, 12,
                unit -> { throw new AssertionError("found by name: no probe"); }, () -> 0);

        assertEquals(bit(CgGlSlot.ALPHA_TEST) | bit(CgGlSlot.VIEWPORT), cache.missing());
        CgGlStateShadow t = new CgGlStateShadow();
        assertTrue(cache.answer(CgGlSlot.BLEND, t));
        assertEquals(32774, t.blendEqRgb);
        assertEquals(32776, t.blendEqAlpha);
        assertEquals(1, t.blendSrcAlpha);
        assertTrue(cache.answer(CgGlSlot.COLOR_MASK, t));
        assertEquals(0xFFFFFFFF, t.colorMaskPacked);
        cache.textures(t, 1 << 3);
        assertEquals(3, t.activeTextureUnit);
        assertEquals(9, t.boundTexture2D[3]);
    }
}
