package com.crystalgraphics.material.shader.syntax;

import java.util.Arrays;

/**
 * A shader file's text and where it came from: what every token, node and error points into.
 *
 * <pre>{@code
 * CgShaderText text = new CgShaderText(source, "mymod:shaders/glass.shader");
 * throw CgShaderError.at(text, offset, "'Pass' is not written in a .shader");
 * }</pre>
 */
public final class CgShaderText {

    private final String source, path;
    /** Each line's first offset, made the first time a position is asked: a file that reads cleanly never needs it. */
    private int[] lineStarts;

    public CgShaderText(String source, String path) {
        this.source = source;
        this.path = path;
    }

    public String source() {
        return source;
    }

    public String path() {
        return path;
    }

    public int length() {
        return source.length();
    }

    public char charAt(int i) {
        return source.charAt(i);
    }

    public String slice(int start, int end) {
        return source.substring(start, end);
    }

    /** The 1-based line holding {@code offset}. */
    public int line(int offset) {
        int[] starts = lineStarts();
        int at = Arrays.binarySearch(starts, Math.max(0, Math.min(offset, source.length())));
        return at >= 0 ? at + 1 : -at - 1;
    }

    /** The 1-based column of {@code offset} on its line. */
    public int column(int offset) {
        return Math.max(0, Math.min(offset, source.length())) - lineStarts()[line(offset) - 1] + 1;
    }

    private int[] lineStarts() {
        int[] starts = lineStarts;
        if (starts != null) return starts;
        int n = 1;
        for (int i = 0; i < source.length(); i++) if (source.charAt(i) == '\n') n++;
        starts = new int[n];
        for (int i = 0, k = 1; i < source.length(); i++) if (source.charAt(i) == '\n') starts[k++] = i + 1;
        return lineStarts = starts;
    }
}
