package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.CgGLBackend;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logs the live GL state the host hands us at an entry point, once: what a new Minecraft version changed
 * about the frame we draw into, read rather than guessed.
 *
 * <pre>{@code
 * CgGlCensus.at("opaque");      // at the top of a world pass, before any scope opens
 * }</pre>
 *
 * <p>Off unless {@code -Dcrystalgraphics.host.census=true}; then each label logs on its 120th visit
 * ({@code -Dcrystalgraphics.host.census.at}), a steady frame rather than the first. One line per value,
 * {@code [crystalgraphics] census <label> <key> = <value>}, so two clients' logs diff line by line —
 * {@code singlejar-logic/census_diff.py} does it.</p>
 *
 * <ul>
 *   <li>Reads go to the backend, past our shadow: this is what GL holds, not what we believe.</li>
 *   <li>A query the context does not know reads {@code n/a}, and its error is drained so nothing after
 *       the census sees it.</li>
 *   <li>Per-unit bindings select each unit and put the active unit back; nothing else is written.</li>
 * </ul>
 */
public final class CgGlCensus {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");
    private static final boolean ENABLED = Boolean.getBoolean("crystalgraphics.host.census");
    private static final int AT = Integer.getInteger("crystalgraphics.host.census.at", 120);
    private static final int UNITS = 8;

    private static final Map<String, Integer> VISITS = new HashMap<>();

    private CgGlCensus() {}

    /** Counts a visit to {@code label}, and logs the state on the one the flag names. Free when off. */
    public static void at(String label) {
        if (!ENABLED) return;
        int visit = VISITS.merge(label, 1, Integer::sum);
        if (visit != AT) return;
        Map<String, String> values = new LinkedHashMap<>();
        try {
            read(CgPlatform.gl(), values);
        } catch (RuntimeException | LinkageError failed) {
            LOG.warn("[crystalgraphics] census {} stopped: {}", label, failed.toString());
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            LOG.info("[crystalgraphics] census {} {} = {}", label, e.getKey(), e.getValue());
        }
    }

    // Enable caps, read with glGetBoolean, which every one of them answers.
    private static final Object[] CAPS = {
            "BLEND", 0x0BE2, "DEPTH_TEST", 0x0B71, "CULL_FACE", 0x0B44, "SCISSOR_TEST", 0x0C11,
            "STENCIL_TEST", 0x0B90, "POLYGON_OFFSET_FILL", 0x8037, "POLYGON_OFFSET_LINE", 0x2A02,
            "FRAMEBUFFER_SRGB", 0x8DB9, "MULTISAMPLE", 0x809D, "SAMPLE_ALPHA_TO_COVERAGE", 0x809E,
            "DEPTH_CLAMP", 0x864F, "PRIMITIVE_RESTART", 0x8F9D, "PRIMITIVE_RESTART_FIXED_INDEX", 0x8D69,
            "PROGRAM_POINT_SIZE", 0x8642, "TEXTURE_CUBE_MAP_SEAMLESS", 0x884F, "COLOR_LOGIC_OP", 0x0BF2,
            "LINE_SMOOTH", 0x0B20, "DITHER", 0x0BD0, "RASTERIZER_DISCARD", 0x8C89, "ALPHA_TEST", 0x0BC0,
            "DEPTH_WRITEMASK", 0x0B72,
    };

    private static final Object[] INTS = {
            "DEPTH_FUNC", 0x0B74, "CULL_FACE_MODE", 0x0B45, "FRONT_FACE", 0x0B46,
            "BLEND_SRC_RGB", 0x80C9, "BLEND_DST_RGB", 0x80C8, "BLEND_SRC_ALPHA", 0x80CB, "BLEND_DST_ALPHA", 0x80CA,
            "BLEND_EQUATION_RGB", 0x8009, "BLEND_EQUATION_ALPHA", 0x883D, "LOGIC_OP_MODE", 0x0BF0,
            "PROVOKING_VERTEX", 0x8E4F, "CLIP_ORIGIN", 0x935C, "CLIP_DEPTH_MODE", 0x935D,
            "STENCIL_FUNC", 0x0B92, "STENCIL_WRITEMASK", 0x0B98,
            "UNPACK_ALIGNMENT", 0x0CF5, "UNPACK_ROW_LENGTH", 0x0CF2, "UNPACK_SKIP_ROWS", 0x0CF3,
            "UNPACK_SKIP_PIXELS", 0x0CF4, "PACK_ALIGNMENT", 0x0D05,
            "DRAW_FRAMEBUFFER_BINDING", 0x8CA6, "READ_FRAMEBUFFER_BINDING", 0x8CAA, "DRAW_BUFFER0", 0x8825,
            "CURRENT_PROGRAM", 0x8B8D, "VERTEX_ARRAY_BINDING", 0x85B5, "ARRAY_BUFFER_BINDING", 0x8894,
            "ELEMENT_ARRAY_BUFFER_BINDING", 0x8895, "UNIFORM_BUFFER_BINDING", 0x8A28,
            "PIXEL_UNPACK_BUFFER_BINDING", 0x88EF, "PIXEL_PACK_BUFFER_BINDING", 0x88ED,
            "DRAW_INDIRECT_BUFFER_BINDING", 0x8F43, "ACTIVE_TEXTURE", 0x84E0,
            "MAJOR_VERSION", 0x821B, "MINOR_VERSION", 0x821C, "CONTEXT_PROFILE_MASK", 0x9126,
            "CONTEXT_FLAGS", 0x821E, "SAMPLES", 0x80A9,
    };

