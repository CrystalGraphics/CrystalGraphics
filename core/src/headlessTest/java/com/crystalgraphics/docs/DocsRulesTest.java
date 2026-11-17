package com.crystalgraphics.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * The machine-checkable rules of {@code docs/AGENTS.md}, over every page that carries a front-matter header. A page
 * without one is an old page awaiting its rewrite: it is counted, not checked.
 */
public class DocsRulesTest {

    private static final Path REPO = Paths.get("..");
    private static final Path DOCS = REPO.resolve("docs");
    private static final Set<String> KINDS = Set.of("overview", "lesson", "task", "reference", "troubleshooting", "glossary");
    private static final long CLIP_BYTES = 1 << 20;

    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,})(.*)$");
    private static final Pattern LINK = Pattern.compile("(!?)\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern EXAMPLE = Pattern.compile("^<!-- example: (.+?) -->$");
    private static final Pattern ALLOWED_HTML = Pattern.compile("</?(details|summary)>|<!--.*?-->");
    private static final Pattern FILE_NAME = Pattern.compile("README\\.md|[a-z][a-z0-9]*(-[a-z0-9]+)*\\.md");
    private static final Pattern IMAGE = Pattern.compile(".*\\.(webp|gif|png|jpg|svg)");

    @Test
    public void everyRewrittenPageFollowsTheRules() throws IOException {
        List<String> problems = new ArrayList<>();
        List<String> untested = new ArrayList<>();
        int legacy = 0;
        int checked = 0;
        for (Path page : pages(DOCS)) {
            String text = read(page);
            if (!text.startsWith("---\n")) {
                legacy++;
                continue;
            }
            checked++;
            problems.addAll(problems(page, text, true, untested));
        }
        System.out.println("DocsRulesTest: " + checked + " pages checked, " + legacy + " old pages await their rewrite");
        untested.forEach(u -> System.out.println("DocsRulesTest: untested example: " + u));
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    /** The templates follow the shapes they teach; their links point at pages that do not exist, so are not resolved. */
    @Test
    public void theTemplatesFollowTheRules() throws IOException {
        List<String> problems = new ArrayList<>();
        Path templates = DOCS.resolve("_templates");
        try (Stream<Path> s = Files.list(templates)) {
            for (Path page : s.filter(p -> p.toString().endsWith(".md")).sorted().collect(Collectors.toList())) {
                problems.addAll(problems(page, read(page), false, new ArrayList<>()));
            }
        }
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    @Test
    public void aBrokenPageIsCaught() {
        String page = """
                ---
                title: Broken
                kind: lesson
                ---

                # Not the title

                ## A `coded` heading

                A bare <V> and a {brace}.

                ```
                no language
                ```

                [into the plan](../../plan/x.md) and ![](img/x.webp)
                """;
        List<String> found = problems(Paths.get("docs", "01-Broken.md"), page, false, new ArrayList<>());
        String all = String.join("\n", found);
        for (String expected : List.of("lowercase-kebab", "lacks description", "lacks sidebar_position", "H1",
                "heading has code", "bare <", "bare {", "names no language", "into plan/", "no alt text",
                "You should see", "Try it yourself")) {
            assertTrue("missed '" + expected + "' in:\n" + all, all.contains(expected));
        }
    }

    // ---- the checks ----

    static List<String> problems(Path page, String text, boolean resolve, List<String> untested) {
        List<String> out = new ArrayList<>();
        Path abs = page.toAbsolutePath().normalize();
        Path docs = DOCS.toAbsolutePath().normalize();
        String where = (abs.startsWith(docs) ? docs.relativize(abs) : page).toString().replace('\\', '/');
        String name = page.getFileName().toString();
        if (!FILE_NAME.matcher(name).matches()) out.add(where + ": file name must be lowercase-kebab with no number prefix");

        int end = text.indexOf("\n---\n", 4);
        if (!text.startsWith("---\n") || end < 0) {
            out.add(where + ": no front-matter header");
            return out;
        }
        Map<String, String> header = new LinkedHashMap<>();
        for (String line : text.substring(4, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && !line.startsWith(" ")) header.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        String title = header.get("title");
        String kind = header.get("kind");
        for (String key : List.of("title", "description", "sidebar_position", "kind")) {
            if (header.get(key) == null || header.get(key).isEmpty()) out.add(where + ": header lacks " + key);
        }
        if (kind != null && !KINDS.contains(kind)) out.add(where + ": kind '" + kind + "' is none of " + KINDS);
        if (header.containsKey("sidebar_position") && !header.get("sidebar_position").matches("\\d+")) {
            out.add(where + ": sidebar_position is not a whole number");
        }

        String body = text.substring(end + 5);
        String[] lines = body.split("\n", -1);
        int first = (int) text.substring(0, end + 5).chars().filter(c -> c == '\n').count() + 1;   // the page's line of body line 0
        Path dir = page.toAbsolutePath().getParent();
        int h1 = 0;
        String fence = null;
        int firstSection = -1;
        int firstImage = -1;
        Set<String> linked = new HashSet<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher f = FENCE.matcher(line);
            if (f.matches()) {
                if (fence == null) {
                    fence = f.group(1);
                    if (f.group(2).trim().isEmpty()) out.add(where + ":" + (i + first) + ": a code block names no language");
                } else if (f.group(1).equals(fence) && f.group(2).trim().isEmpty()) {
                    fence = null;
                }
                continue;
            }
            if (fence != null) continue;

            if (line.startsWith("# ")) {
                h1++;
                if (title != null && !line.substring(2).trim().equals(title)) {
                    out.add(where + ":" + (i + first) + ": the H1 is not the header's title");
                }
            }
            if (line.matches("#{1,6} .*") && line.contains("`")) out.add(where + ":" + (i + first) + ": a heading has code in it");
            if (line.startsWith("## ") && firstSection < 0) firstSection = i;

            Matcher ex = EXAMPLE.matcher(line.trim());
            if (ex.matches()) {
                String ref = ex.group(1);
                if (ref.startsWith("untested")) untested.add(where + ":" + (i + first) + ": " + ref);
                else if (resolve) out.addAll(example(where + ":" + (i + first), ref, lines, i + 1));
            }

            String prose = line.replaceAll("`[^`]*`", "");
            String bare = ALLOWED_HTML.matcher(prose).replaceAll("");
            if (bare.contains("<")) out.add(where + ":" + (i + first) + ": bare < outside code (put it in backticks)");
            if (bare.contains("{")) out.add(where + ":" + (i + first) + ": bare { outside code (put it in backticks)");

            Matcher l = LINK.matcher(prose);
            while (l.find()) {
                boolean image = !l.group(1).isEmpty();
                String target = l.group(3);
                if (image && firstImage < 0) firstImage = i;
                if (image && l.group(2).trim().isEmpty()) out.add(where + ":" + (i + first) + ": an image has no alt text");
                if (target.startsWith("http://") || target.startsWith("https://")) continue;
                if (target.contains("plan/")) {
                    out.add(where + ":" + (i + first) + ": a link into plan/");
                    continue;
                }
                String path = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
                String anchor = target.contains("#") ? target.substring(target.indexOf('#') + 1) : null;
                if (!path.isEmpty() && !path.endsWith(".md") && !IMAGE.matcher(path).matches()) {
                    out.add(where + ":" + (i + first) + ": a link to '" + target + "' is neither a page nor a picture (code links are GitHub URLs)");
                    continue;
                }
                if (!resolve) continue;
                Path file = path.isEmpty() ? page.toAbsolutePath() : dir.resolve(path).normalize();
                if (!Files.exists(file)) {
                    out.add(where + ":" + (i + first) + ": '" + target + "' does not exist");
                    continue;
                }
                linked.add(file.toString());
                if (image && path.matches(".*\\.(webp|gif)") && size(file) > CLIP_BYTES) {
                    out.add(where + ":" + (i + first) + ": '" + path + "' is over 1 MB");
                }
                if (anchor != null && path.endsWith(".md") || anchor != null && path.isEmpty()) {
                    if (!anchors(read(file)).contains(anchor)) out.add(where + ":" + (i + first) + ": no heading '#" + anchor + "' in " + (path.isEmpty() ? "this page" : path));
                }
            }
        }
        if (h1 != 1) out.add(where + ": " + h1 + " H1 headings; a page has exactly one");

        if ("lesson".equals(kind)) {
            if (!body.contains("**You should see:**")) out.add(where + ": a lesson's steps end in **You should see:**");
            if (!body.contains("**If you don't:**")) out.add(where + ": a lesson's steps say **If you don't:**");
            if (!body.contains("## Try it yourself")) out.add(where + ": a lesson ends with ## Try it yourself");
        }
        if (("task".equals(kind) || "overview".equals(kind) || "lesson".equals(kind))
                && (firstImage < 0 || firstSection >= 0 && firstImage > firstSection)) {
            out.add(where + ": a " + kind + " shows its result in a picture before its first section");
        }
        if ("troubleshooting".equals(kind) && !body.contains("| Symptom |")) {
            out.add(where + ": a troubleshooting page is a | Symptom | Cause | Fix | table");
        }
        if ("overview".equals(kind) && resolve) out.addAll(mapsItsFolder(where, page, linked));
        return out;
    }

    /**
     * An overview links every rewritten page beside it, and each sub-folder's README, or each rewritten page in a
     * sub-folder with none (reference/). Old pages are left to the overview's judgement until they are rewritten.
     */
    private static List<String> mapsItsFolder(String where, Path page, Set<String> linked) {
        List<String> out = new ArrayList<>();
        Path dir = page.toAbsolutePath().getParent();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.sorted().collect(Collectors.toList())) {
                String name = p.getFileName().toString();
                if (p.equals(page.toAbsolutePath()) || name.startsWith("_") || name.equals("img")) continue;
                List<Path> targets = new ArrayList<>();
                if (!Files.isDirectory(p)) {
                    targets.add(p);
                } else if (Files.exists(p.resolve("README.md"))) {
                    targets.add(p.resolve("README.md"));
                } else {
                    try (Stream<Path> inner = Files.list(p)) {
                        inner.sorted().forEach(targets::add);
                    }
                }
                for (Path target : targets) {
                    if (!target.toString().endsWith(".md") || !read(target).startsWith("---\n")) continue;
                    if (!linked.contains(target.normalize().toString())) out.add(where + ": the overview's map lacks " + dir.relativize(target).toString().replace('\\', '/'));
                }
            }
        } catch (IOException e) {
            out.add(where + ": " + e);
        }
        return out;
    }

    /**
     * The tagged block below line {@code at} equals the region it names, indentation aside; a tag with no
     * {@code #region} names the whole file (JSON, which has no comment to mark one).
     */
    private static List<String> example(String where, String ref, String[] lines, int at) {
        int hash = ref.indexOf('#');
        String path = hash < 0 ? ref : ref.substring(0, hash);
        Path file = REPO.resolve(path);
        String region = hash < 0 ? null : ref.substring(hash + 1);
        if (!Files.exists(file)) return List.of(where + ": example file " + path + " does not exist");
        int open = at;
        while (open < lines.length && lines[open].trim().isEmpty()) open++;
        if (open >= lines.length || !FENCE.matcher(lines[open]).matches()) return List.of(where + ": an example tag with no code block under it");
        List<String> block = new ArrayList<>();
        for (int i = open + 1; i < lines.length && !lines[i].trim().matches("`{3,}"); i++) block.add(lines[i]);

        if (region == null) {
            List<String> whole = Stream.of(read(file).split("\n", -1))
                    .filter(l -> !l.contains("docs:begin ") && !l.contains("docs:end ")).collect(Collectors.toList());
            return dedent(block).equals(dedent(whole)) ? List.of() : List.of(where + ": the block differs from " + ref);
        }
        List<String> source = new ArrayList<>();
        boolean in = false;
        boolean found = false;
        Pattern begin = Pattern.compile("docs:begin " + Pattern.quote(region) + "\\b"), end = Pattern.compile("docs:end " + Pattern.quote(region) + "\\b");
        for (String line : read(file).split("\n", -1)) {
            if (end.matcher(line).find()) in = false;
            // a region nested inside this one shows without its markers
            if (in && !line.contains("docs:begin ") && !line.contains("docs:end ")) source.add(line);
            if (begin.matcher(line).find()) in = found = true;
        }
        if (!found) return List.of(where + ": " + file + " has no region '" + region + "'");
        if (!dedent(block).equals(dedent(source))) return List.of(where + ": the block differs from " + ref);
        return List.of();
    }

    private static List<String> dedent(List<String> lines) {
        int indent = lines.stream().filter(l -> !l.isBlank()).mapToInt(l -> l.length() - l.stripLeading().length()).min().orElse(0);
        List<String> out = lines.stream().map(l -> l.isBlank() ? "" : l.substring(indent).stripTrailing()).collect(Collectors.toList());
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        while (!out.isEmpty() && out.get(0).isEmpty()) out.remove(0);
        return out;
    }

    /** GitHub's anchors: a heading lowercased, punctuation dropped, spaces to hyphens. */
    static Set<String> anchors(String page) {
        Set<String> out = new HashSet<>();
        boolean fenced = false;
        for (String line : page.split("\n")) {
            if (FENCE.matcher(line).matches()) fenced = !fenced;
            if (fenced || !line.matches("#{1,6} .*")) continue;
            String text = line.replaceFirst("#{1,6} ", "").replace("`", "").replace("*", "").trim();
            out.add(text.toLowerCase().replaceAll("[^\\p{L}\\p{N} _-]", "").replace(' ', '-'));
        }
        return out;
    }

    private static List<Path> pages(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !root.relativize(p).toString().replace('\\', '/').startsWith("_templates/"))
                    .filter(p -> !p.getFileName().toString().equals("AGENTS.md") && !p.getFileName().toString().equals("CLAUDE.md"))
                    .sorted().collect(Collectors.toList());
        }
    }

    private static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }
}
