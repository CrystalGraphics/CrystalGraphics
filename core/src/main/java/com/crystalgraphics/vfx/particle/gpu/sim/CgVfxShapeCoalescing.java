package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.asset.curve.CgRichCurve;
import com.crystalgraphics.object.CgPropertyMeta;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.vfx.particle.CgVfxModuleTypes;
import com.crystalgraphics.vfx.particle.form.CgVfxCurveBlock;
import com.crystalgraphics.vfx.particle.form.CgVfxFormInput;
import com.crystalgraphics.vfx.particle.form.CgVfxModuleForm;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnForm;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnInput;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;

/**
 * Shape coalescing: definitions whose shapes differ only in how their inputs are given share one pool, each input
 * raised to the least general form every one of them can take. A constant becomes a range with equal ends, a pick of
 * one value, a flat curve or a lane holding it; a range or a curve becomes {@code random_range} of two curves; an input
 * a definition lacks becomes the value its Step reads without it. A raise draws the same particles, so only the pool
 * count changes. It raises only once the included definitions' shapes pass the pool budget, the groups saving most
 * pools first.
 *
 * <p>No Unreal counterpart: Niagara dispatches each emitter instance on its own and has no pools to share.</p>
 *
 * <pre>{@code
 * CgVfxShapeCoalescing.loaded().include(look.emitters());   // at READY: what the loaded bundles hold
 * CgVfxShape shape = CgVfxShape.of(emitter, 0);             // raised where its group is
 *
 * CgVfxShapeCoalescing census = new CgVfxShapeCoalescing(0); // a budget of 0 raises every group that saves a pool
 * census.include(move.emitters());
 * census.shape(emitter, 0).key();
 * }</pre>
 *
 * <ul>
 *   <li>Include a definition before its first play: a later include that changes its group's raise gives it another
 *       shape, and so another pool and kernel, from its next slot. {@link #include} answers the definitions so moved,
 *       for their kernels to be warmed.</li>
 *   <li>A word bound to a user parameter is never raised: its value is the instance's, not the definition's.</li>
 *   <li>Without a form, a start colour or mesh scale is rolled between two: it is raised from absence only where the two
 *       agree. A mesh turn and a light are never raised from absence. An orbit so raised stores its zeros in each
 *       particle.</li>
 *   <li>Forms of different widths never share a pool: each width compiles its own code.</li>
 * </ul>
 */
public final class CgVfxShapeCoalescing {

    /**
     * Shapes the loaded bundles may take before their inputs are raised: half the GPU's 1.5 ms particle line, the rest
     * left to the particles themselves, over a pool's fixed GPU cost on Vulkan, about 15.5 us on the reference GPU.
     */
    public static final int POOL_BUDGET = 48;
    private static final CgVfxShapeCoalescing LOADED = new CgVfxShapeCoalescing(CgVar.integer("crystalgraphics.vfx.poolBudget",
            POOL_BUDGET, 0, Integer.MAX_VALUE, CgVar.DEV | CgVar.RESTART,
            "Shapes the loaded bundles may take before coalescing raises their inputs; 0 raises every group that saves a pool, "
                    + "which the form gates run under.").get());
    private static final String BETWEEN = "crystalgraphics:random_range";
    private static final float TURN = 6.2831853f;
    /** Every definition's shape as given, per tier, made once: tables share them. Held weakly. */
    private static final Map<CgVfxGpuEmitter, CgVfxShape[]> BASE = new WeakHashMap<>();
    /** Each definition's raised shapes, by its tier and the choices that raised it: tables share them. Held weakly. */
    private static final Map<CgVfxGpuEmitter, Map<String, CgVfxShape>> RAISED = new WeakHashMap<>();

    private final int budget;
    /** Included definitions by form-free key. */
    private final Map<String, Group> groups = new LinkedHashMap<>();
    /** Each definition's shape per tier as asked: raised where its group is. */
    private final Map<CgVfxGpuEmitter, CgVfxShape[]> shapes = new WeakHashMap<>();
    /** Each included definition per tier. */
    private final Map<CgVfxGpuEmitter, Member[]> members = new WeakHashMap<>();