    private static final Object[] FLOATS = {
            "DEPTH_CLEAR_VALUE", 0x0B73, 1, "DEPTH_RANGE", 0x0B70, 2, "POLYGON_OFFSET_FACTOR", 0x8038, 1,
            "POLYGON_OFFSET_UNITS", 0x2A00, 1, "LINE_WIDTH", 0x0B21, 1, "POINT_SIZE", 0x0B11, 1,
            "COLOR_CLEAR_VALUE", 0x0C22, 4, "BLEND_COLOR", 0x8005, 4,
    };

    private static final Object[] INT_VECTORS = {
            "VIEWPORT", 0x0BA2, 4, "SCISSOR_BOX", 0x0C10, 4, "POLYGON_MODE", 0x0B40, 2,
    };

    private static void read(CgGLBackend gl, Map<String, String> out) {
        drainErrors(gl);
        for (int i = 0; i < CAPS.length; i += 2) {
            final int pname = (Integer) CAPS[i + 1];
            out.put((String) CAPS[i], query(gl, () -> String.valueOf(gl.glGetBoolean(pname))));
        }
        for (int i = 0; i < INTS.length; i += 2) {
            final int pname = (Integer) INTS[i + 1];
            out.put((String) INTS[i], query(gl, () -> String.valueOf(gl.glGetInteger(pname))));
        }
        for (int i = 0; i < FLOATS.length; i += 3) {
            final int pname = (Integer) FLOATS[i + 1], n = (Integer) FLOATS[i + 2];
            out.put((String) FLOATS[i], query(gl, () -> {
                FloatBuffer b = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                gl.glGetFloat(pname, b);
                StringBuilder s = new StringBuilder();
                for (int k = 0; k < n; k++) s.append(k == 0 ? "" : " ").append(b.get(k));
                return s.toString();
            }));
        }
        for (int i = 0; i < INT_VECTORS.length; i += 3) {
            final int pname = (Integer) INT_VECTORS[i + 1], n = (Integer) INT_VECTORS[i + 2];
            out.put((String) INT_VECTORS[i], query(gl, () -> {
                IntBuffer b = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
                gl.glGetInteger(pname, b);
                StringBuilder s = new StringBuilder();
                for (int k = 0; k < n; k++) s.append(k == 0 ? "" : " ").append(b.get(k));
                return s.toString();
            }));
        }
        out.put("COLOR_WRITEMASK", query(gl, () -> {
            ByteBuffer b = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
            gl.glGetBoolean(0x0C23, b);
            return b.get(0) + " " + b.get(1) + " " + b.get(2) + " " + b.get(3);
        }));
        units(gl, out);
        attachments(gl, out);
    }

    /** Texture and sampler bound on each unit, the active unit put back afterwards. */
    private static void units(CgGLBackend gl, Map<String, String> out) {
        int active;
        try {
            active = gl.glGetInteger(0x84E0);
        } catch (RuntimeException e) {
            return;
        }
        try {
            for (int u = 0; u < UNITS; u++) {
                gl.glActiveTexture(CgGL.GL_TEXTURE0 + u);
                out.put("UNIT" + u + "_TEXTURE_2D", query(gl, () -> String.valueOf(gl.glGetInteger(0x8069))));
                out.put("UNIT" + u + "_SAMPLER", query(gl, () -> String.valueOf(gl.glGetInteger(0x8919))));
            }
        } finally {
            gl.glActiveTexture(active);
        }
    }

    /** What the bound draw framebuffer's colour and depth are made of. */
    private static void attachments(CgGLBackend gl, Map<String, String> out) {
        boolean window = query(gl, () -> String.valueOf(gl.glGetInteger(0x8CA6))).equals("0");
        int color = window ? 0x0401 /* BACK_LEFT */ : 0x8CE0 /* COLOR_ATTACHMENT0 */;
        int depth = window ? 0x1801 /* DEPTH */ : 0x8D00 /* DEPTH_ATTACHMENT */;
        int stencil = window ? 0x1802 /* STENCIL */ : 0x8D20 /* STENCIL_ATTACHMENT */;
        attachment(gl, out, "COLOR0", color, new int[] { 0x8211, 0x8212, 0x8210 },
                new String[] { "COMPONENT_TYPE", "RED_SIZE", "ENCODING" });
        attachment(gl, out, "DEPTH", depth, new int[] { 0x8211, 0x8216 }, new String[] { "COMPONENT_TYPE", "SIZE" });
        attachment(gl, out, "STENCIL", stencil, new int[] { 0x8217 }, new String[] { "SIZE" });
    }

    private static void attachment(CgGLBackend gl, Map<String, String> out, String name, int attachment,
                                   int[] pnames, String[] keys) {
        String type = query(gl, () -> String.valueOf(
                gl.getFramebufferAttachmentParameteriv(0x8CA9 /* DRAW_FRAMEBUFFER */, attachment, 0x8CD0)));
        out.put(name + "_OBJECT_TYPE", type);
        if ("0".equals(type) || "n/a".equals(type)) return;   // an empty attachment answers nothing else
        for (int i = 0; i < pnames.length; i++) {
            final int pname = pnames[i];
            out.put(name + "_" + keys[i], query(gl, () -> String.valueOf(
                    gl.getFramebufferAttachmentParameteriv(0x8CA9, attachment, pname))));
        }
    }

    private interface Query { String run(); }

    private static String query(CgGLBackend gl, Query q) {
        try {
            String value = q.run();
            return drainErrors(gl) ? "n/a" : value;
        } catch (RuntimeException e) {
            drainErrors(gl);
            return "n/a";
        }
    }

    /** Clears the error queue; true if anything was in it. */
    private static boolean drainErrors(CgGLBackend gl) {
        boolean any = false;
        for (int i = 0; i < 16 && gl.glGetError() != 0; i++) any = true;
        return any;
    }
}
