package com.crystalgraphics.compute.cpu;

import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgElementField;

import java.util.Arrays;

/**
 * A buffer as the CPU tier sees it: the view a dispatch bound, as 32-bit words, addressed by element and by word
 * within the element. A field's word comes from the {@code .compute}'s declaration, so a body never hard-codes the
 * layout std430 gives a struct.
 *
 * <pre>{@code
 * CgCpuBuffer state = d.buffer("STATE");
 * int life = state.field("positionLife").word() + 3;
 * state.setFloat(e, life, state.getFloat(e, life) - d.time());
 *
 * CgCpuBuffer bins = d.buffer("BINS");                 // a counter: one uint an element
 * bins.addInt(bin, 1);
 * }</pre>
 *
 * <ul>
 *   <li>An index past {@link #length()} throws, where compute would read garbage and write nowhere.</li>
 *   <li>The adds, minima and maxima are plain arithmetic: a scatter kernel's body runs on one thread.</li>
 * </ul>
 */
public final class CgCpuBuffer {

    private final CgBufferDecl decl;
    int[] words;
    /** The view's first word in {@link #words}, its elements, and the words an element takes. */
    int first;
    int length;
    final int stride;

    public CgCpuBuffer(CgBufferDecl decl, int[] words, int first, int length) {
        this.decl = decl;
        this.words = words;
        this.first = first;
        this.length = length;
        this.stride = decl.stride() / 4;
    }

    /** A growable staging buffer of {@code decl}'s elements, for appends made on one thread. */
    static CgCpuBuffer staging(CgBufferDecl decl) {
        return new CgCpuBuffer(decl, new int[Math.max(16, decl.stride() / 4 * 16)], 0, 0);
    }

    public CgBufferDecl decl() {
        return decl;
    }

    /** Elements in the view. */
    public int length() {
        return length;
    }

    /** Words an element takes. */
    public int stride() {
        return stride;
    }

    /** Where field {@code name} of the element sits. */
    public CgElementField field(String name) {
        CgElementField f = decl.field(name);
        if (f == null) throw new IllegalArgumentException(decl.name() + "'s element " + decl.element() + " has no field '" + name + "'");
        return f;
    }

    public float getFloat(int element, int word) {
        return Float.intBitsToFloat(words[at(element, word)]);
    }

    public int getInt(int element, int word) {
        return words[at(element, word)];
    }

    public void setFloat(int element, int word, float value) {
        words[at(element, word)] = Float.floatToRawIntBits(value);
    }

    public void setInt(int element, int word, int value) {
        words[at(element, word)] = value;
    }

    /** Element {@code element} of a {@code float} buffer. */
    public float getFloat(int element) {
        return getFloat(element, 0);
    }

    public int getInt(int element) {
        return getInt(element, 0);
    }

    public void setFloat(int element, float value) {
        setFloat(element, 0, value);
    }

    public void setInt(int element, int value) {
        setInt(element, 0, value);
    }

    /** Adds to an {@code int} or {@code uint} element, answering the value before: {@code NAME_ADD}, {@code NAME_INC}. */
    public int addInt(int element, int value) {
        int i = at(element, 0), before = words[i];
        words[i] = before + value;
        return before;
    }

    public void addFloat(int element, float value) {
        setFloat(element, getFloat(element) + value);
    }

    public void minInt(int element, int value) {
        setInt(element, Math.min(getInt(element), value));
    }

    public void maxInt(int element, int value) {
        setInt(element, Math.max(getInt(element), value));
    }

    /** {@code NAME_MIN} on a {@code uint} element: unsigned. */
    public void minUint(int element, int value) {
        if (Integer.compareUnsigned(value, getInt(element)) < 0) setInt(element, value);
    }

    public void maxUint(int element, int value) {
        if (Integer.compareUnsigned(value, getInt(element)) > 0) setInt(element, value);
    }

    public void minFloat(int element, float value) {
        setFloat(element, Math.min(getFloat(element), value));
    }

    public void maxFloat(int element, float value) {
        setFloat(element, Math.max(getFloat(element), value));
    }

    /** A staging buffer's next element, zeroed: an append. */
    int add() {
        int need = (length + 1) * stride;
        if (need > words.length) words = Arrays.copyOf(words, Math.max(need, words.length * 2));
        Arrays.fill(words, length * stride, need, 0);
        return length++;
    }

    private int at(int element, int word) {
        if (element < 0 || element >= length) {
            throw new IndexOutOfBoundsException(decl.name() + "[" + element + "] of " + length);
        }
        if (word < 0 || word >= stride) throw new IndexOutOfBoundsException(decl.name() + " word " + word + " of " + stride);
        return first + element * stride + word;
    }
}
