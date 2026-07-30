package com.crystalgraphics.gl.material;

import com.crystalgraphics.platform.gl.CgCapabilities;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * What a {@link CgMaterialShader} must remember when a compile fails.
 *
 * <p>These pin the two defects that turned a one-line GLSL bug into an unreadable 900KB log:</p>
 *
 * <ul>
 *   <li><b>The parse was thrown away with the failed compile.</b> {@code getDeclaredFeatureNames()}
 *       then fell back to an empty list, so {@code enableKeyword("WITH_BORDER")} threw
 *       <i>"Keyword 'WITH_BORDER' is not declared as #pragma cg_feature in this shader"</i> — naming
 *       a pragma that was present and correct, and burying the real codegen error.</li>
 *   <li><b>There was no negative caching.</b> {@code CgMaterial}'s lazy-compile guards ask whether
 *       state is still missing, not whether an attempt already failed, so a broken shader was
 *       re-parsed and re-compiled for every draw of every frame.</li>
 * </ul>
 *
 * <p>No GL context is involved. Forcing {@code shaderBufferPath = NONE} makes {@code recompile()}
 * fail at the capability check — which sits <em>after</em> parsing and <em>before</em> the first GL
 * call, i.e. exactly the shape of the real failure, reachable from a plain unit test.</p>
 */
public class CgMaterialShaderFailureStateTest {

    /** Has no {@code #pragma cg_use}, so nothing tries to allocate an engine buffer on the way. */
    private static final String SHADER = "crystalgraphics:shaders/demo_render.shader";

    @After
    public void clearCapabilitiesCache() throws Exception {
        Field cacheField = CgCapabilities.class.getDeclaredField("cachedCaps");
        cacheField.setAccessible(true);
        cacheField.set(null, null);
    }

    /** A failure after parsing must still publish the parse — the feature list included. */
    @Test
    public void parseProductsSurviveACompileFailure() throws Exception {
        CgMaterialShader shader = failToCompile();

        assertNotNull("A shader that parsed but failed to compile must still expose its parse — "
                + "otherwise enableKeyword() blames the author's #pragma for a codegen bug",
                shader.getLastParsed());
        assertNotNull("Declared feature names must survive a compile failure",
                shader.getLastParsed().featureNames());
    }

    /** And it must latch, or the next lazy-compile guard re-attempts it immediately. */
    @Test
    public void failureIsLatched() throws Exception {
        assertTrue("A failed recompile must latch so callers stop retrying it every draw",
                failToCompile().hasCompileFailed());
    }

    /**
     * ...but markDirty() must clear the latch. Without this a shader that failed once could never be
     * fixed by a hot-reload — only by restarting the game.
     */
    @Test
    public void markDirtyClearsTheLatch() throws Exception {
        CgMaterialShader shader = failToCompile();
        assertTrue(shader.hasCompileFailed());

        shader.markDirty();

        assertFalse("markDirty() must clear the failure latch, or F3+T can never recover a shader "
                + "that failed to compile", shader.hasCompileFailed());
        assertTrue("markDirty() must still mark dirty", shader.isDirty());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Drives one {@code recompile()} that parses successfully and then fails, by reporting no usable
     * shader-buffer path. The first compile of a shader throws on failure by contract, so the throw
     * is the expected outcome, not an error.
     */
    private static CgMaterialShader failToCompile() throws Exception {
        installShaderBufferPath(CgCapabilities.ShaderBufferPath.NONE);
        CgMaterialShader shader = CgMaterialShader.create(SHADER);
        try {
            shader.recompile();
            fail("Expected recompile() to fail with no usable shader-buffer path");
        } catch (UnsupportedOperationException expected) {
            // The documented first-compile behaviour: throw rather than return in a broken state.
        }
        return shader;
    }

    private static void installShaderBufferPath(CgCapabilities.ShaderBufferPath path) throws Exception {
        Constructor<CgCapabilities> ctor = CgCapabilities.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        CgCapabilities stub = ctor.newInstance();
        Field pathField = CgCapabilities.class.getDeclaredField("shaderBufferPath");
        pathField.setAccessible(true);
        pathField.set(stub, path);
        Field cacheField = CgCapabilities.class.getDeclaredField("cachedCaps");
        cacheField.setAccessible(true);
        cacheField.set(null, stub);
    }
}
