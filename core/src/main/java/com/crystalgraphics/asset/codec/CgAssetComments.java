package com.crystalgraphics.asset.codec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.object.CgType;
import com.crystalgraphics.serialization.format.file.YamlDocument;

/**
 * A file's comments moved into {@code editor} ({@code spec/core.md} § The writer's rules), as
 * {@code aaa_write.load} places them: the asset's or an element's {@code note}, or a key's in {@code notes}.
 */
final class CgAssetComments {

    private CgAssetComments() {
    }

    /** One comment: its line, column and text, and whether it has the line to itself. */
    private static final class Comment {
        final int line, column;
        final String text;
        final boolean own;

        Comment(int line, int column, String text, boolean own) {
            this.line = line;
            this.column = column;
            this.text = text;
            this.own = own;
        }
    }

    static void place(String text, YamlDocument doc, Map<Object, Object> root) {
        String[] lines = text.split("\n", -1);
        List<Integer> content = new ArrayList<>();
        for (int n = 0; n < lines.length; n++) {
            String l = strip(lines[n]);
            if (!l.isEmpty() && !l.startsWith("#")) content.add(n);
        }
        for (Comment c : comments(lines)) {
            if (c.text.isEmpty()) continue;
            if (c.own && c.column == 0) {   // a top-level comment is the asset's
                note(root, null, c.text);
                continue;
            }
            Integer at = c.own ? next(content, c.line) : Integer.valueOf(c.line);
            if (at == null) {
                note(root, null, c.text);
                continue;
            }
            YamlDocument.Element el = null;
            for (YamlDocument.Element e : doc.elements()) {
                if (e.start <= at && at <= e.end && (el == null || deeper(e, el))) el = e;
            }
            if (el != null && el.start == at) {   // an element's own line
                note(el.value, null, c.text);
                continue;
            }
            List<Object> base = el == null ? new ArrayList<>() : el.path;
            YamlDocument.Key here = null;
            for (YamlDocument.Key k : doc.keys()) {
                if (k.line != at || !startsWith(k.path, base)) continue;
                if (here == null || k.path.size() < here.path.size()) here = k;
            }
            Map<Object, Object> target = el != null ? el.value : root;
            if (here == null) {
                note(target, null, c.text);
                continue;
            }
            List<Object> path = here.path;
            if (c.own && here.first) path = path.subList(0, path.size() - 1);   // above a map's first key: the map's
            note(target, join(path.subList(base.size(), path.size())), c.text);
        }
    }

    /** Python's {@code max} by (path length, start): the later of two equal ones loses. */
    private static boolean deeper(YamlDocument.Element e, YamlDocument.Element than) {
        if (e.path.size() != than.path.size()) return e.path.size() > than.path.size();
        return e.start > than.start;
    }

    @Nullable
    private static Integer next(List<Integer> content, int line) {
        for (Integer c : content) {
            if (c > line) return c;
        }
        return null;
    }

    private static boolean startsWith(List<Object> path, List<Object> base) {
        return path.size() >= base.size() && path.subList(0, base.size()).equals(base);
    }

    /** A key path as Python's {@code '.'.join(map(str, rel))} spells it. */
    private static String join(List<Object> rel) {
        StringBuilder b = new StringBuilder();
        for (Object o : rel) {
            if (b.length() > 0) b.append('.');
            b.append(o instanceof Boolean ? ((Boolean) o ? "True" : "False") : String.valueOf(o));
        }
        return b.toString();
    }

    private static void note(Map<Object, Object> target, @Nullable String key, String text) {
        Object ed = target.get(CgType.EDITOR);
        if (ed == null && !target.containsKey(CgType.EDITOR)) {
            ed = new LinkedHashMap<>();
            target.put(CgType.EDITOR, ed);
        }
        if (!(ed instanceof Map)) return;
        Map<Object, Object> editor = CgAssetYaml.map(ed);
        if (editor.containsKey("notes") && !(editor.get("notes") instanceof Map)) {
            // `notes` written by hand as text: a key's comment has nowhere to go but the note.
            key = null;
        }
        if (key == null || key.isEmpty()) {
            Object was = editor.get("note");
            editor.put("note", was instanceof String && !((String) was).isEmpty() ? was + "; " + text : text);
            return;
        }
        Object notes = editor.get("notes");
        if (notes == null && !editor.containsKey("notes")) {
            notes = new LinkedHashMap<>();
            editor.put("notes", notes);
        }
        Map<Object, Object> by = CgAssetYaml.map(notes);
        Object was = by.get(key);
        by.put(key, was instanceof String && !((String) was).isEmpty() ? was + "; " + text : text);
    }

    /** Every comment outside quotes: {@code #} at a line's start or after a space or tab. */
    private static List<Comment> comments(String[] lines) {
        List<Comment> out = new ArrayList<>();
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            char q = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (q != 0) {
                    if (c == '\\' && q == '"') i++;
                    else if (c == q) q = 0;
                } else if ((c == '"' || c == '\'') && (i == 0 || " [{,:-".indexOf(line.charAt(i - 1)) >= 0)) {
                    q = c;
                } else if (c == '#' && (i == 0 || line.charAt(i - 1) == ' ' || line.charAt(i - 1) == '\t')) {
                    out.add(new Comment(n, i, strip(line.substring(i + 1)), strip(line.substring(0, i)).isEmpty()));
                    break;
                }
            }
        }
        return out;
    }

    /** Python's {@code str.strip()}: whitespace off both ends. */
    private static String strip(String s) {
        int a = 0, b = s.length();
        while (a < b && space(s.charAt(a))) a++;
        while (b > a && space(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean space(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u001c' || c == '\u001d' || c == '\u001e'
                || c == '\u001f' || c == '\u0085';
    }
}