    /** A table raising past {@code budget} shapes: 0 raises every group that saves one. */
    public CgVfxShapeCoalescing(int budget) {
        this.budget = budget;
    }

    /** The engine's: what assets prepared for play include, and what {@link CgVfxShape#of} answers from. */
    public static CgVfxShapeCoalescing loaded() {
        return LOADED;
    }

    /**
     * Includes {@code emitters} and their events' children at their tiers, raising again where they change a group.
     * Answers the definitions included before whose shapes changed.
     */
    public List<CgVfxGpuEmitter> include(Collection<? extends CgVfxGpuEmitter> emitters) {
        Map<CgVfxGpuEmitter, Integer> roots = new LinkedHashMap<>();
        for (CgVfxGpuEmitter e : emitters) roots.put(e, 0);
        return include(roots);
    }

    /**
     * As {@link #include(Collection)}, each emitter at the feed tier it plays at: past 0 for one born on another
     * emitter's particles, which plays a tier after its source.
     */
    public synchronized List<CgVfxGpuEmitter> include(Map<? extends CgVfxGpuEmitter, Integer> atTiers) {
        Set<Group> touched = new HashSet<>();
        Map<Member, String> before = new HashMap<>();
        for (Map.Entry<? extends CgVfxGpuEmitter, Integer> e : atTiers.entrySet()) include(e.getKey(), e.getValue(), touched, before);
        if (touched.isEmpty()) return List.of();
        Map<Group, Boolean> wasRaised = new HashMap<>();
        for (Group g : groups.values()) wasRaised.put(g, g.raised);
        for (Group g : touched) g.plan();
        choose();
        // Only a group planned again or raised otherwise can move a definition.
        List<CgVfxGpuEmitter> changed = new ArrayList<>();
        for (Group g : groups.values()) {
            boolean flipped = wasRaised.get(g) != g.raised;
            if (!flipped && !touched.contains(g)) continue;
            for (Member m : g.members) {
                shapes.remove(m.emitter);
                String was = before.get(m);
                if (was == null && flipped) was = (wasRaised.get(g) ? m.raised : m.base).key();
                if (was != null && !was.equals(m.shape().key()) && !changed.contains(m.emitter)) changed.add(m.emitter);
            }
        }
        return changed;
    }

    /** {@code emitter}'s shape at {@code tier}: its group's raise where its group is raised, else its own. */
    public synchronized CgVfxShape shape(CgVfxGpuEmitter emitter, int tier) {
        CgVfxShape[] tiers = shapes.get(emitter);
        if (tiers == null) shapes.put(emitter, tiers = new CgVfxShape[CgVfxShape.TIERS]);
        CgVfxShape shape = tiers[tier];
        if (shape != null) return shape;
        Member[] in = members.get(emitter);
        Member m = in == null ? null : in[tier];
        shape = m == null ? base(emitter, tier) : m.shape();
        tiers[tier] = shape;
        return shape;
    }

    /** How many shapes its included definitions take now, raised where raised. */
    public synchronized int shapes() {
        int n = 0;
        for (Group g : groups.values()) n += g.raised ? g.raisedKeys : g.baseKeys;
        return n;
    }

    /**
     * What still divides each group's definitions once raised: per input ({@code form:<module>.<input>},
     * {@code spawn:<value>}, {@code user:<word>}), the classes it still takes, {@code absent} where one lacks it, and
     * how many groups it divides. For a census of what coalescing cannot raise.
     */
    public synchronized Map<String, Integer> splits() {
        Map<String, Integer> splits = new TreeMap<>();
        for (Group g : groups.values()) {
            if (g.raisedKeys < 2) continue;
            Map<String, Set<String>> classes = new TreeMap<>();
            List<Map<String, String>> parts = new ArrayList<>();
            for (Member m : g.members) parts.add(inputs((m.raised != null ? m.raised : m.base).key()));
            for (Map<String, String> p : parts) for (String id : p.keySet()) classes.computeIfAbsent(id, k -> new TreeSet<>());
            for (Map<String, String> p : parts) {
                for (Map.Entry<String, Set<String>> c : classes.entrySet()) c.getValue().add(p.getOrDefault(c.getKey(), "absent"));
            }
            for (Map.Entry<String, Set<String>> c : classes.entrySet()) {
                if (c.getValue().size() > 1) splits.merge(c.getKey().replaceFirst("^form:[0-9]+\\.", "form:") + " " + c.getValue(), 1, Integer::sum);
            }
        }
        return splits;
    }

