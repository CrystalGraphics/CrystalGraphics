package com.crystalgraphics.shadergraph;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import org.joml.Matrix4f;

/** One preview mesh at the origin, under a material and its chain of further passes, as an immediate draw. */
final class CgPreviewDraw {

    private static final Matrix4f IDENTITY = new Matrix4f();

    private CgPreviewDraw() {
    }

    /** An identity object record per pass of {@code material}'s chain; custom slots zero. */
    static void object(CgImmediate draw, CgMaterial material, CgMesh mesh) {
        CgChunkBuilder chunks = draw.chunks();
        for (CgMaterial link = material; link != null; link = link.getNextPass()) {
            CgPipeline pipeline = link.pipeline(CgInstanceKind.OBJECT);
            if (pipeline == null) continue;
            chunks.draw(pipeline, link.captureBindings(draw.bindings()), mesh);
            int at = chunks.instance();
            float[] data = chunks.data();
            IDENTITY.get(data, at);
            IDENTITY.get(data, at + 16);
        }
    }
}
