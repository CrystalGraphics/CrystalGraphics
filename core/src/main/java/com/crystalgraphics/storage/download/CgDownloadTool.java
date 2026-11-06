package com.crystalgraphics.storage.download;

import com.crystalgraphics.storage.cache.CgCacheFiles;
import com.crystalgraphics.storage.download.CgDownloadLocations.Line;
import com.crystalgraphics.storage.download.CgDownloadLocations.Listing;
import com.crystalgraphics.storage.download.CgDownloadLocations.Mirror;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The build's checks of a product's {@code download/locations.json}, over the code the runtime reads it with. Run by the
 * Gradle tasks {@code checkDownloadLocations}, {@code verifyDownloadLocations} and {@code stageDownloadMirror}
 * ({@code cgbuildlogic.registerDownloadTasks}); a non-zero exit is the failure.
 *
 * <pre>{@code
 * check  <file>                          well-formed: pins, https, mirrored files pinned and licensed
 * verify <file>                          every address answers with the pinned bytes; online
 * stage  <file> <dir> [<local file>...]  the mirror's files into <dir>, verified, and the commands to upload them
 * }</pre>
 *
 * The file's {@code mirror} block names the mirror's repository, its GitHub release and the licence each mirrored file
 * is redistributed under:
 *
 * <pre>{@code
 * "mirror": { "repository": "mirror", "release": "download-mirror", "github": "owner/repo",
 *             "licences": { "cfr-": "MIT, (c) Lee Benfield", "intermediary-": "CC0-1.0" } }
 * }</pre>
 *
 * Publishing is never done here: {@code stage} prints the commands.
 */
public final class CgDownloadTool {

    private static final Pattern PIN = Pattern.compile("(md5|sha1|gitblob):[0-9a-f]+");

    /** A template's address has no bytes until filled, so it is probed at a version somebody runs. */
    private static final String[][] SAMPLES = {{"{minecraft}", "1.20.1"}, {"{java}", "17"}, {"{version}", "39-1.12"}};

    private CgDownloadTool() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException("usage: check|verify|stage <file> ...");
        Path file = Paths.get(args[1]);
        Listing listing = CgDownloadLocations.listing(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        switch (args[0]) {
            case "check":
                check(listing);
                break;
            case "verify":
                verify(listing);
                break;
            case "stage":
                if (args.length < 3) throw new IllegalArgumentException("stage <file> <dir> [<local file>...]");
                stage(listing, Paths.get(args[2]), args.length > 3 ? java.util.Arrays.asList(args).subList(3, args.length)
                        : Collections.<String>emptyList());
                break;
            default:
                throw new IllegalArgumentException("unknown command " + args[0]);
        }
    }

    /** What is wrong with {@code listing} on its face: empty when nothing is. */
    static List<String> offences(Listing listing) {
        List<String> offences = new ArrayList<>();
        if (listing.self().isEmpty() && !listing.lines().isEmpty()) offences.add("'self' is empty, so a released jar could not re-read this file");
        for (Map.Entry<String, List<String>> repository : listing.repositories().entrySet()) {
            for (String layout : repository.getValue()) {
                if (!layout.contains("{path}") && !layout.contains("{file}")) {
                    offences.add("repository " + repository.getKey() + ": " + layout + " holds neither {path} nor {file}");
                }
            }
        }
        Mirror mirror = listing.mirror();
        Set<String> addresses = new LinkedHashSet<>(listing.self());
        for (List<String> layouts : listing.repositories().values()) addresses.addAll(layouts);
        for (Line line : listing.lines()) {
            addresses.addAll(line.urls());
            String pin = line.digest();
            if (line.urls().isEmpty()) offences.add(line.id() + " has no address");
            if (line.template() && pin != null) offences.add(line.id() + " is a template, and a template is never pinned");
            if (line.template() && line.mirrorFile() != null) {
                offences.add(line.id() + " is a template; only a pinned download can be mirrored");
            }
            if (pin != null && !PIN.matcher(pin).matches()) {
                offences.add(line.id() + ": '" + pin + "' is not md5:, sha1: or gitblob: over lower-case hex");
            }
            if (line.mirrorFile() != null) {
                if (pin == null) offences.add(line.id() + " is mirrored and pins nothing, so no jar could check the copy");
                if (mirror.licenceOf(line.mirrorFile()) == null) {
                    offences.add(line.mirrorFile() + " has no entry in the file's mirror.licences, so it may not be mirrored");
                }
                if (mirror.release() == null || mirror.github() == null) {
                    offences.add(line.id() + " is mirrored, but the mirror block names no release and github repository");
                }
            }
        }
        for (String address : addresses) {
            if (!address.startsWith("https://")) offences.add(address + " is not https");
        }
        return offences;
    }

    static void check(Listing listing) {
        List<String> offences = offences(listing);
        if (!offences.isEmpty()) throw new IllegalStateException("locations file:\n  " + String.join("\n  ", offences));
        System.out.println("download locations OK: " + listing.lines().size() + " downloads");
    }

