package com.crystalgraphics.mc.legacy.platform;

import java.util.List;
import java.util.UUID;

import com.crystalgraphics.command.CgPlainCommands;
import com.crystalgraphics.platform.command.CgCommandSource;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
//? if >=1.10 {
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
//?} else {
/*import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
*///?}

/**
 * CrystalGraphics' commands on Forge 1.8.9-1.12.2, which have no Brigadier: one {@code ICommand} over core's
 * {@link CgPlainCommands}. The server's is {@code /cg}; the client's, registered on {@code ClientCommandHandler}, is
 * {@code /cgc}, so it does not take the server's line. Anyone may run it; core checks the operator level of what changes.
 */
public final class CommandLegacy extends CommandBase {

    private final String name;
    private final boolean client;

    public CommandLegacy(String name, boolean client) {
        this.name = name;
        this.client = client;
    }

    //? if >=1.10 {
    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/" + name + " var list|get|set|reset | reload | info";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        CgPlainCommands.run(source(sender), args);
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos pos) {
        return CgPlainCommands.complete(source(sender), args);
    }
    //?} else {
    /*@Override
    public String getCommandName() {
        return name;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/" + name + " var list|get|set|reset | reload | info";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        CgPlainCommands.run(source(sender), args);
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        return CgPlainCommands.complete(source(sender), args);
    }
    *///?}

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    private CgCommandSource source(ICommandSender sender) {
        return new Source(sender, client);
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
            Entity entity = sender.getCommandSenderEntity();
            if (entity == null) return Kind.CONSOLE;
            return entity instanceof EntityPlayer ? Kind.PLAYER : Kind.ENTITY;
        }

        @Override
        public String name() {
            return sender.getName();
        }

        @Override
        public UUID id() {
            Entity entity = sender.getCommandSenderEntity();
            return entity instanceof EntityPlayer ? entity.getUniqueID() : null;
        }

        @Override
        public int level() {
            if (client) return 4;
            for (int level = 4; level >= 1; level--) {
                //? if >=1.10 {
                if (sender.canUseCommand(level, "cg")) return level;
                //?} else {
                /*if (sender.canCommandSenderUseCommand(level, "cg")) return level;
                *///?}
            }
            return 0;
        }

        @Override
        public void reply(String text) {
            send(text);
        }

        @Override
        public void fail(String text) {
            send("§c" + text);
        }

        private void send(String text) {
            //? if >=1.10 {
            sender.sendMessage(new TextComponentString(text));
            //?} else {
            /*sender.addChatMessage(new ChatComponentText(text));
            *///?}
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> V host() {
            return (V) sender;
        }
    }
}
