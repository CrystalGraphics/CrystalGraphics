package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;

import javax.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An event handler as an emitter file declares it ({@code FNiagaraEventScriptProperties}): what spawns {@code spawn}'s
 * particles when handle {@code source}'s particles do something. Its system resolves both handles and puts the
 * {@link CgVfxEvent} it compiles to on the source ({@code CgVfxSystemAssets}).
 *
 * <pre>{@code
 * # in an emitter of vfx/systems/sparks.yaml
 * events:
 *   - { id: e1, trigger: collision, source: h1, spawn: { emitter: h4, count: { range: [3, 5] }, inherit_velocity: [0.3, 0.3, 0.3] }, max_per_frame: 64 }
 * }</pre>
 *
 * Cascade's receiver names its events instead, and spawns into its own emitter: every handle whose
 * {@link CgVfxEventGenerator} raises that trigger under that name feeds it.
 *
 * <pre>{@code
 * # on the emitter that spawns; its source carries generate_event { type: death, name: big_First }
 * events:
 *   - { id: e2, trigger: death, name: big_First, spawn: { count: { range: [5, 15] } } }
 * }</pre>
 *
 * <pre>{@code
 * CgVfxEventHandler h = CgVfxEventHandler.of("sparks", entry);   // as the reader parses one
 * CgVfxEvent onSource = h.compile(chips);                         // its source's event, spawning the resolved handle
 * }</pre>
 *
 * <ul>
 *   <li>{@code kill} is refused, naming the emitter: no engine path runs it.</li>
 *   <li>A {@code name} with a {@code source} still needs that source to raise it; a name nothing raises spawns nothing,
 *       as Cascade's receiver does.</li>
 *   <li>A count is a number or a {@code range}; a curve is refused. A count of 0 spawns nothing, as Cascade's receiver
 *       rounds it (ParticleModules_Event.cpp:346-380), so its system puts no event on any source. {@code use_particle_time} reads a count over time,
 *       so with neither it changes nothing.</li>
 *   <li>{@code inherit_velocity} is a share per axis, Cascade's {@code InheritVelocityScale}.</li>
 * </ul>
 */
