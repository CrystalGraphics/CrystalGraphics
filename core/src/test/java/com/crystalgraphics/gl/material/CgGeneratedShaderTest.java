package com.crystalgraphics.gl.material;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.1 — {@code .shader} source held in memory rather than loaded from a file.
 *
 * <h3>What is actually being asserted</h3>
 * <p>A node graph compiles to text, and until now there was nowhere to put that text: the only way to
 * reach a {@link CgMaterialShader} was a resource path, and the registry keyed on it. These are the
 * properties the graph compiler depends on, all of them checkable with no GL context — the compile
 * itself needs a driver, but the identity and lifecycle rules do not.</p>
 */
public class CgGeneratedShaderTest {

    /** The registry is a singleton, so a sibling test that called {@code deleteAll()} would otherwise
     * make every method here throw. */
    @Before
    public void resetRegistry() {
        CgMaterialShaderRegistry.resetForTest();
    }

    private static final String SOURCE = """
            #type spatial
            Pass {
                void vertex(out v2f o) { }
                void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }
            }
            """;

    /**
     * <b>Identical source is one shader, not two.</b>
     *
     * <p>Keyed on content rather than on a name, and this is the case a grid of node previews produces
     * constantly: a dozen nodes computing the same thing must not each get their own GL program. It
     * also means an edit that changes the graph without changing its output — moving a node — recompiles
     * nothing.</p>
     */
    @Test
    public void identicalSourceReturnsTheSameAsset() {
        CgMaterialShaderRegistry registry = CgMaterialShaderRegistry.get();

        CgMaterialShader first = registry.getOrCreateGenerated(SOURCE);
        CgMaterialShader second = registry.getOrCreateGenerated(SOURCE);

        assertSame("identical source must share one asset, and therefore one program", first, second);
        assertTrue(first.isGenerated());
    }

    /** And source that differs by a single character is a different shader. */
    @Test
    public void differentSourceReturnsADifferentAsset() {
        CgMaterialShaderRegistry registry = CgMaterialShaderRegistry.get();

        CgMaterialShader first = registry.getOrCreateGenerated(SOURCE);
        CgMaterialShader second = registry.getOrCreateGenerated(SOURCE.replace("1.0", "0.5"));

        assertNotSame(first, second);
    }

    /**
     * <b>A generated shader is distinguishable from a resource-backed one.</b>
     *
     * <p>Not cosmetic: it is what hot reload keys off. The two must never be confused, because one has
     * a file to re-read and the other does not.</p>
     */
    @Test
    public void aResourceBackedShaderIsNotGenerated() {
        CgMaterialShader fromPath = CgMaterialShaderRegistry.get()
                .getOrCreate("crystalgraphics:shaders/does-not-need-to-exist.shader");

        assertFalse("a path-backed shader is not generated", fromPath.isGenerated());
    }

    /**
     * <b>Hot reload leaves generated shaders alone.</b>
     *
     * <p>F3+T means "re-read the files". A generated shader has no file, so marking it dirty would make
     * it recompile from the source it already holds — waste at best. Its source cannot change without
     * its owner emitting different text, which is a different content hash and therefore a different
     * asset entirely. Invalidation flows from the graph, never from the resource manager.</p>
     */
    @Test
    public void reloadAllSkipsGeneratedShaders() {
        CgMaterialShaderRegistry registry = CgMaterialShaderRegistry.get();
        CgMaterialShader generated = registry.getOrCreateGenerated(SOURCE + "\n// unique\n");

        // Freshly created assets are dirty by construction, so the flag has to be cleared first or the
        // assertion below would pass without reloadAll having been correct about anything. recompile()
        // clears it on its very first line and only then reaches for a driver, so swallowing the GL
        // failure leaves exactly the state this test needs — and uses no test-only API to get there.
        try {
            generated.recompile();
        } catch (Throwable expectedWithoutAGlContext) {
            // no driver here; the flag is already clear
        }
        assertFalse(generated.isDirty());

        registry.reloadAll();

        assertFalse("a generated shader has no file to re-read", generated.isDirty());
    }

    /** Empty source is refused rather than producing a shader that fails later, somewhere else. */
    @Test
    public void emptySourceIsRefusedUpFront() {
        CgMaterialShaderRegistry registry = CgMaterialShaderRegistry.get();
        for (String bad : new String[] { null, "" }) {
            try {
                registry.getOrCreateGenerated(bad);
                fail("expected empty generated source to be refused");
            } catch (IllegalArgumentException expected) {
                // the point: it fails here, not at bind() with a driver error
            }
        }
    }
}
