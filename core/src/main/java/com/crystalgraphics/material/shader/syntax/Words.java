package com.crystalgraphics.material.shader.syntax;

import com.github.bsideup.jabel.Desugar;

import java.util.ArrayList;
import java.util.List;

/**
 * A block body as words: each run of tokens with nothing between them ({@code mymod:Ribbons}, {@code -1},
 * {@code cg_Vfx.presence}), and each {@code {}, {@code }}} and {@code ,} a word alone. What the settings and render-state
 * grammars read.
 */
final class Words {

    @Desugar
    record Word(String text, int first, int last) {
        boolean is(String s) {
            return text.equals(s);
        }
    }

    private Words() {}

    static List<Word> of(CgShaderTokens t, int from, int to) {
        List<Word> out = new ArrayList<>();
        int i = from;
        while (i < to) {
            int first = i;
            if (!alone(t, i)) {
                while (i + 1 < to && t.touchesNext(i) && !alone(t, i + 1)) i++;
            }
            out.add(new Word(t.slice(first, i), first, i));
            i++;
        }
        return out;
    }

    private static boolean alone(CgShaderTokens t, int i) {
        return t.is(i, '{') || t.is(i, '}') || t.is(i, ',');
    }
}
