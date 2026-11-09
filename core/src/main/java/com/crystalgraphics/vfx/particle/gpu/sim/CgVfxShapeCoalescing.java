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
import com.crystalgraphics.vfx.particle.gpu.CgVfxDataInterface;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxLane;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;

import java.util.ArrayList;
import java.util.Arrays;
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
 * <p>Then, always, it stacks: a shape whose modules are, in order, some of a wider shape's, the rest of its key alike,
 * runs in the widest such shape's kernel and pool, its parameter row's mask switching off the modules it lacks
 * ({@link CgVfxShape#stacked}). Niagara's lightweight emitters are the model: one shader for every one of them, each module
 * always run and doing nothing where the emitter's parameters leave it off. It draws the same particles
 * ({@code --mode=vfx-stack}), and each stack is one kernel to compile and one pool a frame instead of several.</p>
 *
 * <p>Last, every definition sharing a pool places by all their spawn shapes ({@link CgVfxShape#placeKinds}), its Step
 * compiling only those: Niagara's Shape Location takes its shape as a static switch.</p>
 *
 * <p>Raising has no Unreal counterpart: Niagara dispatches each stateful emitter instance on its own and has no pools to
 * share.</p>
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
 *   <li>Include a definition before its first play, and a move's together: a later include that changes its group's raise
 *       or its stack gives it another shape, and so another pool and kernel, from its next slot. {@link #include} answers
 *       the definitions so moved, for their kernels to be warmed. One never included plays its own shape.</li>
 *   <li>A module it lacks may sample textures (its slot takes no entry) but never be a beam's.</li>
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
    private static final CgVar.Bool STACK = CgVar.bool("crystalgraphics.vfx.stackShapes", true, CgVar.DEV | CgVar.RESTART,
            "Shapes whose modules are some of a wider shape's run in its kernel and pool, what they lack switched off; false "
                    + "keeps every shape its own, to compare.");
    private static final String BETWEEN = "crystalgraphics:random_range";
    private static final float TURN = 6.2831853f;
    /** Every definition's shape as given, per tier, made once: tables share them. Held weakly. */
    private static final Map<CgVfxGpuEmitter, CgVfxShape[]> BASE = new WeakHashMap<>();
    /** Each definition's raised shapes, by its tier and the choices that raised it: tables share them. Held weakly. */
    private static final Map<CgVfxGpuEmitter, Map<String, CgVfxShape>> RAISED = new WeakHashMap<>();
    /** Each definition's stacked shapes, by what it was stacked from and into. Held weakly. */
    private static final Map<CgVfxGpuEmitter, Map<String, CgVfxShape>> STACKED = new WeakHashMap<>();

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
        Map<Member, String> before = new HashMap<>();
        for (Group g : groups.values()) for (Member m : g.members) before.put(m, m.shape().key());
        Set<Group> touched = new HashSet<>();
        for (Map.Entry<? extends CgVfxGpuEmitter, Integer> e : atTiers.entrySet()) include(e.getKey(), e.getValue(), touched);
        if (touched.isEmpty()) return List.of();
        for (Group g : touched) g.plan();
        choose();
        if (STACK.get()) stack();
        place();
        shapes.clear();
        List<CgVfxGpuEmitter> changed = new ArrayList<>();
        for (Map.Entry<Member, String> was : before.entrySet()) {
            Member m = was.getKey();
            if (!was.getValue().equals(m.shape().key()) && !changed.contains(m.emitter)) changed.add(m.emitter);
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

    /** Includes {@code e} at {@code tier} and its events' children, noting the groups it adds to. */
    private void include(CgVfxGpuEmitter e, int tier, Set<Group> touched) {
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
            touched.add(g);
            Member m = new Member(e, tier, base, g);
            g.members.add(m);
            if (in == null) members.put(e, in = new Member[CgVfxShape.TIERS]);
            in[tier] = m;
        }
        for (CgVfxEvent ev : e.events()) {
            if (ev.child() != null) include(ev.child(), tier + 1, touched);
        }
    }

    /**
     * Stacks shapes into wider ones, Niagara's lightweight emitters' rule (one shader, a module an emitter lacks off by
     * its parameters): a shape whose modules are, in order, some of another's and the rest of whose key is that one's
     * runs in the widest such shape's kernel and pool, its row's mask switching off what it lacks. Each tier apart.
     */
    private void stack() {
        Map<String, List<Member>> byKey = new LinkedHashMap<>();
        for (Group g : groups.values()) {
            for (Member m : g.members) {
                m.stacked = null;
                m.stack = null;
                // Alike but for their spawn kinds, which join after (place).
                byKey.computeIfAbsent(CgVfxShape.unplaced(m.unstacked().key()), k -> new ArrayList<>()).add(m);
            }
        }
        Map<String, String[]> sigs = new HashMap<>();
        Map<String, String> rests = new HashMap<>();
        for (Map.Entry<String, List<Member>> k : byKey.entrySet()) {
            CgVfxShape s = k.getValue().get(0).unstacked();
            String[] sig = new String[s.modules()];
            for (int i = 0; i < sig.length; i++) sig[i] = signature(s, i);
            sigs.put(k.getKey(), sig);
            rests.put(k.getKey(), rest(s));
        }
        // Each key's widest container, if any.
        Map<String, List<String>> under = new LinkedHashMap<>();
        for (String k : byKey.keySet()) {
            String target = null;
            for (String t : byKey.keySet()) {
                if (t.equals(k) || !rests.get(t).equals(rests.get(k)) || sigs.get(t).length <= sigs.get(k).length) continue;
                if (placement(sigs.get(k), sigs.get(t)) == null) continue;
                if (target == null || sigs.get(t).length > sigs.get(target).length) target = t;
            }
            if (target != null) under.computeIfAbsent(target, x -> new ArrayList<>()).add(k);
        }
        // A widest container is under none: anything wider holding it would hold what it holds, and be chosen first.
        for (Map.Entry<String, List<String>> e : under.entrySet()) {
            String target = e.getKey();
            Member owner = byKey.get(target).get(0);
            List<? extends CgVfxGpuModule> prototypes = owner.emitter.modules();
            String[] wide = sigs.get(target);
            CgVfxShape ownerStacked = stacked(owner, prototypes, placement(wide, wide));
            if (ownerStacked == null) continue;
            List<String> keys = new ArrayList<>(e.getValue());
            keys.add(0, target);
            int joined = 0;
            for (String k : keys) {
                int[] at = placement(sigs.get(k), wide);
                if (at == null) continue;
                for (Member m : byKey.get(k)) {
                    CgVfxShape s = stacked(m, prototypes, at);
                    if (s == null || !CgVfxShape.unplaced(s.key()).equals(CgVfxShape.unplaced(ownerStacked.key()))) continue;
                    m.stacked = s;
                    m.stack = new CgVfxShape.Stack(prototypes, at);
                    if (!k.equals(target)) joined++;
                }
            }
            // Nothing joined: the owner keeps its own shape, no mask to read.
            if (joined == 0) {
                for (Member m : byKey.get(target)) {
                    m.stacked = null;
                    m.stack = null;
                }
            }
        }
    }

    /**
     * Every definition sharing a pool places by the spawn kinds of them all, and its Step compiles only those: Niagara's
     * Shape Location takes its shape as a static switch, each emitter compiling its own. Its table holds as many sets of
     * a textured kind's textures as they sample, the Step compiling the kind once a set: Niagara binds each emitter's
     * textures per dispatch, one copy of the module.
     */
    private void place() {
        Map<String, Integer> unions = new HashMap<>();
        Map<String, List<Set<List<Integer>>>> sets = new HashMap<>();
        for (Group g : groups.values()) {
            for (Member m : g.members) {
                CgVfxShape s = m.unplacedShape();
                String key = CgVfxShape.unplaced(s.key());
                unions.merge(key, s.ownPlaces(), (a, b) -> a | b);
                List<Set<List<Integer>>> perModule = sets.computeIfAbsent(key, k -> new ArrayList<>());
                for (int i = 0; i < s.modules(); i++) {
                    while (perModule.size() <= i) perModule.add(new HashSet<>());
                    if (s.textureCount(i) == 0 || s.ownModule(i) < 0) continue;   // a module it lacks takes no entry
                    List<Integer> textures = new ArrayList<>();
                    for (int t = 0; t < s.textureCount(i); t++) textures.add(System.identityHashCode(s.texture(i, t)));
                    perModule.get(i).add(textures);
                }
            }
        }
        for (Group g : groups.values()) {
            for (Member m : g.members) {
                m.placed = null;
                CgVfxShape s = m.unplacedShape();
                String key = CgVfxShape.unplaced(s.key());
                int union = unions.get(key), textureSets = 1;
                for (Set<List<Integer>> used : sets.get(key)) textureSets = Math.max(textureSets, used.size());
                if (union != s.placeKinds() || textureSets != s.textureSets()) m.placed = built(m, m.stack, union, textureSets);
            }
        }
    }

    /** {@code m}'s shape over {@code prototypes}, its modules at {@code at}; cached; null where it cannot be. */
    private static CgVfxShape stacked(Member m, List<? extends CgVfxGpuModule> prototypes, int[] at) {
        return built(m, new CgVfxShape.Stack(prototypes, at), 0, 0);
    }

    /** {@code m}'s shape, its forms as raised, over {@code stack} where given, placing by {@code places} too; cached; null where it cannot be. */
    private static CgVfxShape built(Member m, CgVfxShape.Stack stack, int places, int textureSets) {
        CgVfxShape from = m.unstacked();
        String key = m.tier + "|" + from.key() + "|" + (stack == null ? "-" : Arrays.toString(stack.at()) + "|"
                + System.identityHashCode(stack.modules())) + "|" + places + "|" + textureSets;
        synchronized (STACKED) {
            CgVfxShape known = STACKED.computeIfAbsent(m.emitter, k -> new HashMap<>()).get(key);
            if (known != null) return known;
        }
        List<CgVfxModuleForm> forms = new ArrayList<>();
        for (int f = 0; f < from.forms(); f++) forms.add(from.form(f));
        List<CgVfxSpawnForm> spawns = new ArrayList<>();
        for (int f = 0; f < from.spawnForms(); f++) spawns.add(from.spawnForm(f));
        CgVfxShape shape;
        try {
            shape = new CgVfxShape(m.emitter, m.tier, forms, spawns, stack, places, textureSets);
        } catch (RuntimeException refused) {
            return null;
        }
        synchronized (STACKED) {
            STACKED.get(m.emitter).put(key, shape);
        }
        return shape;
    }

    /** Where each of {@code some} lies in {@code all}, in order; null where they are not, in order, among them. */
    private static int[] placement(String[] some, String[] all) {
        int[] at = new int[some.length];
        int j = 0;
        for (int i = 0; i < some.length; i++) {
            while (j < all.length && !all[j].equals(some[i])) j++;
            if (j == all.length) return null;
            at[i] = j++;
        }
        return at;
    }

    /** Module {@code i}'s part of {@code s}'s key: what a module in its place must be for one kernel to run both. */
    private static String signature(CgVfxShape s, int i) {
        StringBuilder b = new StringBuilder(s.kind(i)).append(s.afterSolve(i) ? '>' : ':').append(s.paramVectors(i));
        for (CgVfxLane l : s.lanes(i)) b.append(',').append(l.name());
        for (CgVfxDataInterface di : s.dataInterfaces(i)) b.append('@').append(di.id());
        for (int t = 0; t < s.textureCount(i); t++) b.append('#').append(s.volume(i, t) ? "3d" : "2d");
        return b.toString();
    }

    /** {@code s}'s key less its modules and what names their places: what a stack must share besides them. */
    private static String rest(CgVfxShape s) {
        String[] parts = s.key().split("\\|");
        StringBuilder out = new StringBuilder(parts[0]);
        for (int p = 1 + s.modules(); p < parts.length; p++) {
            if (parts[p].startsWith("form:") || parts[p].startsWith("user:") || parts[p].startsWith("tex:") || parts[p].startsWith("place:")) continue;
            out.append('|').append(parts[p]);
        }
        return out.toString();
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
            if (part.startsWith("form:") || part.startsWith("spawn:") || part.startsWith("tex:") || part.startsWith("place:")) continue;
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
        return between != null ? between : new CgVfxFormInput.Function(by.name(), 0, BETWEEN, by, by, CgPropertyMeta.Rolls.PARTICLE, false, null);
    }

    /** {@code f} given in {@code as}'s class, drawing the same values. */
    static CgVfxFormInput raise(CgVfxFormInput f, CgVfxFormInput as) {
        if (CgVfxShape.formKey(f).equals(CgVfxShape.formKey(as))) return f;
        if (f instanceof CgVfxFormInput.Constant c) return constant(c.name(), c.values(), as);
        if (as instanceof CgVfxFormInput.Function fn && fn.fn().equals(BETWEEN) && fn.first() instanceof CgVfxFormInput.Curve by) {
            if (f instanceof CgVfxFormInput.Range r && !r.perChannel()) {
                // The range's own key and rolls: the same roll picks between the same two values.
                return new CgVfxFormInput.Function(r.name(), r.key(), BETWEEN, flat(r.name() + ".min", r.min(), by),
                        flat(r.name() + ".max", r.max(), by), r.rolls(), false, null);
            }
            if (f instanceof CgVfxFormInput.Curve curve) {
                return new CgVfxFormInput.Function(curve.name(), 0, BETWEEN, curve, curve, CgPropertyMeta.Rolls.PARTICLE, false, null);
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
            int[] own = new int[v.length];
            for (int c = 0; c < own.length; c++) own[c] = c;
            return new CgVfxFormInput.Binding(name, "user.", 1f, null, null, null, null, new String[]{"direct"}, v.clone(), own);
        }
        if (as instanceof CgVfxFormInput.Function fn) {
            float[] second = fn.fn().equals(BETWEEN) ? v : new float[v.length];
            return new CgVfxFormInput.Function(name, 0, fn.fn(), constant(name + ".a", v, fn.first()),
                    constant(name + ".b", second, fn.second()), fn.rolls(), fn.perChannel(), fn.locked());
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
        /** Its shape stacked into a wider one ({@link #stack}) and the stack, or null; placing by its pool's spawn kinds ({@link #place}), or null. */
        CgVfxShape stacked, placed;
        CgVfxShape.Stack stack;
        private int[] spawnWords;
        private float[] curveRow;

        Member(CgVfxGpuEmitter emitter, int tier, CgVfxShape base, Group group) {
            this.emitter = emitter;
            this.tier = tier;
            this.base = base;
            this.group = group;
        }

        CgVfxShape shape() {
            return placed != null ? placed : unplacedShape();
        }

        /** Its shape stacked where stacked, before its pool's spawn kinds join. */
        CgVfxShape unplacedShape() {
            return stacked != null ? stacked : unstacked();
        }

        /** Its shape raised where its group is, before any stacking. */
        CgVfxShape unstacked() {
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
            for (Member m : members) base.add(CgVfxShape.unplaced(m.base.key()));
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
                raisedSet.add(CgVfxShape.unplaced(m.raised.key()));
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