    /** A key's inputs and their classes. */
    private static Map<String, String> inputs(String key) {
        Map<String, String> out = new HashMap<>();
        for (String part : key.split("\\|")) {
            int colon = part.indexOf(':');
            String kind = colon < 0 ? "" : part.substring(0, colon);
            if (!kind.equals("form") && !kind.equals("spawn") && !kind.equals("user")) continue;
            int depth = 0, from = colon + 1;
            for (int i = from; i <= part.length(); i++) {
                char ch = i < part.length() ? part.charAt(i) : ',';
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                else if (ch == ',' && depth == 0) {
                    String item = part.substring(from, i);
                    int eq = item.indexOf('=');
                    out.put(kind + ":" + (eq < 0 ? item : item.substring(0, eq)), eq < 0 ? "bound" : item.substring(eq + 1));
                    from = i + 1;
                }
            }
        }
        return out;
    }

    /** Includes {@code e} at {@code tier}, noting in {@code before} the shapes of the groups it touches first. */
    private void include(CgVfxGpuEmitter e, int tier, Set<Group> touched, Map<Member, String> before) {
        if (tier >= CgVfxShape.TIERS) return;
        Member[] in = members.get(e);
        if (in == null || in[tier] == null) {
            CgVfxShape base;
            try {
                base = base(e, tier);
            } catch (RuntimeException refused) {
                return;   // its play throws, naming why
            }
            Group g = groups.computeIfAbsent(formFree(base.key()), k -> new Group());
            if (touched.add(g)) for (Member m : g.members) before.put(m, m.shape().key());
            Member m = new Member(e, tier, base, g);
            g.members.add(m);
            if (in == null) members.put(e, in = new Member[CgVfxShape.TIERS]);
            in[tier] = m;
        }
        for (CgVfxEvent ev : e.events()) {
            if (ev.child() != null) include(ev.child(), tier + 1, touched, before);
        }
    }

    /** Raises the groups saving most until the shapes fit the budget. */
    private void choose() {
        int total = 0;
        List<Group> all = new ArrayList<>(groups.values());
        for (Group g : all) {
            g.raised = false;
            total += g.baseKeys;
        }
        all.sort(Comparator.comparingInt((Group g) -> g.raisedKeys - g.baseKeys));
        for (Group g : all) {
            if (total <= budget || g.raisedKeys >= g.baseKeys) break;
            g.raised = true;
            total -= g.baseKeys - g.raisedKeys;
        }
    }

    /** {@code e}'s shape at {@code tier} as its own forms give it. */
    private static CgVfxShape base(CgVfxGpuEmitter e, int tier) {
        synchronized (BASE) {
            CgVfxShape[] tiers = BASE.get(e);
            if (tiers == null) BASE.put(e, tiers = new CgVfxShape[CgVfxShape.TIERS]);
            if (tiers[tier] == null) tiers[tier] = new CgVfxShape(e, tier);
            return tiers[tier];
        }
    }

    /** {@code m}'s shape raised by {@code chosen}, its group's choices, which {@code under} names. */
    private static CgVfxShape raised(Member m, String under, List<Choice> chosen) {
        String key = m.tier + "|" + under;
        synchronized (RAISED) {
            CgVfxShape known = RAISED.computeIfAbsent(m.emitter, k -> new HashMap<>()).get(key);
            if (known != null) return known;
        }
        List<CgVfxModuleForm> moduleForms = new ArrayList<>();
        List<CgVfxSpawnForm> spawnForms = new ArrayList<>();
        for (Choice c : chosen) c.raiseInto(m, moduleForms, spawnForms);
        CgVfxShape shape = new CgVfxShape(m.emitter, m.tier, moduleForms, spawnForms);
        synchronized (RAISED) {
            RAISED.get(m.emitter).put(key, shape);
        }
        return shape;
    }

