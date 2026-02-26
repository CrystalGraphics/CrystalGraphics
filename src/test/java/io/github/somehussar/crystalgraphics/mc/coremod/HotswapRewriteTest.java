package io.github.somehussar.crystalgraphics.mc.coremod;

import org.junit.After;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;

import static org.junit.Assert.*;

/**
 * Tests for hotswap (class re-definition) scenarios exercised through the
 * {@link CrystalGraphicsTransformer} rewrite path.
 *
 * <p>In a real hotswap scenario (DCEVM, IntelliJ hot-reload, etc.), the JVM
 * re-invokes registered class transformers on the new class bytes. These tests
 * verify that the transformer correctly handles:</p>
 * <ul>
 *   <li>Initial rewriting of GL30 static calls to {@code CrystalGLRedirects}</li>
 *   <li>Idempotency: applying the transformer twice produces identical bytes</li>
 *   <li>Default (full) mode behavior via the standard CG transformer path</li>
 *   <li>Re-transformation of classes that mix GL and non-GL calls</li>
 *   <li>Multiple GL call sites within a single class</li>
 *   <li>Descriptor and argument preservation across re-transformation</li>
 * </ul>
 *
 * <p>All tests are JVM-only; no IntelliJ, DCEVM, or OpenGL context is required.
 * Synthetic class bytes are generated via ASM, fed through the transformer, and
 * the resulting bytecode is inspected to verify correctness.</p>
 *
 * @see CrystalGraphicsTransformer
 * @see CoverageMatrix
 */
public class HotswapRewriteTest {

    /** Internal name of the redirect target class. */
    private static final String REDIRECT_OWNER =
        "io/github/somehussar/crystalgraphics/mc/coremod/CrystalGLRedirects";

    /**
     * Resets {@code CrystalGraphicsCoremod.gapOnlyMode} to {@code false} after
     * each test to prevent inter-test contamination.
     */
    @After
    public void resetGapOnlyMode() throws Exception {
        setGapOnlyMode(false);
    }

    // ---------------------------------------------------------------
    //  Task 5: GL30 static call rewrite (hotswap path)
    // ---------------------------------------------------------------

