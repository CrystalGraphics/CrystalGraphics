package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;

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
 * <pre>{@code
 * CgVfxEventHandler h = CgVfxEventHandler.of("sparks", entry);   // as the reader parses one
 * CgVfxEvent onSource = h.compile(chips);                         // its source's event, spawning the resolved handle
 * }</pre>
 *
 * <ul>
 *   <li>{@code kill} and an event {@code name} are refused, naming the emitter: no engine path runs them.</li>
 *   <li>A count is a number or a {@code range}; a curve is refused. {@code use_particle_time} reads a count over time,
 *       so with neither it changes nothing.</li>
 *   <li>{@code inherit_velocity} is one share for all three axes: a vector of three different shares is refused.</li>
 * </ul>
 */
public record CgVfxEventHandler(String id, String trigger, String source, String spawn, int countMin, int countMax,
                                float inherit, boolean atEmitter, int maxPerFrame) {

    public CgVfxEventHandler {
        CgVfxEvent.ofSpec(trigger);
        if (countMin < 1 || countMax < countMin || countMax > CgVfxEvent.MAX_CHILDREN) {
            throw new IllegalArgumentException("event " + id + ": " + countMin + " to " + countMax + " children, 1 to "
                    + CgVfxEvent.MAX_CHILDREN);
        }
        if (maxPerFrame < 1) throw new IllegalArgumentException("event " + id + ": max_per_frame " + maxPerFrame);
    }

    /** The event it puts on its source, spawning {@code child}: the handle {@link #spawn} resolved to. */
    public CgVfxEvent compile(CgVfxGpuEmitter child) {
        CgVfxEvent e = CgVfxEvent.ofSpec(trigger).spawn(child, countMin, countMax).maxPerFrame(maxPerFrame);
        if (inherit != 0f) e = e.inherit(inherit);
        return atEmitter ? e.fromEmitter() : e;
    }

    /** Entry {@code entry} of emitter {@code emitter}'s {@code events}. */
    public static CgVfxEventHandler of(String emitter, Map<?, ?> entry) {
        String id = String.valueOf(entry.get("id"));
        String at = emitter + ": events." + id;
        if (Boolean.TRUE.equals(entry.get("kill"))) throw new IllegalArgumentException(at + ": kill is not run by any engine path");
        if (entry.get("name") != null) throw new IllegalArgumentException(at + ": events are not named; name is refused");
        if (!(entry.get("source") instanceof String source)) throw new IllegalArgumentException(at + ": no source");
        if (!(entry.get("spawn") instanceof Map<?, ?> spawn) || !(spawn.get("emitter") instanceof String child)) {
            throw new IllegalArgumentException(at + ": it spawns nothing, so it does nothing");
        }
        int min, max;
        Object count = spawn.get("count");
        if (count == null) {
            min = max = 1;
        } else if (count instanceof Number n) {
            min = max = Math.round(n.floatValue());
        } else if (count instanceof Map<?, ?> m && m.get("range") instanceof List<?> r && r.size() == 2
                && r.get(0) instanceof Number a && r.get(1) instanceof Number b) {
            min = Math.round(a.floatValue());
            max = Math.round(b.floatValue());
        } else {
            throw new IllegalArgumentException(at + ": count " + count + ": a number or a range");
        }
        float inherit = 0f;
        if (spawn.get("inherit_velocity") instanceof List<?> v) {
            if (v.size() != 3 || !(v.get(0) instanceof Number x) || !(v.get(1) instanceof Number y) || !(v.get(2) instanceof Number z)
                    || x.floatValue() != y.floatValue() || y.floatValue() != z.floatValue()) {
                throw new IllegalArgumentException(at + ": inherit_velocity " + v + ": one share for all three axes");
            }
            inherit = x.floatValue();
        }
        int most = entry.get("max_per_frame") instanceof Number n ? n.intValue() : CgVfxEvent.MAX_PER_FRAME;
        return new CgVfxEventHandler(id, String.valueOf(entry.get("trigger")), source, child, min, max, inherit,
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
        return new CgVfxEventHandler(id, trigger, source, spawn, event.countMin(), event.count(), event.inherit(),
                event.atEmitter(), event.maxPerFrame());
    }

    /** As a file writes it. */
    public Map<String, Object> tree() {
        Map<String, Object> spawned = new LinkedHashMap<>();
        spawned.put("emitter", spawn);
        spawned.put("count", countMin == countMax ? (Object) countMax : Map.of("range", List.of(countMin, countMax)));
        if (inherit != 0f) spawned.put("inherit_velocity", List.of((double) inherit, (double) inherit, (double) inherit));
        if (atEmitter) spawned.put("use_emitter_location", true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("trigger", trigger);
        out.put("source", source);
        out.put("spawn", spawned);
        if (maxPerFrame != CgVfxEvent.MAX_PER_FRAME) out.put("max_per_frame", maxPerFrame);
        return out;
    }
}