    /** {@code key} less its inputs' forms: what definitions alike but for how their inputs are given share. */
    static String formFree(String key) {
        StringBuilder out = new StringBuilder();
        for (String part : key.split("\\|")) {
            if (part.startsWith("form:") || part.startsWith("spawn:")) continue;
            if (out.length() > 0) out.append('|');
            out.append(part);
        }
        return out.toString();
    }

    /**
     * A form of the least general class covering every one of {@code forms}, one of each class, or null: one class covers
     * itself; a range rolled once for every channel, curves by one lookup and {@code random_range} of two such curves are
     * all that {@code random_range}. Forms of different widths share nothing.
     */
    static CgVfxFormInput cover(Collection<CgVfxFormInput> forms) {
        CgVfxFormInput first = forms.iterator().next();
        if (forms.size() == 1) return first;
        CgVfxFormInput.Curve by = null;
        CgVfxFormInput between = null;
        for (CgVfxFormInput f : forms) {
            if (f.components() != first.components()) return null;
            CgVfxFormInput.Curve curve;
            if (f instanceof CgVfxFormInput.Range r && !r.perChannel()) {
                continue;
            } else if (f instanceof CgVfxFormInput.Curve c) {
                curve = c;
            } else if (f instanceof CgVfxFormInput.Function fn && fn.fn().equals(BETWEEN) && fn.first() instanceof CgVfxFormInput.Curve a
                    && fn.second() instanceof CgVfxFormInput.Curve b && a.by() == b.by()) {
                curve = a;
                between = f;
            } else {
                return null;
            }
            if (by != null && by.by() != curve.by()) return null;
            by = curve;
        }
        if (by == null) return null;
        return between != null ? between : new CgVfxFormInput.Function(by.name(), 0, BETWEEN, by, by, CgPropertyMeta.Rolls.PARTICLE);
    }

    /** {@code f} given in {@code as}'s class, drawing the same values. */
    static CgVfxFormInput raise(CgVfxFormInput f, CgVfxFormInput as) {
        if (CgVfxShape.formKey(f).equals(CgVfxShape.formKey(as))) return f;
        if (f instanceof CgVfxFormInput.Constant c) return constant(c.name(), c.values(), as);
        if (as instanceof CgVfxFormInput.Function fn && fn.fn().equals(BETWEEN) && fn.first() instanceof CgVfxFormInput.Curve by) {
            if (f instanceof CgVfxFormInput.Range r && !r.perChannel()) {
                // The range's own key and rolls: the same roll picks between the same two values.
                return new CgVfxFormInput.Function(r.name(), r.key(), BETWEEN, flat(r.name() + ".min", r.min(), by),
                        flat(r.name() + ".max", r.max(), by), r.rolls());
            }
            if (f instanceof CgVfxFormInput.Curve curve) {
                return new CgVfxFormInput.Function(curve.name(), 0, BETWEEN, curve, curve, CgPropertyMeta.Rolls.PARTICLE);
            }
        }
        throw new IllegalStateException(f.name() + " in " + CgVfxShape.formKey(f) + " has no raise to " + CgVfxShape.formKey(as));
    }

    /** {@code v} in {@code as}'s class. */
    private static CgVfxFormInput constant(String name, float[] v, CgVfxFormInput as) {
        if (as instanceof CgVfxFormInput.Constant) return new CgVfxFormInput.Constant(name, v);
        if (as instanceof CgVfxFormInput.Range r) return new CgVfxFormInput.Range(name, 0, v, v, r.perChannel(), r.locked(), r.rolls());
        if (as instanceof CgVfxFormInput.Pick p) return new CgVfxFormInput.Pick(name, 0, v, v, p.rolls());
        if (as instanceof CgVfxFormInput.Curve c) return flat(name, v, c);
        if (as instanceof CgVfxFormInput.Array a) {
            return new CgVfxFormInput.Array(name, 0, new float[][]{v}, a.by(), a.interpolate(), a.address());
        }
        if (as instanceof CgVfxFormInput.Binding b && b.path().startsWith("user.")) {
            // A lane holding it: a parameter no instance has, so every instance writes its fallback.
            return new CgVfxFormInput.Binding(name, "user.", 1f, null, null, null, null, new String[]{"direct"}, v.clone());
        }
        if (as instanceof CgVfxFormInput.Function fn) {
            float[] second = fn.fn().equals(BETWEEN) ? v : new float[v.length];
            return new CgVfxFormInput.Function(name, 0, fn.fn(), constant(name + ".a", v, fn.first()),
                    constant(name + ".b", second, fn.second()), fn.rolls());
        }
        throw new IllegalStateException(name + " has no raise to " + CgVfxShape.formKey(as));
    }

