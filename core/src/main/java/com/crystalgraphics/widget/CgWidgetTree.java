package com.crystalgraphics.widget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * A widget blueprint's tree, compiled: UMG's {@code UWidgetTree}. Shared and never changed; each use is a
 * {@link CgUserWidget} from {@link #newInstance()}, which holds what changes (text, visibility, what a sequence writes)
 * and lays the tree out by Slate's rules.
 *
 * <pre>{@code
 * static final CgWidgetTree POP = CgWidgetTree.of(CgWidget.canvas("All_Nut")
 *         .child(CgWidget.text("Num_Dmg").style(GOLD)
 *                 .slot(CgWidgetSlot.builder().anchors(0.5f, 0.5f).alignment(0.5f, 0.5f).autoSize(true).build()))
 *         .build());
 *
 * CgUserWidget pop = POP.newInstance();   // once per pooled pop, not per hit
 * }</pre>
 *
 * <ul>
 *   <li>Every name is unique in the tree: a second widget of a name fails here, naming it.</li>
 *   <li>Widgets are numbered depth first, the root 0: {@link #index} once, then the instance's index methods, on a
 *       hot path.</li>
 * </ul>
 */
public final class CgWidgetTree {

    private final CgWidget[] widgets;
    private final int[] parent;
    private final int[][] children;
    private final int[][] paintOrder;
    private final Map<String, Integer> names = new HashMap<>();
    private final CgWidget root;
    @Nullable
    private final String id;

    // Each widget's slot, resolved against its parent's kind.
    final float[] anchors;
    final float[] offsets;
    final float[] alignment;
    final boolean[] autoSize;
    final float[] padding;
    final CgWidgetSlot.Align[] horizontal;
    final CgWidgetSlot.Align[] vertical;
    final boolean[] fills;
    final float[] fill;

    private CgWidgetTree(@Nullable String id, CgWidget root) {
        this.id = id;
        this.root = root;
        List<CgWidget> flat = new ArrayList<>();
        List<Integer> parents = new ArrayList<>();
        flatten(root, -1, flat, parents);
        int n = flat.size();
        widgets = flat.toArray(new CgWidget[0]);
        parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = parents.get(i);
        children = new int[n][];
        paintOrder = new int[n][];
        anchors = new float[n * 4];
        offsets = new float[n * 4];
        alignment = new float[n * 2];
        autoSize = new boolean[n];
        padding = new float[n * 4];
        horizontal = new CgWidgetSlot.Align[n];
        vertical = new CgWidgetSlot.Align[n];
        fills = new boolean[n];
        fill = new float[n];
        for (int i = 0; i < n; i++) {
            if (names.put(widgets[i].name, i) != null) {
                throw new IllegalArgumentException("two widgets are named " + widgets[i].name);
            }
        }
        for (int i = 0; i < n; i++) {
            int count = 0;
            for (int c = i + 1; c < n; c++) if (parent[c] == i) count++;
            int[] own = new int[count];
            for (int c = i + 1, k = 0; c < n; c++) if (parent[c] == i) own[k++] = c;
            children[i] = own;
            paintOrder[i] = own;
            if (widgets[i].kind == CgWidget.Kind.CANVAS) {
                // SConstraintCanvas: by z order, ties in tree order.
                Integer[] sorted = new Integer[count];
                for (int k = 0; k < count; k++) sorted[k] = own[k];
                Arrays.sort(sorted, (a, b) -> widgets[a].slot.zOrder != widgets[b].slot.zOrder
                        ? Integer.compare(widgets[a].slot.zOrder, widgets[b].slot.zOrder) : Integer.compare(a, b));
                int[] order = new int[count];
                for (int k = 0; k < count; k++) order[k] = sorted[k];
                paintOrder[i] = order;
            }
            resolveSlot(i, parent[i] < 0 ? null : widgets[parent[i]].kind);
        }
    }

    /**
     * The tree under {@code root}.
     *
     * @throws IllegalArgumentException two widgets of one name
     */
    public static CgWidgetTree of(CgWidget root) {
        return of(null, root);
    }

    /**
     * The tree under {@code root}, named {@code id} (its asset's, else null) in errors and in a user widget naming it.
     *
     * @throws IllegalArgumentException two widgets of one name
     */
    public static CgWidgetTree of(@Nullable String id, CgWidget root) {
        return new CgWidgetTree(id, Objects.requireNonNull(root, "root"));
    }

    /** Its asset's id; null for a tree built in Java without one. */
    @Nullable
    public String id() {
        return id;
    }

    private static void flatten(CgWidget w, int parent, List<CgWidget> out, List<Integer> parents) {
        int at = out.size();
        out.add(w);
        parents.add(parent);
        for (CgWidget c : w.children) flatten(c, at, out, parents);
    }

    /** Each UMG slot class's defaults for what the widget's slot leaves unset. */
    private void resolveSlot(int i, @Nullable CgWidget.Kind parentKind) {
        CgWidgetSlot s = widgets[i].slot;
        // UBorderSlot pads by (4, 2); a retainer box's child fills it (UPanelSlot), a box slot has no padding.
        float[] padDefault = parentKind == CgWidget.Kind.BORDER ? new float[]{4f, 2f, 4f, 2f} : new float[4];
        // UCanvasPanelSlot: offsets (0, 0, 100, 30), anchors and alignment 0.
        float[] offDefault = {0f, 0f, 100f, 30f};
        for (int k = 0; k < 4; k++) {
            anchors[i * 4 + k] = or(s.anchors[k], 0f);
            offsets[i * 4 + k] = or(s.offsets[k], offDefault[k]);
            padding[i * 4 + k] = or(s.padding[k], padDefault[k]);
        }
        alignment[i * 2] = or(s.alignment[0], 0f);
        alignment[i * 2 + 1] = or(s.alignment[1], 0f);
        autoSize[i] = Boolean.TRUE.equals(s.autoSize);
        horizontal[i] = s.horizontal != null ? s.horizontal : CgWidgetSlot.Align.FILL;
        vertical[i] = s.vertical != null ? s.vertical : CgWidgetSlot.Align.FILL;
        fills[i] = s.size == CgWidgetSlot.Size.FILL;
        fill[i] = s.fill;
    }

    private static float or(float value, float otherwise) {
        return Float.isNaN(value) ? otherwise : value;
    }

    /** A new instance at the designer's values. */
    public CgUserWidget newInstance() {
        return new CgUserWidget(this, null);
    }

    /** The root as built. */
    public CgWidget root() {
        return root;
    }

    /** How many widgets it has. */
    public int size() {
        return widgets.length;
    }

    /** The index of the widget {@code name}, or -1. */
    public int index(String name) {
        Integer i = names.get(name);
        return i == null ? -1 : i;
    }

    /** Widget {@code i}, depth first, the root 0. */
    public CgWidget widget(int i) {
        return widgets[i];
    }

    /** Widget {@code i}'s parent, -1 for the root. */
    public int parent(int i) {
        return parent[i];
    }

    int[] children(int i) {
        return children[i];
    }

    int[] paintOrder(int i) {
        return paintOrder[i];
    }

    @Override
    public String toString() {
        return id != null ? id : "widget tree " + root.name;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgWidgetTree t && Objects.equals(id, t.id) && root.equals(t.root);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, root);
    }
}
