package com.crystalgraphics.widget;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.text.style.CgTextStyle;

/**
 * A {@code widgets/} file and a {@link CgWidgetTree}, each way: {@link #compile} reads a resolved asset, its widgets
 * a map by name; {@link #toTree} writes a tree back so that compiling it gives an equal one.
 *
 * <pre>{@code
 * CgWidgetCodec.register();                                       // the widget kind's compiler, once
 * CgWidgetTree tree = CgWidgetCodec.compile(asset, deps);         // a load's compile step
 * String yaml = CgAssetYaml.write(CgWidgetCodec.toTree(tree), CgLookKinds.WIDGET);
 * }</pre>
 *
 * <ul>
 *   <li>A name a panel lists that the map lacks, a widget two panels list, one no panel reaches from {@code root}, an
 *       unknown kind, a material not of the UI domain: each fails at load, naming the asset and the widget.</li>
 *   <li>A text block's style, a user widget's tree and a brush's or an effect's material are hard references: they
 *       compile first.</li>
 *   <li>Colours are linear {@code [r, g, b, a]}, written as held.</li>
 * </ul>
 */
public final class CgWidgetCodec {

    /** The widget kind's compiler. */
    public static final CgAssetCompiler<CgWidgetTree> COMPILER = CgWidgetCodec::compile;

    private CgWidgetCodec() {
    }

    /** Registers {@link #COMPILER} as the widget kind's. */
    public static void register() {
        CgAssetCompiler.register(CgLookKinds.WIDGET, COMPILER);
    }

    /**
     * {@code asset}'s tree, resolved over its parent.
     *
     * @throws CgAssetException a widget out of place, unknown or not drawable, naming the asset and the widget
     */
    public static CgWidgetTree compile(CgAssetData asset, CgAssetCompiler.Dependencies deps) {
        Map<Object, Object> tree = asset.resolved();
        String id = asset.id();
        if (!(tree.get("root") instanceof String root)) throw new CgAssetException(id + ": root: a widget tree needs a root");
        if (!(tree.get("widgets") instanceof Map<?, ?> widgets)) throw new CgAssetException(id + ": widgets: none");
        Set<String> reached = new HashSet<>();
        CgWidget built;
        try {
            built = widget(id, root, widgets, reached, deps);
        } catch (IllegalArgumentException e) {
            throw new CgAssetException(id + ": " + e.getMessage());
        }
        for (Object name : widgets.keySet()) {
            if (!reached.contains(String.valueOf(name))) {
                throw new CgAssetException(id + ": widgets." + name + ": no panel reaches it from " + root);
            }
        }
        try {
            return CgWidgetTree.of(id, built);
        } catch (IllegalArgumentException e) {
            throw new CgAssetException(id + ": " + e.getMessage());
        }
    }

