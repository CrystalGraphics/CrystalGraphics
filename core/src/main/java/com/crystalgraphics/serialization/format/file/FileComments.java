package com.crystalgraphics.serialization.format.file;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A person's comments in a file about to be replaced, by key {@link #path}: the lines above a key, the one after its
 * value, the file's header (above {@code ""}) and what follows its last value. Each format keeps its own comment
 * syntax in the text: a TOML line without its {@code #}, a JSONC comment whole.
 */
final class FileComments {

    final Map<String, List<String>> above = new HashMap<>();
    final Map<String, String> after = new HashMap<>();
    final List<String> end = new ArrayList<>();

    /** A key path: {@code segment} under {@code parent}, a list element as {@code #index}; null stays null. */
    @Nullable
    static String path(@Nullable String parent, String segment) {
        if (parent == null) return null;
        return parent.isEmpty() ? segment : parent + '\u001f' + segment;
    }

    /** What a person wrote above {@code path}, or null where they wrote nothing. */
    @Nullable
    static List<String> above(@Nullable FileComments theirs, String path) {
        List<String> lines = theirs == null ? null : theirs.above.get(path);
        return lines == null || lines.isEmpty() ? null : lines;
    }
}
