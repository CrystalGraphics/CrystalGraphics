package io.github.somehussar.crystalgraphics.mc.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/**
 * ASM class transformer that rewrites INVOKESTATIC call sites for OpenGL
 * state-mutating methods, redirecting them through CrystalGraphics' state-tracking
 * layer ({@code CrystalGLRedirects}).
 *
 * <h3>How It Works</h3>
 * <p>For every class loaded by LaunchWrapper, this transformer:</p>
 * <ol>
 *   <li><b>Excludes</b> classes that must never be transformed (LWJGL internals,
 *       our own redirect classes, Angelica, ASM internals, and LaunchWrapper
 *       itself).</li>
 *   <li>Performs a <b>fast pre-filter</b> by scanning the raw class bytes for
 *       UTF-8 constant-pool strings matching any redirect owner class name.
 *       This avoids the cost of parsing the full class AST for the vast majority
 *       of classes that contain no GL calls.</li>
 *   <li>Parses the class into a {@link ClassNode} and iterates every
 *       {@link MethodInsnNode} with opcode {@code INVOKESTATIC}.</li>
 *   <li>Looks up each call site in a pre-built {@link HashMap} of active
 *       redirects (O(1) per instruction).</li>
 *   <li>Rewrites matching call sites by replacing the owner and method name
 *       with the redirect target, preserving the original descriptor.</li>
 * </ol>
 *
 * <h3>Pre-filter</h3>
 * <p>The pre-filter is critical for performance. In a typical Minecraft instance,
 * thousands of classes are loaded, but only a small fraction contain calls to
 * the specific GL classes we redirect. Scanning the raw byte array for owner
 * strings is orders of magnitude faster than parsing the full class structure.</p>
 *
 * <h3>Exclusions</h3>
 * <p>The following class prefixes are never transformed:</p>
 * <ul>
 *   <li>{@code org.lwjgl.} &mdash; loaded by the system classloader; transforming
 *       them would either fail or cause undefined behavior.</li>
 *   <li>{@code io.github.somehussar.crystalgraphics.mc.coremod.} &mdash; our own
 *       redirect target classes; transforming them would cause infinite
 *       recursion.</li>
 *   <li>{@code com.gtnewhorizons.angelica.} &mdash; Angelica has its own
 *       redirection layer; we must never interfere with it.</li>
 *   <li>{@code org.objectweb.asm.} &mdash; ASM internals used during
 *       transformation.</li>
 *   <li>{@code net.minecraft.launchwrapper.} &mdash; LaunchWrapper infrastructure
 *       that loads this transformer.</li>
 * </ul>
 *
 * <h3>Idempotency</h3>
 * <p>If a call site already targets {@code CrystalGLRedirects}, it is skipped.
 * This guarantees that applying the transformer multiple times to the same
 * class bytes produces the same result.</p>
 *
 * @see CoverageMatrix
 * @see CrystalGraphicsCoremod
 */
public final class CrystalGraphicsTransformer implements IClassTransformer {

    /** Logger for transformation diagnostics. */
    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphicsTransformer");

    /**
     * Internal name of the redirect target class, used for idempotency checks.
     * If a {@link MethodInsnNode} already has this owner, it has already been
     * rewritten and is skipped.
     */
    private static final String REDIRECT_OWNER =
        "io/github/somehussar/crystalgraphics/mc/coremod/CrystalGLRedirects";

    /**
     * Class-name prefixes that must never be transformed.
     *
     * <p>These are checked using dot-separated names (as provided by
     * LaunchWrapper's {@code transformedName} parameter).</p>
     */
    private static final String[] EXCLUSION_PREFIXES = {
        "org.lwjgl.",
        "io.github.somehussar.crystalgraphics.mc.coremod.",
        "com.gtnewhorizons.angelica.",
        "org.objectweb.asm.",
        "net.minecraft.launchwrapper."
    };

    /**
     * The active list of redirects, determined at construction time by querying
     * {@link CrystalGraphicsCoremod#isGapOnlyMode()}.
     */
    private final List<CoverageMatrix.Redirect> activeRedirects;

    /**
     * O(1) lookup map from composite key
     * {@code "ownerInternalName.methodName.descriptor"} to the corresponding
     * {@link CoverageMatrix.Redirect} entry.
     */
    private final Map<String, CoverageMatrix.Redirect> redirectLookup;

    /**
     * Set of unique owner internal names from {@link #activeRedirects}, used
     * for the fast pre-filter scan against raw class bytes.
     */
    private final String[] ownerStrings;

    /**
     * Creates the transformer and initializes the redirect lookup structures.
     *
     * <p>The operating mode (full or gap-only) is determined by calling
     * {@link CrystalGraphicsCoremod#isGapOnlyMode()} at construction time.
     * The active redirect list from {@link CoverageMatrix#forMode(boolean)}
     * is then indexed into a {@link HashMap} for O(1) lookup during
     * transformation.</p>
     */
    public CrystalGraphicsTransformer() {
        boolean gapOnly = CrystalGraphicsCoremod.isGapOnlyMode();
        this.activeRedirects = CoverageMatrix.forMode(gapOnly);

        this.redirectLookup = new HashMap<String, CoverageMatrix.Redirect>();
        java.util.Set<String> owners = new java.util.HashSet<String>();

        for (CoverageMatrix.Redirect r : activeRedirects) {
            String key = r.ownerInternalName + "." + r.methodName + "." + r.descriptor;
            redirectLookup.put(key, r);
            owners.add(r.ownerInternalName);
        }

        this.ownerStrings = owners.toArray(new String[0]);

        LOGGER.info(
            "CrystalGraphicsTransformer: Initialized with {} redirects (mode={})",
            Integer.valueOf(activeRedirects.size()),
            gapOnly ? "gap-only" : "full"
        );
    }

