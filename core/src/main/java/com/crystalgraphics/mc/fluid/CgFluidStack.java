package com.crystalgraphics.mc.fluid;

import java.util.Objects;

import com.crystalgraphics.mc.CgCommandReader;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtTag;

/**
 * An amount of one fluid, with its data: what a tank holds. Millibuckets on every loader; Fabric's droplets kept exact.
 *
 * <pre>{@code
 * CgFluidStack lava = CgFluidStack.of("minecraft:lava", 1000);        // a bucket
 * CgFluidStack drop = CgFluidStack.ofDroplets("minecraft:water", 27); // a third of a millibucket, Fabric's unit
 * CgFluidStack half = lava.withMillibuckets(500);
 * CgFluidStack parsed = CgFluidStack.parse("minecraft:water 250");    // id{data} amount
 * String name = CgFluids.name(lava);                                   // "Lava", from the client's loader
 * }</pre>
 *
 * <ul>
 *   <li>{@link #toString} is {@code id{data} millibuckets}, what {@link #parse} reads; an amount that is not whole
 *       millibuckets is written in droplets, {@code 27d}.</li>
 *   <li>Equal when the fluid, the amount and the data are; an amount of 0 or less is empty, and every empty stack is
 *       equal.</li>
 * </ul>
 */
public final class CgFluidStack {

    /** Fabric's unit: a bucket is 81000 droplets, a millibucket 81. */
    public static final long DROPLETS_PER_MILLIBUCKET = 81;

    public static final CgFluidStack EMPTY = new CgFluidStack("", 0, new CgNbtCompound());

    /** {@code {id, amount, data}}, the amount in droplets. */
    public static final CgCodec<CgFluidStack> CODEC = CgCodecs.record(
            CgCodecs.field("id", CgCodecs.STRING, CgFluidStack::fluid),
            CgCodecs.field("amount", CgCodecs.LONG, CgFluidStack::droplets),
            CgCodecs.optionalField("data", CgNbtCompound.CODEC, CgFluidStack::data, new CgNbtCompound()),
            CgFluidStack::new);

    private final String fluid;
    private final long droplets;
    private final CgNbtCompound data;

    private CgFluidStack(String fluid, long droplets, CgNbtCompound data) {
        this.fluid = fluid;
        this.droplets = droplets;
        this.data = data.copy();
    }

    public static CgFluidStack of(String fluid, int millibuckets) {
        return new CgFluidStack(fluid, millibuckets * DROPLETS_PER_MILLIBUCKET, new CgNbtCompound());
    }

    public static CgFluidStack ofDroplets(String fluid, long droplets) {
        return new CgFluidStack(fluid, droplets, new CgNbtCompound());
    }

    /** From {@code id}, {@code id{data}}, then an amount: millibuckets, or droplets with a {@code d} after it. */
    public static CgFluidStack parse(String text) {
        CgCommandReader r = new CgCommandReader(text);
        r.skipSpaces();
        String fluid = r.readId();
        CgNbtCompound data = new CgNbtCompound();
        if (r.peek() == '{') {
            CgNbtTag tag = r.readTag();
            if (!(tag instanceof CgNbtCompound)) throw r.error("the fluid's data as a compound");
            data = (CgNbtCompound) tag;
        }
        r.skipSpaces();
        long droplets = DROPLETS_PER_MILLIBUCKET * 1000;
        if (!r.atEnd()) {
            long n = r.readLong();
            droplets = r.skip('d') ? n : n * DROPLETS_PER_MILLIBUCKET;
        }
        r.expectEnd();
        return new CgFluidStack(fluid, droplets, data);
    }

    /** The fluid's registry name; {@code ""} when empty. */
    public String fluid() {
        return fluid;
    }

    public long droplets() {
        return droplets;
    }

    /** Whole millibuckets, rounded down. */
    public int millibuckets() {
        return (int) Math.min(Integer.MAX_VALUE, droplets / DROPLETS_PER_MILLIBUCKET);
    }

    /** The data (Forge's tag, NeoForge's and Fabric's components), a copy. */
    public CgNbtCompound data() {
        return data.copy();
    }

    public boolean isEmpty() {
        return droplets <= 0 || fluid.isEmpty();
    }

    public CgFluidStack withMillibuckets(int millibuckets) {
        return new CgFluidStack(fluid, millibuckets * DROPLETS_PER_MILLIBUCKET, data);
    }

    public CgFluidStack withDroplets(long droplets) {
        return new CgFluidStack(fluid, droplets, data);
    }

    public CgFluidStack withData(CgNbtCompound data) {
        return new CgFluidStack(fluid, droplets, data);
    }

    /** The same fluid with the same data, whatever the amount. */
    public boolean sameFluid(CgFluidStack other) {
        return fluid.equals(other.fluid) && data.equals(other.data);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgFluidStack)) return false;
        CgFluidStack other = (CgFluidStack) o;
        if (isEmpty() || other.isEmpty()) return isEmpty() && other.isEmpty();
        return droplets == other.droplets && fluid.equals(other.fluid) && data.equals(other.data);
    }

    @Override
    public int hashCode() {
        return isEmpty() ? 0 : Objects.hash(fluid, droplets, data);
    }

    /** {@code minecraft:lava 1000}, or {@code 27d} where the amount is not whole millibuckets: what {@link #parse} reads. */
    @Override
    public String toString() {
        if (isEmpty()) return "empty";
        String amount = droplets % DROPLETS_PER_MILLIBUCKET == 0
                ? Long.toString(droplets / DROPLETS_PER_MILLIBUCKET) : droplets + "d";
        return fluid + (data.isEmpty() ? "" : data.toString()) + " " + amount;
    }
}
