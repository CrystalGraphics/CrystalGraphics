package com.crystalgraphics.mc.modern.platform.command;

import java.util.UUID;

import com.crystalgraphics.mc.modern.platform.ServersModern;
import com.crystalgraphics.platform.command.CgCommandSource;
import com.crystalgraphics.platform.command.CgCommands;
import com.crystalgraphics.platform.world.CgPlayers;
import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
//? if >=1.21.11 {
/*import net.minecraft.server.permissions.Permissions;
*///?}
//? if <1.19 {
/*import net.minecraft.network.chat.TextComponent;
*///?}

/**
 * {@link CgCommands.Host} on Minecraft's own dispatcher, 1.13.2 to 26.3. Each loader forwards its registration event
 * here with the dispatcher it was handed, and CrystalGraphics' commands go into it as any mod's would, so the client's
 * tree, suggestions and colouring are Minecraft's. Server-safe.
 *
 * <pre>{@code
 * // a loader's registration event
 * CommandsModern.registerServer(event.getDispatcher());          // RegisterCommandsEvent, CommandRegistrationCallback
 * CommandsModern.registerClient(event.getDispatcher());          // RegisterClientCommandsEvent, where the loader has one
 * // an era's install, both sides
 * CgPlatform.provide(CgCommands.HOST, CommandsModern.INSTANCE);
 * }</pre>
 *
 * <ul>
 *   <li>A source with an entity is a {@link CgCommandSource.Kind#PLAYER player} or an {@code ENTITY}; one without is the
 *       console, RCON or a command block, which vanilla gives the same level 4, so they are one {@code CONSOLE}.</li>
 *   <li>The level is the vanilla operator level, 0 to 4: {@code hasPermission(int)} to 1.21.10 and the
 *       {@code PermissionSet} from 1.21.11.</li>
 * </ul>
 */
public final class CommandsModern implements CgCommands.Host {

    public static final CommandsModern INSTANCE = new CommandsModern();

    private volatile boolean clientCommands;

    private CommandsModern() {
    }

    /** A loader that can register client commands says so, once, from its client side. */
    public static void clientCommands(boolean available) {
        INSTANCE.clientCommands = available;
    }

    /** The server's registration event: puts the server commands into {@code dispatcher}. */
    public static void registerServer(CommandDispatcher<CommandSourceStack> dispatcher) {
        CgCommands.install(CgCommands.Side.SERVER, dispatcher, CommandsModern::server);
    }

    /** The client's registration event: puts the client commands into {@code dispatcher}. */
    public static void registerClient(CommandDispatcher<CommandSourceStack> dispatcher) {
        CgCommands.install(CgCommands.Side.CLIENT, dispatcher, CommandsModern::client);
    }

    /** A server-side source as facts. */
    public static CgCommandSource server(CommandSourceStack stack) {
        return new Source(stack, false);
    }

    /** A client-side source as facts: a client command's, always {@code CLIENT}. */
    public static CgCommandSource client(CommandSourceStack stack) {
        return new Source(stack, true);
    }

    @Override
    public void refresh(Object player) {
        if (!(player instanceof ServerPlayer)) return;
        ServerPlayer p = (ServerPlayer) player;
        MinecraftServer server = ServersModern.INSTANCE.minecraftServer();
        if (server != null) server.getCommands().sendCommands(p);
    }

    @Override
    public boolean supports(CgCommands.Side side) {
        return side == CgCommands.Side.SERVER || clientCommands;
    }

    /** The text, spelled by the era: {@code Component.literal} from 1.19, {@code TextComponent} before. */
    public static Component text(String text) {
        //? if >=1.19 {
        return Component.literal(text);
        //?} else {
        /*return new TextComponent(text);
        *///?}
    }

    private static final class Source implements CgCommandSource {

        private final CommandSourceStack stack;
        private final boolean client;

        Source(CommandSourceStack stack, boolean client) {
            this.stack = stack;
            this.client = client;
        }

        @Override
        public Kind kind() {
            if (client) return Kind.CLIENT;
            Entity entity = stack.getEntity();
            if (entity == null) return Kind.CONSOLE;
            return entity instanceof ServerPlayer ? Kind.PLAYER : Kind.ENTITY;
        }

        @Override
        public String name() {
            return stack.getTextName();
        }

        @Override
        public UUID id() {
            Entity entity = stack.getEntity();
            return entity == null ? null : CgPlayers.idOf(entity);
        }

        @Override
        public int level() {
            //? if >=1.21.11 {
            /*if (stack.permissions().hasPermission(Permissions.COMMANDS_OWNER)) return 4;
            if (stack.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) return 3;
            if (stack.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) return 2;
            if (stack.permissions().hasPermission(Permissions.COMMANDS_MODERATOR)) return 1;
            return 0;
            *///?} else {
            for (int level = 4; level >= 1; level--) {
                if (stack.hasPermission(level)) return level;
            }
            return 0;
            //?}
        }

        @Override
        public void reply(String text) {
            // 1.20 took a supplier, so the text is built only if someone hears it.
            //? if >=1.20 {
            stack.sendSuccess(() -> text(text), false);
            //?} else {
            /*stack.sendSuccess(text(text), false);
            *///?}
        }

        @Override
        public void fail(String text) {
            stack.sendFailure(text(text));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> V host() {
            return (V) stack;
        }
    }
}
