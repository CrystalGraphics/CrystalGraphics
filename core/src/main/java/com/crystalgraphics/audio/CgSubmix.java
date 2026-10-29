package com.crystalgraphics.audio;

import java.util.Arrays;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * A mix in a tree: Unreal's {@code USoundSubmix}. Voices sent to it are summed into its buffer, its effect chain runs
 * on that, and the result, at its volume, goes into its parent; the root is the output. An engine's are {@code main}
 * and, under it, {@code world} (with {@code reverb} under that), {@code ui} and {@code music}.
 *
 * <pre>{@code
 * CgSubmix world = audio.submix("world");
 * world.effect(new CgLowPass(800)).volume(0.8f);
 * }</pre>
 *
 * <ul>
 *   <li>Made by its engine, in its tree; a sound class names the one its sounds go to.</li>
 *   <li>Effects are added from any thread and take the next buffer; they run in the order added.</li>
 * </ul>
 */
public final class CgSubmix {

    private final String id;
    @Nullable
    private final CgSubmix parent;
    private volatile CgSubmixEffect[] chain = new CgSubmixEffect[0];
    private volatile float volume = 1f;
    final int index;
    float[] buffer;

    CgSubmix(String id, @Nullable CgSubmix parent, int index) {
        this.id = Objects.requireNonNull(id, "id");
        this.parent = parent;
        this.index = index;
    }

    public String id() {
        return id;
    }

    @Nullable
    public CgSubmix parent() {
        return parent;
    }

    /** Adds {@code effect} to the end of its chain. */
    public synchronized CgSubmix effect(CgSubmixEffect effect) {
        CgSubmixEffect[] next = Arrays.copyOf(chain, chain.length + 1);
        next[chain.length] = Objects.requireNonNull(effect, "effect");
        chain = next;
        return this;
    }

    public synchronized CgSubmix remove(CgSubmixEffect effect) {
        CgSubmixEffect[] c = chain;
        for (int i = 0; i < c.length; i++) {
            if (c[i] != effect) continue;
            CgSubmixEffect[] next = new CgSubmixEffect[c.length - 1];
            System.arraycopy(c, 0, next, 0, i);
            System.arraycopy(c, i + 1, next, i, c.length - i - 1);
            chain = next;
            break;
        }
        return this;
    }

    /** Its output volume into its parent, linear. */
    public CgSubmix volume(float volume) {
        this.volume = volume;
        return this;
    }

    public float volume() {
        return volume;
    }

    CgSubmixEffect[] chain() {
        return chain;
    }

    @Override
    public String toString() {
        return "CgSubmix[" + id + "]";
    }
}
