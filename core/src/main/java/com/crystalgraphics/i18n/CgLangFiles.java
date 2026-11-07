package com.crystalgraphics.i18n;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.format.CgFiles;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A mod's translations, one format on every version: {@code assets/<namespace>/lang/<code>.json}, the file Minecraft
 * reads itself from 1.13. A host on an older Minecraft, which reads only {@code .lang}, merges them into its own table
 * with this.
 *
 * <pre>{@code
 * // assets/mymod/lang/en_us.json
 * { "key.mymod.map": "Open Map", "key.category.mymod.keys": "My Mod" }
 *
 * // a host, after the game loaded its .lang files for the languages in use
 * CgLangFiles.mergeInto(table, languages, namespaces, (namespace, path) -> openAll(namespace, path));
 * }</pre>
 *
 * <ul>
 *   <li>File names are lower case ({@code en_us}, {@code de_de}); a host's {@code en_US} finds {@code en_us.json}.</li>
 *   <li>A key the table already has is kept: a {@code .lang} file or a resource pack's wins over a mod's JSON.</li>
 *   <li>Read through {@code CgFiles.JSON}: a file that is not an object of strings is logged and skipped.</li>
 * </ul>
 */
public final class CgLangFiles {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /** Every copy of {@code lang/<code>.json} under a namespace, lowest resource pack first; empty when there is none. */
    @FunctionalInterface
    public interface Opener {
        List<InputStream> open(String namespace, String path) throws IOException;
    }

    private CgLangFiles() {
    }

    /**
     * Merges each namespace's {@code lang/<code>.json} into {@code table}, keeping every key the table has.
     *
     * @param languages the host's language codes in the order it loads them, a later one's text replacing an
     *                  earlier's ({@code [en_US, de_DE]}: German where it has a key, English otherwise)
     */
    public static void mergeInto(Map<String, String> table, List<String> languages, Iterable<String> namespaces,
                                 Opener open) {
        Map<String, String> ours = new LinkedHashMap<>();
        for (String language : languages) {
            String path = "lang/" + language.toLowerCase(Locale.ROOT) + ".json";
            for (String namespace : namespaces) {
                List<InputStream> files;
                try {
                    files = open.open(namespace, path);
                } catch (IOException none) {
                    continue;
                }
                for (InputStream file : files) read(namespace + ":" + path, file, ours);
            }
        }
        for (Map.Entry<String, String> entry : ours.entrySet()) {
            if (!table.containsKey(entry.getKey())) table.put(entry.getKey(), entry.getValue());
        }
    }

    private static void read(String name, InputStream in, Map<String, String> into) {
        try (InputStream file = in) {
            into.putAll(CgFiles.JSON.fromText(TABLE, new String(file.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException | CgCodecException bad) {
            LOGGER.warn("[cg] {} could not be read: {}", name, bad.getMessage());
        }
    }

    /** A language file: an object of key to text. */
    private static final CgCodec<Map<String, String>> TABLE = CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.STRING);
}
