package com.crystalgraphics.shadergraph;

import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import org.joml.Matrix4f;
import com.crystalgraphics.api.material.CgMaterialInterface;

/** One preview mesh at the origin, under a material and its chain of further passes, recorded into a pass. */
final class CgPreviewDraw {

    private static final Matrix4f IDENTITY = new Matrix4f();

    private CgPreviewDraw() {
    }

    /** {@code material}'s draw of {@code mesh} with an identity object record; custom slots zero. */
    static void object(CgRecording recording, CgRasterPass pass, CgMaterialInterface material, CgMesh mesh) {
        CgMaterialProxy proxy = CgMaterialProxy.of(material);
        CgPipeline pipeline = proxy.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, proxy.bindings(), mesh);
        int at = chunks.instance();
        float[] data = chunks.data();
        IDENTITY.get(data, at);
        IDENTITY.get(data, at + 16);
        pass.add(chunks.end());
    }
}