    static void verify(Listing listing) throws IOException {
        Path scratch = Files.createTempFile("cg-verify", ".bin");
        try {
            List<String> unserved = new ArrayList<>();
            int live = 0;
            int dead = 0;
            List<Line> lines = new ArrayList<>(listing.lines());
            lines.add(new Line("self", null, listing.self(), listing.self(), null, false));
            for (Line line : lines) {
                int served = 0;
                for (String template : line.urls()) {
                    String url = template;
                    for (String[] sample : SAMPLES) url = url.replace(sample[0], sample[1]);
                    String why = fetchInto(url, scratch, line.template());
                    if (why == null && line.digest() != null && !CgCacheFiles.isValid(scratch, line.digest())) {
                        why = "other bytes than the pin";
                    }
                    if (why == null) {
                        served++;
                    } else {
                        System.err.println(line.id() + ": " + url + " -- " + why);
                    }
                }
                if (served == 0) unserved.add(line.id());
                live += served;
                dead += line.urls().size() - served;
            }
            if (!unserved.isEmpty()) throw new IllegalStateException("no address serves: " + String.join(", ", unserved));
            System.out.println("every download is served, by " + live + " of " + (live + dead) + " addresses"
                    + (dead == 0 ? "" : "; the dead ones are warned above"));
        } finally {
            Files.deleteIfExists(scratch);
        }
    }

    static void stage(Listing listing, Path dir, List<String> localFiles) throws IOException {
        check(listing);
        Mirror mirror = listing.mirror();
        deleteTree(dir);
        Files.createDirectories(dir);
        Map<String, Path> local = new LinkedHashMap<>();
        for (String name : localFiles) local.put(Paths.get(name).getFileName().toString(), Paths.get(name));
        List<String> rows = new ArrayList<>();
        for (Line line : listing.lines()) {
            String name = line.mirrorFile();
            if (name == null) continue;
            String pin = line.digest();
            Path target = dir.resolve(name);
            if (Files.exists(target)) {
                if (!CgCacheFiles.isValid(target, pin)) throw new IllegalStateException(name + " is named by two downloads with different pins");
                continue;
            }
            Path have = local.get(name);
            if (have != null && CgCacheFiles.isValid(have, pin)) {
                Files.copy(have, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                boolean fetched = false;
                for (String url : line.upstream()) {
                    if (fetchInto(url, target, false) == null && CgCacheFiles.isValid(target, pin)) {
                        fetched = true;
                        break;
                    }
                }
                if (!fetched) {
                    Files.deleteIfExists(target);
                    throw new IllegalStateException(line.id() + ": no upstream address serves the pinned bytes");
                }
            }
            rows.add("| `" + name + "` | " + mirror.licenceOf(name) + " | `" + line.id() + "` |");
        }
        StringBuilder notice = new StringBuilder("# ").append(mirror.release()).append("\n\n")
                .append("Unmodified copies of files ").append(mirror.github()).append(" downloads at runtime, so a released jar still\n")
                .append("finds them if their publisher moves them. Every jar checks each file against the digest it shipped with,\n")
                .append("so this release can only ever serve the bytes the publisher did. `download/locations.json` lists them.\n\n")
                .append("Each file keeps its own licence.\n\n| File | Licence | Id |\n|---|---|---|\n");
        for (String row : rows) notice.append(row).append('\n');
        Path noticeFile = dir.resolve("NOTICE.md");
        Files.write(noticeFile, notice.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder files = new StringBuilder();
        try (java.util.stream.Stream<Path> list = Files.list(dir)) {
            list.sorted().forEach(p -> files.append(" \"").append(p.toAbsolutePath().toString().replace('\\', '/')).append('"'));
        }
        String notes = noticeFile.toAbsolutePath().toString().replace('\\', '/');
        System.out.println("Staged " + rows.size() + " files in " + dir.toAbsolutePath().toString().replace('\\', '/')
                + ". Publishing is yours to run:\n\n"
                + "  gh release create " + mirror.release() + " --repo " + mirror.github() + " --target master --latest=false "
                + "--title \"" + mirror.release() + "\" --notes-file \"" + notes + "\"    (once)\n"
                + "  gh release upload " + mirror.release() + " --repo " + mirror.github() + " --clobber" + files);
    }

    /** Fetches {@code url} into {@code into}: null when it arrived, else why not. {@code probe} reads one byte only. */
    private static String fetchInto(String url, Path into, boolean probe) {
        try (CgDownloads.Body body = CgDownloads.fetch(url)) {
            InputStream in = body.stream();
            if (probe) return in.read() < 0 ? "an empty body" : null;
            long copied = Files.copy(in, into, StandardCopyOption.REPLACE_EXISTING);
            return copied == 0 ? "an empty body" : null;
        } catch (IOException | RuntimeException unreachable) {
            return unreachable.toString();
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            Collections.reverse(all);
            for (Path p : all) Files.deleteIfExists(p);
        }
    }
}
