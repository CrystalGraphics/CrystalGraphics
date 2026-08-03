package com.crystalgraphics.shadergraph;

import com.crystalgraphics.api.mesh.CgMeshData;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.*;

/**
 * P6.3.12 — the main preview's shape set.
 *
 * <h3>The assertion worth having</h3>
 * <p>{@link #everySolidFitsInsideItsOwnFramingAtEveryAngle()} is the reason this file exists. Geometry
 * and camera framing are two numbers that must agree, declared a few lines apart, and nothing but a test
 * connects them — lengthen a capsule and it silently starts poking out of its own preview, which looks
 * like a clipping bug in the renderer rather than a stale constant in an enum.</p>
 *
 * <p>The bound is the worst <b>projected</b> extent over every orbit angle, not a bounding sphere. The
 * camera is orthographic with an axis-aligned box, so what has to fit is what reaches the screen; the
 * sphere is a looser bound and demanding it cost the quad a permanent margin it does not need. Working
 * that out is what turned up the one shape that genuinely cannot satisfy it — see that test.</p>
 */
public class CgPreviewMeshTest {

    /**
     * <b>Every solid stays inside its framing at every orbit angle.</b>
     *
     * <p>{@link CgPreviewMesh#QUAD} is exempt, and the exemption is a decision rather than a fudged
     * constant. A flat quad tilted 45° after a 90° yaw presents its <em>diagonal</em> to the screen —
     * {@code √2}, not 1 — so framing it to survive that would leave a permanent 40% margin around the
     * common 2D case: colours, masks, noise, UV distortions, everything that has no third dimension to
     * inspect. That is exactly the letterboxing {@code ShaderNodePreview} deliberately refuses for quads.
     * Filling the panel at rest and clipping the corners at an angle nobody inspects a flat shape from is
     * the better trade, so it is taken explicitly here.</p>
     */
    @Test
    public void everySolidFitsInsideItsOwnFramingAtEveryAngle() {
        for (CgPreviewMesh mesh : CgPreviewMesh.values()) {
            if (mesh == CgPreviewMesh.QUAD) continue;
            double extent = worstProjectedExtent(mesh.build(CgVertexFormat.SPATIAL));
            assertTrue(mesh + " projects to " + extent + " but is framed at " + mesh.viewRadius()
                            + " — it will be clipped at some orbit angle",
                    extent <= mesh.viewRadius());
        }
    }

    /** Including the quad: whatever else it does when tilted, at rest everything must be fully visible. */
    @Test
    public void everyShapeFitsUnrotated() {
        for (CgPreviewMesh mesh : CgPreviewMesh.values()) {
            double extent = restingExtent(mesh.build(CgVertexFormat.SPATIAL));
            assertTrue(mesh + " does not even fit unrotated: " + extent + " vs " + mesh.viewRadius(),
                    extent <= mesh.viewRadius());
        }
    }

    /**
     * ...and the framing is not so loose that the shape swims in the middle of the panel.
     *
     * <p>Measured against what each shape's framing is actually <em>for</em>: a solid is framed to
     * survive rotation, a quad to fill at rest. Judging the cube by its resting half-extent of 0.5 would
     * call its necessary 1.05 "absurdly wide" — it needs 0.87 the moment it is turned corner-on.</p>
     */
    @Test
    public void nothingIsFramedAbsurdlyWide() {
        for (CgPreviewMesh mesh : CgPreviewMesh.values()) {
            double extent = framingTarget(mesh);
            assertTrue(mesh + " is framed at " + mesh.viewRadius() + " for an extent of " + extent,
                    mesh.viewRadius() <= extent * 1.6);
        }
    }

    /** The extent a shape's framing is chosen to contain. @see #everySolidFitsInsideItsOwnFramingAtEveryAngle */
    private static double framingTarget(CgPreviewMesh mesh) {
        CgMeshData data = mesh.build(CgVertexFormat.SPATIAL);
        return mesh == CgPreviewMesh.QUAD ? restingExtent(data) : worstProjectedExtent(data);
    }

    /** Largest |x| or |y| with no rotation applied — what fills the panel when the panel first opens. */
    private static double restingExtent(CgMeshData data) {
        ByteBuffer vbo = data.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        int stride = data.format().getStride();
        double worst = 0;
        for (int i = 0; i < data.getVertexCount(); i++) {
            int base = i * stride;
            worst = Math.max(worst, Math.max(Math.abs(vbo.getFloat(base)), Math.abs(vbo.getFloat(base + 4))));
        }
        return worst;
    }

