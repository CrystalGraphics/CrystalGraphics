package com.crystalgraphics.widget;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.asset.load.CgAssetPreparer;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.world.CgPipelineList;

/**
 * A widget asset's UI materials built before it is READY, as Unreal precaches a widget's materials with it: every
 * image's material brush and every retainer box's effect, nested trees' too, drawn as canvas quads; and, where it has a
 * retainer box, the blur's kernels, which an animation may key though the box is sharp as designed. Its text blocks'
 * styles are hard references, prepared as text styles are.
 *
 * <pre>{@code
 * assets.preparer(CgLookKinds.WIDGET, new CgWidgetPreparer());   // CgVfx.preparers registers it
 * }</pre>
 *
 * <ul>
 *   <li>Pipelines only: a texture brush loads at its first draw, kept.</li>
 *   <li>A dedicated server registers none: it draws no widget.</li>
 * </ul>
 */
public final class CgWidgetPreparer implements CgAssetPreparer {

    @Override
    public void plan(Object definition, Plan plan) {
        if (!(definition instanceof CgWidgetTree tree)) return;
        boolean materials = holds(tree, false), retains = holds(tree, true);
        if (!materials && !retains) return;
        plan.pipelines(pipelines -> {
            add(tree, pipelines);
            // A retained image is RGBA8 (CgCanvasRetainer): its blur's kernels on that format.
            if (retains) pipelines.kernels(() -> CgGpuOps.precacheBlur(CgTextureType.RGBA8));
        });
    }

    /** Whether {@code tree} or a tree it nests has a retainer box ({@code retainer}), else a UI material. */
    private static boolean holds(CgWidgetTree tree, boolean retainer) {
        for (int i = 0; i < tree.size(); i++) {
            CgWidget w = tree.widget(i);
            if (retainer ? w.kind() == CgWidget.Kind.RETAINER_BOX : w.brush().material() != null || w.effect() != null) {
                return true;
            }
            if (w.widget() != null && holds(w.widget(), retainer)) return true;
        }
        return false;
    }

    private static void add(CgWidgetTree tree, CgPipelineList pipelines) {
        for (int i = 0; i < tree.size(); i++) {
            CgWidget w = tree.widget(i);
            if (w.brush().material() != null) add(w.brush().material(), pipelines);
            if (w.effect() != null) add(w.effect(), pipelines);
            if (w.widget() != null) add(w.widget(), pipelines);
        }
    }

    // The painter draws through the shared instance (CgCanvasWidgetPainter.material), so this is the one it draws.
    private static void add(CgMaterialDefinition material, CgPipelineList pipelines) {
        pipelines.addPass(CgMaterial.of(material), CgInstanceKind.QUAD);
    }
}
