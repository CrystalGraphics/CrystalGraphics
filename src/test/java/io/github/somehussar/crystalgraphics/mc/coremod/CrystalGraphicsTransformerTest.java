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
import java.util.Arrays;
import java.util.ListIterator;

import static org.junit.Assert.*;

/**
 * Bytecode-level unit tests for {@link CrystalGraphicsTransformer}.
 *
 * <p>These tests generate synthetic class bytes using ASM, pass them through
 * the transformer, and verify that the resulting bytecode contains the expected
 * redirected call sites. No Minecraft runtime or OpenGL context is required;
 * all tests operate on pure Java bytecode.</p>
 *
 * <p>Each test exercises a specific redirect entry from {@link CoverageMatrix},
 * verifying that the transformer correctly rewrites the owner and method name
 * of INVOKESTATIC instructions while preserving the descriptor.</p>
 */
public class CrystalGraphicsTransformerTest {

    /** Internal name of the redirect target class used by all CrystalGraphics redirects. */
    private static final String REDIRECT_OWNER =
        "io/github/somehussar/crystalgraphics/mc/coremod/CrystalGLRedirects";

    /**
     * Resets {@code CrystalGraphicsCoremod.gapOnlyMode} to {@code false} after
     * each test to prevent inter-test contamination.
     *
     * @throws Exception if reflective field access fails
     */
    @After
    public void resetGapOnlyMode() throws Exception {
        setGapOnlyMode(false);
    }

    // ---------------------------------------------------------------
    //  Full-mode redirect tests
    // ---------------------------------------------------------------