    /**
     * Unity's list minus Sprite.
     *
     * <p>Unity separates Sprite from Quad by <em>material</em> — unlit and premultiplied — and this
     * engine has no lighting for a sprite to opt out of, so the two would draw the same picture under two
     * names. Asserted as an exact list so re-adding it is a decision rather than a reflex.</p>
     */
    @Test
    public void theShapeSetIsUnitysMinusSprite() {
        assertArrayEquals(
                new String[] { "Sphere", "Capsule", "Cylinder", "Cube", "Quad" },
                java.util.Arrays.stream(CgPreviewMesh.values())
                        .map(CgPreviewMesh::label).toArray(String[]::new));
    }

    @Test
    public void everyShapeBuildsRealGeometry() {
        for (CgPreviewMesh mesh : CgPreviewMesh.values()) {
            CgMeshData data = mesh.build(CgVertexFormat.SPATIAL);
            assertTrue(mesh + " has no vertices", data.getVertexCount() > 0);
            assertTrue(mesh + " has no indices", data.indexCount() > 0);
            assertEquals(mesh + " index count is not a whole number of triangles",
                    0, data.indexCount() % 3);
        }
    }

    /**
     * The largest half-extent the shape ever projects to, over every orbit angle the panel allows.
     *
     * <p><b>Not the bounding sphere</b>, which is the obvious bound and is wrong here — and wrong in the
     * direction that matters. The camera is orthographic with an axis-aligned box, so what has to fit is
     * the <em>projected</em> X and Y extent, and rotation only ever foreshortens a flat shape. Bounding
     * the quad by its sphere demands {@code √2} of framing for something that never projects past 1, and
     * would have forced a permanent margin around every 2D preview — exactly the letterboxing
     * {@code ShaderNodePreview} deliberately does not apply to quads.</p>
     *
     * <p>Sampled rather than derived: the rotation is {@code Rx(pitch) · Ry(yaw)}, the same composition
     * {@code CgMainPreviewRenderer.applyCamera} builds, and a closed form for the worst case over both
     * angles is a harder thing to get right than a sweep is to run.</p>
     */
    private static double worstProjectedExtent(CgMeshData data) {
        ByteBuffer vbo = data.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        int stride = data.format().getStride();
        int vertexCount = data.getVertexCount();

        double worst = 0;
        for (int yawStep = 0; yawStep < 24; yawStep++) {
            double yaw = 2 * Math.PI * yawStep / 24;
            double cosY = Math.cos(yaw), sinY = Math.sin(yaw);
            for (int pitchStep = -6; pitchStep <= 6; pitchStep++) {
                double pitch = (Math.PI / 2) * pitchStep / 6;
                double cosP = Math.cos(pitch), sinP = Math.sin(pitch);

                for (int i = 0; i < vertexCount; i++) {
                    int base = i * stride;
                    float x = vbo.getFloat(base);
                    float y = vbo.getFloat(base + 4);
                    float z = vbo.getFloat(base + 8);

                    // Ry then Rx, matching identity().rotateX(pitch).rotateY(yaw).
                    double rx = x * cosY + z * sinY;
                    double rz = -x * sinY + z * cosY;
                    double ry = y;

                    double px = rx;
                    double py = ry * cosP - rz * sinP;
                    worst = Math.max(worst, Math.max(Math.abs(px), Math.abs(py)));
                }
            }
        }
        return worst;
    }

    /** Distance from the origin to the furthest vertex. */
    private static double boundingRadius(CgMeshData data) {
        // duplicate() comes back BIG_ENDIAN whatever the original was, so the order has to be restated
        // or every absolute read is byte-swapped.
        ByteBuffer vbo = data.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        int stride = data.format().getStride();
        double worst = 0;
        for (int i = 0; i < data.getVertexCount(); i++) {
            int base = i * stride;
            float x = vbo.getFloat(base);
            float y = vbo.getFloat(base + 4);
            float z = vbo.getFloat(base + 8);
            worst = Math.max(worst, Math.sqrt(x * x + y * y + z * z));
        }
        return worst;
    }
}
