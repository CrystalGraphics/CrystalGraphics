package com.crystalgraphics.shadergraph;

import java.util.HashMap;
import java.util.Map;

/**
 * What a node's preview thumbnail is drawn on — and, more importantly, how that choice <b>travels
 * down the graph</b>.
 *
 * <h3>Ported from Unity, because it is a convention rather than a derivable answer</h3>
 * <p>Unity's previews are a flat quad by default, but {@code Position} and {@code Normal Vector} draw on
 * a sphere, and <b>every node downstream of one inherits it</b>. That is the part worth porting: it is
 * not a per-node setting a user configures, it is a property that propagates, so wiring a Position into
 * a Multiply turns the Multiply's preview into a sphere too.</p>
 *
 * <p>The reason is that a flat quad cannot show a 3D quantity at all. A world-space normal previewed on
 * a quad is one flat colour — technically correct and completely uninformative — while on a sphere it is
 * the familiar RGB ball that tells you instantly whether the vector is right.</p>
 *
 * <h3>Why this mirrors {@link CgShaderDomain} rather than being a new mechanism</h3>
 * <p>Both are node-declared properties that propagate along edges and are resolved by a walk over
 * {@link CgShaderGraph#orderedFrom}. The difference is the direction of authority: a domain conflict is
 * an <em>error</em> (a fragment value cannot reach the vertex stage), whereas a geometry disagreement is
 * merely a choice, so sphere simply wins. There is nothing to report and nothing that can fail.</p>
 */
public enum CgPreviewGeometry {

    /**
     * Take the answer from upstream. The default for every node, and the reason a chain of Math nodes
     * fed by a Position all preview as spheres without any of them saying so.
     */
    INHERIT,

    /** A flat unit quad — the right answer for colours, masks, noise and anything 2D. */
    QUAD,

    /** A unit sphere — for directions, normals and positions, which a quad cannot show. */
    SPHERE;

    /**
     * The geometry a node's preview should actually use, after propagation.
     *
     * <p>A node that declares something concrete keeps it. A node that declares {@link #INHERIT} — which
     * is nearly all of them — resolves to {@link #SPHERE} if <b>anything</b> it depends on resolved to a
     * sphere, and {@link #QUAD} otherwise.</p>
     *
     * <p><b>Sphere wins ties deliberately.</b> Mixing a Position into a colour produces a value that is
     * still spatial, and previewing it flat throws away the only dimension that made it interesting. The
     * asymmetry matches Unity's, where the 3D mode is what spreads.</p>
     *
     * @return the resolved geometry, never {@link #INHERIT}
     */
    public static CgPreviewGeometry resolve(CgShaderGraph graph, String nodeId) {
        Map<String, CgPreviewGeometry> resolved = new HashMap<>();

        // orderedFrom is dependency order, so every input of a node is resolved before the node itself
        // and one forward pass is enough — no fixpoint, and no risk of a cycle, because orderedFrom
        // has already refused one.
        for (CgShaderGraph.Instance instance : graph.orderedFrom(nodeId)) {
            CgPreviewGeometry declared = instance.type().previewGeometry();
            if (declared == null) declared = INHERIT;

            CgPreviewGeometry effective = declared;
            if (declared == INHERIT) {
                effective = QUAD;
                for (CgShaderPort port : instance.type().inputs()) {
                    CgShaderGraph.Link link = graph.linkInto(instance.id(), port.id());
                    if (link != null && resolved.get(link.fromNode()) == SPHERE) {
                        effective = SPHERE;
                        break;
                    }
                }
            }
            resolved.put(instance.id(), effective);
        }
        // Read the root back by id rather than trusting it to be the last element iterated. It is, for a
        // topological order rooted here — but that is the walk's business, and a preview silently drawn
        // on the wrong shape is not the kind of coupling worth saving a map lookup for.
        return resolved.getOrDefault(nodeId, QUAD);
    }
}
