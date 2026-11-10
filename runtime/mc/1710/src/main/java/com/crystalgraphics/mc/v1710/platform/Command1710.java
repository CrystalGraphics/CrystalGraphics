package com.crystalgraphics.mc.v1710.platform;

import java.util.List;
import java.util.UUID;

import com.crystalgraphics.command.CgPlainCommands;
import com.crystalgraphics.platform.command.CgCommandSource;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

/**
 * CrystalGraphics' commands on 1.7.10, which has no Brigadier: one {@code ICommand} over core's
 * {@link CgPlainCommands}. The server's is {@code /cg}; the client's, registered on {@code ClientCommandHandler}, is
 * {@code /cgc}, so it does not take the server's line. Anyone may run it; core checks the operator level of what changes.
 */
public final class Command1710 extends CommandBase {

    private final String name;
    private final boolean client;

    public Command1710(String name, boolean client) {
        this.name = name;
        this.client = client;
    }

    @Override
    public String getCommandName() {
        return name;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/" + name + " var list|get|set|reset | reload | info";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        CgPlainCommands.run(new Source(sender, client), args);
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        return CgPlainCommands.complete(new Source(sender, client), args);
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    private static final class Source implements CgCommandSource {

        private final ICommandSender sender;
        private final boolean client;

        Source(ICommandSender sender, boolean client) {
            this.sender = sender;
            this.client = client;
        }

        @Override
        public Kind kind() {
            if (client) return Kind.CLIENT;
            if (sender instanceof EntityPlayer) return Kind.PLAYER;
            return sender instanceof Entity ? Kind.ENTITY : Kind.CONSOLE;
        }

        @Override
        public String name() {
            return sender.getCommandSenderName();
        }

        @Override
        public UUID id() {
            return sender instanceof EntityPlayer ? ((EntityPlayer) sender).getUniqueID() : null;
        }

        @Override
        public int level() {
            if (client) return 4;
            for (int level = 4; level >= 1; level--) {
                if (sender.canCommandSenderUseCommand(level, "cg")) return level;
            }
            return 0;
        }

        @Override
        public void reply(String text) {
            sender.addChatMessage(new ChatComponentText(text));
        }

        @Override
        public void fail(String text) {
            sender.addChatMessage(new ChatComponentText("§c" + text));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> V host() {
            return (V) sender;
        }
    }
}