    private static CgWidget widget(String id, String name, Map<?, ?> widgets, Set<String> reached,
                                   CgAssetCompiler.Dependencies deps) {
        String at = id + ": widgets." + name;
        if (!(widgets.get(name) instanceof Map<?, ?> m)) throw new CgAssetException(at + ": a panel lists it; widgets lacks it");
        if (!reached.add(name)) throw new CgAssetException(at + ": two panels list it");
        CgWidget.Kind kind = m.get("kind") instanceof String k ? CgWidget.Kind.of(k) : null;
        if (kind == null) throw new CgAssetException(at + ".kind: " + m.get("kind") + " is no widget kind");
        CgWidget.Builder b = switch (kind) {
            case CANVAS -> CgWidget.canvas(name);
            case HORIZONTAL_BOX -> CgWidget.horizontalBox(name);
            case BORDER -> CgWidget.border(name);
            case SCALE_BOX -> {
                // UScaleBox's other stretches size by the room it is given; only a user scale is drawn.
                if (!"user_specified".equals(m.get("stretch"))) {
                    throw new CgAssetException(at + ".stretch: " + m.get("stretch") + " is not drawn; user_specified is");
                }
                yield CgWidget.scaleBox(name, num(m.get("user_specified_scale"), 1f));
            }
            case RETAINER_BOX -> CgWidget.retainerBox(name);
            case IMAGE -> CgWidget.image(name);
            case TEXT_BLOCK -> CgWidget.text(name);
            case USER_WIDGET -> {
                String ref = (String) m.get("widget");
                if (ref == null || !(deps.definition(CgLookKinds.WIDGET, ref) instanceof CgWidgetTree t)) {
                    throw new CgAssetException(at + ".widget: " + ref + " did not compile");
                }
                yield CgWidget.userWidget(name, t);
            }
        };
        if (m.get("slot") instanceof Map<?, ?> s) b.slot(slot(s));
        if (m.get("transform") instanceof Map<?, ?> t) {
            float[] tr = pair(t.get("translation"), 0f, 0f), sc = pair(t.get("scale"), 1f, 1f);
            float[] sh = pair(t.get("shear"), 0f, 0f);
            b.translation(tr[0], tr[1]).scale(sc[0], sc[1]).shear(sh[0], sh[1]).angle(num(t.get("angle"), 0f));
        }
        if (m.get("pivot") != null) {
            float[] p = pair(m.get("pivot"), 0.5f, 0.5f);
            b.pivot(p[0], p[1]);
        }
        if (m.get("render_opacity") instanceof Number n) b.renderOpacity(n.floatValue());
        if (m.get("visibility") instanceof String v) b.visibility(CgWidget.Visibility.valueOf(v.toUpperCase(Locale.ROOT)));
        if (m.get("colour_and_opacity") != null) b.colour(colour(m.get("colour_and_opacity")));
        switch (kind) {
            case CANVAS, HORIZONTAL_BOX -> {
                if (m.get("children") instanceof List<?> children) {
                    for (Object c : children) b.child(widget(id, String.valueOf(c), widgets, reached, deps));
                }
            }
            case SCALE_BOX -> {
                if (m.get("content") instanceof String c) b.child(widget(id, c, widgets, reached, deps));
            }
            case RETAINER_BOX -> {
                if (m.get("effect") instanceof String effect) {
                    String param = m.get("texture_parameter") instanceof String p ? p : "Texture";
                    b.effect(uiMaterial(effect, at + ".effect", deps), param);
                }
                if (m.get("blur") instanceof Number blur) b.blur(blur.floatValue());
                if (m.get("content") instanceof String c) b.child(widget(id, c, widgets, reached, deps));
            }
            case BORDER -> {
                if (m.get("background") instanceof Map<?, ?> bg) b.brush(brush(bg, at + ".background", deps));
                if (m.get("brush_colour") != null) b.brushColour(colour(m.get("brush_colour")));
                if (m.get("content_colour_and_opacity") != null) b.contentColour(colour(m.get("content_colour_and_opacity")));
                if (m.get("content") instanceof String c) b.child(widget(id, c, widgets, reached, deps));
            }
            case IMAGE -> {
                if (m.get("brush") instanceof Map<?, ?> br) b.brush(brush(br, at + ".brush", deps));
            }
            case TEXT_BLOCK -> {
                String style = (String) m.get("style");
                if (style == null || !(deps.definition(CgLookKinds.TEXT_STYLE, style) instanceof CgTextStyle s)) {
                    throw new CgAssetException(at + ".style: " + style + " did not compile");
                }
                b.style(s);
                if (m.get("text") != null) b.text(String.valueOf(m.get("text")));
                if (m.get("justification") instanceof String j) {
                    b.justification(CgWidget.Justification.valueOf(j.toUpperCase(Locale.ROOT)));
                }
                if (m.get("wrap") instanceof Number w) b.wrap(w.floatValue());
            }
            case USER_WIDGET -> {
            }
        }
        return b.build();
    }

    private static CgWidgetSlot slot(Map<?, ?> s) {
        CgWidgetSlot.Builder b = CgWidgetSlot.builder();
        Object anchors = s.get("anchors");
        if (anchors instanceof List<?> a && !a.isEmpty()) {
            if (a.get(0) instanceof List<?>) {
                float[] min = pair(a.get(0), 0f, 0f), max = pair(a.get(1), 0f, 0f);
                b.anchors(min[0], min[1], max[0], max[1]);
            } else {
                float[] p = pair(a, 0f, 0f);
                b.anchors(p[0], p[1]);
            }
        }
        if (s.get("offsets") instanceof List<?> o) {
            b.offsets(num(o.get(0), 0f), num(o.get(1), 0f), num(o.get(2), 0f), num(o.get(3), 0f));
        }
        if (s.get("alignment") != null) {
            float[] al = pair(s.get("alignment"), 0f, 0f);
            b.alignment(al[0], al[1]);
        }
        if (s.get("auto_size") instanceof Boolean auto) b.autoSize(auto);
        if (s.get("z_order") instanceof Number z) b.zOrder(z.intValue());
        Object padding = s.get("padding");
        if (padding instanceof Number p) b.padding(p.floatValue(), p.floatValue(), p.floatValue(), p.floatValue());
        else if (padding instanceof List<?> p) b.padding(num(p.get(0), 0f), num(p.get(1), 0f), num(p.get(2), 0f), num(p.get(3), 0f));
        if (s.get("size") instanceof Map<?, ?> fill) b.size(CgWidgetSlot.Size.FILL, num(fill.get("fill"), 1f));
        if (s.get("horizontal_alignment") instanceof String h) b.horizontal(align(h));
        if (s.get("vertical_alignment") instanceof String v) b.vertical(align(v));
        return b.build();
    }