    /** A curve holding {@code v} throughout, looked up as {@code like}. */
    private static CgVfxFormInput.Curve flat(String name, float[] v, CgVfxFormInput.Curve like) {
        CgVfxCurveBlock block = new CgVfxCurveBlock();
        CgRichCurve[] channels = new CgRichCurve[v.length];
        for (int c = 0; c < v.length; c++) channels[c] = CgRichCurve.decode(Map.of("default", (double) v[c]));
        return new CgVfxFormInput.Curve(name, like.form(), 0, block, block.add(channels), v.length, like.by());
    }

    /** Whether {@code f} can be raised to {@code as}'s class. */
    private static boolean raises(CgVfxFormInput f, CgVfxFormInput as) {
        try {
            raise(f, as);
            return true;
        } catch (IllegalStateException no) {
            return false;
        }
    }

    /** One definition at one tier: its shape as given, and as its group raises it. */
    private static final class Member {
        final CgVfxGpuEmitter emitter;
        final int tier;
        final CgVfxShape base;
        final Group group;
        CgVfxShape raised;
        /** The group's choices its raised shape was made under: the same choices make the same shape. */
        String raisedUnder;
        private int[] spawnWords;
        private float[] curveRow;

        Member(CgVfxGpuEmitter emitter, int tier, CgVfxShape base, Group group) {
            this.emitter = emitter;
            this.tier = tier;
            this.base = base;
            this.group = group;
        }

        CgVfxShape shape() {
            return group.raised && raised != null ? raised : base;
        }

        /** Its spawn row's words, read once. */
        int[] spawnWords() {
            if (spawnWords == null) {
                spawnWords = new int[CgVfxGpuEmitter.SPAWN_VECTORS * 4];
                CgVfxWords words = new CgVfxWords();
                words.target(spawnWords, 0, CgVfxGpuEmitter.SPAWN_VECTORS, emitter.name());
                emitter.writeSpawn(words);
            }
            return spawnWords;
        }

        /** Its curve row, read once. */
        float[] curveRow() {
            if (curveRow == null) {
                curveRow = new float[4 * (2 * CgVfxGpuEmitter.CURVE_TEXELS + CgVfxGpuEmitter.CURVE_STARTS)];
                emitter.writeCurves(curveRow, 0, CgVfxGpuEmitter.CURVE_TEXELS);
            }
            return curveRow;
        }
    }

    /** Definitions alike but for how their inputs are given. */
    private static final class Group {
        final List<Member> members = new ArrayList<>();
        int baseKeys, raisedKeys;
        boolean raised;

