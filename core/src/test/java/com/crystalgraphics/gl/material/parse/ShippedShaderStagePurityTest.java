package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.api.material.CgAttachedBuffer;
import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.api.shader.CgShaderStages;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.util.io.CgIO;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * Every shipped {@code .shader} must generate a vertex stage free of fragment-only GLSL builtins.
 *
 * <p><b>Why this exists.</b> {@code sdf.glsl}'s {@code sdf_coverage} calls {@code fwidth}, and
 * {@code gui_rounded_rect.shader} includes that lib at <i>material</i> scope. The compiler hoists
 * every preamble {@code #}-line into <i>both</i> stages, so {@code fwidth} landed in the vertex
 * shader. NVIDIA accepted it silently; AMD rejected it and the gallery could not start at all. A GL
 * test on an NVIDIA machine structurally cannot catch that — this one can, because it never asks a
 * driver for an opinion.</p>
 *
 * <p><b>Two things make this non-vacuous, and both are load-bearing:</b></p>
 * <ul>
 *   <li>It scans the <i>preprocessed</i> source. The offending code arrives through an
 *       {@code #include}; scanning the generated string before expansion would see nothing.</li>
 *   <li>It evaluates the stage conditionals itself. {@link CgShaderPreprocessor} resolves includes
 *       but leaves {@code #ifdef} to the driver, so after the fix the raw preprocessed vertex text
 *       <i>still contains</i> {@code fwidth} — inside a block the driver will discard. Without
 *       {@link #stripInactiveStageBlocks} this test could never go green.</li>
 * </ul>
 *
 * <p>Non-stage conditionals ({@code CG_USE_SSBO}, keyword variants) keep <b>both</b> branches, so a
 * banned builtin cannot hide behind a {@code #pragma cg_feature} that happens to be off.</p>
 *
 * <p>CrystalGUI carries a near-identical test for {@code crystalgui:shaders/*}. The duplication is
 * deliberate: the two live in separate Gradle builds, and each must gate its own regressions.</p>
 */
public class ShippedShaderStagePurityTest {

    /** Builtins that exist only in the fragment stage. Keep in sync with CrystalGUI's copy. */
    private static final String[] FRAGMENT_ONLY = {
            "fwidth", "fwidthFine", "fwidthCoarse",
            "dFdx", "dFdy", "dFdxFine", "dFdyFine", "dFdxCoarse", "dFdyCoarse",
            "discard",
            "gl_FragCoord", "gl_FrontFacing", "gl_PointCoord", "gl_FragDepth",
            "interpolateAtCentroid", "interpolateAtSample", "interpolateAtOffset",
            "gl_SampleID", "gl_SamplePosition", "gl_SampleMask", "gl_SampleMaskIn",
    };

    private static final Pattern BANNED =
            Pattern.compile("\\b(" + String.join("|", FRAGMENT_ONLY) + ")\\b");

    private static final String NAMESPACE = "crystalgraphics";

    /**
     * Documentation, not shaders. {@code example.shader} is the annotated reference file AGENTS.md
     * points authors at; it carries commented-out {@code Pass} blocks and illustrative fragments, no
     * code loads it, and the parser is not comment-aware, so it does not parse. Excluded by name
     * rather than by swallowing parse errors — a real shader that stops parsing must still fail.
     */
    private static final List<String> DOCUMENTATION_ONLY =
            Collections.singletonList("crystalgraphics:shaders/example.shader");

    private static final List<CgAttachedBuffer> NO_BUFFERS = Collections.emptyList();

    @After
    public void clearCapabilitiesCache() throws Exception {
        Field cacheField = CgCapabilities.class.getDeclaredField("cachedCaps");
        cacheField.setAccessible(true);
        cacheField.set(null, null);
    }

    // ── The gate ──────────────────────────────────────────────────────────────

    /** TBO path — {@code #version 330 core}. */
    @Test
    public void shippedShaders_vertexStage_hasNoFragmentOnlyBuiltins_tboPath() throws Exception {
        assertAllShippedShadersPure(CgCapabilities.ShaderBufferPath.TBO);
    }

    /**
     * SSBO path — {@code #version 430 core}. This is what the AMD box runs, and the sources differ
     * between the two paths, so covering only one leaves half the generated GLSL unexamined.
     */
    @Test
    public void shippedShaders_vertexStage_hasNoFragmentOnlyBuiltins_ssboPath() throws Exception {
        assertAllShippedShadersPure(CgCapabilities.ShaderBufferPath.SSBO_GL43);
    }

    private void assertAllShippedShadersPure(CgCapabilities.ShaderBufferPath path) throws Exception {
        installCapabilities(path);

        List<String> shaders = shippedShaderPaths(NAMESPACE);
        shaders.removeAll(DOCUMENTATION_ONLY);
        assertFalse("Found no shipped .shader files under assets/" + NAMESPACE + "/shaders/ — "
                + "this test would pass vacuously", shaders.isEmpty());

        for (String resourcePath : shaders) {
            String source = CgIO.loadSource(resourcePath);
            assertNotNull("Could not load " + resourcePath, source);

            CgParsedShader parsed = CgShaderParser.parse(source, resourcePath);
            for (CgParsedPass pass : parsed.passes()) {
                CgMaterialShaderCompiler.CompiledSource cs = CgMaterialShaderCompiler.compile(
                        parsed, pass, NO_BUFFERS, null,
                        CgMaterialShaderCompiler.CompileConfig.DEFAULT);

                String offender = firstFragmentOnlyBuiltinInVertexStage(cs.vertexSource(), resourcePath);
                assertNull(resourcePath + " pass '" + pass.name() + "' (" + path + "): the generated"
                        + " VERTEX stage uses the fragment-only builtin '" + offender + "'."
                        + " Guard it with #if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE) in the lib"
                        + " that defines it.",
                        offender);
            }
        }
    }

    // ── Meta-tests: prove the detector actually detects ────────────────────────
    // These drive firstFragmentOnlyBuiltinInVertexStage directly rather than through the compiler.
    // Going through the compiler would test the wrong thing: partitionGlobalDecls splits a pass's
    // '#' lines away from its code lines, so a hand-written "#ifndef ... #endif" around a function
    // in a .shader is torn apart before it ever reaches this scan. Real guards live inside included
    // .glsl files, which the preprocessor expands intact — the gate above covers that end to end.

    /** A bare builtin in the vertex source must be reported. */
    @Test
    public void detector_flags_fwidthInVertexSource() {
        assertEquals("fwidth", firstFragmentOnlyBuiltinInVertexStage(
                "#version 330 core\nfloat bad(float d) { return fwidth(d); }\n", "test"));
    }

    /** One guarded out of the vertex stage must not be — this is what the fix relies on. */
    @Test
    public void detector_ignores_builtinGuardedOutOfVertexStage() {
        assertNull(firstFragmentOnlyBuiltinInVertexStage(
                "#version 330 core\n#define CG_VERTEX_STAGE 1\n"
                        + "#ifndef CG_VERTEX_STAGE\nfloat ok(float d) { return fwidth(d); }\n#endif\n",
                "test"));
    }

    /** The three-stage guard, as the shipped libs write it. */
    @Test
    public void detector_ignores_builtinGuardedOutOfVertexAndComputeStages() {
        assertNull(firstFragmentOnlyBuiltinInVertexStage(
                String.join("\n", "#version 330 core", "#define CG_VERTEX_STAGE 1",
                        "#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)",
                        "float ok(float d) { return fwidth(d); }", "#endif", ""),
                "test"));
    }

    /** ...but one guarded <i>into</i> it still must be. */
    @Test
    public void detector_flags_builtinGuardedIntoVertexStage() {
        assertEquals("dFdx", firstFragmentOnlyBuiltinInVertexStage(
                "#version 330 core\n#define CG_VERTEX_STAGE 1\n"
                        + "#ifdef CG_VERTEX_STAGE\nfloat bad(float d) { return dFdx(d); }\n#endif\n",
                "test"));
    }

    /** A builtin behind an inactive keyword must still be reported — variants get compiled too. */
    @Test
    public void detector_flags_builtinBehindAnUnknownMacro() {
        assertEquals("gl_FragCoord", firstFragmentOnlyBuiltinInVertexStage(
                "#version 330 core\n#ifdef SOME_FEATURE\nvec4 bad() { return gl_FragCoord; }\n#endif\n",
                "test"));
    }

    /** Prose must not false-positive — {@code example.shader} discusses {@code discard} in comments. */
    @Test
    public void detector_ignores_builtinNamesInComments() {
        assertNull(firstFragmentOnlyBuiltinInVertexStage(
                "#version 330 core\n// use discard here\n/* or fwidth, or gl_FragCoord */\n", "test"));
    }

    // ── Machinery ─────────────────────────────────────────────────────────────

    /**
     * Returns the first fragment-only builtin reachable in the given generated vertex source, or
     * {@code null} if there is none.
     */
    static String firstFragmentOnlyBuiltinInVertexStage(String generatedVertexSource, String path) {
        String expanded = new CgShaderPreprocessor().process(generatedVertexSource, path);
        String scannable = stripInactiveStageBlocks(stripComments(expanded), true);
        java.util.regex.Matcher m = BANNED.matcher(scannable);
        return m.find() ? m.group(1) : null;
    }

    /** Removes {@code //} and block comments so prose ("...or discard...") cannot false-positive. */
    public static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < src.length() && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i++; // land on '/', loop's i++ steps past it
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Drops what the driver discards for the vertex stage, or the fragment stage. */
    static String stripInactiveStageBlocks(String src, boolean vertexStage) {
        return CgShaderStages.reachable(src, vertexStage ? CgShaderStages.Stage.VERTEX : CgShaderStages.Stage.FRAGMENT);
    }

    /** Every {@code .shader} under {@code assets/<namespace>/shaders/} and its subdirectories, as CgIO resource paths. */
    static List<String> shippedShaderPaths(String namespace) throws Exception {
        List<String> out = new ArrayList<>();
        URL dir = ShippedShaderStagePurityTest.class.getResource("/assets/" + namespace + "/shaders/");
        if (dir == null) return out;

        if ("file".equals(dir.getProtocol())) {
            Path root = Paths.get(dir.toURI());
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(f -> f.toString().endsWith(".shader"))
                    .forEach(f -> out.add(namespace + ":shaders/" + root.relativize(f).toString().replace(File.separatorChar, '/')));
            }
        } else if ("jar".equals(dir.getProtocol())) {
            String spec = dir.getPath();
            String jarPath = spec.substring(5, spec.indexOf("!"));
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(new File(new URL("file:" + jarPath).toURI()))) {
                String prefix = "assets/" + namespace + "/shaders/";
                for (java.util.Enumeration<java.util.jar.JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    String n = e.nextElement().getName();
                    if (n.startsWith(prefix) && n.endsWith(".shader")) {
                        out.add(namespace + ":shaders/" + n.substring(prefix.length()));
                    }
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Same reflection stub {@link CgMaterialShaderCompilerTest} uses — no GL context involved. */
    static void installCapabilities(CgCapabilities.ShaderBufferPath path) throws Exception {
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
