package com.crystalgraphics.mc.shared;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The variant table a merged jar carries, and the answer to "which entry class runs here".
 *
 * <pre>
 * Variants table = Variants.load("crystalgui");
 * Variant variant = table.select("fabric", "1.20.4");   // throws UnsupportedVariant if none
 * Object entry = Class.forName(variant.commonEntry()).newInstance();
 * ((VariantEntry) entry).start(null);
 * </pre>
 *
 * <p>Selection is by loader <i>and</i> version, and takes the <b>first</b> matching row. The build
 * refuses to emit two rows of one loader whose ranges overlap, so a first match is also the only
 * match; order decides what is read first, never which variant is correct.</p>
 *
 * <p>Easy to get wrong: {@link #load} reads a resource, so it must be called after the loader's
 * class loader can see the jar — from an entry point, never from a static initialiser that might run
 * during scanning. It never returns null: a missing or malformed file throws, because a mod that
 * silently registers nothing is the failure this whole mechanism exists to prevent.</p>
 */
public final class Variants {

    /** The format this reader understands. A file declaring a higher one is refused, not guessed at. */
    private static final int FORMAT = 1;

    private final String modId;
    private final List<Variant> variants;

    private Variants(String modId, List<Variant> variants) {
        this.modId = modId;
        this.variants = Collections.unmodifiableList(variants);
    }

    /**
     * Reads {@code META-INF/<modId>/variants.json} through the MOD's own class loader.
     *
     * <p><b>Never this class's loader.</b> On ModLauncher this module is a library on the parent
     * layer and the table is a resource of the mod on the child layer, so a parent-loader lookup
     * finds nothing — measured as "has no META-INF/crystalgui/variants.json in its jar" on a jar that
     * plainly contained it. Pass the bootstrapper's own class, which is in the mod.</p>
     */
    public static Variants load(String modId, ClassLoader owner) {
        String path = "META-INF/" + modId + "/variants.json";
        InputStream in = owner.getResourceAsStream(path);
        if (in == null) {
            in = owner.getResourceAsStream("/" + path);
        }
        if (in == null) {
            throw new UnsupportedVariant(modId + " has no " + path
                    + " in its jar — the merge did not write one, so no variant can be chosen");
        }
        try {
            return read(readAll(in), modId);
        } finally {
            close(in);
        }
    }

    /** The same, over text already in hand — what the tests use. */
    public static Variants read(String json, String modId) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) {
            throw new UnsupportedVariant("variants.json for " + modId + " is not a JSON object");
        }
        Map<?, ?> map = (Map<?, ?>) root;
        Object format = map.get("format");
        int declared = format instanceof Number ? ((Number) format).intValue() : -1;
        if (declared > FORMAT) {
            throw new UnsupportedVariant("variants.json for " + modId + " declares format "
                    + declared + "; this build reads " + FORMAT + " — the jar is newer than its own reader");
        }
        Object rows = map.get("variants");
        if (!(rows instanceof List)) {
            throw new UnsupportedVariant("variants.json for " + modId + " has no variants array");
        }
        List<Variant> parsed = new ArrayList<Variant>();
        for (Object row : (List<?>) rows) {
            if (!(row instanceof Map)) {
                throw new UnsupportedVariant("a variant of " + modId + " is not a JSON object");
            }
            Map<?, ?> r = (Map<?, ?>) row;
            parsed.add(new Variant(
                    string(r, "loader", modId),
                    string(r, "minecraft", modId),
                    r.get("era") == null ? "" : String.valueOf(r.get("era")),
                    r.get("common") == null ? null : String.valueOf(r.get("common")),
                    r.get("client") == null ? null : String.valueOf(r.get("client")),
                    strings(r.get("mixins"))));
        }
        return new Variants(modId, parsed);
    }

    /**
     * The variant for this loader and version.
     *
     * @throws UnsupportedVariant naming every range the jar does carry for that loader
     */
    public Variant select(String loader, String minecraftVersion) {
        List<Variant> candidates = of(loader);
        for (Variant v : candidates) {
            if (v.covers(minecraftVersion)) {
                return v;
            }
        }
        StringBuilder supported = new StringBuilder();
        for (Variant v : candidates) {
            if (supported.length() > 0) {
                supported.append(", ");
            }
            supported.append(v.minecraft());
        }
        throw new UnsupportedVariant(modId + " has no variant for " + loader + " "
                + minecraftVersion + "; supported: "
                + (supported.length() == 0 ? "none on this loader" : supported.toString()));
    }

    /** Every variant of one loader, in file order — what the mixin config plugin gates on. */
    public List<Variant> of(String loader) {
        List<Variant> out = new ArrayList<Variant>();
        for (Variant v : variants) {
            if (v.loader().equals(loader)) {
                out.add(v);
            }
        }
        return out;
    }

    public List<Variant> all() {
        return variants;
    }

    public String modId() {
        return modId;
    }

    private static String string(Map<?, ?> row, String key, String modId) {
        Object value = row.get(key);
        if (value == null) {
            throw new UnsupportedVariant("a variant of " + modId + " has no `" + key + "`");
        }
        return String.valueOf(value);
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<String>();
        if (value instanceof List) {
            for (Object each : (List<?>) value) {
                out.add(String.valueOf(each));
            }
        }
        return out;
    }

    private static String readAll(InputStream in) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), "UTF-8");
        } catch (IOException e) {
            throw new UnsupportedVariant("variants.json could not be read: " + e);
        }
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Reading already succeeded or threw; a failure to close changes neither.
        }
    }
}