    /**
     * Verifies that a synthetic class containing a GL30.glBindFramebuffer call
     * is correctly rewritten to CrystalGLRedirects.bindFramebufferCore when
     * passed through the transformer (simulating the hotswap rewrite path).
     */
    @Test
    public void testHotswapRewritesGL30BindFramebuffer() {
        byte[] input = makeSyntheticClass(
            "test/hotswap/GL30Bind",
            "org/lwjgl/opengl/GL30",
            "glBindFramebuffer",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform(
            "test.hotswap.GL30Bind", "test.hotswap.GL30Bind", input
        );

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("Expected an INVOKESTATIC instruction after hotswap rewrite", insn);
        assertEquals(REDIRECT_OWNER, insn.owner);
        assertEquals("bindFramebufferCore", insn.name);
        assertEquals("(II)V", insn.desc);
    }

    /**
     * Simulates hotswap of a class that uses GL20.glUseProgram. Verifies the
     * call is rewritten to CrystalGLRedirects.useProgramCore.
     */
    @Test
    public void testHotswapRewritesGL20UseProgram() {
        byte[] input = makeSyntheticClass(
            "test/hotswap/GL20Use",
            "org/lwjgl/opengl/GL20",
            "glUseProgram",
            "(I)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform(
            "test.hotswap.GL20Use", "test.hotswap.GL20Use", input
        );

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("Expected an INVOKESTATIC instruction after hotswap rewrite", insn);
        assertEquals(REDIRECT_OWNER, insn.owner);
        assertEquals("useProgramCore", insn.name);
        assertEquals("(I)V", insn.desc);
    }

    // ---------------------------------------------------------------
    //  Task 8: Idempotency under hotswap
    // ---------------------------------------------------------------

    /**
     * Verifies that applying the transformer twice to the same class bytes
     * produces identical output on the second pass (idempotency guarantee).
     *
     * <p>This is the critical hotswap invariant: when the JVM re-applies
     * transformers during class redefinition, already-rewritten call sites
     * must not be modified again.</p>
     */
    @Test
    public void testHotswapIdempotencyGL30() {
        byte[] input = makeSyntheticClass(
            "test/hotswap/IdempotentGL30",
            "org/lwjgl/opengl/GL30",
            "glBindFramebuffer",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] firstPass = transformer.transform(
            "test.hotswap.IdempotentGL30", "test.hotswap.IdempotentGL30", input
        );
        byte[] secondPass = transformer.transform(
            "test.hotswap.IdempotentGL30", "test.hotswap.IdempotentGL30", firstPass
        );

        assertTrue(
            "Second hotswap pass must produce identical bytes",
            Arrays.equals(firstPass, secondPass)
        );
    }

    /**
     * Verifies idempotency for ARB shader calls. The second pass should
     * detect that call sites already target CrystalGLRedirects and skip them.
     */
    @Test
    public void testHotswapIdempotencyArbShader() {
        byte[] input = makeSyntheticClass(
            "test/hotswap/IdempotentArbShader",
            "org/lwjgl/opengl/ARBShaderObjects",
            "glUseProgramObjectARB",
            "(I)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] firstPass = transformer.transform(
            "test.hotswap.IdempotentArbShader",
            "test.hotswap.IdempotentArbShader",
            input
        );
        byte[] secondPass = transformer.transform(
            "test.hotswap.IdempotentArbShader",
            "test.hotswap.IdempotentArbShader",
            firstPass
        );

        assertTrue(
            "Second hotswap pass must produce identical bytes for ARB shader",
            Arrays.equals(firstPass, secondPass)
        );
    }

    /**
     * Verifies idempotency across three consecutive transformer applications.
     * This catches edge cases where state accumulates across multiple passes.
     */
    @Test
    public void testHotswapIdempotencyTriplePass() {
        byte[] input = makeSyntheticClass(
            "test/hotswap/TriplePass",
            "org/lwjgl/opengl/EXTFramebufferObject",
            "glBindFramebufferEXT",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] pass1 = transformer.transform(
            "test.hotswap.TriplePass", "test.hotswap.TriplePass", input
        );
        byte[] pass2 = transformer.transform(
            "test.hotswap.TriplePass", "test.hotswap.TriplePass", pass1
        );
        byte[] pass3 = transformer.transform(
            "test.hotswap.TriplePass", "test.hotswap.TriplePass", pass2
        );

        assertTrue(
            "Pass 2 and Pass 3 must produce identical bytes",
            Arrays.equals(pass2, pass3)
        );
        assertTrue(
            "Pass 1 and Pass 2 must produce identical bytes",
            Arrays.equals(pass1, pass2)
        );
    }

    /**
     * Verifies idempotency when using a fresh transformer instance for each
     * pass. In a real hotswap scenario, the same transformer instance is
     * reused, but this test verifies no instance-specific state leaks.
     */
    @Test
    public void testHotswapIdempotencyWithFreshTransformers() {
        byte[] input = makeSyntheticClass(
            "test/hotswap/FreshXformer",
            "org/lwjgl/opengl/GL30",
            "glBindFramebuffer",
            "(II)V"
        );

        CrystalGraphicsTransformer t1 = new CrystalGraphicsTransformer();
        byte[] firstPass = t1.transform(
            "test.hotswap.FreshXformer", "test.hotswap.FreshXformer", input
        );

        CrystalGraphicsTransformer t2 = new CrystalGraphicsTransformer();
        byte[] secondPass = t2.transform(
            "test.hotswap.FreshXformer", "test.hotswap.FreshXformer", firstPass
        );

        assertTrue(
            "Different transformer instances must produce idempotent results",
            Arrays.equals(firstPass, secondPass)
        );
    }

    // ---------------------------------------------------------------
    //  Default mode behavior (CG transformer path)
    // ---------------------------------------------------------------

    /**
     * Verifies that the transformer operates in full (default) mode when
     * gapOnlyMode is false, rewriting all GL call sites including GL30.
     */
    @Test
    public void testDefaultModeRewritesAllCallSites() throws Exception {
        setGapOnlyMode(false);

        // GL30 should be rewritten in full mode
        byte[] input = makeSyntheticClass(
            "test/hotswap/DefaultGL30",
            "org/lwjgl/opengl/GL30",
            "glBindFramebuffer",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform(
            "test.hotswap.DefaultGL30", "test.hotswap.DefaultGL30", input
        );

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("GL30 call should be rewritten in default mode", insn);
        assertEquals(REDIRECT_OWNER, insn.owner);
        assertEquals("bindFramebufferCore", insn.name);
    }

    /**
     * Verifies that in default (full) mode, GL20.glUseProgram, GL13.glActiveTexture,
     * and GL11.glBindTexture are all rewritten.
     */
    @Test
    public void testDefaultModeRewritesShaderAndTextureCalls() throws Exception {
        setGapOnlyMode(false);

        // GL20 shader
        byte[] shaderInput = makeSyntheticClass(
            "test/hotswap/DefaultShader",
            "org/lwjgl/opengl/GL20",
            "glUseProgram",
            "(I)V"
        );
        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] shaderOutput = transformer.transform(
            "test.hotswap.DefaultShader", "test.hotswap.DefaultShader", shaderInput
        );
        MethodInsnNode shaderInsn = findFirstInvokeStatic(shaderOutput);
        assertNotNull("GL20 call should be rewritten in default mode", shaderInsn);
        assertEquals("useProgramCore", shaderInsn.name);

        // GL13 active texture
        byte[] texInput = makeSyntheticClass(
            "test/hotswap/DefaultActiveTex",
            "org/lwjgl/opengl/GL13",
            "glActiveTexture",
            "(I)V"
        );
        byte[] texOutput = transformer.transform(
            "test.hotswap.DefaultActiveTex", "test.hotswap.DefaultActiveTex", texInput
        );
        MethodInsnNode texInsn = findFirstInvokeStatic(texOutput);
        assertNotNull("GL13 call should be rewritten in default mode", texInsn);
        assertEquals("activeTextureCore", texInsn.name);

        // GL11 bind texture
        byte[] bindTexInput = makeSyntheticClass(
            "test/hotswap/DefaultBindTex",
            "org/lwjgl/opengl/GL11",
            "glBindTexture",
            "(II)V"
        );
        byte[] bindTexOutput = transformer.transform(
            "test.hotswap.DefaultBindTex", "test.hotswap.DefaultBindTex", bindTexInput
        );
        MethodInsnNode bindTexInsn = findFirstInvokeStatic(bindTexOutput);
        assertNotNull("GL11 call should be rewritten in default mode", bindTexInsn);
        assertEquals("bindTexture", bindTexInsn.name);
    }

    /**
     * Verifies that gap-only mode skips GL30/GL20/GL13/GL11 calls (Angelica
     * already handles them) but still rewrites ARB/EXT variants.
     */
    @Test
    public void testGapOnlyModeSkipsAngelicaCoveredCalls() throws Exception {
        setGapOnlyMode(true);

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();

        // GL30 should NOT be rewritten in gap-only mode
        byte[] gl30Input = makeSyntheticClass(
            "test/hotswap/GapGL30",
            "org/lwjgl/opengl/GL30",
            "glBindFramebuffer",
            "(II)V"
        );
        byte[] gl30Output = transformer.transform(
            "test.hotswap.GapGL30", "test.hotswap.GapGL30", gl30Input
        );
        MethodInsnNode gl30Insn = findFirstInvokeStatic(gl30Output);
        assertNotNull(gl30Insn);
        assertEquals(
            "GL30 should NOT be rewritten in gap-only mode",
            "org/lwjgl/opengl/GL30", gl30Insn.owner
        );

        // ARB FBO SHOULD be rewritten in gap-only mode
        byte[] arbInput = makeSyntheticClass(
            "test/hotswap/GapArb",
            "org/lwjgl/opengl/ARBFramebufferObject",
            "glBindFramebuffer",
            "(II)V"
        );
        byte[] arbOutput = transformer.transform(
            "test.hotswap.GapArb", "test.hotswap.GapArb", arbInput
        );
        MethodInsnNode arbInsn = findFirstInvokeStatic(arbOutput);
        assertNotNull(arbInsn);
        assertEquals(REDIRECT_OWNER, arbInsn.owner);
        assertEquals("bindFramebufferArb", arbInsn.name);
    }

    // ---------------------------------------------------------------
    //  Multi-call and mixed-call hotswap scenarios
    // ---------------------------------------------------------------

    /**
     * Verifies that a class with multiple different GL call sites has all of
     * them rewritten during a single hotswap transformation pass.
     */
    @Test
    public void testHotswapRewritesMultipleCallSitesInOneClass() {
        byte[] input = makeSyntheticClassMultipleCalls(
            "test/hotswap/MultiCall",
            new String[][] {
                {"org/lwjgl/opengl/GL30", "glBindFramebuffer", "(II)V"},
                {"org/lwjgl/opengl/GL20", "glUseProgram", "(I)V"},
                {"org/lwjgl/opengl/GL11", "glBindTexture", "(II)V"},
            }
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform(
            "test.hotswap.MultiCall", "test.hotswap.MultiCall", input
        );

        List<MethodInsnNode> statics = findAllInvokeStatic(output);
        assertEquals("Expected 3 INVOKESTATIC instructions", 3, statics.size());

        // All should be redirected
        for (MethodInsnNode insn : statics) {
            assertEquals(
                "All GL calls should be redirected to CrystalGLRedirects",
                REDIRECT_OWNER, insn.owner
            );
        }

        // Verify specific redirects by name
        assertEquals("bindFramebufferCore", statics.get(0).name);
        assertEquals("useProgramCore", statics.get(1).name);
        assertEquals("bindTexture", statics.get(2).name);
    }

    /**
     * Verifies that idempotency holds for a class with multiple GL call sites.
     */
    @Test
    public void testHotswapIdempotencyMultipleCalls() {
        byte[] input = makeSyntheticClassMultipleCalls(
            "test/hotswap/MultiIdem",
            new String[][] {
                {"org/lwjgl/opengl/GL30", "glBindFramebuffer", "(II)V"},
                {"org/lwjgl/opengl/GL20", "glUseProgram", "(I)V"},
                {"org/lwjgl/opengl/GL13", "glActiveTexture", "(I)V"},
            }
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] firstPass = transformer.transform(
            "test.hotswap.MultiIdem", "test.hotswap.MultiIdem", input
        );
        byte[] secondPass = transformer.transform(
            "test.hotswap.MultiIdem", "test.hotswap.MultiIdem", firstPass
        );

        assertTrue(
            "Multi-call idempotency: second pass must produce identical bytes",
            Arrays.equals(firstPass, secondPass)
        );
    }

    /**
     * Verifies that a class containing both GL calls and non-GL calls
     * (e.g. System.currentTimeMillis) only has the GL calls rewritten,
     * and the non-GL calls are preserved unchanged.
     */
    @Test
    public void testHotswapMixedGlAndNonGlCalls() {
        byte[] input = makeSyntheticClassMultipleCalls(
            "test/hotswap/MixedCalls",
            new String[][] {
                {"org/lwjgl/opengl/GL30", "glBindFramebuffer", "(II)V"},
                {"java/lang/System", "currentTimeMillis", "()J"},
                {"org/lwjgl/opengl/GL20", "glUseProgram", "(I)V"},
            }
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform(
            "test.hotswap.MixedCalls", "test.hotswap.MixedCalls", input
        );

        List<MethodInsnNode> statics = findAllInvokeStatic(output);
        assertEquals("Expected 3 INVOKESTATIC instructions", 3, statics.size());

        // First: GL30 -> redirected
        assertEquals(REDIRECT_OWNER, statics.get(0).owner);
        assertEquals("bindFramebufferCore", statics.get(0).name);

        // Second: System.currentTimeMillis -> NOT redirected
        assertEquals("java/lang/System", statics.get(1).owner);
        assertEquals("currentTimeMillis", statics.get(1).name);
        assertEquals("()J", statics.get(1).desc);

        // Third: GL20 -> redirected
        assertEquals(REDIRECT_OWNER, statics.get(2).owner);
        assertEquals("useProgramCore", statics.get(2).name);
    }

    /**
     * Verifies that idempotency holds for mixed GL/non-GL call classes.
     * Non-GL calls should remain untouched across both passes.
     */
    @Test
    public void testHotswapIdempotencyMixedCalls() {
        byte[] input = makeSyntheticClassMultipleCalls(
            "test/hotswap/MixedIdem",
            new String[][] {
                {"org/lwjgl/opengl/GL30", "glBindFramebuffer", "(II)V"},
                {"java/lang/Math", "abs", "(I)I"},
                {"org/lwjgl/opengl/GL11", "glBindTexture", "(II)V"},
            }
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] firstPass = transformer.transform(
            "test.hotswap.MixedIdem", "test.hotswap.MixedIdem", input
        );
        byte[] secondPass = transformer.transform(
            "test.hotswap.MixedIdem", "test.hotswap.MixedIdem", firstPass
        );

        assertTrue(
            "Mixed-call idempotency: second pass must produce identical bytes",
            Arrays.equals(firstPass, secondPass)
        );
    }

    // ---------------------------------------------------------------
    //  Descriptor and argument preservation
    // ---------------------------------------------------------------

    /**
     * Verifies that the method descriptor is preserved exactly across
     * hotswap rewrite for all covered redirect entries.
     */
    @Test
    public void testHotswapPreservesDescriptorsForAllRedirects() {
        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();

        for (CoverageMatrix.Redirect redirect : CoverageMatrix.FULL_MODE) {
            String className = "test/hotswap/Desc_"
                + redirect.methodName.replace(".", "_");
            byte[] input = makeSyntheticClass(
                className,
                redirect.ownerInternalName,
                redirect.methodName,
                redirect.descriptor
            );

            String dotName = className.replace('/', '.');
            byte[] output = transformer.transform(dotName, dotName, input);

            MethodInsnNode insn = findFirstInvokeStatic(output);
            assertNotNull(
                "Expected rewrite for " + redirect.ownerInternalName + "." + redirect.methodName,
                insn
            );
            assertEquals(
                "Descriptor must be preserved for " + redirect.methodName,
                redirect.descriptor, insn.desc
            );
            assertEquals(
                "Redirect method must match for " + redirect.methodName,
                redirect.redirectMethod, insn.name
            );
        }
    }

    // ---------------------------------------------------------------
    //  Simulated class modification + re-transformation
    // ---------------------------------------------------------------

    /**
     * Simulates a hotswap scenario where a class is initially transformed,
     * then "modified" by adding a new GL call site, and re-transformed.
     * Both old and new call sites must be correctly redirected.
     */
    @Test
    public void testHotswapAfterClassModification() {
        // Step 1: Original class with one GL call
        byte[] original = makeSyntheticClass(
            "test/hotswap/Modified",
            "org/lwjgl/opengl/GL30",
            "glBindFramebuffer",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] firstTransform = transformer.transform(
            "test.hotswap.Modified", "test.hotswap.Modified", original
        );

        // Verify first transform worked
        MethodInsnNode insn1 = findFirstInvokeStatic(firstTransform);
        assertNotNull(insn1);
        assertEquals(REDIRECT_OWNER, insn1.owner);

        // Step 2: Simulate class modification - new version with TWO GL calls
        byte[] modifiedClass = makeSyntheticClassMultipleCalls(
            "test/hotswap/Modified",
            new String[][] {
                {"org/lwjgl/opengl/GL30", "glBindFramebuffer", "(II)V"},
                {"org/lwjgl/opengl/GL20", "glUseProgram", "(I)V"},
            }
        );

        // Step 3: Re-transform the modified class (hotswap re-definition)
        byte[] reTransformed = transformer.transform(
            "test.hotswap.Modified", "test.hotswap.Modified", modifiedClass
        );

        List<MethodInsnNode> statics = findAllInvokeStatic(reTransformed);
        assertEquals("Modified class should have 2 redirected calls", 2, statics.size());
        assertEquals(REDIRECT_OWNER, statics.get(0).owner);
        assertEquals("bindFramebufferCore", statics.get(0).name);
        assertEquals(REDIRECT_OWNER, statics.get(1).owner);
        assertEquals("useProgramCore", statics.get(1).name);
    }

    /**
     * Verifies that re-transforming an already-redirected class that has been
     * "modified" to remove a GL call site results in only the remaining call
     * being redirected (no ghost rewrites from the previous version).
     */
    @Test
    public void testHotswapAfterCallSiteRemoval() {
        // Step 1: Original class with two GL calls
        byte[] original = makeSyntheticClassMultipleCalls(
            "test/hotswap/Removed",
            new String[][] {
                {"org/lwjgl/opengl/GL30", "glBindFramebuffer", "(II)V"},
                {"org/lwjgl/opengl/GL20", "glUseProgram", "(I)V"},
            }
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] firstTransform = transformer.transform(
            "test.hotswap.Removed", "test.hotswap.Removed", original
        );

        List<MethodInsnNode> firstStatics = findAllInvokeStatic(firstTransform);
        assertEquals(2, firstStatics.size());

        // Step 2: Simulate hotswap with modified class - only one GL call remains
        byte[] modifiedClass = makeSyntheticClass(
            "test/hotswap/Removed",
            "org/lwjgl/opengl/GL20",
            "glUseProgram",
            "(I)V"
        );

        byte[] reTransformed = transformer.transform(
            "test.hotswap.Removed", "test.hotswap.Removed", modifiedClass
        );

        List<MethodInsnNode> secondStatics = findAllInvokeStatic(reTransformed);
        assertEquals("Modified class should have only 1 redirected call", 1, secondStatics.size());
        assertEquals(REDIRECT_OWNER, secondStatics.get(0).owner);
        assertEquals("useProgramCore", secondStatics.get(0).name);
    }

    // ---------------------------------------------------------------
    //  Edge cases
    // ---------------------------------------------------------------

    /**
     * Verifies that null input bytes are handled gracefully (returned as-is).
     */
    @Test
    public void testHotswapNullInput() {
        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] result = transformer.transform("test.Null", "test.Null", null);
        assertNull("Null input should return null", result);
    }

    /**
     * Verifies that empty input bytes are handled gracefully (returned as-is).
     */
    @Test
    public void testHotswapEmptyInput() {
        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] empty = new byte[0];
        byte[] result = transformer.transform("test.Empty", "test.Empty", empty);
        assertSame("Empty input should return same reference", empty, result);
    }

    /**
     * Verifies that excluded classes are not transformed even during
     * a hotswap scenario.
     */
    @Test
    public void testHotswapExcludedClassSkipped() {
        byte[] input = makeSyntheticClass(
            "org/lwjgl/opengl/internal/Test",
            "org/lwjgl/opengl/GL30",
            "glBindFramebuffer",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform(
            "org.lwjgl.opengl.internal.Test",
            "org.lwjgl.opengl.internal.Test",
            input
        );

        assertSame(
            "Excluded class should be returned unmodified during hotswap",
            input, output
        );
    }

    /**
     * Verifies that a class with only non-GL INVOKESTATIC calls is
     * returned unmodified (same byte[] reference) during hotswap.
     */
    @Test
    public void testHotswapNoGlCallsReturnsSameReference() {
        byte[] input = makeSyntheticClass(
            "test/hotswap/NoGl",
            "java/lang/System",
            "currentTimeMillis",
            "()J"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform(
            "test.hotswap.NoGl", "test.hotswap.NoGl", input
        );

        assertSame(
            "Class with no GL calls should return same reference",
            input, output
        );
    }

    // ---------------------------------------------------------------
    //  Helper methods (reuse patterns from CrystalGraphicsTransformerTest)
    // ---------------------------------------------------------------

    /**
     * Generates a minimal class with a single static method containing one
     * {@code INVOKESTATIC} call to the specified target.
     */
    private static byte[] makeSyntheticClass(String className,
                                             String targetOwner,
                                             String targetMethod,
                                             String targetDesc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
            Opcodes.V1_6,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            className,
            null,
            "java/lang/Object",
            null
        );

        MethodNode method = new MethodNode(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "syntheticCall",
            "()V",
            null,
            null
        );

        pushArgsForDescriptor(method, targetDesc);

        method.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            targetOwner,
            targetMethod,
            targetDesc,
            false
        );

        int returnOpcode = returnOpcodeFor(targetDesc);
        if (returnOpcode == Opcodes.LRETURN || returnOpcode == Opcodes.DRETURN) {
            method.visitInsn(Opcodes.POP2);
        } else if (returnOpcode != Opcodes.RETURN) {
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN);

        int maxStack = countArgs(targetDesc);
        if (returnOpcode == Opcodes.LRETURN || returnOpcode == Opcodes.DRETURN) {
            maxStack = Math.max(maxStack, 2);
        } else if (returnOpcode != Opcodes.RETURN) {
            maxStack = Math.max(maxStack, 1);
        }
        method.maxStack = Math.max(maxStack, 1);
        method.maxLocals = 0;

        method.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generates a minimal class with a single static method containing multiple
     * {@code INVOKESTATIC} calls. Each call is specified as
     * {@code {owner, method, descriptor}}.
     */
    private static byte[] makeSyntheticClassMultipleCalls(String className,
                                                          String[][] calls) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
            Opcodes.V1_6,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            className,
            null,
            "java/lang/Object",
            null
        );

        MethodNode method = new MethodNode(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "syntheticCall",
            "()V",
            null,
            null
        );

        int maxStack = 1;

        for (String[] call : calls) {
            String owner = call[0];
            String name = call[1];
            String desc = call[2];

            pushArgsForDescriptor(method, desc);

            method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                owner,
                name,
                desc,
                false
            );

            int returnOpcode = returnOpcodeFor(desc);
            if (returnOpcode == Opcodes.LRETURN || returnOpcode == Opcodes.DRETURN) {
                method.visitInsn(Opcodes.POP2);
            } else if (returnOpcode != Opcodes.RETURN) {
                method.visitInsn(Opcodes.POP);
            }

            int callStack = countArgs(desc);
            if (returnOpcode == Opcodes.LRETURN || returnOpcode == Opcodes.DRETURN) {
                callStack = Math.max(callStack, 2);
            } else if (returnOpcode != Opcodes.RETURN) {
                callStack = Math.max(callStack, 1);
            }
            maxStack = Math.max(maxStack, callStack);
        }

