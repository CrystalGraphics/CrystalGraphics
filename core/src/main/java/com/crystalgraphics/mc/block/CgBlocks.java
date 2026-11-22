package com.crystalgraphics.mc.block;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;

/**
 * The game's block states as {@link CgBlockState}s and back: {@link CgBlockState#of(Object)} and
 * {@link CgBlockState#toHost} come here. An era's host saves and loads a state in the game's own
 * {@code {Name, Properties}}; everything else is Java.
 *
 * <pre>{@code
 * CgBlockState state = CgBlocks.of(level.getBlockState(pos));
 * level.setBlock(pos, CgBlocks.toHost(state.with("lit", "true")), 3);
 * boolean here = CgBlocks.present();      // false on a test classpath or a tool
 * }</pre>
 */
public final class CgBlocks {

    /** What an era answers: its block states saved and loaded as {@code {Name, Properties}}. */
    public interface Host {

        /** Whether {@code o} is the game's block state. */
        boolean isState(Object o);

        /** {@code {Name, Properties}}, each property's value as the game names it. 1.7.10: the block and its {@code meta}. */
        CgNbtCompound save(Object state);

        /**
         * The game's state from {@link #save}'s form; properties not given keep the block's default.
         *
         * @throws IllegalArgumentException naming the block or the property the game has not got, or the value it refuses
         */
        Object load(CgNbtCompound saved);

        Host NONE = new Host() {
            @Override public boolean isState(Object o) { return false; }
            @Override public CgNbtCompound save(Object state) { throw absent(); }
            @Override public Object load(CgNbtCompound saved) { throw absent(); }
        };
    }

    public static final CgService<Host> HOST = CgService.of("crystalgraphics:blocks", Host.NONE);

    private CgBlocks() {
    }

    /** Whether a game is here to make and read states. */
    public static boolean present() {
        return CgPlatform.get(HOST) != Host.NONE;
    }

    /** A copy of the game's state. Throws outside Minecraft, or where {@code hostState} is not a block state. */
    public static CgBlockState of(Object hostState) {
        Host host = CgPlatform.get(HOST);
        if (!host.isState(hostState)) {
            if (host == Host.NONE) throw absent();
            throw new IllegalArgumentException("not a block state: " + hostState);
        }
        return CgNbt.decode(CgBlockState.CODEC, host.save(hostState));
    }

    /** The game's state for {@code state}; its block entity's data is not part of a state. */
    @SuppressWarnings("unchecked")
    public static <V> V toHost(CgBlockState state) {
        CgNbtCompound saved = (CgNbtCompound) CgNbt.encode(CgBlockState.CODEC, state.withBlockEntity(new CgNbtCompound()));
        return (V) CgPlatform.get(HOST).load(saved);
    }

    static IllegalStateException absent() {
        return new IllegalStateException("no Minecraft here to make or read a block state (CgBlocks.HOST is not provided)");
    }
}