    private static CgWidgetSlot.Align align(String name) {
        return switch (name) {
            case "fill" -> CgWidgetSlot.Align.FILL;
            case "left", "top" -> CgWidgetSlot.Align.LEFT;
            case "centre" -> CgWidgetSlot.Align.CENTRE;
            default -> CgWidgetSlot.Align.RIGHT;
        };
    }

    private static CgWidgetBrush brush(Map<?, ?> m, String at, CgAssetCompiler.Dependencies deps) {
        float[] size = pair(m.get("image_size"), 32f, 32f);
        CgLinearColour tint = m.get("tint") != null ? colour(m.get("tint")) : CgLinearColour.WHITE;
        boolean draws = !"none".equals(m.get("draw_as"));
        String texture = m.get("texture") instanceof String t ? t : null;
        CgMaterialDefinition material = m.get("material") instanceof String mat
                ? uiMaterial(mat, at + ".material", deps) : null;
        return new CgWidgetBrush(texture, material, size[0], size[1], tint, draws);
    }

    /** {@code id}, compiled, a UI-domain material: any other fails, naming the widget's field. */
    private static CgMaterialDefinition uiMaterial(String id, String at, CgAssetCompiler.Dependencies deps) {
        if (!(deps.definition(CgLookKinds.MATERIAL, id) instanceof CgMaterialDefinition d)) {
            throw new CgAssetException(at + ": " + id + " did not compile");
        }
        d.requireDomain(CgMaterialDomain.UI, at);
        return d;
    }

    // ---- writing ----