        method.visitInsn(Opcodes.RETURN);
        method.maxStack = maxStack;
        method.maxLocals = 0;

        method.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Pushes dummy constant values matching the parameter types in the descriptor.
     */
    private static void pushArgsForDescriptor(MethodNode method, String desc) {
        int i = 1; // skip '('
        while (desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            switch (c) {
                case 'I': case 'Z': case 'B': case 'C': case 'S':
                    method.visitInsn(Opcodes.ICONST_0);
                    i++;
                    break;
                case 'J':
                    method.visitInsn(Opcodes.LCONST_0);
                    i++;
                    break;
                case 'F':
                    method.visitInsn(Opcodes.FCONST_0);
                    i++;
                    break;
                case 'D':
                    method.visitInsn(Opcodes.DCONST_0);
                    i++;
                    break;
                case 'L':
                    method.visitInsn(Opcodes.ACONST_NULL);
                    i = desc.indexOf(';', i) + 1;
                    break;
                case '[':
                    method.visitInsn(Opcodes.ACONST_NULL);
                    while (desc.charAt(i) == '[') { i++; }
                    if (desc.charAt(i) == 'L') {
                        i = desc.indexOf(';', i) + 1;
                    } else {
                        i++;
                    }
                    break;
                default:
                    throw new IllegalArgumentException(
                        "Unexpected descriptor char: " + c + " in " + desc
                    );
            }
        }
    }

