package com.crystalgraphics.mc.item;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * What a recipe slot accepts and shows: one stack, several alternatives, or every item in an item tag.
 *
 * <pre>{@code
 * CgIngredient planks = CgIngredient.tag("#minecraft:planks");        // every plank, cycling where it is shown
 * CgIngredient either = CgIngredient.of(CgItemStack.of("minecraft:coal", 1), CgItemStack.of("minecraft:charcoal", 1));
 * CgIngredient parsed = CgIngredient.parse("#minecraft:logs");        // or a stack's /give text, or several with |
 * List<CgItemStack> shown = planks.items();                           // the tag's items, from the game
 * boolean fits = planks.test(CgItemStack.of("minecraft:oak_planks", 1));
 * }</pre>
 *
 * <ul>
 *   <li>A tag is resolved by the game when asked ({@link #items}, {@link #test}): none before 1.13, which has no item
 *       tags, and none without a game.</li>
 *   <li>{@link #toString} is what {@link #parse} reads: {@code #tag}, or the stacks' text joined by {@code " | "}.</li>
 * </ul>
 */
public final class CgIngredient {

    public static final CgIngredient EMPTY = new CgIngredient(null, Collections.emptyList());

    @Nullable private final String tag;
    private final List<CgItemStack> stacks;

    private CgIngredient(@Nullable String tag, List<CgItemStack> stacks) {
        this.tag = tag;
        this.stacks = Collections.unmodifiableList(new ArrayList<>(stacks));
    }

    public static CgIngredient of(CgItemStack stack) {
        return new CgIngredient(null, Collections.singletonList(stack));
    }

    public static CgIngredient of(CgItemStack... stacks) {
        return new CgIngredient(null, Arrays.asList(stacks));
    }

    public static CgIngredient of(List<CgItemStack> stacks) {
        return new CgIngredient(null, stacks);
    }

    /** Every item in item tag {@code tag}: {@code #minecraft:planks}; the {@code #} and namespace are optional. */
    public static CgIngredient tag(String tag) {
        String t = tag.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.isEmpty()) throw new IllegalArgumentException("a tag needs a name");
        return new CgIngredient(t.indexOf(':') < 0 ? "minecraft:" + t : t, Collections.emptyList());
    }

    /** {@code #tag}, or one or more stacks' {@code /give} text joined by {@code |}. */
    public static CgIngredient parse(String text) {
        String t = text.trim();
        if (t.startsWith("#")) return tag(t);
        List<CgItemStack> out = new ArrayList<>();
        for (String part : alternatives(t)) out.add(CgItemStack.parse(part));
        return of(out);
    }

    // Each | outside quotes and brackets: a stack's data may hold one in a string
    private static List<String> alternatives(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            } else if (c == '|' && depth == 0) {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        out.add(text.substring(start));
        return out;
    }

    /** The tag, without its {@code #}; null for stacks. */
    @Nullable
    public String tag() {
        return tag;
    }

    public boolean isTag() {
        return tag != null;
    }

    /** The stacks given in Java or parsed; empty for a tag. */
    public List<CgItemStack> stacks() {
        return stacks;
    }

    /** What the slot shows: the stacks, or one of each item in the tag, from the game. */
    public List<CgItemStack> items() {
        if (tag == null) return stacks;
        List<CgItemStack> out = new ArrayList<>();
        for (String item : CgItems.tagItems(tag)) out.add(CgItemStack.of(item, 1));
        return out;
    }

    public boolean isEmpty() {
        return tag == null && stacks.isEmpty();
    }

    /** Whether {@code stack} fits: its item is one of the stacks' items, or in the tag. */
    public boolean test(CgItemStack stack) {
        if (stack.isEmpty()) return false;
        if (tag != null) return CgItems.tagItems(tag).contains(stack.item());
        for (CgItemStack s : stacks) if (s.item().equals(stack.item())) return true;
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgIngredient)) return false;
        CgIngredient other = (CgIngredient) o;
        return Objects.equals(tag, other.tag) && stacks.equals(other.stacks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tag, stacks);
    }

    @Override
    public String toString() {
        if (tag != null) return "#" + tag;
        StringBuilder out = new StringBuilder();
        for (CgItemStack s : stacks) {
            if (out.length() > 0) out.append(" | ");
            out.append(s);
        }
        return out.toString();
    }
}
