package com.crystalgraphics.mc;

import java.text.ParsePosition;

import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtTag;

/**
 * A cursor over a command's text, for the values that parse from one ({@code CgItemStack.parse},
 * {@code CgBlockState.parse}, ...): Brigadier's {@code StringReader} shape, with the pieces those commands share.
 *
 * <pre>{@code
 * CgCommandReader r = new CgCommandReader("minecraft:furnace[lit=true]{BurnTime:200s}");
 * String block = r.readId();                    // "minecraft:furnace"
 * if (r.skip('[')) { ... r.readWord() ... }     // lit, =, true
 * CgNbtCompound data = r.readTag();             // {BurnTime:200s}
 * r.expectEnd();
 * }</pre>
 *
 * <ul>
 *   <li>An id without a namespace is {@code minecraft:}'s, as the game reads one. Capitals are read: before 1.11 an
 *       entity's id is its name ({@code Sheep}).</li>
 *   <li>Every read that fails throws {@link CgCodecException} naming what it wanted, the position and the text.</li>
 * </ul>
 */
public final class CgCommandReader {

    private final String text;
    private int at;

    public CgCommandReader(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    public int position() {
        return at;
    }

    public boolean atEnd() {
        return at >= text.length();
    }

    /** The next character, or {@code 0} at the end. */
    public char peek() {
        return atEnd() ? 0 : text.charAt(at);
    }

    /** Steps over {@code c} where it is next, answering whether it was. */
    public boolean skip(char c) {
        if (peek() != c) return false;
        at++;
        return true;
    }

    public void expect(char c) {
        if (!skip(c)) throw error("'" + c + "'");
    }

    public void skipSpaces() {
        while (!atEnd() && Character.isWhitespace(text.charAt(at))) at++;
    }

    /** Fails unless only spaces are left. */
    public void expectEnd() {
        skipSpaces();
        if (!atEnd()) throw error("the end");
    }

    /** A resource id, {@code namespace:path}; {@code minecraft:} where it names none. */
    public String readId() {
        int start = at;
        while (!atEnd() && isIdChar(text.charAt(at))) at++;
        if (at == start) throw error("an id");
        String id = text.substring(start, at);
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /** A bare word: letters, digits and {@code _ - . + :}, as a block property's name or value is written. */
    public String readWord() {
        int start = at;
        while (!atEnd() && isWordChar(text.charAt(at))) at++;
        if (at == start) throw error("a word");
        return text.substring(start, at);
    }

    /** An optionally signed whole number. */
    public long readLong() {
        int start = at;
        if (peek() == '-' || peek() == '+') at++;
        while (!atEnd() && Character.isDigit(text.charAt(at))) at++;
        try {
            return Long.parseLong(text.substring(start, at));
        } catch (NumberFormatException e) {
            at = start;
            throw error("a whole number");
        }
    }

    public int readInt() {
        int start = at;
        long v = readLong();
        if (v != (int) v) {
            at = start;
            throw error("a number in int range");
        }
        return (int) v;
    }

    /** One SNBT tag, as {@code /data} writes it. */
    public <T extends CgNbtTag> T readTag() {
        ParsePosition position = new ParsePosition(at);
        T tag = CgNbt.parse(text, position);
        at = position.getIndex();
        return tag;
    }

    /** What failed and where: {@code wanted <what> at 12 in "<text>"}. */
    public CgCodecException error(String wanted) {
        return new CgCodecException("wanted " + wanted + " at " + at + " in \"" + text + "\"");
    }

    private static boolean isIdChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                || c == '_' || c == '-' || c == '.' || c == '/' || c == ':';
    }

    private static boolean isWordChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                || c == '_' || c == '-' || c == '.' || c == '+' || c == ':';
    }
}