    /**
     * Transforms a class by rewriting matching INVOKESTATIC call sites.
     *
     * <p>This method is called by LaunchWrapper for every class being loaded.
     * It applies exclusion checks, a fast pre-filter, and then performs the
     * actual ASM rewriting if any matching call sites are found.</p>
     *
     * @param name            the obfuscated class name (dot-separated)
     * @param transformedName the deobfuscated class name (dot-separated)
     * @param basicClass      the raw class bytes, or {@code null} if the class
     *                        does not exist
     * @return the (possibly modified) class bytes, or the original bytes if no
     *         changes were made
     */
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || basicClass.length == 0) {
            return basicClass;
        }

        // --- Exclusions ---
        if (isExcluded(transformedName)) {
            return basicClass;
        }

        // --- Fast pre-filter: scan raw bytes for owner strings ---
        if (!containsAnyOwner(basicClass)) {
            return basicClass;
        }

        // --- Full ASM parse and rewrite ---
        return rewriteClass(transformedName, basicClass);
    }

    /**
     * Checks whether the given class name matches any exclusion prefix.
     *
     * @param transformedName the deobfuscated class name (dot-separated)
     * @return {@code true} if the class must not be transformed
     */
    private static boolean isExcluded(String transformedName) {
        for (String prefix : EXCLUSION_PREFIXES) {
            if (transformedName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scans the raw class bytes for any of the redirect owner internal names
     * as UTF-8 substrings.
     *
     * <p>This is a fast heuristic: it checks whether any owner class name
     * (e.g. {@code "org/lwjgl/opengl/GL30"}) appears anywhere in the byte
     * array. This can produce false positives (e.g. the string appears in a
     * string constant or annotation) but never false negatives. False positives
     * are harmless &mdash; the ASM rewrite pass will simply find no matching
     * instructions and return the original bytes.</p>
     *
     * @param classBytes the raw class file bytes
     * @return {@code true} if at least one owner string is found in the bytes
     */
    private boolean containsAnyOwner(byte[] classBytes) {
        for (String owner : ownerStrings) {
            if (containsUtf8(classBytes, owner)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the given byte array contains the specified ASCII/UTF-8
     * string as a contiguous subsequence.
     *
     * <p>This performs a simple byte-by-byte scan. Since all owner class names
     * are pure ASCII, each character maps to exactly one byte in UTF-8.</p>
     *
     * @param data the byte array to search
     * @param s    the ASCII string to search for
     * @return {@code true} if {@code s} is found as a substring of {@code data}
     */
    private static boolean containsUtf8(byte[] data, String s) {
        int sLen = s.length();
        if (sLen == 0) {
            return true;
        }
        int limit = data.length - sLen;
        byte first = (byte) s.charAt(0);

        outer:
        for (int i = 0; i <= limit; i++) {
            if (data[i] != first) {
                continue;
            }
            for (int j = 1; j < sLen; j++) {
                if (data[i + j] != (byte) s.charAt(j)) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Parses the class bytes into an ASM {@link ClassNode}, iterates all
     * methods, and rewrites matching INVOKESTATIC instructions.
     *
     * <p>If any instructions were rewritten, the modified class is serialized
     * back to bytes using {@link ClassWriter} with flags {@code 0} (no automatic
     * frame or max computation, which is appropriate for simple owner/name
     * replacements that do not alter the stack shape).</p>
     *
     * @param transformedName the deobfuscated class name (dot-separated),
     *                        used for logging
     * @param originalBytes   the original class bytes
     * @return the modified class bytes if any rewrites occurred, or
     *         {@code originalBytes} if no changes were made
     */
    private byte[] rewriteClass(String transformedName, byte[] originalBytes) {
        ClassReader reader = new ClassReader(originalBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        boolean changed = false;
        int rewriteCount = 0;

        for (MethodNode method : classNode.methods) {
            ListIterator<AbstractInsnNode> it = method.instructions.iterator();
            while (it.hasNext()) {
                AbstractInsnNode insn = it.next();
                if (insn.getOpcode() != Opcodes.INVOKESTATIC) {
                    continue;
                }
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode methodInsn = (MethodInsnNode) insn;

                // Idempotency: skip if already rewritten
                if (REDIRECT_OWNER.equals(methodInsn.owner)) {
                    continue;
                }

                String key = methodInsn.owner + "." + methodInsn.name + "." + methodInsn.desc;
                CoverageMatrix.Redirect redirect = redirectLookup.get(key);
                if (redirect != null) {
                    methodInsn.owner = redirect.redirectClass;
                    methodInsn.name = redirect.redirectMethod;
                    changed = true;
                    rewriteCount++;
                }
            }
        }

        if (changed) {
            if (CrystalGraphicsCoremod.VERBOSE) {
                String prefix = CrystalGraphicsCoremod.VERBOSE_PREFIX;
                if (prefix == null || prefix.length() == 0 || transformedName.startsWith(prefix)) {
                    LOGGER.info(
                        "CrystalGraphicsTransformer: Rewrote {} call site(s) in {}",
                        Integer.valueOf(rewriteCount),
                        transformedName
                    );
                }
            }
            ClassWriter writer = new ClassWriter(0);
            classNode.accept(writer);
            return writer.toByteArray();
        }

        return originalBytes;
    }
}
