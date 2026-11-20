package com.crystalgraphics.widget;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.load.CgAssetPreparer;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.precache.CgPipelineList;

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
        boolean materials = holds(tree);
        // A retainer box is a canvas layer: its blur's kernels are the canvas's own precache
        if (materials) plan.pipelines(pipelines -> add(tree, pipelines));
    }

    /** Whether {@code tree} or a tree it nests draws a UI material: a brush's or a retainer box's effect. */
    private static boolean holds(CgWidgetTree tree) {
        for (int i = 0; i < tree.size(); i++) {
            CgWidget w = tree.widget(i);
            if (w.brush().material() != null || w.effect() != null) return true;
            if (w.widget() != null && holds(w.widget())) return true;
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

    // A widget draws through an instance of the shared material (its own parameters): one program, built here.
    private static void add(CgMaterialDefinition material, CgPipelineList pipelines) {
        pipelines.addPass(CgMaterial.of(material), CgInstanceKind.ELEMENT);
    }
}
