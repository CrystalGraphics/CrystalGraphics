package com.crystalgraphics.serialization;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * A list whose elements each carry a key, unique in the list: written as {@link CgCodecs#listOf} writes it, and saying
 * its element codec and key for code that compiles a codec into something else. A networked type sends such a list's
 * changes by key (an element added, moved, replaced or removed) instead of the whole list.
 *
 * <pre>{@code
 * public static final CgObjectCodec<Shop> CODEC = CgCodecs.object(Shop::new)
 *         .into("offers", CgCodecs.keyedListOf(Offer.CODEC, Offer::id), s -> s.offers)
 *         .build();
 * }</pre>
 *
 * Two elements with one key make a networked list's changes ambiguous: keep the key unique.
 */
public final class CgKeyedListCodec<A> implements CgCodec<List<A>> {

    private final CgCodec<A> elements;
    private final Function<? super A, ?> key;
    private final CgCodec<List<A>> list;

    CgKeyedListCodec(CgCodec<A> elements, Function<? super A, ?> key) {
        this.elements = Objects.requireNonNull(elements, "elements");
        this.key = Objects.requireNonNull(key, "key");
        this.list = CgCodecs.listOf(elements);
    }

    /** The codec of each element. */
    public CgCodec<A> elements() {
        return elements;
    }

    /** Each element's key. */
    public Function<? super A, ?> key() {
        return key;
    }

    @Override
    public <T> T encode(CgDynamicOps<T> ops, List<A> input) {
        return list.encode(ops, input);
    }

    @Override
    public <T> List<A> decode(CgDynamicOps<T> ops, T input) {
        return list.decode(ops, input);
    }

    @Override
    public <T> List<A> decode(CgDynamicOps<T> ops, T input, @Nullable List<A> target) {
        return list.decode(ops, input, target);
    }
}
