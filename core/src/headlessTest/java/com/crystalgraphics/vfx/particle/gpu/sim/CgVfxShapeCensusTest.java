package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetReferences;
import com.crystalgraphics.asset.load.CgAssetLoad;
import com.crystalgraphics.asset.load.CgBuiltInCompilers;
import com.crystalgraphics.asset.load.CgAssetPipeline;
import com.crystalgraphics.asset.load.CgAssetState;
import com.crystalgraphics.asset.registry.CgAssetEntry;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.system.CgVfxSystemAssets;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The kits' pool census: for each move (a sequence, with the systems its references reach), how many shapes its systems' emitters
 * take at every feed tier: as given, as {@link CgVfxShapeCoalescing} raises them, with the inputs' form classes set aside
 * (the least raising could reach) and with module stacks set aside too. Writes {@code build/kit-gates/shape-census.txt}.
 *
 * <p>Runs over {@code -Pkits=<namespace>=<dir>;...}; {@code -Dcrystalgraphics.census.packs=<text>,...} keeps the
 * sequences whose id contains one, for a timed slice; {@code -Dcrystalgraphics.census.systems=<text>,...} adds the systems
 * whose id contains one, moves or not. Skipped without kits.</p>
 */
public class CgVfxShapeCensusTest {

    /** What a stand-in material draws with: its usages are what a renderer checks, not its shader. */
    private static final String STUB_SHADER = "crystalgraphics:shaders/vfx/particle/sprite.shader";

