package com.crystalgraphics.vfx.particle.ribbon;

import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties.Shape;

/**
 * A ribbon's cross section: Niagara's {@code FNiagaraRibbonShapeGeometryData} as {@code GenerateShapeState*} builds it
 * ({@code NiagaraRendererRibbons.cpp:1064-1242}), the slice's vertices placed by the strip factory from their index
 * ({@code particle_factory.glsl}'s {@code _cg_ribbon_slice}). A Cascade trail is a plane of one segment.
 *
 * <pre>{@code
 * CgVfxRibbonShape shape = CgVfxRibbonShape.of(renderer);
 * int vertices = segments * shape.verticesPerSegment();     // two triangles a slice edge
 * draw.custom(1, shape.code(), shape.segments(), shape.planes(), shape.edges());
 * }</pre>
 *
 * @param shape     plane, multi-plane or tube
 * @param segments  a plane's or each plane's width segments, or the tube's sides
 * @param planes    a multi-plane's planes, else 1
 * @param edges     slice edges: each two triangles a sub-segment
 * @param flipToView whether its normals turn to face the eye: a multi-plane's, whose geometry is not doubled
 */
public record CgVfxRibbonShape(Shape shape, int segments, int planes, int edges, boolean flipToView) {

    /** {@code renderer}'s cross section. */
    public static CgVfxRibbonShape of(CgVfxRibbonRendererProperties renderer) {
        if (renderer.trail() != null) return new CgVfxRibbonShape(Shape.PLANE, 1, 1, 1, false);
        int w = renderer.widthSegments();
        // InitializeShape: a multi-plane of 2 to 16 planes, a tube of 3 to 16 sides, else a plane.
        return switch (renderer.shape()) {
            case MULTI_PLANE -> {
                int count = CgVfxRibbonRendererProperties.MULTI_PLANES;
                yield new CgVfxRibbonShape(Shape.MULTI_PLANE, w, count, count * w, true);
            }
            case TUBE -> new CgVfxRibbonShape(Shape.TUBE, CgVfxRibbonRendererProperties.TUBE_SIDES, 1,
                    CgVfxRibbonRendererProperties.TUBE_SIDES, false);
            case PLANE, CUSTOM -> new CgVfxRibbonShape(Shape.PLANE, w, 1, w, false);
        };
    }

    /** Vertices a sub-segment draws: two triangles a slice edge. */
    public int verticesPerSegment() {
        return edges * 6;
    }

    /** The factory's shape code: 0 plane, 1 multi-plane, 2 tube. */
    public int code() {
        return switch (shape) {
            case MULTI_PLANE -> 1;
            case TUBE -> 2;
            default -> 0;
        };
    }
}