    /** As its file writes it, with no parent: the tree {@link #compile} reads back to an equal one. */
    public static Map<String, Object> toTree(CgWidgetTree tree) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(CgType.VERSION, 1L);
        m.put("root", tree.root().name());
        Map<String, Object> widgets = new LinkedHashMap<>();
        for (int i = 0; i < tree.size(); i++) widgets.put(tree.widget(i).name(), write(tree.widget(i)));
        m.put("widgets", widgets);
        return m;
    }

    private static Map<String, Object> write(CgWidget w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", w.kind().id());
        Map<String, Object> slot = slot(w.slot());
        if (!slot.isEmpty()) m.put("slot", slot);
        float[] t = w.transform();
        Map<String, Object> transform = new LinkedHashMap<>();
        if (t[0] != 0f || t[1] != 0f) transform.put("translation", list(t[0], t[1]));
        if (t[2] != 1f || t[3] != 1f) transform.put("scale", list(t[2], t[3]));
        if (t[4] != 0f || t[5] != 0f) transform.put("shear", list(t[4], t[5]));
        if (t[6] != 0f) transform.put("angle", (double) t[6]);
        if (!transform.isEmpty()) m.put("transform", transform);
        if (w.pivotX() != 0.5f || w.pivotY() != 0.5f) m.put("pivot", list(w.pivotX(), w.pivotY()));
        if (w.renderOpacity() != 1f) m.put("render_opacity", (double) w.renderOpacity());
        if (w.visibility() != CgWidget.Visibility.VISIBLE) m.put("visibility", w.visibility().id());
        switch (w.kind()) {
            case CANVAS, HORIZONTAL_BOX -> {
                List<Object> children = new ArrayList<>();
                for (CgWidget c : w.children()) children.add(c.name());
                if (!children.isEmpty()) m.put("children", children);
            }
            case SCALE_BOX -> {
                m.put("stretch", "user_specified");
                m.put("user_specified_scale", (double) w.userScale());
                if (!w.children().isEmpty()) m.put("content", w.children().get(0).name());
            }
            case RETAINER_BOX -> {
                if (!w.children().isEmpty()) m.put("content", w.children().get(0).name());
                if (w.effect() != null) {
                    m.put("effect", w.effect().id());
                    if (!w.textureParameter().equals("Texture")) m.put("texture_parameter", w.textureParameter());
                }
                if (w.blur() > 0f) m.put("blur", (double) w.blur());
            }
            case BORDER -> {
                if (!w.children().isEmpty()) m.put("content", w.children().get(0).name());
                if (!w.brush().equals(CgWidgetBrush.WHITE)) m.put("background", brush(w.brush()));
                if (!w.brushColour().equals(CgLinearColour.WHITE)) m.put("brush_colour", colour(w.brushColour()));
                if (!w.contentColour().equals(CgLinearColour.WHITE)) {
                    m.put("content_colour_and_opacity", colour(w.contentColour()));
                }
            }
            case IMAGE -> {
                if (!w.brush().equals(CgWidgetBrush.WHITE)) m.put("brush", brush(w.brush()));
                colourOf(w, m);
            }
            case TEXT_BLOCK -> {
                if (!w.text().isEmpty()) m.put("text", w.text());
                String style = w.style().id();
                if (style == null) throw new IllegalArgumentException(w.name() + ": its style has no id to write");
                m.put("style", style);
                colourOf(w, m);
                if (w.justification() != CgWidget.Justification.LEFT) {
                    m.put("justification", w.justification().name().toLowerCase(Locale.ROOT));
                }
                if (w.wrap() > 0f) m.put("wrap", (double) w.wrap());
            }
            case USER_WIDGET -> {
                String ref = w.widget().id();
                if (ref == null) throw new IllegalArgumentException(w.name() + ": its tree has no id to write");
                m.put("widget", ref);
                colourOf(w, m);
            }
        }
        return m;
    }

    private static void colourOf(CgWidget w, Map<String, Object> m) {
        if (!w.colour().equals(CgLinearColour.WHITE)) m.put("colour_and_opacity", colour(w.colour()));
    }

    private static Map<String, Object> slot(CgWidgetSlot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        float[] a = s.anchors;
        if (!Float.isNaN(a[0])) {
            m.put("anchors", a[0] == a[2] && a[1] == a[3] ? list(a[0], a[1]) : List.of(list(a[0], a[1]), list(a[2], a[3])));
        }
        if (!Float.isNaN(s.offsets[0])) m.put("offsets", list(s.offsets));
        if (!Float.isNaN(s.alignment[0])) m.put("alignment", list(s.alignment));
        if (s.autoSize != null) m.put("auto_size", s.autoSize);
        if (s.zOrder != 0) m.put("z_order", (long) s.zOrder);
        if (!Float.isNaN(s.padding[0])) m.put("padding", list(s.padding));
        if (s.size == CgWidgetSlot.Size.FILL) m.put("size", Map.of("fill", (double) s.fill));
        if (s.horizontal != null) m.put("horizontal_alignment", alignName(s.horizontal, false));
        if (s.vertical != null) m.put("vertical_alignment", alignName(s.vertical, true));
        return m;
    }

    private static String alignName(CgWidgetSlot.Align a, boolean vertical) {
        return switch (a) {
            case FILL -> "fill";
            case LEFT -> vertical ? "top" : "left";
            case CENTRE -> "centre";
            case RIGHT -> vertical ? "bottom" : "right";
        };
    }

    private static Map<String, Object> brush(CgWidgetBrush b) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (b.texture() != null) m.put("texture", b.texture());
        if (b.material() != null) m.put("material", b.material().id());
        if (b.width() != 32f || b.height() != 32f) m.put("image_size", list(b.width(), b.height()));
        if (!b.tint().equals(CgLinearColour.WHITE)) m.put("tint", colour(b.tint()));
        if (!b.draws()) m.put("draw_as", "none");
        return m;
    }

    // ---- values ----

    private static CgLinearColour colour(Object o) {
        if (!(o instanceof List<?> c) || c.size() != 4) throw new IllegalArgumentException("a colour is [r, g, b, a]: " + o);
        return new CgLinearColour(num(c.get(0), 0f), num(c.get(1), 0f), num(c.get(2), 0f), num(c.get(3), 1f));
    }

    private static List<Object> colour(CgLinearColour c) {
        return list(c.r(), c.g(), c.b(), c.a());
    }

    private static float[] pair(Object o, float x, float y) {
        if (o instanceof List<?> p && p.size() == 2) return new float[]{num(p.get(0), x), num(p.get(1), y)};
        if (o == null) return new float[]{x, y};
        throw new IllegalArgumentException("expected [x, y]: " + o);
    }

    private static float num(Object o, float otherwise) {
        return o instanceof Number n ? n.floatValue() : otherwise;
    }

    private static List<Object> list(float... values) {
        List<Object> out = new ArrayList<>(values.length);
        for (float v : values) out.add((double) v);
        return out;
    }
}
