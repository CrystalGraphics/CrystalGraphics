package com.crystalgraphics.compute.parse;

import com.crystalgraphics.gl.material.parse.CgShaderParseException;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text-level GLSL helpers the scanner and the analysis share. Offsets into a blanked text are offsets into the text. */
final class GlslText {

    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private GlslText() {}

    /** Comments as spaces, newlines kept, quoted text left alone: the same length and lines as {@code text}. */
    static String blankComments(String text) {
        char[] out = text.toCharArray();
        int n = out.length;
        for (int i = 0; i < n; i++) {
            char c = out[i];
            if (c == '"') {
                while (++i < n && out[i] != '"' && out[i] != '\n') { }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) out[i++] = ' ';
                if (i < n) out[i] = ' ';
            }
        }
        return new String(out);
    }

    /** The {@code '}'} closing the {@code '{'} at {@code open}. */
    static int matchBrace(String text, int open, String path) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        throw CgShaderParseException.at("[" + path + "] '{' is never closed", text, open);
    }

    /** The end of the line holding {@code at}: its newline's offset, or the text's length. */
    static int lineEnd(String text, int at) {
        int end = text.indexOf('\n', at);
        return end < 0 ? text.length() : end;
    }

    /** Every identifier in {@code text}, in order of first appearance. */
    static Set<String> identifiers(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = IDENTIFIER.matcher(text);
        while (m.find()) {
            int start = m.start();
            if (start > 0 && Character.isDigit(text.charAt(start - 1))) continue;   // 1e5, 2u
            out.add(m.group());
        }
        return out;
    }

    /** Runs of whitespace as one space, trimmed. */
    static String squash(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
