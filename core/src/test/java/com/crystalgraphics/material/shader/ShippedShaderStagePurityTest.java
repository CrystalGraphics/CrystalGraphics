package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.api.shader.CgShaderStages;
import com.crystalgraphics.util.io.CgIO;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * Every shipped {@code .shader} writes a vertex stage free of fragment-only GLSL builtins.
 *
 * <p>A library included at material scope reaches both stages; NVIDIA accepts {@code fwidth} in a vertex shader and
 * AMD refuses it, so no driver can be the judge. The scan runs on the preprocessed source (the builtin arrives through
 * an {@code #include}) with the stage conditionals evaluated, keeping both branches of every keyword conditional.</p>
 *
 * <p>CrystalGUI carries a copy for its own shaders: the two are separate Gradle builds.</p>
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

    private static final Pattern BANNED = Pattern.compile("\\b(" + String.join("|", FRAGMENT_ONLY) + ")\\b");

    private static final String NAMESPACE = "crystalgraphics";

    @Test
    public void shippedShaders_vertexStage_hasNoFragmentOnlyBuiltins() throws Exception {
        List<String> shaders = shippedShaderPaths(NAMESPACE);
        assertFalse("no shipped .shader under assets/" + NAMESPACE + "/shaders/: this test would pass vacuously",
                shaders.isEmpty());

        for (String path : shaders) {
            String source = CgIO.loadSource(path);
            assertNotNull("could not load " + path, source);
            CgShaderSpec spec = CgShaderSpec.read(source, path);
            for (CgShaderPass pass : spec.passes()) {
                String vertex = CgGlslWriter.write(spec, pass, Collections.emptyList(), null,
                        Collections.emptySet()).vertex();
                String offender = firstFragmentOnlyBuiltinInVertexStage(vertex, path);
                assertNull(path + " pass '" + pass.name() + "': the vertex stage uses the fragment-only builtin '"
                        + offender + "'. Guard it with #if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)"
                        + " in the library that defines it.", offender);
            }
        }
    }

    // ── the detector detects ─────────────────────────────────────────────────

    @Test
    public void detector_flags_fwidthInVertexSource() {
        assertEquals("fwidth", firstFragmentOnlyBuiltinInVertexStage(
                "#version 330 core\nfloat bad(float d) { return fwidth(d); }\n", "test"));
    }

    @Test
    public void detector_ignores_builtinGuardedOutOfVertexStage() {
        assertNull(firstFragmentOnlyBuiltinInVertexStage(
                "#version 330 core\n#define CG_VERTEX_STAGE 1\n"
                        + "#ifndef CG_VERTEX_STAGE\nfloat ok(float d) { return fwidth(d); }\n#endif\n", "test"));
    }

    @Test
    public void detector_ignores_builtinGuardedOutOfVertexAndComputeStages() {
        assertNull(firstFragmentOnlyBuiltinInVertexStage(
                String.join("\n", "#version 330 core", "#define CG_VERTEX_STAGE 1",
                        "#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)",
                        "float ok(float d) { return fwidth(d); }", "#endif", ""), "test"));
    }

    @Test
    public void detector_flags_builtinGuardedIntoVertexStage() {
        assertEquals("dFdx", firstFragmentOnlyBuiltinInVertexStage(
                "#version 330 core\n#define CG_VERTEX_STAGE 1\n"
                        + "#ifdef CG_VERTEX_STAGE\nfloat bad(float d) { return dFdx(d); }\n#endif\n", "test"));
    }

    /** Keyword variants are compiled too, so a builtin behind an unknown macro still counts. */
    @Test
    public void detector_flags_builtinBehindAnUnknownMacro() {
        assertEquals("gl_FragCoord", firstFragmentOnlyBuiltinInVertexStage(
                "#version 330 core\n#ifdef SOME_FEATURE\nvec4 bad() { return gl_FragCoord; }\n#endif\n", "test"));
    }

    @Test
    public void detector_ignores_builtinNamesInComments() {
        assertNull(firstFragmentOnlyBuiltinInVertexStage(
                "#version 330 core\n// use discard here\n/* or fwidth, or gl_FragCoord */\n", "test"));
    }

    // ── machinery ────────────────────────────────────────────────────────────

    /** The first fragment-only builtin the vertex stage of {@code vertexSource} reaches, or {@code null}. */
    static String firstFragmentOnlyBuiltinInVertexStage(String vertexSource, String path) {
        String expanded = new CgShaderPreprocessor().process(vertexSource, path);
        Matcher m = BANNED.matcher(CgShaderStages.reachable(stripComments(expanded), CgShaderStages.Stage.VERTEX));
        return m.find() ? m.group(1) : null;
    }

    /** {@code src} without its comments, line breaks kept. */
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
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Every {@code .shader} under {@code assets/<namespace>/shaders/}, as CgIO resource paths. */
    static List<String> shippedShaderPaths(String namespace) throws Exception {
        List<String> out = new ArrayList<>();
        URL dir = ShippedShaderStagePurityTest.class.getResource("/assets/" + namespace + "/shaders/");
        if (dir == null) return out;
        if ("file".equals(dir.getProtocol())) {
            Path root = Paths.get(dir.toURI());
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(f -> f.toString().endsWith(".shader")).forEach(f -> out.add(
                        namespace + ":shaders/" + root.relativize(f).toString().replace(File.separatorChar, '/')));
            }
        } else if ("jar".equals(dir.getProtocol())) {
            String spec = dir.getPath();
            String prefix = "assets/" + namespace + "/shaders/";
            try (JarFile jar = new JarFile(new File(new URL(spec.substring(0, spec.indexOf('!'))).toURI()))) {
                for (JarEntry e : Collections.list(jar.entries())) {
                    String n = e.getName();
                    if (n.startsWith(prefix) && n.endsWith(".shader")) {
                        out.add(namespace + ":shaders/" + n.substring(prefix.length()));
                    }
                }
            }
        }
        Collections.sort(out);
        return out;
    }
}
