package com.crystalgraphics.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.codec.CgAssetYaml;

/**
 * A game's text by string id, per language: a kit's {@code strings/<locale>.yaml}, what a sequence's {@code subtitle}
 * section names as {@code <ns>:text/<id>}. Read in the player's language ({@link CgI18n#language()}), {@code en_us}
 * where that language has no text.
 *
 * <pre>{@code
 * # assets/kakarot_kit/strings/en_us.yaml
 * _v: 1
 * strings:
 *   Cpl001A: Goku
 *   V_C001A_skl_01_Msg: { text: Kamehame..., speaker: kakarot:text/Cpl001A }   # a line with its speaker's name
 * }</pre>
 *
 * <pre>{@code
 * CgKitStrings.registerKit("kakarot_kit", Paths.get("assets/kakarot_kit"));   // the asset registry does this
 * CgKitStrings.Entry line = CgKitStrings.entry("kakarot:text/V_C001A_skl_01_Msg");
 * line.text();                                    // "Kamehame..."
 * CgKitStrings.text(line.speaker());              // "Goku"
 * }</pre>
 *
 * <ul>
 *   <li>The namespace is the game's: a strings file's own {@code namespace}, else its kit's {@code sounds.yaml}'s, else
 *       the kit's.</li>
 *   <li>A language is a file's name, a locale in lower case: {@code en_us}, {@code ja_jp}, {@code fr_fr}.</li>
 *   <li>Null where neither the player's language nor {@code en_us} has the id.</li>
 * </ul>
 */
public final class CgKitStrings {

    /** The language a missing one falls back to. */
    public static final String FALLBACK = "en_us";
    private static final String TEXT = ":text/";

    /** A string: its text, and who says it where it is a line (a {@code <ns>:text/<id>} of its own), else null. */
    public record Entry(String text, @Nullable String speaker) {
    }

    /** Per game namespace, per language, the strings by id. */
    private static final Map<String, Map<String, Map<String, Entry>>> BY_GAME = new ConcurrentHashMap<>();
    /** The kit that registered each game's strings: where its subtitle style and fonts are. */
    private static final Map<String, String> KIT_OF_GAME = new ConcurrentHashMap<>();

    private CgKitStrings() {
    }

    /** {@code folder}'s {@code strings/*.yaml}, the kit {@code namespace}'s; nothing where it ships none. */
    public static void registerKit(String namespace, Path folder) {
        Path dir = folder.resolve("strings");
        if (!Files.isDirectory(dir)) return;
        String kitGame = gameOf(folder, namespace);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.yaml")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                Map<?, ?> root = root(file);
                String game = root.get("namespace") instanceof String g ? g : kitGame;
                KIT_OF_GAME.put(game, namespace);
                BY_GAME.computeIfAbsent(game, g -> new ConcurrentHashMap<>())
                        .put(name.substring(0, name.length() - ".yaml".length()).toLowerCase(), entries(file, root));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(dir + ": " + e.getMessage(), e);
        }
    }

    /** The game the kit's sound cues are named under, its {@code sounds.yaml}'s namespace; the kit's where it has none. */
    private static String gameOf(Path folder, String namespace) {
        Path sounds = folder.resolve("sounds.yaml");
        if (!Files.isRegularFile(sounds)) return namespace;
        try {
            return root(sounds).get("namespace") instanceof String g ? g : namespace;
        } catch (IOException e) {
            throw new IllegalArgumentException(sounds + ": " + e.getMessage(), e);
        }
    }

    private static Map<?, ?> root(Path file) throws IOException {
        Object tree = CgAssetYaml.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        if (!(tree instanceof Map<?, ?> root)) throw new IllegalArgumentException(file + ": expected a map");
        return root;
    }

    private static Map<String, Entry> entries(Path file, Map<?, ?> root) {
        if (!(root.get("strings") instanceof Map<?, ?> strings)) {
            throw new IllegalArgumentException(file + ": expected _v and a strings: map of id to text");
        }
        Map<String, Entry> table = new HashMap<>();
        for (Map.Entry<?, ?> e : strings.entrySet()) {
            String id = String.valueOf(e.getKey());
            if (e.getValue() instanceof Map<?, ?> line) {
                if (!(line.get("text") instanceof String text)) throw new IllegalArgumentException(file + ": strings." + id + " has no text");
                table.put(id, new Entry(text, line.get("speaker") instanceof String s ? s : null));
            } else {
                table.put(id, new Entry(String.valueOf(e.getValue()), null));
            }
        }
        return table;
    }

    /** The string {@code ref}, a {@code <ns>:text/<id>}, in the player's language or {@code en_us}; null where neither has it. */
    @Nullable
    public static Entry entry(String ref) {
        int at = ref.indexOf(TEXT);
        if (at <= 0) return null;
        Map<String, Map<String, Entry>> languages = BY_GAME.get(ref.substring(0, at));
        if (languages == null) return null;
        String id = ref.substring(at + TEXT.length());
        Map<String, Entry> own = languages.get(CgI18n.language());
        Entry entry = own != null ? own.get(id) : null;
        if (entry != null) return entry;
        Map<String, Entry> fallback = languages.get(FALLBACK);
        return fallback != null ? fallback.get(id) : null;
    }

    /** The kit holding {@code ref}'s strings, a {@code <ns>:text/<id>}; null where none registered its game. */
    @Nullable
    public static String kitOf(String ref) {
        int at = ref.indexOf(TEXT);
        return at <= 0 ? null : KIT_OF_GAME.get(ref.substring(0, at));
    }

    /** The text of {@code ref}, a {@code <ns>:text/<id>}; null where no table holds it, or {@code ref} is null. */
    @Nullable
    public static String text(@Nullable String ref) {
        Entry entry = ref == null ? null : entry(ref);
        return entry != null ? entry.text() : null;
    }
}