        /** Each member's raised shape and how many shapes the group takes either way. */
        void plan() {
            Set<String> base = new HashSet<>();
            for (Member m : members) base.add(m.base.key());
            baseKeys = base.size();
            // Every input any member gives a form, and each member's value of it: its form, or its Step's without one.
            Map<String, Map<Member, CgVfxFormInput>> inputs = new TreeMap<>();
            Map<String, Object> places = new HashMap<>();
            for (Member m : members) {
                for (int f = 0; f < m.base.forms(); f++) {
                    CgVfxModuleForm form = m.base.form(f);
                    String id = "form:" + form.module() + "." + form.input();
                    inputs.computeIfAbsent(id, k -> new HashMap<>()).put(m, form.compiled());
                    places.putIfAbsent(id, form);
                }
                for (int f = 0; f < m.base.spawnForms(); f++) {
                    CgVfxSpawnForm form = m.base.spawnForm(f);
                    String id = "spawn:" + form.target().name();
                    inputs.computeIfAbsent(id, k -> new HashMap<>()).put(m, form.compiled());
                    places.putIfAbsent(id, form);
                }
            }
            // The group's choice for each input first: forms are made only for a definition with no shape under them.
            List<Choice> chosen = new ArrayList<>();
            StringBuilder choices = new StringBuilder();
            for (Map.Entry<String, Map<Member, CgVfxFormInput>> input : inputs.entrySet()) {
                Object place = places.get(input.getKey());
                Map<Member, CgVfxFormInput> given = new LinkedHashMap<>();
                for (Member m : members) {
                    CgVfxFormInput f = input.getValue().get(m);
                    if (f == null) {
                        f = place instanceof CgVfxModuleForm mf ? absentModule(m, mf)
                                : absentSpawn(m, ((CgVfxSpawnForm) place).target(), ((CgVfxSpawnForm) place).compiled());
                    }
                    if (f != null) given.put(m, f);
                }
                // A constant takes any class, so only the other classes choose the cover.
                Map<String, CgVfxFormInput> classes = new LinkedHashMap<>();
                Map<String, Integer> counts = new HashMap<>();
                for (CgVfxFormInput f : given.values()) {
                    if (f instanceof CgVfxFormInput.Constant) continue;
                    String c = CgVfxShape.formKey(f);
                    classes.putIfAbsent(c, f);
                    counts.merge(c, 1, Integer::sum);
                }
                CgVfxFormInput as = classes.isEmpty() ? null : cover(classes.values());
                // No class covers them all: a constant joins the class most share, where it raises to it.
                String most = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
                choices.append(input.getKey()).append('=').append(as == null ? "-" : CgVfxShape.formKey(as)).append('~')
                        .append(most).append(';');
                chosen.add(new Choice(place, given, as, most == null ? null : classes.get(most)));
            }
            Set<String> raisedSet = new HashSet<>();
            String under = choices.toString();
            for (Member m : members) {
                if (m.raised == null || !under.equals(m.raisedUnder)) {
                    try {
                        m.raised = raised(m, under, chosen);
                    } catch (RuntimeException refused) {
                        m.raised = m.base;
                    }
                    m.raisedUnder = under;
                }
                raisedSet.add(m.raised.key());
            }
            raisedKeys = raisedSet.size();
        }
    }

    /** One input's choice in a group: each definition's value of it, the class covering them, or the class most take. */
    private record Choice(Object place, Map<Member, CgVfxFormInput> given, CgVfxFormInput as, CgVfxFormInput most) {

        /** {@code m}'s value raised by this choice into {@code moduleForms} or {@code spawnForms}; nothing where it has none. */
        void raiseInto(Member m, List<CgVfxModuleForm> moduleForms, List<CgVfxSpawnForm> spawnForms) {
            CgVfxFormInput f = given.get(m);
            if (f == null) return;
            CgVfxFormInput raised = f;
            if (as != null && f.components() == as.components()) {
                raised = raise(f, as);
            } else if (f instanceof CgVfxFormInput.Constant && most != null && raises(f, most)) {
                raised = raise(f, most);
            }
            if (place instanceof CgVfxModuleForm mf) {
                moduleForms.add(new CgVfxModuleForm(mf.module(), mf.input(), mf.value(), raised));
            } else {
                CgVfxSpawnForm sf = (CgVfxSpawnForm) place;
                spawnForms.add(new CgVfxSpawnForm(sf.target(), sf.element(), sf.input(), sf.value(), raised));
            }
        }
    }

    /** Module input {@code like}'s value in {@code m}, which gives it no form: its module's word; null if a user binds it. */
    private static CgVfxFormInput absentModule(Member m, CgVfxModuleForm like) {
        int components = like.compiled().components();
        float[] v = new float[components];
        int[] words = m.emitter.moduleWords();
        for (int k = 0; k < components; k++) {
            int word;
            try {
                word = m.base.paramAt(like.module()) * 4
                        + CgVfxModuleTypes.of(m.emitter.modules().get(like.module())).words().lane(like.input(), k);
            } catch (RuntimeException none) {
                return null;
            }
            if (bound(m.base, word)) return null;
            v[k] = Float.intBitsToFloat(words[word - CgVfxGpuEmitter.SPAWN_VECTORS * 4]);
        }
        return new CgVfxFormInput.Constant(like.compiled().name(), v);
    }