    @Test
    public void shapesPerMove() throws Exception {
        String kits = System.getProperty("crystalgraphics.kits");
        Assume.assumeTrue("no -Pkits: the census did not run", kits != null && !kits.isEmpty());
        String packs = System.getProperty("crystalgraphics.census.packs", "");
        String systemsWanted = System.getProperty("crystalgraphics.census.systems", "");
        boolean all = packs.equals("*");
        long began = System.nanoTime();
        CgAssetKinds.register();
        // Materials compile to definitions, as the engine's init does; with -PcensusMaterials=false they stay raw, so an
        // emitter's modules all compile before its renderer fails on its material, which the report then names apart.
        // With -PcensusMaterials=stub each compiles to a stand-in drawing every particle usage, so every emitter compiles whole
        // and the report names what the engine lacks, past the materials not ported yet.
        String materialMode = System.getProperty("crystalgraphics.census.materials", "true");
        boolean materials = !"false".equals(materialMode);
        if (materials) CgBuiltInCompilers.register();
        if (materialMode.equals("stub")) {
            CgAssetCompiler.register(CgLookKinds.MATERIAL, (asset, deps) -> CgMaterialDefinition.of(asset.id(), STUB_SHADER)
                    .usage(CgMaterialUsage.SPRITES, CgMaterialUsage.MESH_PARTICLES, CgMaterialUsage.RIBBONS).build());
        }
        CgVfxSystemAssets.register();
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        List<String> namespaces = new ArrayList<>();
        for (String kit : kits.split(";")) {
            String[] nd = kit.split("=", 2);
            registry.scan(nd[0], Paths.get(nd[1]), CgAssetSource.DEV);
            namespaces.add(nd[0]);
        }
        // A move is a sequence; its systems are what its references reach, through cues and the rest, not other sequences.
        Map<String, Set<String>> moveSystems = new TreeMap<>();
        Set<String> wanted = new HashSet<>();
        for (String ns : namespaces) {
            for (CgAssetEntry e : registry.entries(ns)) {
                if (e.kind() != CgSequenceKinds.SEQUENCE) continue;
                if (!packs.isEmpty() && !all && !matches(e.id(), packs)) continue;
                Set<String> systems = reach(registry, e);
                if (systems.isEmpty()) continue;
                moveSystems.put(e.id(), systems);
                wanted.addAll(systems);
            }
        }
        if (!systemsWanted.isEmpty()) {
            for (String ns : namespaces) {
                for (CgAssetEntry e : registry.entries(ns)) {
                    if (e.kind() == CgParticleKinds.SYSTEM && matches(e.id(), systemsWanted)) wanted.add(e.id());
                }
            }
        }
        if (all) {
            for (String ns : namespaces) {
                for (CgAssetEntry e : registry.entries(ns)) if (e.kind() == CgParticleKinds.SYSTEM) wanted.add(e.id());
            }
        }
        List<String> ids = new ArrayList<>(wanted);
        java.util.Collections.sort(ids);
        StringBuilder failures = new StringBuilder();
        Map<String, Map<String, Integer>> detail = new TreeMap<>();
        Map<String, Set<String>> systemFull = new HashMap<>(), systemCoalesced = new HashMap<>(), systemUnion = new HashMap<>();
        Map<String, List<CgVfxEmitter>> systemEmitters = new HashMap<>();
        Map<String, Integer> splitBy = new TreeMap<>();
        int failed = 0;
        Map<String, Integer> why = new TreeMap<>();
        for (int from = 0; from < ids.size(); from += 500) {
            CgAssetPipeline pipeline = new CgAssetPipeline(registry, CgJobLoop.create("census"));
            List<CgAssetLoad> asked = new ArrayList<>();
            for (String id : ids.subList(from, Math.min(ids.size(), from + 500))) asked.add(pipeline.request(CgParticleKinds.SYSTEM, id));
            while (!pipeline.idle()) {
                pipeline.drain();
                Thread.sleep(1);
            }
            pipeline.drain();
            for (int i = 0; i < asked.size(); i++) {
                CgAssetLoad l = asked.get(i);
                if (l.state() != CgAssetState.COMPILED || !(l.definition() instanceof CgVfxSystemDefinition d)) {
                    failed++;
                    String raw = String.valueOf(l.failure());
                    String cause = cause(raw);
                    why.merge(cause, 1, Integer::sum);
                    String tail = raw.substring(raw.lastIndexOf("): ") < 0 ? 0 : raw.lastIndexOf("): ") + 3);
                    detail.computeIfAbsent(cause, k -> new HashMap<>()).merge(tail.substring(0, Math.min(140, tail.length())), 1, Integer::sum);
                    failures.append(ids.get(from + i)).append('\t').append(raw.replace('\n', ' ')).append('\n');
                    continue;
                }
                Set<String> f = new HashSet<>(), c = new HashSet<>(), u = new HashSet<>();
                for (CgVfxEmitter e : d.emitters()) shapes(e, 0, f, c, u, splitBy);
                systemFull.put(ids.get(from + i), f);
                systemCoalesced.put(ids.get(from + i), c);
                systemUnion.put(ids.get(from + i), u);
                systemEmitters.put(ids.get(from + i), d.emitters());
            }
            System.out.printf("[census] %d of %d systems, %.0f s%n", Math.min(ids.size(), from + 500), ids.size(),
                    (System.nanoTime() - began) / 1e9);
        }
        Map<String, Set<String>> full = new TreeMap<>(), coalesced = new TreeMap<>(), union = new TreeMap<>();
        Set<String> kitFull = new HashSet<>(), kitCoalesced = new HashSet<>(), kitUnion = new HashSet<>();
        for (Map.Entry<String, Set<String>> m : moveSystems.entrySet()) {
            Set<String> f = new HashSet<>(), c = new HashSet<>(), u = new HashSet<>();
            for (String sys : m.getValue()) {
                if (systemFull.containsKey(sys)) f.addAll(systemFull.get(sys));
                if (systemCoalesced.containsKey(sys)) c.addAll(systemCoalesced.get(sys));
                if (systemUnion.containsKey(sys)) u.addAll(systemUnion.get(sys));
            }
            full.put(m.getKey(), f);
            coalesced.put(m.getKey(), c);
            union.put(m.getKey(), u);
        }
        for (Set<String> s : full.values()) kitFull.addAll(s);
        for (Set<String> s : coalesced.values()) kitCoalesced.addAll(s);
        for (Set<String> s : union.values()) kitUnion.addAll(s);
        // Raised as the engine raises them: each move on its own, then every system the kits hold together.
        Map<String, Integer> raised = new TreeMap<>();
        CgVfxShapeCoalescing kit = new CgVfxShapeCoalescing(0);
        for (Map.Entry<String, Set<String>> m : moveSystems.entrySet()) {
            CgVfxShapeCoalescing move = new CgVfxShapeCoalescing(0);
            for (String sys : m.getValue()) if (systemEmitters.containsKey(sys)) move.include(systemEmitters.get(sys));
            raised.put(m.getKey(), move.shapes());
        }
        for (List<CgVfxEmitter> emitters : systemEmitters.values()) kit.include(emitters);
        int kitRaised = kit.shapes();
        List<Map.Entry<String, Set<String>>> worst = new ArrayList<>(full.entrySet());
        worst.sort((a, b) -> b.getValue().size() - a.getValue().size());
        StringBuilder report = new StringBuilder();
        report.append(String.format("%d systems over %d moves (sequences); %d failed to compile%s%n", ids.size(), full.size(), failed,
                materialMode.equals("stub") ? " (materials stood in for)" : materials ? "" : " (materials uncompiled: \"not a material\" means its emitter compiled up to its renderers)"));
        why.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(40).forEach(e -> {
            report.append(String.format("  failed %5d  %s%n", e.getValue(), e.getKey()));
            detail.get(e.getKey()).entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(6)
                    .forEach(d -> report.append(String.format("           %5d  %s%n", d.getValue(), d.getKey())));
        });
        report.append(String.format("kit: %d shapes, %d raised, %d with form classes set aside, %d with module stacks too%n",
                kitFull.size(), kitRaised, kitCoalesced.size(), kitUnion.size()));
        report.append("moves by shapes (shapes, raised, without form classes, without module stacks too):\n");
        for (int i = 0; i < Math.min(25, worst.size()); i++) {
            String move = worst.get(i).getKey();
            report.append(String.format("  %-28s %4d %4d %4d %4d%n", move, worst.get(i).getValue().size(),
                    raised.get(move), coalesced.get(move).size(), union.get(move).size()));
        }
        report.append("inputs still dividing a group once raised (kit-wide, groups):\n");
        kit.splits().entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(30)
                .forEach(e -> report.append(String.format("  %6d  %s%n", e.getValue(), e.getKey())));
        report.append("what splits a shape from its form-free key (key parts):\n");
        splitBy.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(20)
                .forEach(e -> report.append(String.format("  %6d  %s%n", e.getValue(), e.getKey())));
        report.append(String.format("%.0f s%n", (System.nanoTime() - began) / 1e9));
        Path out = Paths.get("build", "kit-gates", "shape-census.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(out.resolveSibling("census-failures.txt"), failures.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println(report);
    }

    /** {@code e}'s shape at {@code tier} and its events' children's at the tiers below, each key into {@code full}. */
    private static void shapes(CgVfxEmitter e, int tier, Set<String> full, Set<String> coalesced, Set<String> union,
                               Map<String, Integer> splitBy) {
        if (tier >= CgVfxShape.TIERS) return;
        String key;
        try {
            key = CgVfxShape.of(e, tier).key();
        } catch (RuntimeException refused) {
            return;
        }
        String stripped = stripForms(key);
        if (full.add(key) && !stripped.equals(key)) {
            for (String part : key.split("\\|")) {
                if (part.startsWith("form:") || part.startsWith("spawn:") || part.startsWith("user:")) {
                    splitBy.merge(part.substring(0, part.indexOf(':')), 1, Integer::sum);
                }
            }
        }
        coalesced.add(stripped);
        union.add(stripModules(stripped));
        for (CgVfxEvent ev : e.events()) {
            if (ev.child() instanceof CgVfxEmitter child) shapes(child, tier + 1, full, coalesced, union, splitBy);
        }
    }

    /** {@code key} without its module stack: the floor a pool merging its definitions' modules could reach. */
    private static String stripModules(String key) {
        StringBuilder out = new StringBuilder();
        for (String part : key.split("\\|")) {
            boolean module = part.matches("[a-z0-9_]+[:>][0-9]+.*") && !part.startsWith("drawn:") && !part.startsWith("beam:");
            if (!module) out.append(out.length() == 0 ? "" : "|").append(part);
        }
        return out.toString();
    }

    /**
     * A failure's cause, without the system, emitter and names that differ: the message after its last element's
     * {@code (Name): }, its leading {@code Name: } dropped, ids and numbers set aside.
     */
    static String cause(String raw) {
        String m = raw.substring(raw.lastIndexOf("): ") < 0 ? 0 : raw.lastIndexOf("): ") + 3);
        m = m.replaceFirst("^[A-Za-z0-9_ .-]+: ", "");
        m = m.replaceAll("[a-z0-9_]+:[^ ,}\\]]+", "<id>").replaceAll("-?[0-9]+(\\.[0-9]+)?(E-?[0-9]+)?", "n");
        m = m.replaceAll("\\{[^{}]*\\}", "{..}");
        return m.substring(0, Math.min(110, m.length()));
    }

    /** {@code key} less its inputs' form classes and user bindings: what coalescing would raise to share a pool. */
    static String stripForms(String key) {
        StringBuilder out = new StringBuilder();
        for (String part : key.split("\\|")) {
            if (part.startsWith("form:") || part.startsWith("spawn:") || part.startsWith("user:")) continue;
            if (out.length() > 0) out.append('|');
            out.append(part);
        }
        return out.toString();
    }

    /** The systems {@code from}'s references reach, breadth first, through every kind but other sequences. */
    private static Set<String> reach(CgAssetRegistry registry, CgAssetEntry from) {
        Set<String> systems = new HashSet<>(), seen = new HashSet<>();
        ArrayDeque<CgAssetEntry> queue = new ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            for (CgAssetReferences.Reference r : queue.poll().references()) {
                if (r.kind() == CgSequenceKinds.SEQUENCE || !seen.add(r.kind().id() + "|" + r.id())) continue;
                if (r.kind() == CgParticleKinds.SYSTEM) systems.add(r.id());
                CgAssetEntry next = registry.entry(r.kind(), r.id());
                if (next != null) queue.add(next);
            }
        }
        return systems;
    }

    private static boolean matches(String id, String prefixes) {
        for (String p : prefixes.split(",")) if (id.contains(p)) return true;
        return false;
    }
}