public record CgVfxEventHandler(String id, String trigger, @Nullable String source, @Nullable String name,
                                @Nullable String spawn, float countMin, float countMax, boolean rounded, List<Float> inherit,
                                boolean atEmitter, int maxPerFrame) {

    public CgVfxEventHandler {
        CgVfxEvent.ofSpec(trigger);
        if (!(countMin >= 0f) || !(countMax >= countMin) || Math.round(countMax) > CgVfxEvent.MAX_CHILDREN) {
            throw new IllegalArgumentException("event " + id + ": " + countMin + " to " + countMax + " children, 0 to "
                    + CgVfxEvent.MAX_CHILDREN);
        }
        inherit = List.copyOf(inherit);
        if (inherit.size() != 3) throw new IllegalArgumentException("event " + id + ": inherit_velocity " + inherit + ": three shares");
        if (maxPerFrame < 1) throw new IllegalArgumentException("event " + id + ": max_per_frame " + maxPerFrame);
    }

    /** The event it puts on its source, spawning {@code child}: the handle {@link #spawn} resolved to. */
    public CgVfxEvent compile(CgVfxGpuEmitter child) {
        CgVfxEvent spec = CgVfxEvent.ofSpec(trigger);
        CgVfxEvent e = (rounded ? spec.spawnRounded(child, countMin, countMax)
                : spec.spawn(child, Math.round(countMin), Math.round(countMax))).maxPerFrame(maxPerFrame);
        e = e.inherit(inherit.get(0), inherit.get(1), inherit.get(2));
        return atEmitter ? e.fromEmitter() : e;
    }

    /** Entry {@code entry} of emitter {@code emitter}'s {@code events}. */
    public static CgVfxEventHandler of(String emitter, Map<?, ?> entry) {
        String id = String.valueOf(entry.get("id"));
        String at = emitter + ": events." + id;
        if (Boolean.TRUE.equals(entry.get("kill"))) throw new IllegalArgumentException(at + ": kill is not run by any engine path");
        String source = entry.get("source") instanceof String s ? s : null;
        String name = entry.get("name") instanceof String n && !n.isEmpty() ? n : null;
        if (source == null && name == null) throw new IllegalArgumentException(at + ": no source and no name, so nothing feeds it");
        Map<?, ?> spawn = entry.get("spawn") instanceof Map<?, ?> m ? m : Map.of();
        String child = spawn.get("emitter") instanceof String c ? c : null;
        float min, max;
        boolean rounded = false;
        Object count = spawn.get("count");
        if (count == null) {
            min = max = 1;
        } else if (count instanceof Number n) {
            min = max = Math.round(n.floatValue());
        } else if (count instanceof Map<?, ?> m && m.get("range") instanceof List<?> r && r.size() == 2
                && r.get(0) instanceof Number a && r.get(1) instanceof Number b) {
            Object rounding = m.get("rounding");
            if (rounding != null && !"nearest".equals(rounding)) {
                throw new IllegalArgumentException(at + ": count's rounding " + rounding + ": nearest, or none for a whole roll");
            }
            rounded = rounding != null;
            min = rounded ? a.floatValue() : Math.round(a.floatValue());
            max = rounded ? b.floatValue() : Math.round(b.floatValue());
        } else {
            throw new IllegalArgumentException(at + ": count " + count + ": a number or a range");
        }
        List<Float> inherit = List.of(0f, 0f, 0f);
        if (spawn.get("inherit_velocity") instanceof List<?> v) {
            if (v.size() != 3 || !(v.get(0) instanceof Number x) || !(v.get(1) instanceof Number y) || !(v.get(2) instanceof Number z)) {
                throw new IllegalArgumentException(at + ": inherit_velocity " + v + ": three numbers");
            }
            inherit = List.of(x.floatValue(), y.floatValue(), z.floatValue());
        }
        int most = entry.get("max_per_frame") instanceof Number n ? n.intValue() : CgVfxEvent.MAX_PER_FRAME;
        return new CgVfxEventHandler(id, String.valueOf(entry.get("trigger")), source, name, child, min, max, rounded, inherit,
                Boolean.TRUE.equals(spawn.get("use_emitter_location")), most);
    }

    /**
     * {@code event} of handle {@code source}, spawning handle {@code spawn}, as a handler: what a system file writes.
     * Throws, naming what, for an event the format cannot say: an age past birth, a rate, a node, rows to the CPU, a
     * collision's firings.
     */
    public static CgVfxEventHandler of(String id, String source, String spawn, CgVfxEvent event) {
        String trigger = event.specTrigger();
        String what = source + "'s " + event.trigger() + " event";
        if (trigger == null) throw new IllegalArgumentException(what + " has no trigger in the format");
        if (event.node() != CgVfxEvent.NO_NODE) throw new IllegalArgumentException(what + " fires at a clip node: the clip's to write");
        if (event.readback() > 0) throw new IllegalArgumentException(what + " reports rows to the CPU: Java's alone");
        boolean rounded = event.rounded();
        return new CgVfxEventHandler(id, trigger, source, null, spawn, rounded ? event.roundMin() : event.countMin(),
                rounded ? event.roundMax() : event.count(), rounded, List.of(event.inherit(0), event.inherit(1), event.inherit(2)), event.atEmitter(), event.maxPerFrame());
    }

    /** As a file writes it. */
    public Map<String, Object> tree() {
        Map<String, Object> spawned = new LinkedHashMap<>();
        if (spawn != null) spawned.put("emitter", spawn);
        if (rounded) {
            spawned.put("count", Map.of("range", List.of((double) countMin, (double) countMax), "rounding", "nearest"));
        } else {
            int least = Math.round(countMin), most = Math.round(countMax);
            spawned.put("count", least == most ? (Object) most : Map.of("range", List.of(least, most)));
        }
        if (inherit.get(0) != 0f || inherit.get(1) != 0f || inherit.get(2) != 0f) {
            spawned.put("inherit_velocity", List.of((double) inherit.get(0), (double) inherit.get(1), (double) inherit.get(2)));
        }
        if (atEmitter) spawned.put("use_emitter_location", true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("trigger", trigger);
        if (source != null) out.put("source", source);
        if (name != null) out.put("name", name);
        out.put("spawn", spawned);
        if (maxPerFrame != CgVfxEvent.MAX_PER_FRAME) out.put("max_per_frame", maxPerFrame);
        return out;
    }
}
