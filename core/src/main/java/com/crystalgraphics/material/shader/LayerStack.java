package com.crystalgraphics.material.shader;

import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.material.shader.syntax.CgKeywordGrammar;
import com.crystalgraphics.material.shader.syntax.CgShaderError;
import com.crystalgraphics.material.shader.syntax.CgPropertyGrammar;
import com.crystalgraphics.material.shader.syntax.CgShaderSyntax;
import com.crystalgraphics.material.shader.syntax.CgShaderTokens;
import com.github.bsideup.jabel.Desugar;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A surface material's {@code Layers { }}: Unreal's material layers ({@code FMaterialLayersFunctions}), a stack of layer
 * functions, each but the first blended over the ones below it by a blend function. Each slot's choice is a one-of
 * keyword, so an asset swaps or hides a layer as a static permutation, keyed and precached as any keyword set is
 * (Unreal's {@code StaticMaterialLayersParameter}); a slot's properties are its own, as Unreal scopes a layer's
 * parameters by its index.
 *
 * <pre>{@code
 * Layers {
 *     Rock  rock_layer                                   // the base: CgSurface rock_layer(in v2f i, CgSurface s)
 *     Moss  moss_layer | wet_moss  by slope_blend {      // CgSurface slope_blend(in v2f i, CgSurface below, CgSurface top)
 *         Tint ("Moss colour", color) = (0.3, 0.5, 0.2, 1)   // Moss_Tint, which this slot's functions read as Tint
 *     }
 * }
 * void surface(in v2f i, inout CgSurface s) { s = cg_MaterialLayers(i, s); }
 * }</pre>
 *
 * <ul>
 *   <li>A slot is {@code LAYER_<SLOT>}: its layers, then {@code HIDDEN} for any slot but the first; the first value is
 *       the default. An asset picks with {@code layers: { Moss: wet_moss }} or {@code Moss: hidden}.</li>
 *   <li>Every layer and blend function is copied per slot, its properties defined as the slot's own: one function may
 *       serve two slots with different values.</li>
 *   <li>The functions, and what they call, come before {@code surface()}, where the stack is written.</li>
 * </ul>
 */
final class LayerStack {

    /** The function {@code surface()} calls. */
    static final String CALL = "cg_MaterialLayers";
    static final String HIDDEN = "HIDDEN";

    @Desugar
    record Slot(String name, List<String> layers, @Nullable String blend, List<CgMaterialProperty> properties, int token) {
        /** Its keyword's name: {@code LAYER_MOSS}. */
        String keyword() {
            return "LAYER_" + name.toUpperCase(Locale.ROOT);
        }

        /** The one-of value a layer of it, or {@code hidden}, picks. */
        static String value(String choice) {
            return choice.toUpperCase(Locale.ROOT);
        }
    }

    final List<Slot> slots;

    private LayerStack(List<Slot> slots) {
        this.slots = slots;
    }

    /** The file's {@code Layers { }}, checked against its functions; null where it has none. */
    @Nullable
    static LayerStack read(CgShaderSyntax file, Map<String, List<CgShaderSyntax.Function>> functions) {
        CgShaderSyntax.Block block = file.block("Layers");
        if (block == null) return null;
        CgShaderTokens t = file.tokens();
        List<Slot> slots = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        int i = block.open() + 1, end = block.close();
        while (i < end) {
            int at = i;
            if (!t.isIdent(i)) throw shape(t, at);
            String name = t.text(i++);
            if (!names.add(name)) throw t.error(at, "'" + name + "' is a layer slot twice");
            List<String> layers = new ArrayList<>();
            if (!t.isIdent(i)) throw shape(t, at);
            layers.add(t.text(i++));
            while (i < end && t.is(i, '|')) {
                if (!t.isIdent(i + 1)) throw shape(t, at);
                layers.add(t.text(i + 1));
                i += 2;
            }
            String blend = null;
            if (i < end && t.isIdent(i, "by")) {
                if (!t.isIdent(i + 1)) throw shape(t, at);
                blend = t.text(i + 1);
                i += 2;
            }
            List<CgMaterialProperty> properties = List.of();
            if (i < end && t.is(i, '{')) {
                int close = t.close(i);
                properties = scoped(name, CgPropertyGrammar.read(t, i + 1, close, false));
                i = close + 1;
            }
            if (slots.isEmpty() && blend != null) {
                throw t.error(at, "'" + name + "' is the base layer: nothing is below it to blend over ('by " + blend + "')");
            }
            if (!slots.isEmpty() && blend == null) {
                throw t.error(at, "'" + name + "' needs 'by <blend>': how it lies over the layers below it");
            }
            for (String f : layers) requireFunction(t, at, functions, f, "layer");
            if (blend != null) requireFunction(t, at, functions, blend, "blend");
            Set<String> values = new LinkedHashSet<>();
            for (String f : layers) {
                if (!values.add(Slot.value(f))) throw t.error(at, "'" + f + "' is a layer of slot " + name + " twice");
            }
            if (values.contains(HIDDEN)) throw t.error(at, "'" + HIDDEN.toLowerCase(Locale.ROOT) + "' is no layer: it hides one");
            slots.add(new Slot(name, layers, blend, properties, at));
        }
        if (slots.isEmpty()) throw t.error(block.first(), "'Layers { }' with no layer");
        return new LayerStack(slots);
    }

    private static void requireFunction(CgShaderTokens t, int at, Map<String, List<CgShaderSyntax.Function>> functions,
                                        String name, String what) {
        if (!functions.containsKey(name)) {
            throw t.error(at, "'" + name + "' is no function of this file: a " + what + (what.equals("layer")
                    ? " is 'CgSurface " + name + "(in v2f i, CgSurface s)'"
                    : " is 'CgSurface " + name + "(in v2f i, CgSurface below, CgSurface top)'"));
        }
    }

    /** A slot's properties under its own names, {@code Moss_Tint} labelled "Moss: ...". */
    private static List<CgMaterialProperty> scoped(String slot, List<CgMaterialProperty> properties) {
        List<CgMaterialProperty> out = new ArrayList<>(properties.size());
        for (CgMaterialProperty p : properties) {
            out.add(p.copyNamed(slot + "_" + p.getName(), slot + ": " + p.getDisplayName()));
        }
        return out;
    }

    /** Each slot's one-of: its layers, and {@code HIDDEN} but for the base; none for a base of one layer. */
    List<CgKeywordGrammar.Keyword> keywords() {
        List<CgKeywordGrammar.Keyword> out = new ArrayList<>();
        for (int s = 0; s < slots.size(); s++) {
            Slot slot = slots.get(s);
            List<String> values = new ArrayList<>();
            for (String f : slot.layers()) values.add(Slot.value(f));
            if (s > 0) values.add(HIDDEN);
            if (values.size() > 1) out.add(new CgKeywordGrammar.Keyword(slot.keyword(), values, false));
        }
        return out;
    }

    /** Every slot's properties, under their scoped names. */
    List<CgMaterialProperty> properties() {
        List<CgMaterialProperty> out = new ArrayList<>();
        for (Slot s : slots) out.addAll(s.properties());
        return out;
    }

    /** The layer and blend functions: written per slot, so not as the file has them. */
    Set<String> functions() {
        Set<String> out = new LinkedHashSet<>();
        for (Slot s : slots) {
            out.addAll(s.layers());
            if (s.blend() != null) out.add(s.blend());
        }
        return out;
    }

    /** The per-slot copies and {@link #CALL}: written just before {@code surface()}. */
    String glsl(CgShaderSyntax file, Map<String, List<CgShaderSyntax.Function>> functions) {
        CgShaderTokens t = file.tokens();
        StringBuilder sb = new StringBuilder();
        for (Slot slot : slots) {
            for (CgMaterialProperty p : slot.properties()) {
                String own = p.getName().substring(slot.name().length() + 1);
                sb.append("#define ").append(own).append(' ').append(p.getName()).append('\n');
            }
            Set<String> copied = new LinkedHashSet<>(slot.layers());
            if (slot.blend() != null) copied.add(slot.blend());
            for (String name : copied) {
                for (CgShaderSyntax.Function f : functions.get(name)) {
                    sb.append(t.plain(f.first(), f.close(), f.nameToken(), copy(slot, name))).append('\n');
                }
            }
            for (CgMaterialProperty p : slot.properties()) {
                sb.append("#undef ").append(p.getName().substring(slot.name().length() + 1)).append('\n');
            }
        }
        sb.append("CgSurface ").append(CALL).append("(in v2f i, CgSurface s) {\n    CgSurface layers;\n");
        for (int s = 0; s < slots.size(); s++) {
            Slot slot = slots.get(s);
            boolean branches = slot.layers().size() > 1 || s > 0;
            if (s > 0) sb.append("#if defined(").append(slot.keyword()).append('_').append(HIDDEN).append(")\n");
            for (int k = slot.layers().size() - 1; k >= 0; k--) {
                String f = slot.layers().get(k);
                if (k > 0) {
                    String condition = "defined(" + slot.keyword() + "_" + Slot.value(f) + ")";
                    sb.append(s > 0 || k < slot.layers().size() - 1 ? "#elif " : "#if ").append(condition).append('\n');
                } else if (branches) {
                    sb.append("#else\n");
                }
                String layer = copy(slot, f) + "(i, s)";
                sb.append("    layers = ").append(s == 0 ? layer : copy(slot, slot.blend()) + "(i, layers, " + layer + ")")
                  .append(";\n");
            }
            if (branches) sb.append("#endif\n");
        }
        return sb.append("    return layers;\n}\n").toString();
    }

    /** A slot's copy of a function. */
    private static String copy(Slot slot, String function) {
        return "_cg_layer_" + slot.name() + "_" + function;
    }

    private static CgShaderError shape(CgShaderTokens t, int at) {
        return t.error(at, "a layer slot is 'Name layer [| layer ...] [by blend] [{ properties }]': the first the base,"
                + " each after it 'by' the blend that lays it over those below");
    }
}
