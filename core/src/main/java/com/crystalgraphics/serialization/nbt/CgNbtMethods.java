package com.crystalgraphics.serialization.nbt;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgDynamicOps;

/**
 * A codec over a class's own pair of NBT methods, its save and its load, for a class that already saves itself:
 * {@link CgNbt#methods}. It decodes only into an object that already exists, so a codec holds it with {@code into}.
 *
 * <pre>{@code
 * static final CgObjectCodec<Npc> CODEC = CgCodecs.fieldsOf(Npc.class)
 *         .skip("inventory")
 *         .into("inventory", CgNbt.methods(Inventory::save, Inventory::load), n -> n.inventory)
 *         .build();
 * }</pre>
 *
 * A networked type sends such a key as its tag, whole, whenever its encoding changes.
 */
public final class CgNbtMethods<A> implements CgCodec<A> {

    private final Function<? super A, CgNbtCompound> save;
    private final BiConsumer<? super A, CgNbtCompound> load;

    CgNbtMethods(Function<? super A, CgNbtCompound> save, BiConsumer<? super A, CgNbtCompound> load) {
        this.save = Objects.requireNonNull(save, "save");
        this.load = Objects.requireNonNull(load, "load");
    }

    /** {@code value} as its own save method writes it. */
    public CgNbtCompound save(A value) {
        return save.apply(value);
    }

    /** {@code tag} read into {@code value} by its own load method. */
    public void load(A value, CgNbtCompound tag) {
        load.accept(value, tag);
    }

    @Override
    public <T> T encode(CgDynamicOps<T> ops, A input) {
        return CgNbtCompound.CODEC.encode(ops, save.apply(input));
    }

    @Override
    public <T> A decode(CgDynamicOps<T> ops, T input) {
        throw new CgCodecException("a codec over NBT methods decodes into a held object only: declare its key with into(...)");
    }

    @Override
    public <T> A decode(CgDynamicOps<T> ops, T input, @Nullable A target) {
        if (target == null) return decode(ops, input);
        load.accept(target, CgNbtCompound.CODEC.decode(ops, input));
        return target;
    }
}
