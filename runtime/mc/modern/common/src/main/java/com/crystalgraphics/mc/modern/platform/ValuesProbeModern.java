package com.crystalgraphics.mc.modern.platform;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.entity.CgEntityData;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.probe.CgValuesProbe;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
//? if <1.16 {
/*import net.minecraft.world.level.dimension.DimensionType;
*///?}
//? if >=1.19 <1.19.3 {
/*import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.Registry;
*///?}
//? if >=1.19.3 {
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
//?}
//? if >=1.21.3 {
/*import net.minecraft.world.entity.EntitySpawnReason;
*///?}
//? if >=1.21.6 {
/*import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;
*///?}
//? if >=1.21.11 {
/*import net.minecraft.world.entity.EntityProcessor;
*///?}
//? if >=26.2 {
/*import net.minecraft.world.entity.EntitySpawnRequest;
*///?}

/**
 * {@link CgValuesProbe.Host} on the modern tree: the game's {@code ItemArgument}, {@code BlockStateParser},
 * {@code TagParser} and {@code EntityType.loadEntityRecursive}, against the running server's registries and overworld.
 * 1.13.2 is absent: it makes no item stacks yet (NR2).
 */
public final class ValuesProbeModern implements CgValuesProbe.Host {

    public static final ValuesProbeModern INSTANCE = new ValuesProbeModern();

    private ValuesProbeModern() {
    }

    @Override
    public String absent() {
        //? if <1.14 {
        /*return "1.13.2 makes no item stacks yet";
        *///?} else {
        return ServersModern.INSTANCE.minecraftServer() == null ? "no server in this game (a multiplayer client)" : null;
        //?}
    }

    private static MinecraftServer server() {
        MinecraftServer s = ServersModern.INSTANCE.minecraftServer();
        if (s == null) throw new IllegalStateException("no server");
        return s;
    }

    /** A game parser's call: Brigadier's checked refusal turned into one naming the text. */
    private interface Parse<T> {
        T get() throws CommandSyntaxException;
    }

    private static <T> T parse(Parse<T> p, String text) {
        try {
            return p.get();
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("the game refused " + text + ": " + e.getMessage(), e);
        }
    }

    @Override
    public CgItemStack gameItem(String text) {
        //? if <1.14 {
        /*return null;
        *///?} elif <1.19 {
        /*return CgItemStack.of(parse(() -> ItemArgument.item().parse(new StringReader(text)).createItemStack(1, false), text));
        *///?} elif <1.19.3 {
        /*return CgItemStack.of(parse(() -> ItemArgument.item(new CommandBuildContext(server().registryAccess()))
                .parse(new StringReader(text)).createItemStack(1, false), text));
        *///?} elif <26.1 {
        return CgItemStack.of(parse(() -> ItemArgument.item(Commands.createValidationContext(server().registryAccess()))
                .parse(new StringReader(text)).createItemStack(1, false), text));
        //?} else {
        /*return CgItemStack.of(parse(() -> ItemArgument.item(Commands.createValidationContext(server().registryAccess()))
                .parse(new StringReader(text)).createItemStack(1), text));
        *///?}
    }

    @Override
    public CgBlockState gameBlock(String text) {
        //? if <1.14 {
        /*return null;
        *///?} elif <1.19 {
        /*BlockStateParser p = parse(() -> new BlockStateParser(new StringReader(text), false).parse(true), text);
        return block(p.getState(), p.getNbt());
        *///?} elif <1.19.3 {
        /*BlockStateParser.BlockResult r = parse(() -> BlockStateParser.parseForBlock(Registry.BLOCK, text, true), text);
        return block(r.blockState(), r.nbt());
        *///?} else {
        BlockStateParser.BlockResult r = parse(() -> BlockStateParser.parseForBlock(
                server().registryAccess().lookupOrThrow(Registries.BLOCK), text, true), text);
        return block(r.blockState(), r.nbt());
        //?}
    }

    private static CgBlockState block(BlockState state, CompoundTag nbt) {
        return CgBlockState.of(state).withBlockEntity(nbt == null ? new CgNbtCompound() : CgNbt.of(nbt));
    }

    @Override
    public CgEntityData gameEntity(String snbt) {
        //? if <1.21.5 {
        CompoundTag tag = parse(() -> TagParser.parseTag(snbt), snbt);
        //?} else {
        /*CompoundTag tag = parse(() -> TagParser.parseCompoundFully(snbt), snbt);
        *///?}
        ServerLevel level = overworld();
        // Made, never added: a picture's entity never joins the level
        //? if <1.14 {
        /*Entity e = null;
        *///?} elif <1.21.3 {
        Entity e = EntityType.loadEntityRecursive(tag, level, x -> x);
        //?} elif <1.21.11 {
        /*Entity e = EntityType.loadEntityRecursive(tag, level, EntitySpawnReason.LOAD, x -> x);
        *///?} elif <26.2 {
        /*Entity e = EntityType.loadEntityRecursive(tag, level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
        *///?} else {
        /*Entity e = EntityType.loadEntityRecursive(tag, level, new EntitySpawnRequest(EntitySpawnReason.LOAD, false),
                EntityProcessor.NOP);
        *///?}
        if (e == null) throw new IllegalArgumentException("the game made no entity from " + snbt);
        //? if <1.21.6 {
        CompoundTag out = e.saveWithoutId(new CompoundTag());
        //?} else {
        /*TagValueOutput o = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        e.saveWithoutId(o);
        CompoundTag out = o.buildResult();
        *///?}
        CgNbtCompound saved = CgNbt.of(out);
        saved.put("id", String.valueOf(EntityType.getKey(e.getType())));
        return CgEntityData.fromNbt(saved);
    }

    private static ServerLevel overworld() {
        //? if <1.14 {
        /*return null;
        *///?} elif <1.16 {
        /*return server().getLevel(DimensionType.OVERWORLD);
        *///?} else {
        return server().overworld();
        //?}
    }

    @Override
    public String sheep() {
        return "minecraft:sheep";
    }

    @Override
    public List<String> faces() {
        List<String> out = new ArrayList<>();
        for (Direction d : Direction.values()) out.add(d.getName());
        return out;
    }

    @Override
    public Long packed(int x, int y, int z) {
        //? if <1.14 {
        /*return null;
        *///?} else {
        return BlockPos.asLong(x, y, z);
        //?}
    }
}
