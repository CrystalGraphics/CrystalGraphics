package com.crystalgraphics.audio;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * A sound as an asset names it, compiled once at load: a host's sound event, a file with its settings, a game's cue
 * name through its kit's sound table, or a pick among those. What a cue's {@code sounds} and a {@code sound} track
 * play through the engine.
 *
 * <pre>{@code
 * CgSoundRef boom  = CgSoundRef.of("minecraft:entity.generic.explode", "mymod:boom");
 * CgSoundRef loop  = CgSoundRef.of(Map.of("path", "mymod:sounds/kame_loop.ogg", "stream", true,
 *         "attenuation", Map.of("inner", 4, "outer", 48, "falloff", "linear")), "mymod:kame");
 * CgSoundRef punch = CgSoundRef.of("kakarot:sound/S_battle_punch_sw", "kakarot:hit");
 * long id = audio.play(punch, CgSoundClass.SFX, x, y, z, 1f, 1f, false, Double.NaN, seed);
 * }</pre>
 *
 * <ul>
 *   <li>Compiled where its asset loads: a cue name no kit table holds, a speaker no kit cast holds, or an unknown
 *       form fails there, naming {@code where}.</li>
 *   <li>A voice line names its speaker, {@code { cue: kakarot:sound/V_PL_SKL_01, speaker: Goku }}: the speaker's
 *       voice sheet in the kit's cast makes it {@code V_C001A/V_PL_SKL_01} in the table.</li>
 *   <li>A {@code pick} is rolled from the play's seed, so every client picks the same.</li>
 *   <li>A host event is resolved when it plays, the host's {@code sounds.json} being read then.</li>
 * </ul>
 */
public final class CgSoundRef {

    public enum Kind { EVENT, FILE, CUE, PICK }

    private final Kind kind;
    private final String id;
    private final float volume, pitch;
    private final boolean stream;
    @Nullable
    private final CgSoundAttenuation attenuation;
    @Nullable
    private final Path file;
    private final CgSoundRef[] picks;

    private CgSoundRef(Kind kind, String id, float volume, float pitch, boolean stream,
            @Nullable CgSoundAttenuation attenuation, @Nullable Path file, CgSoundRef[] picks) {
        this.kind = kind;
        this.id = id;
        this.volume = volume;
        this.pitch = pitch;
        this.stream = stream;
        this.attenuation = attenuation;
        this.file = file;
        this.picks = picks;
    }

    /** {@code written}, a sound as the spec writes it, compiled; {@code where} names it in a failure. */
    public static CgSoundRef of(Object written, String where) {
        if (written instanceof String s) return ofString(s, where);
        if (!(written instanceof Map<?, ?> m)) throw new IllegalArgumentException(where + ": a sound is a name or a map, not " + written);
        if (m.get("pick") instanceof List<?> list) {
            if (list.isEmpty()) throw new IllegalArgumentException(where + ".pick is empty");
            CgSoundRef[] picks = new CgSoundRef[list.size()];
            for (int i = 0; i < picks.length; i++) picks[i] = of(list.get(i), where + ".pick[" + i + "]");
            return new CgSoundRef(Kind.PICK, where, 1f, 1f, false, null, null, picks);
        }
        if (m.get("speaker") instanceof String speaker && m.get("cue") instanceof String line) {
            CgKitSounds.Cue cue;
            try {
                cue = CgKitSounds.resolveVoice(line, speaker);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": " + e.getMessage(), e);
            }
            return new CgSoundRef(Kind.CUE, cue.id(), cue.volume(), cue.pitch(), cue.stream(), null, cue.file(), new CgSoundRef[0]);
        }
        if (m.get("cue") instanceof String cue) return ofString(cue, where);
        if (!(m.get("path") instanceof String path)) throw new IllegalArgumentException(where + ": a sound map needs path, cue or pick");
        CgSoundAttenuation a = m.get("attenuation") instanceof Map<?, ?> am ? attenuation(am, where + ".attenuation") : null;
        return new CgSoundRef(Kind.FILE, path, number(m.get("volume"), 1f), number(m.get("pitch"), 1f),
                Boolean.TRUE.equals(m.get("stream")), a, null, new CgSoundRef[0]);
    }

    private static CgSoundRef ofString(String s, String where) {
        if (CgKitSounds.isCue(s)) {
            CgKitSounds.Cue cue = resolve(s, where);
            return new CgSoundRef(Kind.CUE, cue.id(), cue.volume(), cue.pitch(), cue.stream(), null, cue.file(), new CgSoundRef[0]);
        }
        if (s.contains(":sound/")) resolve(s, where);  // a game's cue with no table: fails naming it
        if (s.endsWith(".ogg")) return new CgSoundRef(Kind.FILE, s, 1f, 1f, false, null, null, new CgSoundRef[0]);
        if (s.indexOf(':') < 0) throw new IllegalArgumentException(where + ": sound " + s + " has no namespace");
        return new CgSoundRef(Kind.EVENT, s, 1f, 1f, false, null, null, new CgSoundRef[0]);
    }

    private static CgKitSounds.Cue resolve(String s, String where) {
        try {
            return CgKitSounds.resolve(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + ": " + e.getMessage(), e);
        }
    }

    /** The spec's {@code { inner, outer, falloff }}: full within inner, its curve to nothing at outer. */
    private static CgSoundAttenuation attenuation(Map<?, ?> m, String where) {
        double inner = number(m.get("inner"), 4f), outer = number(m.get("outer"), 40f);
        if (outer < inner) throw new IllegalArgumentException(where + ": outer " + outer + " is inside inner " + inner);
        CgSoundAttenuation a = new CgSoundAttenuation().inner(inner).falloff(outer - inner);
        if (m.get("falloff") instanceof String f) {
            try {
                a.algorithm(CgSoundAttenuation.Algorithm.valueOf(f.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ".falloff: " + f + " is not linear, logarithmic, inverse, "
                        + "log_reverse or natural_sound", e);
            }
        }
        return a;
    }

    private static float number(@Nullable Object o, float otherwise) {
        return o instanceof Number n ? n.floatValue() : otherwise;
    }

    public Kind kind() {
        return kind;
    }

    /** The event, the file's path, or the cue's file as a path; for a pick, where it was written. */
    public String id() {
        return id;
    }

    public float volume() {
        return volume;
    }

    public float pitch() {
        return pitch;
    }

    public boolean stream() {
        return stream;
    }

    /** Its own attenuation, written with it; null for the play's default. */
    @Nullable
    public CgSoundAttenuation attenuation() {
        return attenuation;
    }

    /** A game cue's file on disk; null for any other kind. */
    @Nullable
    public Path file() {
        return file;
    }

    /** The one a pick rolls from {@code seed}; itself for any other kind. */
    public CgSoundRef pick(long seed) {
        if (kind != Kind.PICK) return this;
        // SplitMix64's finaliser: neighbouring seeds pick independently.
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return picks[(int) Math.floorMod(z, (long) picks.length)].pick(seed * 31 + 7);
    }

    /** Equal where it names the same sound with the same settings: a cue built in Java equals its file's. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgSoundRef r)) return false;
        return kind == r.kind && id.equals(r.id) && volume == r.volume && pitch == r.pitch && stream == r.stream
                && Objects.equals(attenuation, r.attenuation) && Objects.equals(file, r.file) && Arrays.equals(picks, r.picks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, id, volume, pitch, stream, file) * 31 + Arrays.hashCode(picks);
    }

    @Override
    public String toString() {
        return kind + " " + id;
    }
}
