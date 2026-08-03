package com.crystalgraphics.shadergraph;

import com.crystalgraphics.api.mesh.CgMeshData;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;

/**
 * The shapes the main preview can draw a finished graph on.
 *
 * <h3>Not {@link CgPreviewGeometry}, and the difference is the point</h3>
 * <p>{@code CgPreviewGeometry} is a two-value <em>inference</em> — quad or sphere, decided by the graph
 * and propagated down it, so a node fed by a Position previews on a ball without anyone asking. This is a
 * <b>user choice</b> about one panel. Folding them together would mean either the graph could override
 * what the user picked, or picking a cube would change every node's thumbnail.</p>
 *
 * <h3>Unity's list, minus one</h3>
 * <p>Unity offers Sphere, Capsule, Cylinder, Cube, Quad, Sprite and Custom Mesh. <b>Sprite is dropped</b>:
 * Unity distinguishes it from Quad by <em>material</em> — a sprite is unlit and premultiplied — and this
 * engine has no lighting for it to opt out of, so the two would render the same picture under two names.
 * Custom Mesh belongs to whatever is doing the file picking, not here.</p>
 *
 * <h3>Dimensions and framing live together</h3>
 * <p>{@link #viewRadius()} is the half-extent the camera must fit, and it sits beside the numbers the
 * geometry is built from. Kept apart, a capsule lengthened by one line silently starts poking out of its
 * own preview — the two are one decision and drift the moment they are two.</p>
 */
public enum CgPreviewMesh {

    /** The default, and what a node thumbnail already uses. */
    SPHERE("Sphere", 1.15f),
    CAPSULE("Capsule", 1.25f),
    CYLINDER("Cylinder", 1.20f),
    CUBE("Cube", 1.05f),
    /** Flat, facing the camera — the right choice for anything genuinely 2D. */
    QUAD("Quad", 1.15f);

    /** Divisions around the axis of revolution. Enough that a silhouette reads as curved at panel size. */
    private static final int SECTORS = 32;

    private final String label;
    private final float viewRadius;

    CgPreviewMesh(String label, float viewRadius) {
        this.label = label;
        this.viewRadius = viewRadius;
    }

    /** Display name, for a menu. */
    public String label() {
        return label;
    }

    /**
     * Half-extent of an orthographic box that contains this shape with a little air around it.
     *
     * <p>Deliberately not derived from the mesh's actual bounds: the shape is rotated by the orbit
     * gesture, so the box has to hold the <b>worst</b> orientation rather than the current one, and a
     * box that resized as you dragged would read as the object breathing.</p>
     */
    public float viewRadius() {
        return viewRadius;
    }

    /** Builds the geometry. Callers upload and cache; nothing here touches GL. */
    public CgMeshData build(CgVertexFormat format) {
        switch (this) {
            case SPHERE:
                return CgMeshBuilder.uvSphere(format, 24, SECTORS, 1f);
            case CAPSULE:
                // Total height 2.0 — a 1.0 cylindrical section between two 0.5 caps — so it reads as
                // Unity's capsule rather than as a stretched pill.
                return CgMeshBuilder.capsule(format, SECTORS, 8, 0.5f, 1f);
            case CYLINDER:
                return CgMeshBuilder.cylinder(format, SECTORS, 0.6f, 1.6f);
            case CUBE:
                return CgMeshBuilder.unitCube(format);
            case QUAD:
            default:
                return CgMeshBuilder.quad2D(format, -1f, -1f, 1f, 1f);
        }
    }
}
