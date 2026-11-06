package com.crystalgraphics.storage.download;

import com.crystalgraphics.storage.cache.CgCacheFiles;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.format.CgJsonOps;
import com.google.gson.JsonElement;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a product's runtime downloads can be found: a {@code download/locations.json} the jar ships a copy of, and
 * re-reads from master, so a dead link is repaired for every released jar by editing one file. Each product has its
 * own ({@link #of(Source)}); CrystalGraphics' is {@link CgDownloads#LOCATIONS}.
 *
 * <pre>{@code
 * CgDownloadLocations locations = CgDownloadLocations.of(new CgDownloadLocations.Source(
 *         "mymod", MyMod.class, "/assets/mymod/download/locations.json",
 *         "mymod.download.remote", "mymod.download.locations", null));
 *
 * CgDownloads.located(locations, "fabric/intermediary/1.20.1").into(target);   // the usual way
 * CgDownloadLocations.Location where = locations.find("jdk-sources/17");
 * where.urls();     // the override file's, then master's, then the jar's own
 * where.digest();   // the jar's pin, or null for an artifact nobody can pin
 * }</pre>
 *
 * The file names each download by id, with URLs, or Maven coordinates and the repositories to find them in:
 *
 * <pre>{@code
 * "repositories": { "fabric": ["https://maven.fabricmc.net/{path}"], "mirror": ["https://example.org/{file}"] },
 * "files": {
 *   "mojang/version-manifest": { "urls": ["https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"] },
 *   "fabric/intermediary/1.20.1": { "maven": "net.fabricmc:intermediary:1.20.1:v2",
 *                                   "from": ["fabric", "mirror"], "digest": "sha1:20ba…" }
 * },
 * "engines": { "from": ["maven-central"], "bands": { "8": { "org.benf:cfr:0.152": "sha1:48ef…" } } }   // id engine/8/cfr-0.152.jar
 * }</pre>
 *
 * Three copies are merged per id, their repositories by name:
 * <ul>
 *   <li>The jar's: the ids it knows and their digests. The only copy that pins anything.</li>
 *   <li>Master's: fetched before the first download when the copy on disk is a day old or missing, and again when
 *       every URL has failed. It adds URLs for ids the jar lists; its digests and unknown ids are ignored, so it can
 *       move an artifact but never change what the jar accepts.</li>
 *   <li>An override file named by the source's override property, tried first: a pack's mirror, an offline machine.</li>
 * </ul>
 *
 * <ul>
 *   <li>{@link #find} answers null for an id the jar does not list; never guess a URL.</li>
 *   <li>A copy that is not JSON, holds what this jar cannot read, or declares another {@code format} than
 *       {@value #FORMAT} is ignored whole.</li>
 *   <li>{@link #useCacheRoot} lets master's copy survive a restart; without it, it is fetched every session.</li>
 *   <li>The source's remote property set to {@code false} never reads master's copy.</li>
 * </ul>
 */
public final class CgDownloadLocations {

    /** The layout this jar reads. */
    static final int FORMAT = 1;

    /** How old master's copy on disk may be before a download fetches it again. */
    public static final long STALE_AFTER_MILLIS = 24L * 60 * 60 * 1000;

    /** More than this is not the file but something else that answered. */
    private static final int MAX_BYTES = 1 << 20;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)\\}");

    /** One artifact: the jar's pin (null when nothing can be pinned) and every URL, best first. */
    public record Location(String id, @Nullable String digest, List<String> urls) {
    }

    /**
     * What the file says about its own mirror, for the build's tools; the runtime ignores it.
     *
     * @param repository the entry of {@code repositories} that is the mirror
     * @param release    the GitHub release holding the files
     * @param github     {@code owner/repo} the release is in
     * @param licences   what a mirrored file is redistributed under, by file-name prefix; a file matching none is never mirrored
     */
    public record Mirror(@Nullable String repository, @Nullable String release, @Nullable String github,
                         Map<String, String> licences) {

        static final Mirror NONE = new Mirror(null, null, null, Collections.<String, String>emptyMap());

        /** The licence a file named {@code fileName} is mirrored under, or null. */
        public @Nullable String licenceOf(String fileName) {
            for (Map.Entry<String, String> licence : licences.entrySet()) {
                if (fileName.startsWith(licence.getKey())) return licence.getValue();
            }
            return null;
        }
    }

    /**
     * One download as the build checks it.
     *
     * @param urls       every address, best first
     * @param upstream   the addresses that are not the mirror's
     * @param mirrorFile the file's name in the mirror, or null when it does not come from there
     */
    public record Line(String id, @Nullable String digest, List<String> urls, List<String> upstream,
                       @Nullable String mirrorFile, boolean template) {
    }

    /** A whole file as the build reads it. */
    public record Listing(List<String> self, Map<String, List<String>> repositories, List<Line> lines, Mirror mirror) {
    }

    /**
     * {@code text} as the build's checks read it, through the code the runtime reads it with.
     *
     * @throws IllegalArgumentException when it is not a locations file in this format
     */
    public static Listing listing(String text) {
        Copy copy = parse(text);
        if (copy == null) throw new IllegalArgumentException("not a locations file in format " + FORMAT);
        List<Line> lines = new ArrayList<>();
        for (Entry entry : copy.entries()) {
            List<String> all = new ArrayList<>();
            List<String> upstream = new ArrayList<>();
            String mirrorFile = null;
            for (Address address : entry.addresses()) {
                List<String> urls = expand(new Entry(entry.id(), entry.digest(), Collections.singletonList(address)),
                        Collections.<String, String>emptyMap(), copy.repositories());
                all.addAll(urls);
                if (address.repository() != null && address.repository().equals(copy.mirror().repository())) {
                    mirrorFile = mavenFile(address.coordinates());
                } else {
                    upstream.addAll(urls);
                }
            }
            lines.add(new Line(entry.id(), entry.digest(), all, upstream, mirrorFile, entry.isTemplate()));
        }
        return new Listing(copy.self(), copy.repositories(), lines, copy.mirror());
    }

    /** A download as the file writes it: its URLs, or Maven coordinates {@code from} named repositories; and a pin. */
    private record Spec(List<String> urls, @Nullable String maven, List<String> from, @Nullable String digest) {

        static final CgCodec<Spec> CODEC = CgCodecs.record(
                CgCodecs.optionalField("urls", STRINGS, Spec::urls, Collections.<String>emptyList()),
                CgCodecs.optionalField("maven", CgCodecs.STRING, Spec::maven, null),
                CgCodecs.optionalField("from", STRINGS, Spec::from, Collections.<String>emptyList()),
                CgCodecs.optionalField("digest", CgCodecs.STRING, Spec::digest, null),
                Spec::new);
    }

    private static final CgCodec<List<String>> STRINGS = CgCodecs.listOf(CgCodecs.STRING);

    private static final CgCodec<Map<String, List<String>>> REPOSITORIES = CgCodecs.mapOf(CgCodecs.STRING, STRINGS);

    private static final CgCodec<Map<String, Spec>> FILES = CgCodecs.mapOf(CgCodecs.STRING, Spec.CODEC);

    private static final CgCodec<Map<String, String>> LICENCES = CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.STRING);

    /** Each engine band's jars: coordinates to digest. */
    private static final CgCodec<Map<String, Map<String, String>>> BANDS =
            CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.STRING));

    /** One way to an artifact: a URL, or Maven coordinates looked up in a named repository. */
    private record Address(@Nullable String url, @Nullable String repository, @Nullable String coordinates) {
    }

    /** What one copy says about one id. */
    private record Entry(String id, @Nullable String digest, List<Address> addresses) {

        boolean isTemplate() {
            return id.indexOf('{') >= 0;
        }
    }

    /** One copy of the file: where it lives, each repository's addresses, and its entries. */
    private record Copy(List<String> self, Map<String, List<String>> repositories, List<Entry> entries, Mirror mirror) {
    }

    private static final Copy EMPTY = new Copy(Collections.<String>emptyList(),
            Collections.<String, List<String>>emptyMap(), Collections.<Entry>emptyList(), Mirror.NONE);

    /** An id resolved against one entry: the entry, and what each placeholder stands for. */
    private record Match(Entry entry, Map<String, String> values) {
    }

    /**
     * One product's locations file: a name for messages, the resource its jar carries (read through {@code anchor}'s
     * class loader), the system properties that switch master's copy off and name an override file, and optionally
     * where the cache lives until {@link #useCacheRoot} says.
     */
    public record Source(String name, Class<?> anchor, String resource, String remoteProperty, String overrideProperty,
                         @Nullable Supplier<Path> defaultCacheRoot) {
    }

    @Nullable
    private final Source source;
    private volatile Copy bundled;
    private volatile Copy override;
    @Nullable
    private final Supplier<Path> remoteFile;
    private final boolean remoteAllowed;
    private final AtomicBoolean fetched = new AtomicBoolean();
    private volatile Copy remote;

    /** Where this installation keeps its cache; until a host says, master's copy is held in memory only. */
    private volatile Path cacheRoot;

    private CgDownloadLocations(@Nullable Source source, @Nullable Copy bundled, @Nullable Copy override,
                                @Nullable Supplier<Path> remoteFile, boolean remoteAllowed) {
        this.source = source;
        this.bundled = bundled;
        this.override = override;
        this.remoteFile = remoteFile;
        this.remoteAllowed = remoteAllowed;
    }

    /** The locations of {@code source}, read on first use. */
    public static CgDownloadLocations of(Source source) {
        return new CgDownloadLocations(source, null, null, null,
                !"false".equalsIgnoreCase(System.getProperty(source.remoteProperty())));
    }

    private Copy bundled() {
        Copy local = bundled;
        if (local == null) bundled = local = readable(resourceText(), source.resource(), source.name());
        return local;
    }

    private Copy override() {
        Copy local = override;
        if (local == null) override = local = readable(overrideText(), source.overrideProperty(), source.name());
        return local;
    }

    @Nullable
    private Path remoteFile() {
        if (remoteFile != null) return remoteFile.get();
        Path root = cacheRoot;
        if (root == null && source != null && source.defaultCacheRoot() != null) root = source.defaultCacheRoot().get();
        return root == null ? null : root.resolve("download").resolve("locations.json");
    }

    /**
     * Where master's copy is kept so it survives a restart. Null changes nothing.
     *
     * <pre>{@code
     * locations.useCacheRoot(layout.cacheIn(CgGameDirectory.game()));   // a host, at startup
     * }</pre>
     */
    public void useCacheRoot(@Nullable Path root) {
        if (root != null) cacheRoot = root;
    }

    /**
     * These copies of the file, with master's kept at {@code remoteFile}: for tests and tools.
     *
     * <pre>{@code
     * CgDownloadLocations locations = CgDownloadLocations.of(jarJson, null, temp.resolve("master.json"), true);
     * }</pre>
     */
    public static CgDownloadLocations of(String bundled, @Nullable String override, @Nullable Path remoteFile,
                                       boolean remoteAllowed) {
        return new CgDownloadLocations(null, readable(bundled, "the jar's copy", "downloads"),
                readable(override, "the override file", "downloads"), () -> remoteFile, remoteAllowed);
    }

    /** Where {@code id} can be had, or null when the jar's copy does not list it. */
    public @Nullable Location find(String id) {
        Match known = match(bundled().entries(), id);
        if (known == null) return null;
        List<Copy> copies = copies();
        Map<String, List<String>> repositories = repositoriesOf(copies);
        Set<String> urls = new LinkedHashSet<>();
        for (Copy copy : copies) {
            for (Entry entry : copy.entries()) {
                // Its entry for this id, or for the template the id fits.
                if (entry.id().equals(id) || entry.id().equals(known.entry().id())) {
                    urls.addAll(expand(entry, known.values(), repositories));
                }
            }
        }
        String digest = known.entry().isTemplate() ? null : known.entry().digest();
        return new Location(id, digest, Collections.unmodifiableList(new ArrayList<>(urls)));
    }

    /** Whether the jar's copy lists {@code id}. Reads nothing from disk or the network. */
    public boolean lists(String id) {
        return match(bundled().entries(), id) != null;
    }

    /** The jar's pin for {@code id}, or null. Reads nothing from disk or the network. */
    public @Nullable String pinOf(String id) {
        Match known = match(bundled().entries(), id);
        return known == null || known.entry().isTemplate() ? null : known.entry().digest();
    }

    /** Every id the jar lists under {@code prefix}, sorted: the jars an engine band is made of. */
    public List<String> idsUnder(String prefix) {
        List<String> ids = new ArrayList<>();
        for (Entry entry : bundled().entries()) {
            if (!entry.isTemplate() && entry.id().startsWith(prefix)) ids.add(entry.id());
        }
        Collections.sort(ids);
        return ids;
    }

    /** Fetches master's copy before a download when the one on disk is a day old or missing. */
    public void refreshIfStale() {
        if (!remoteAllowed || fetched.get()) return;
        Path file = remoteFile();
        try {
            if (file != null && Files.isRegularFile(file)
                    && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < STALE_AFTER_MILLIS) {
                return;
            }
        } catch (IOException unreadable) {
            // An age that cannot be read is fetched again rather than trusted.
        }
        refresh();
    }

    /**
     * Fetches master's copy, at most once per session. A caller arriving while it is being fetched waits
     * for it, then {@link #find} answers with whatever it added.
     *
     * @return whether this call fetched a copy
     */
    public synchronized boolean refresh() {
        if (!remoteAllowed || !fetched.compareAndSet(false, true)) return false;
        for (String url : selfUrls()) {
            try (CgDownloads.Body body = CgDownloads.fetch(url)) {
                byte[] bytes = readBounded(body.stream());
                Copy copy = parse(new String(bytes, StandardCharsets.UTF_8));
                // The file always names itself. An error page that answered 200 does not parse as one.
                if (copy == null || copy.self().isEmpty()) continue;
                remote = copy;
                Path file = remoteFile();
                if (file != null) CgCacheFiles.install(file, new ByteArrayInputStream(bytes), null);
                return true;
            } catch (IOException | RuntimeException unreachable) {
                // The file's next address, or the copy already held.
            }
        }
        return false;
    }

    /** Where the file itself lives: the override's word first, then master's, then the jar's. */
    private List<String> selfUrls() {
        Set<String> urls = new LinkedHashSet<>();
        for (Copy copy : copies()) urls.addAll(copy.self());
        return new ArrayList<>(urls);
    }

    /** The override, master's when it may be read, then the jar's: the order URLs are tried in. */
    private List<Copy> copies() {
        List<Copy> copies = new ArrayList<>(3);
        copies.add(override());
        if (remoteAllowed) copies.add(remote());
        copies.add(bundled());
        return copies;
    }

    private Copy remote() {
        Copy local = remote;
        if (local == null) {
            local = EMPTY;
            Path file = remoteFile();
            if (file != null && Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    Copy read = parse(new String(readBounded(in), StandardCharsets.UTF_8));
                    if (read != null) local = read;
                } catch (IOException unreadable) {
                    // Treated as absent; the next refresh replaces it.
                }
            }
            remote = local;
        }
        return local;
    }

    /** Every copy's addresses for each repository, merged by name in the order the copies are tried. */
    private static Map<String, List<String>> repositoriesOf(List<Copy> copies) {
        Map<String, List<String>> merged = new LinkedHashMap<>();
        for (Copy copy : copies) {
            for (Map.Entry<String, List<String>> repository : copy.repositories().entrySet()) {
                List<String> layouts = merged.computeIfAbsent(repository.getKey(), name -> new ArrayList<>());
                for (String layout : repository.getValue()) {
                    if (!layouts.contains(layout)) layouts.add(layout);
                }
            }
        }
        return merged;
    }

    /** An entry's addresses as URLs: its own, then each repository it names, with {@code values} filled in. */
    private static List<String> expand(Entry entry, Map<String, String> values,
                                       Map<String, List<String>> repositories) {
        List<String> urls = new ArrayList<>();
        for (Address address : entry.addresses()) {
            if (address.url() != null) {
                urls.add(fill(address.url(), values));
                continue;
            }
            String coordinates = fill(address.coordinates(), values);
            List<String> layouts = repositories.get(address.repository());
            if (layouts == null || !isCoordinates(coordinates)) continue;
            for (String layout : layouts) {
                urls.add(layout.replace("{path}", mavenPath(coordinates)).replace("{file}", mavenFile(coordinates)));
            }
        }
        return urls;
    }

    /** The exact entry for {@code id}, else the first template it fits. */
    private static @Nullable Match match(List<Entry> entries, String id) {
        for (Entry entry : entries) {
            if (!entry.isTemplate() && entry.id().equals(id)) return new Match(entry, Collections.emptyMap());
        }
        for (Entry entry : entries) {
            if (!entry.isTemplate()) continue;
            Map<String, String> values = bind(entry.id(), id);
            if (values != null) return new Match(entry, values);
        }
        return null;
    }

    /** What each placeholder of {@code template} stands for in {@code id}, or null when it does not fit. */
    private static @Nullable Map<String, String> bind(String template, String id) {
        StringBuilder regex = new StringBuilder();
        List<String> names = new ArrayList<>();
        Matcher placeholder = PLACEHOLDER.matcher(template);
        int at = 0;
        while (placeholder.find()) {
            regex.append(Pattern.quote(template.substring(at, placeholder.start())));
            // One path segment: a placeholder never swallows a slash.
            regex.append("([^/]+)");
            names.add(placeholder.group(1));
            at = placeholder.end();
        }
        regex.append(Pattern.quote(template.substring(at)));
        Matcher fitted = Pattern.compile(regex.toString()).matcher(id);
        if (!fitted.matches()) return null;
        Map<String, String> values = new LinkedHashMap<>();
        for (int group = 0; group < names.size(); group++) values.put(names.get(group), fitted.group(group + 1));
        return values;
    }

    private static String fill(String text, Map<String, String> values) {
        String filled = text;
        for (Map.Entry<String, String> value : values.entrySet()) {
            filled = filled.replace("{" + value.getKey() + "}", value.getValue());
        }
        return filled;
    }

    /**
     * The file Maven stores {@code coordinates} as: {@code org.benf:cfr:0.152} is {@code cfr-0.152.jar},
     * {@code net.fabricmc:intermediary:1.20.1:v2} is {@code intermediary-1.20.1-v2.jar}, and an
     * {@code @zip} suffix names the extension.
     */
    static String mavenFile(String coordinates) {
        String[] parts = partsOf(coordinates);
        return parts[1] + "-" + parts[2] + (parts.length > 3 ? "-" + parts[3] : "") + "." + extensionOf(coordinates);
    }

    /** Where a Maven repository keeps it: {@code org/benf/cfr/0.152/cfr-0.152.jar}. */
    static String mavenPath(String coordinates) {
        String[] parts = partsOf(coordinates);
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + mavenFile(coordinates);
    }

    /** {@code group:artifact:version}, then optionally {@code :classifier} and {@code @extension}. */
    private static boolean isCoordinates(String coordinates) {
        String[] parts = partsOf(coordinates);
        if (parts.length < 3 || parts.length > 4) return false;
        for (String part : parts) {
            if (part.isEmpty()) return false;
        }
        return !extensionOf(coordinates).isEmpty();
    }

    private static String[] partsOf(String coordinates) {
        int at = coordinates.indexOf('@');
        return (at < 0 ? coordinates : coordinates.substring(0, at)).split(":", -1);
    }

    private static String extensionOf(String coordinates) {
        int at = coordinates.indexOf('@');
        return at < 0 ? "jar" : coordinates.substring(at + 1);
    }

    /** A copy somebody meant to be read: the jar's, or an override. Said once when it cannot be. */
    private static Copy readable(@Nullable String text, String what, String product) {
        if (text == null) return EMPTY;
        Copy copy = parse(text);
        if (copy == null) {
            System.err.println("[" + product + "] " + what + " is not a locations file this jar can read; ignoring it");
            return EMPTY;
        }
        return copy;
    }

    /** One copy, or null when it is not JSON, holds what this jar cannot read, or is in another format. */
    private static @Nullable Copy parse(String text) {
        try {
            CgDynamicOps<JsonElement> ops = CgJsonOps.INSTANCE;
            CgCodecs.MapCodecReader<JsonElement> file = CgCodecs.read(ops, CgJsonOps.fromText(text));
            if (file.optional("format", CgCodecs.INT, FORMAT) != FORMAT) return null;

            List<Entry> entries = new ArrayList<>();
            for (Map.Entry<String, Spec> member : file.optional("files", FILES, Collections.<String, Spec>emptyMap()).entrySet()) {
                Spec spec = member.getValue();
                List<Address> addresses = new ArrayList<>();
                for (String url : spec.urls()) addresses.add(new Address(url, null, null));
                if (spec.maven() != null && isCoordinates(spec.maven())) {
                    for (String repository : spec.from()) addresses.add(new Address(null, repository, spec.maven()));
                }
                entries.add(new Entry(member.getKey(), spec.digest(), addresses));
            }

            if (file.has("engines")) {
                CgCodecs.MapCodecReader<JsonElement> engines = CgCodecs.read(ops, file.raw("engines"));
                List<String> from = engines.optional("from", STRINGS, Collections.<String>emptyList());
                Map<String, Map<String, String>> bands = engines.optional("bands", BANDS, Collections.<String, Map<String, String>>emptyMap());
                for (Map.Entry<String, Map<String, String>> band : bands.entrySet()) {
                    for (Map.Entry<String, String> jar : band.getValue().entrySet()) {
                        String coordinates = jar.getKey();
                        if (!isCoordinates(coordinates)) continue;
                        List<Address> addresses = new ArrayList<>();
                        for (String repository : from) addresses.add(new Address(null, repository, coordinates));
                        entries.add(new Entry("engine/" + band.getKey() + "/" + mavenFile(coordinates), jar.getValue(), addresses));
                    }
                }
            }
            Mirror mirror = Mirror.NONE;
            if (file.has("mirror")) {
                CgCodecs.MapCodecReader<JsonElement> block = CgCodecs.read(ops, file.raw("mirror"));
                mirror = new Mirror(block.optional("repository", CgCodecs.STRING, null),
                        block.optional("release", CgCodecs.STRING, null), block.optional("github", CgCodecs.STRING, null),
                        block.optional("licences", LICENCES, Collections.<String, String>emptyMap()));
            }
            return new Copy(file.optional("self", STRINGS, Collections.<String>emptyList()),
                    file.optional("repositories", REPOSITORIES, Collections.<String, List<String>>emptyMap()), entries, mirror);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private @Nullable String resourceText() {
        try (InputStream in = source.anchor().getResourceAsStream(source.resource())) {
            return in == null ? null : new String(readBounded(in), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private @Nullable String overrideText() {
        String path = System.getProperty(source.overrideProperty());
        if (path == null || path.trim().isEmpty()) return null;
        try (InputStream in = Files.newInputStream(Paths.get(path.trim()))) {
            return new String(readBounded(in), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            System.err.println("[" + source.name() + "] " + source.overrideProperty() + " could not be read (" + unreadable + ")");
            return null;
        }
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_BYTES) throw new IOException("larger than the locations file ever is");
        }
        return out.toByteArray();
    }
}