    /**
     * Verifies that a call to {@code GL30.glBindFramebuffer(II)V} is rewritten
     * to {@code CrystalGLRedirects.bindFramebufferCore(II)V} in full mode.
     */
    @Test
    public void testBindFramebufferCoreIsRewritten() {
        byte[] input = makeSyntheticClass(
            "test/BindCore",
            "org/lwjgl/opengl/GL30",
            "glBindFramebuffer",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform("test.BindCore", "test.BindCore", input);

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("Expected an INVOKESTATIC instruction in the output", insn);
        assertEquals(REDIRECT_OWNER, insn.owner);
        assertEquals("bindFramebufferCore", insn.name);
        assertEquals("(II)V", insn.desc);
    }

    /**
     * Verifies that a call to {@code ARBFramebufferObject.glBindFramebuffer(II)V}
     * is rewritten to {@code CrystalGLRedirects.bindFramebufferArb(II)V} in full mode.
     */
    @Test
    public void testBindFramebufferArbIsRewritten() {
        byte[] input = makeSyntheticClass(
            "test/BindArb",
            "org/lwjgl/opengl/ARBFramebufferObject",
            "glBindFramebuffer",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform("test.BindArb", "test.BindArb", input);

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("Expected an INVOKESTATIC instruction in the output", insn);
        assertEquals(REDIRECT_OWNER, insn.owner);
        assertEquals("bindFramebufferArb", insn.name);
        assertEquals("(II)V", insn.desc);
    }

    /**
     * Verifies that a call to {@code EXTFramebufferObject.glBindFramebufferEXT(II)V}
     * is rewritten to {@code CrystalGLRedirects.bindFramebufferExt(II)V} in full mode.
     */
    @Test
    public void testBindFramebufferExtIsRewritten() {
        byte[] input = makeSyntheticClass(
            "test/BindExt",
            "org/lwjgl/opengl/EXTFramebufferObject",
            "glBindFramebufferEXT",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform("test.BindExt", "test.BindExt", input);

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("Expected an INVOKESTATIC instruction in the output", insn);
        assertEquals(REDIRECT_OWNER, insn.owner);
        assertEquals("bindFramebufferExt", insn.name);
        assertEquals("(II)V", insn.desc);
    }

    /**
     * Verifies that a call to Minecraft's {@code OpenGlHelper.func_153171_g(II)V}
     * is rewritten to {@code CrystalGLRedirects.bindFramebufferMc(II)V} in full mode.
     */
    @Test
    public void testBindFramebufferMcIsRewritten() {
        byte[] input = makeSyntheticClass(
            "test/BindMc",
            "net/minecraft/client/renderer/OpenGlHelper",
            "func_153171_g",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform("test.BindMc", "test.BindMc", input);

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("Expected an INVOKESTATIC instruction in the output", insn);
        assertEquals(REDIRECT_OWNER, insn.owner);
        assertEquals("bindFramebufferMc", insn.name);
        assertEquals("(II)V", insn.desc);
    }

    /**
     * Verifies that a call to {@code GL20.glUseProgram(I)V} is rewritten
     * to {@code CrystalGLRedirects.useProgramCore(I)V} in full mode.
     */
    @Test
    public void testUseProgramCoreIsRewritten() {
        byte[] input = makeSyntheticClass(
            "test/UseCore",
            "org/lwjgl/opengl/GL20",
            "glUseProgram",
            "(I)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform("test.UseCore", "test.UseCore", input);

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("Expected an INVOKESTATIC instruction in the output", insn);
        assertEquals(REDIRECT_OWNER, insn.owner);
        assertEquals("useProgramCore", insn.name);
        assertEquals("(I)V", insn.desc);
    }

    /**
     * Verifies that a call to {@code ARBShaderObjects.glUseProgramObjectARB(I)V}
     * is rewritten to {@code CrystalGLRedirects.useProgramArb(I)V} in full mode.
     */
    @Test
    public void testUseProgramArbIsRewritten() {
        byte[] input = makeSyntheticClass(
            "test/UseArb",
            "org/lwjgl/opengl/ARBShaderObjects",
            "glUseProgramObjectARB",
            "(I)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform("test.UseArb", "test.UseArb", input);

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("Expected an INVOKESTATIC instruction in the output", insn);
        assertEquals(REDIRECT_OWNER, insn.owner);
        assertEquals("useProgramArb", insn.name);
        assertEquals("(I)V", insn.desc);
    }

    /**
     * Verifies that a call to {@code GL13.glActiveTexture(I)V} is rewritten
     * to {@code CrystalGLRedirects.activeTextureCore(I)V} in full mode.
     */
    @Test
    public void testActiveTextureCoreIsRewritten() {
        byte[] input = makeSyntheticClass(
            "test/ActiveTexCore",
            "org/lwjgl/opengl/GL13",
            "glActiveTexture",
            "(I)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform("test.ActiveTexCore", "test.ActiveTexCore", input);

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("Expected an INVOKESTATIC instruction in the output", insn);
        assertEquals(REDIRECT_OWNER, insn.owner);
        assertEquals("activeTextureCore", insn.name);
        assertEquals("(I)V", insn.desc);
    }

    /**
     * Verifies that a call to {@code ARBMultitexture.glActiveTextureARB(I)V} is rewritten
     * to {@code CrystalGLRedirects.activeTextureArb(I)V} in full mode.
     */
    @Test
    public void testActiveTextureArbIsRewritten() {
        byte[] input = makeSyntheticClass(
            "test/ActiveTexArb",
            "org/lwjgl/opengl/ARBMultitexture",
            "glActiveTextureARB",
            "(I)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform("test.ActiveTexArb", "test.ActiveTexArb", input);

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("Expected an INVOKESTATIC instruction in the output", insn);
        assertEquals(REDIRECT_OWNER, insn.owner);
        assertEquals("activeTextureArb", insn.name);
        assertEquals("(I)V", insn.desc);
    }

    /**
     * Verifies that a call to {@code GL11.glBindTexture(II)V} is rewritten
     * to {@code CrystalGLRedirects.bindTexture(II)V} in full mode.
     */
    @Test
    public void testBindTextureIsRewritten() {
        byte[] input = makeSyntheticClass(
            "test/BindTex",
            "org/lwjgl/opengl/GL11",
            "glBindTexture",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform("test.BindTex", "test.BindTex", input);

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("Expected an INVOKESTATIC instruction in the output", insn);
        assertEquals(REDIRECT_OWNER, insn.owner);
        assertEquals("bindTexture", insn.name);
        assertEquals("(II)V", insn.desc);
    }

    // ---------------------------------------------------------------
    //  Idempotency test
    // ---------------------------------------------------------------

    /**
     * Verifies that passing already-transformed bytes through the transformer
     * a second time produces identical output (idempotency guarantee).
     *
     * <p>The transformer must detect that call sites already target
     * {@code CrystalGLRedirects} and skip them.</p>
     */
    @Test
    public void testIdempotency() {
        byte[] input = makeSyntheticClass(
            "test/Idempotent",
            "org/lwjgl/opengl/GL30",
            "glBindFramebuffer",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] firstPass = transformer.transform("test.Idempotent", "test.Idempotent", input);
        byte[] secondPass = transformer.transform("test.Idempotent", "test.Idempotent", firstPass);

        assertTrue(
            "Second transformation pass should produce identical bytes",
            Arrays.equals(firstPass, secondPass)
        );
    }

    // ---------------------------------------------------------------
    //  Exclusion tests
    // ---------------------------------------------------------------

    /**
     * Verifies that classes whose name matches an exclusion prefix (e.g.
     * {@code org.lwjgl.*}) are not transformed, even if they contain
     * matching call sites.
     */
    @Test
    public void testExcludedClassNotTransformed() {
        byte[] input = makeSyntheticClass(
            "org/lwjgl/test/Foo",
            "org/lwjgl/opengl/GL30",
            "glBindFramebuffer",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform("org.lwjgl.test.Foo", "org.lwjgl.test.Foo", input);

        assertSame(
            "Excluded class bytes should be returned unmodified (same reference)",
            input,
            output
        );
    }

    /**
     * Verifies that a class containing no GL calls (only standard Java method
     * calls) is not modified by the transformer.
     */
    @Test
    public void testClassWithNoGlCallsNotTransformed() {
        byte[] input = makeSyntheticClass(
            "test/NoGl",
            "java/lang/System",
            "currentTimeMillis",
            "()J"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform("test.NoGl", "test.NoGl", input);

        assertSame(
            "Class with no GL calls should be returned unmodified (same reference)",
            input,
            output
        );
    }

    // ---------------------------------------------------------------
    //  Gap-only mode tests
    // ---------------------------------------------------------------

    /**
     * Verifies that in gap-only mode (Angelica present), a call to
     * {@code GL30.glBindFramebuffer(II)V} is NOT rewritten, because
     * Angelica already intercepts this call site.
     *
     * @throws Exception if reflective field access fails
     */
    @Test
    public void testGapOnlyModeSkipsGL30() throws Exception {
        setGapOnlyMode(true);

        byte[] input = makeSyntheticClass(
            "test/GapGL30",
            "org/lwjgl/opengl/GL30",
            "glBindFramebuffer",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform("test.GapGL30", "test.GapGL30", input);

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("Expected an INVOKESTATIC instruction in the output", insn);
        assertEquals(
            "GL30 call should NOT be rewritten in gap-only mode",
            "org/lwjgl/opengl/GL30",
            insn.owner
        );
        assertEquals("glBindFramebuffer", insn.name);
    }

    /**
     * Verifies that in gap-only mode (Angelica present), a call to
     * {@code ARBFramebufferObject.glBindFramebuffer(II)V} IS rewritten,
     * because Angelica does not cover the ARB extension variant.
     *
     * @throws Exception if reflective field access fails
     */
    @Test
    public void testGapOnlyModeRewritesArb() throws Exception {
        setGapOnlyMode(true);

        byte[] input = makeSyntheticClass(
            "test/GapArb",
            "org/lwjgl/opengl/ARBFramebufferObject",
            "glBindFramebuffer",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform("test.GapArb", "test.GapArb", input);

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("Expected an INVOKESTATIC instruction in the output", insn);
        assertEquals(REDIRECT_OWNER, insn.owner);
        assertEquals("bindFramebufferArb", insn.name);
        assertEquals("(II)V", insn.desc);
    }

    /**
     * Verifies that in gap-only mode (Angelica present), a call to
     * {@code EXTFramebufferObject.glBindFramebufferEXT(II)V} IS rewritten,
     * because Angelica does not cover the EXT extension variant.
     *
     * @throws Exception if reflective field access fails
     */
    @Test
    public void testGapOnlyModeRewritesExt() throws Exception {
        setGapOnlyMode(true);

        byte[] input = makeSyntheticClass(
            "test/GapExt",
            "org/lwjgl/opengl/EXTFramebufferObject",
            "glBindFramebufferEXT",
            "(II)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform("test.GapExt", "test.GapExt", input);

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("Expected an INVOKESTATIC instruction in the output", insn);
        assertEquals(REDIRECT_OWNER, insn.owner);
        assertEquals("bindFramebufferExt", insn.name);
        assertEquals("(II)V", insn.desc);
    }

    /**
     * Verifies that in gap-only mode (Angelica present), a call to
     * {@code ARBShaderObjects.glUseProgramObjectARB(I)V} IS rewritten,
     * because Angelica does not cover the ARB shader objects entry point.
     *
     * @throws Exception if reflective field access fails
     */
    @Test
    public void testGapOnlyModeRewritesArbShader() throws Exception {
        setGapOnlyMode(true);

        byte[] input = makeSyntheticClass(
            "test/GapArbShader",
            "org/lwjgl/opengl/ARBShaderObjects",
            "glUseProgramObjectARB",
            "(I)V"
        );

        CrystalGraphicsTransformer transformer = new CrystalGraphicsTransformer();
        byte[] output = transformer.transform("test.GapArbShader", "test.GapArbShader", input);

        MethodInsnNode insn = findFirstInvokeStatic(output);
        assertNotNull("Expected an INVOKESTATIC instruction in the output", insn);
        assertEquals(REDIRECT_OWNER, insn.owner);
        assertEquals("useProgramArb", insn.name);
        assertEquals("(I)V", insn.desc);
    }

    // ---------------------------------------------------------------
    //  Helper methods
    // ---------------------------------------------------------------

    /**
     * Generates a minimal class with a single static method containing one
     * {@code INVOKESTATIC} call to the specified target.
     *
     * <p>The generated method pushes the appropriate number of dummy arguments
     * onto the stack (matching the descriptor) and then calls the target method.
     * This creates a valid bytecode class that the transformer can process.</p>
     *
     * @param className    the slash-form class name for the generated class,
     *                     e.g. {@code "test/Foo"}
     * @param targetOwner  the INVOKESTATIC owner, e.g. {@code "org/lwjgl/opengl/GL30"}
     * @param targetMethod the method name, e.g. {@code "glBindFramebuffer"}
     * @param targetDesc   the method descriptor, e.g. {@code "(II)V"}
     * @return raw class bytes for the generated class
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

        // Determine the argument pushes and return opcode from the descriptor
        int returnOpcode = returnOpcodeFor(targetDesc);
        int maxStack = countArgs(targetDesc);
        // If return type is long or double, we need extra stack for the result
        if (returnOpcode == Opcodes.LRETURN || returnOpcode == Opcodes.DRETURN) {
            maxStack = Math.max(maxStack, 2);
        } else if (returnOpcode != Opcodes.RETURN) {
            maxStack = Math.max(maxStack, 1);
        }

        MethodNode method = new MethodNode(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "syntheticCall",
            "()V",
            null,
            null
        );

        // Push dummy arguments matching the descriptor
        pushArgsForDescriptor(method, targetDesc);

        // The actual INVOKESTATIC call
        method.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            targetOwner,
            targetMethod,
            targetDesc,
            false
        );

        // Pop or return the result
        if (returnOpcode == Opcodes.LRETURN) {
            method.visitInsn(Opcodes.POP2);
            method.visitInsn(Opcodes.RETURN);
        } else if (returnOpcode == Opcodes.DRETURN) {
            method.visitInsn(Opcodes.POP2);
            method.visitInsn(Opcodes.RETURN);
        } else if (returnOpcode == Opcodes.FRETURN) {
            method.visitInsn(Opcodes.POP);
            method.visitInsn(Opcodes.RETURN);
        } else if (returnOpcode == Opcodes.IRETURN || returnOpcode == Opcodes.ARETURN) {
            method.visitInsn(Opcodes.POP);
            method.visitInsn(Opcodes.RETURN);
        } else {
            // void return
            method.visitInsn(Opcodes.RETURN);
        }

        method.maxStack = Math.max(maxStack, 1);
        method.maxLocals = 0;

        classNodeAddMethod(cw, method);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Writes a {@link MethodNode} to a {@link ClassWriter} by having the
     * method node accept the class writer as a visitor.
     *
     * @param cw     the class writer to write to
     * @param method the method node to write
     */
    private static void classNodeAddMethod(ClassWriter cw, MethodNode method) {
        method.accept(cw);
    }

    /**
     * Pushes dummy constant values onto the operand stack matching the
     * parameter types in the given descriptor.
     *
     * <p>For each parameter type: {@code I} pushes {@code ICONST_0},
     * {@code J} pushes {@code LCONST_0}, {@code F} pushes {@code FCONST_0},
     * {@code D} pushes {@code DCONST_0}, and object/array types push
     * {@code ACONST_NULL}.</p>
     *
     * @param method the method node to add instructions to
     * @param desc   the method descriptor whose parameter types determine the pushes
     */
    private static void pushArgsForDescriptor(MethodNode method, String desc) {
        // Parse the parameter portion of the descriptor: between '(' and ')'
        int i = 1; // skip '('
        while (desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            switch (c) {
                case 'I':
                case 'Z':
                case 'B':
                case 'C':
                case 'S':
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
                    // Skip to the ';'
                    i = desc.indexOf(';', i) + 1;
                    break;
                case '[':
                    method.visitInsn(Opcodes.ACONST_NULL);
                    // Skip array dimensions and the base type
                    while (desc.charAt(i) == '[') {
                        i++;
                    }
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

    /**
     * Counts the number of JVM stack slots consumed by the parameters in the
     * given descriptor (longs and doubles count as 2 slots each).
     *
     * @param desc the method descriptor
     * @return the total number of stack slots for parameters
     */
    private static int countArgs(String desc) {
        int count = 0;
        int i = 1; // skip '('
        while (desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            switch (c) {
                case 'J':
                case 'D':
                    count += 2;
                    i++;
                    break;
                case 'L':
                    count++;
                    i = desc.indexOf(';', i) + 1;
                    break;
                case '[':
                    count++;
                    while (desc.charAt(i) == '[') {
                        i++;
                    }
                    if (desc.charAt(i) == 'L') {
                        i = desc.indexOf(';', i) + 1;
                    } else {
                        i++;
                    }
                    break;
                default:
                    count++;
                    i++;
                    break;
            }
        }
        return count;
    }

    /**
     * Returns the appropriate return opcode for the given method descriptor's
     * return type.
     *
     * @param desc the method descriptor
     * @return the opcode: {@code RETURN} for void, {@code IRETURN} for int/boolean/etc.,
     *         {@code LRETURN} for long, {@code FRETURN} for float, {@code DRETURN} for
     *         double, or {@code ARETURN} for object/array types
     */
    private static int returnOpcodeFor(String desc) {
        char ret = desc.charAt(desc.indexOf(')') + 1);
        switch (ret) {
            case 'V': return Opcodes.RETURN;
            case 'I':
            case 'Z':
            case 'B':
            case 'C':
            case 'S': return Opcodes.IRETURN;
            case 'J': return Opcodes.LRETURN;
            case 'F': return Opcodes.FRETURN;
            case 'D': return Opcodes.DRETURN;
            default:  return Opcodes.ARETURN;
        }
    }

    /**
     * Finds the first {@code INVOKESTATIC} instruction in the given class bytes
     * and returns it as a {@link MethodInsnNode}.
     *
     * <p>This is used by test assertions to verify that the transformer has
     * correctly rewritten (or preserved) the target call site.</p>
     *
     * @param classBytes the raw class bytes to scan
     * @return the first {@link MethodInsnNode} with opcode {@code INVOKESTATIC},
     *         or {@code null} if none is found
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
     * Sets the {@code gapOnlyMode} field on {@link CrystalGraphicsCoremod} via
     * reflection.
     *
     * <p>This allows tests to control the operating mode without calling
     * {@link CrystalGraphicsCoremod#injectData}, which requires FML
     * infrastructure not available in unit tests.</p>
     *
     * @param value the desired gap-only mode state
     * @throws Exception if reflective field access fails
     */
    private static void setGapOnlyMode(boolean value) throws Exception {
        Field field = CrystalGraphicsCoremod.class.getDeclaredField("gapOnlyMode");
        field.setAccessible(true);
        field.set(null, value);
    }
}