    /**
     * Spawn value {@code target}'s value in {@code m}, which gives it no form, as wide as {@code like}, another's form of
     * it: what the Step's launch and the Look stage read without one, or null where no form is sure to draw the same.
     */
    private static CgVfxFormInput absentSpawn(Member m, CgVfxSpawnInput target, CgVfxFormInput like) {
        CgVfxFormInput absent = target == CgVfxSpawnInput.COLOR || target == CgVfxSpawnInput.MESH_SCALE
                ? start(m, target, like.name(), like.components()) : absentSpawn(m, target, like.name());
        return absent != null && absent.components() == like.components() ? absent : null;
    }

    private static CgVfxFormInput absentSpawn(Member m, CgVfxSpawnInput target, String name) {
        int[] s = m.spawnWords();
        int[] keys = m.emitter.rollKeys();
        return switch (target) {
            case SPEED -> range(m, name, keys[CgVfxGpuEmitter.KEY_SPEED], s, 0);
            case LIFETIME -> range(m, name, keys[CgVfxGpuEmitter.KEY_LIFE], s, 2);
            case SPRITE_SIZE -> range(m, name, keys[CgVfxGpuEmitter.KEY_SIZE], s, 4);
            case SPRITE_ROTATION -> new CgVfxFormInput.Range(name, keys[CgVfxGpuEmitter.KEY_ROTATION], new float[]{0f},
                    new float[]{TURN}, false, null, CgPropertyMeta.Rolls.PARTICLE);
            // Added to its velocity, or an orbit of no offset, turn or rate: nothing moves.
            case VELOCITY, ORBIT_OFFSET, ORBIT_ROTATION, ORBIT_RATE -> new CgVfxFormInput.Constant(name, new float[3]);
            case RADIUS -> words(m, name, s, 1f, 29);
            case OFFSET -> words(m, name, s, 1f, 36, 37, 38);
            // A form there is halved: the row's word is the half already.
            case CONE_ANGLE -> words(m, name, s, 2f, 17);
            case CYLINDER_HEIGHT -> words(m, name, s, 2f, 30);
            default -> null;
        };
    }

    /**
     * The first {@code n} channels of a start colour or mesh scale where the row's two starts it is rolled between agree
     * in them: the Look stage's roll then changes nothing there, so a stored start of that value draws the same. An rgb
     * colour leaves its alpha to that roll.
     */
    private static CgVfxFormInput start(Member m, CgVfxSpawnInput target, String name, int n) {
        float[] row = m.curveRow();
        int lo = 8 * CgVfxGpuEmitter.CURVE_TEXELS + (target == CgVfxSpawnInput.COLOR ? 0 : 8);
        if (n > (target == CgVfxSpawnInput.COLOR ? 4 : 3)) return null;
        float[] v = new float[n];
        for (int c = 0; c < n; c++) {
            if (row[lo + c] != row[lo + 4 + c]) return null;
            v[c] = row[lo + c];
        }
        return new CgVfxFormInput.Constant(name, v);
    }

    private static CgVfxFormInput range(Member m, String name, int key, int[] s, int word) {
        if (bound(m.base, word) || bound(m.base, word + 1)) return null;
        return new CgVfxFormInput.Range(name, key, new float[]{Float.intBitsToFloat(s[word])},
                new float[]{Float.intBitsToFloat(s[word + 1])}, false, null, CgPropertyMeta.Rolls.PARTICLE);
    }

    private static CgVfxFormInput words(Member m, String name, int[] s, float times, int... at) {
        float[] v = new float[at.length];
        for (int k = 0; k < at.length; k++) {
            if (bound(m.base, at[k])) return null;
            v[k] = times * Float.intBitsToFloat(s[at[k]]);
        }
        return new CgVfxFormInput.Constant(name, v);
    }

    /** Whether a user parameter fills row word {@code word}. */
    private static boolean bound(CgVfxShape shape, int word) {
        for (int b = 0; b < shape.userBindings(); b++) if (shape.userWord(b) == word) return true;
        return false;
    }
}
