package com.crystalgraphics.vfx.particle.gpu.sim;

import java.util.IdentityHashMap;
import java.util.Map;

import com.crystalgraphics.object.CgPropertyMeta;
import com.crystalgraphics.vfx.particle.form.CgVfxCurveBlock;
import com.crystalgraphics.vfx.particle.form.CgVfxFormInput;

/**
 * A compiled form flattened for the Step kernel: where each of its nodes' vec4s sit in the parameter row's form area,
 * and which roll key slot each node that rolls takes. A function's arguments are nodes of their own, so a form nests
 * to any depth.
 *
 * <pre>{@code
 * CgVfxFormLayout layout = CgVfxFormLayout.of(what, form.compiled(), true, false, param, keys);
 * param += layout.vectors();
 * keys += layout.keys();
 * }</pre>
 *
 * <ul>
 *   <li>A top-level range or pick keeps its first end in its row word, so its node holds the other end alone; nested,
 *       a node holds both.</li>
 *   <li>Throws, naming {@code what}, for a form the kernel does not build: a curve or array by {@code length}, a binding
 *       to anything but {@code particles.age}, a roll per loop or instance, or at spawn a lookup the newborn has no
 *       value for.</li>
 * </ul>
 */
final class CgVfxFormLayout {

    /** A node: its form, its first row vector, its roll key's slot (-1 for none). */
    record Node(CgVfxFormInput form, int at, int key, boolean top) {
    }

    private final Map<CgVfxFormInput, Node> nodes = new IdentityHashMap<>();
    private final CgVfxFormInput root;
    private int vectors, keys;

    private CgVfxFormLayout(CgVfxFormInput root) {
        this.root = root;
    }

    /**
     * {@code form} laid out from row vector {@code at} and key slot {@code key}; {@code top} for a form whose first end
     * sits in its own row word, {@code spawn} for a spawn value's.
     */
    static CgVfxFormLayout of(String what, CgVfxFormInput form, boolean top, boolean spawn, int at, int key) {
        CgVfxFormLayout l = new CgVfxFormLayout(form);
        l.vectors = at;
        l.keys = key;
        l.add(what, form, top, spawn);
        l.vectors -= at;
        l.keys -= key;
        return l;
    }

    private void add(String what, CgVfxFormInput c, boolean top, boolean spawn) {
        if (c.components() > 4) throw new IllegalArgumentException(what + " has " + c.components() + " components");
        int at = vectors, key = -1;
        if (c instanceof CgVfxFormInput.Constant) {
            vectors += 1;
        } else if (c instanceof CgVfxFormInput.Range r) {
            perParticle(what, r.rolls());
            vectors += top ? 1 : 2;
            key = keys++;
        } else if (c instanceof CgVfxFormInput.Pick p) {
            perParticle(what, p.rolls());
            vectors += top ? 1 : 2;
            key = keys++;
        } else if (c instanceof CgVfxFormInput.Curve curve) {
            lookup(what, curve.by(), spawn);
            vectors += 1 + CgVfxCurveBlock.TEXELS;
            key = keys++;
        } else if (c instanceof CgVfxFormInput.Array array) {
            lookup(what, array.by(), spawn);
            vectors += 1 + array.values().length;
            key = keys++;
        } else if (c instanceof CgVfxFormInput.Function f) {
            perParticle(what, f.rolls());
            key = keys++;
            nodes.put(c, new Node(c, at, key, top));
            add(what, f.first(), false, spawn);
            add(what, f.second(), false, spawn);
            return;
        } else if (c instanceof CgVfxFormInput.Binding b && b.path().equals("particles.age") && b.inLo() == null && !spawn) {
            // Read in the kernel: nothing in the row.
        } else {
            throw new IllegalArgumentException(what + ": the " + c.form().specName() + " form is not built in the Step yet");
        }
        nodes.put(c, new Node(c, at, key, top));
    }

    private static void perParticle(String what, CgPropertyMeta.Rolls rolls) {
        if (rolls != null && rolls != CgPropertyMeta.Rolls.PARTICLE) {
            throw new IllegalArgumentException(what + ": a roll per " + rolls + " is not built yet; no module input declares one");
        }
    }

    private static void lookup(String what, CgVfxFormInput.Lookup by, boolean spawn) {
        if (by == CgVfxFormInput.Lookup.LENGTH) throw new IllegalArgumentException(what + ": a lookup by length is a trail's (P8)");
        if (spawn && by != CgVfxFormInput.Lookup.RANDOM && by != CgVfxFormInput.Lookup.ID && by != CgVfxFormInput.Lookup.LOOP) {
            throw new IllegalArgumentException(what + ": at spawn a lookup by " + by + " has no value; by random, id or loop");
        }
    }

    CgVfxFormInput root() {
        return root;
    }

    /** Node {@code c}: a form of this layout. */
    Node node(CgVfxFormInput c) {
        Node n = nodes.get(c);
        if (n == null) throw new IllegalStateException(c.name() + " is not a node of this layout");
        return n;
    }

    Iterable<Node> nodes() {
        return nodes.values();
    }

    /** Its vec4s in the form area. */
    int vectors() {
        return vectors;
    }

    /** Its roll keys. */
    int keys() {
        return keys;
    }
}
