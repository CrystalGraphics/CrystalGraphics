package com.crystalgraphics.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.codec.CgAssetYaml;

/**
 * Games' sounds by their cue names: each kit's sound table, its {@code sounds.yaml}, read when the kit is scanned. What
 * turns {@code kakarot:sound/S_battle_punch_sw} into the file the kit decoded from the game's own banks.
 *
 * <pre>{@code
 * // assets/kakarot_kit/sounds.yaml
 * //   _v: 1
 * //   namespace: kakarot
 * //   sounds:
 * //     S_battle_punch_sw: { path: kakarot_kit:sounds/Sound/Se/battle/S_battle_punch_sw.ogg }
 * CgKitSounds.registerKit(Paths.get("assets/kakarot_kit"));
 * CgKitSounds.Cue punch = CgKitSounds.resolve("kakarot:sound/S_battle_punch_sw");
 * }</pre>
 *
 * <ul>
 *   <li>A kit's asset scan registers its table; a mod need not.</li>
 *   <li>A cue its game's table does not hold fails at {@link #resolve}, naming it: at load, never as silence.</li>
 *   <li>The table's {@code namespace} is the game's, what its sequences write before {@code :sound/}; a path's
 *       namespace is the kit's own folder.</li>
 * </ul>
 */
public final class CgKitSounds {

    /** A cue's sound: its id as a path, the file it is, and its settings. */
    public record Cue(String id, Path file, float volume, float pitch, boolean stream) {
    }

    private static final String SOUND = ":sound/";
    private static final Map<String, Map<String, Cue>> BY_GAME = new ConcurrentHashMap<>();
    /** Per game, each character's voice sheet, from its kit's {@code cast.yaml}. */
    private static final Map<String, Map<String, String>> SHEETS = new ConcurrentHashMap<>();

    private CgKitSounds() {
    }

    /** {@code folder}'s {@code sounds.yaml}, a kit's root; nothing where it ships none. */
    public static void registerKit(Path folder) {
        Path file = folder.resolve("sounds.yaml");
        if (!Files.isRegularFile(file)) return;
        Object tree;
        try {
            tree = CgAssetYaml.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
        if (!(tree instanceof Map<?, ?> root) || !(root.get("namespace") instanceof String game)
                || !(root.get("sounds") instanceof Map<?, ?> sounds)) {
            throw new IllegalArgumentException(file + ": expected _v, namespace and a sounds: map of cue to its path");
        }
        Map<String, Cue> table = new ConcurrentHashMap<>();
        for (Map.Entry<?, ?> e : sounds.entrySet()) {
            String cue = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> s) || !(s.get("path") instanceof String path)) {
                throw new IllegalArgumentException(file + ": sounds." + cue + " has no path");
            }
            int colon = path.indexOf(':');
            if (colon < 0) throw new IllegalArgumentException(file + ": sounds." + cue + ".path " + path + " has no namespace");
            Path on = folder.resolve(path.substring(colon + 1));
            table.put(cue, new Cue(path, on, number(s.get("volume"), 1f), number(s.get("pitch"), 1f),
                    Boolean.TRUE.equals(s.get("stream"))));
        }
        BY_GAME.merge(game, table, (a, b) -> {
            a.putAll(b);
            return a;
        });
        readSheets(game, folder.resolve("cast.yaml"));
    }

    /** A cast's {@code voice:} sheets, by character: what a voice line's speaker resolves through. */
    private static void readSheets(String game, Path cast) {
        if (!Files.isRegularFile(cast)) return;
        Object tree;
        try {
            tree = CgAssetYaml.read(new String(Files.readAllBytes(cast), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException(cast + ": " + e.getMessage(), e);
        }
        if (!(tree instanceof Map<?, ?> root)) return;
        Map<String, String> sheets = SHEETS.computeIfAbsent(game, k -> new ConcurrentHashMap<>());
        for (Map.Entry<?, ?> e : root.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> member && member.get("voice") instanceof String sheet) {
                sheets.put(String.valueOf(e.getKey()), sheet);
            }
        }
    }

    /**
     * A voice line, {@code ref} as its speaker says it: {@code <sheet>/<cue>} in the table, the sheet the speaker's in
     * the kit's cast ({@code Kakarot's ATAnimNotify_PlaySoundByVoiceID}: one voice ID in every character's sheet).
     */
    public static Cue resolveVoice(String ref, String speaker) {
        int at = ref.indexOf(SOUND);
        if (at <= 0) throw new IllegalArgumentException(ref + " is not a game's cue name (<game>:sound/<cue>)");
        String game = ref.substring(0, at);
        Map<String, String> sheets = SHEETS.get(game);
        String sheet = sheets == null ? null : sheets.get(speaker);
        if (sheet == null) throw new IllegalArgumentException(ref + ": the speaker " + speaker + " is not in " + game + "'s cast");
        return resolve(game + SOUND + sheet + "/" + ref.substring(at + SOUND.length()));
    }

    private static float number(@Nullable Object o, float otherwise) {
        return o instanceof Number n ? n.floatValue() : otherwise;
    }

    /** Whether {@code ref} names a game's cue: {@code <game>:sound/<cue>} with a kit table for that game. */
    public static boolean isCue(String ref) {
        int at = ref.indexOf(SOUND);
        return at > 0 && BY_GAME.containsKey(ref.substring(0, at));
    }

    /** {@code ref}'s sound, {@code <game>:sound/<cue>}. */
    public static Cue resolve(String ref) {
        int at = ref.indexOf(SOUND);
        if (at <= 0) throw new IllegalArgumentException(ref + " is not a game's cue name (<game>:sound/<cue>)");
        Map<String, Cue> table = BY_GAME.get(ref.substring(0, at));
        if (table == null) throw new IllegalArgumentException(ref + ": no kit ships a sound table for " + ref.substring(0, at));
        Cue cue = table.get(ref.substring(at + SOUND.length()));
        if (cue == null) throw new IllegalArgumentException(ref + ": not in " + ref.substring(0, at) + "'s sounds.yaml");
        return cue;
    }
}
