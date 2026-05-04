package io.github.somehussar.crystalgraphics.gl.mesh;

import io.github.somehussar.crystalgraphics.api.vertex.CgVertexAttribute;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexSemantic;

/**
 * Pre-computed presence flags for optional vertex semantics in a {@link CgVertexFormat}.
 *
 * <p>Replaces the duplicated semantic-detection loops in {@link CgMeshBuilder},
 * {@link CgObjLoader}, and {@link CgGltfLoader}. Construct once per format before
 * the vertex-packing loop and query {@link #wantsUv}, {@link #wantsColor}, and
 * {@link #wantsNormal} as needed.</p>
 */
final class CgMeshFormatFlags {

    final boolean wantsUv;
    final boolean wantsColor;
    final boolean wantsNormal;
    /** True if the POSITION attribute has 3 components; false if it has 2. */
    final boolean positionIs3D;

    CgMeshFormatFlags(CgVertexFormat format) {
        boolean uv = false;
        boolean color = false;
        boolean normal = false;
        boolean pos3d = false;
        for (int i = 0; i < format.getAttributeCount(); i++) {
            CgVertexAttribute attr = format.getAttribute(i);
            CgVertexSemantic sem = attr.getSemantic();
            if (sem == CgVertexSemantic.UV) {
                uv = true;
            } else if (sem == CgVertexSemantic.COLOR) {
                color = true;
            } else if (sem == CgVertexSemantic.NORMAL) {
                normal = true;
            } else if (sem == CgVertexSemantic.POSITION) {
                pos3d = attr.getComponents() >= 3;
            }
        }
        this.wantsUv = uv;
        this.wantsColor = color;
        this.wantsNormal = normal;
        this.positionIs3D = pos3d;
    }
}