    /** Counts JVM stack slots consumed by parameters. */
    private static int countArgs(String desc) {
        int count = 0;
        int i = 1;
        while (desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            switch (c) {
                case 'J': case 'D':
                    count += 2; i++; break;
                case 'L':
                    count++; i = desc.indexOf(';', i) + 1; break;
                case '[':
                    count++;
                    while (desc.charAt(i) == '[') { i++; }
                    if (desc.charAt(i) == 'L') { i = desc.indexOf(';', i) + 1; }
                    else { i++; }
                    break;
                default:
                    count++; i++; break;
            }
        }
        return count;
    }

    /** Returns the appropriate return opcode for the descriptor's return type. */
    private static int returnOpcodeFor(String desc) {
        char ret = desc.charAt(desc.indexOf(')') + 1);
        switch (ret) {
            case 'V': return Opcodes.RETURN;
            case 'I': case 'Z': case 'B': case 'C': case 'S': return Opcodes.IRETURN;
            case 'J': return Opcodes.LRETURN;
            case 'F': return Opcodes.FRETURN;
            case 'D': return Opcodes.DRETURN;
            default:  return Opcodes.ARETURN;
        }
    }

    /**
     * Finds the first INVOKESTATIC instruction in the given class bytes.
     */
    private static MethodInsnNode findFirstInvokeStatic(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        for (MethodNode method : classNode.methods) {
            ListIterator<AbstractInsnNode> it = method.instructions.iterator();
            while (it.hasNext()) {
                AbstractInsnNode insn = it.next();
                if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode) {
                    return (MethodInsnNode) insn;
                }
            }
        }
        return null;
    }

    /**
     * Finds all INVOKESTATIC instructions in the given class bytes, in order.
     */
    private static List<MethodInsnNode> findAllInvokeStatic(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        List<MethodInsnNode> result = new ArrayList<MethodInsnNode>();
        for (MethodNode method : classNode.methods) {
            ListIterator<AbstractInsnNode> it = method.instructions.iterator();
            while (it.hasNext()) {
                AbstractInsnNode insn = it.next();
                if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode) {
                    result.add((MethodInsnNode) insn);
                }
            }
        }
        return result;
    }

    /**
     * Sets the {@code gapOnlyMode} field on {@link CrystalGraphicsCoremod} via
     * reflection.
     */
    private static void setGapOnlyMode(boolean value) throws Exception {
        Field field = CrystalGraphicsCoremod.class.getDeclaredField("gapOnlyMode");
        field.setAccessible(true);
        field.set(null, value);
    }
}
