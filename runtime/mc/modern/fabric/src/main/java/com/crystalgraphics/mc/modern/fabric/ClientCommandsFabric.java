package com.crystalgraphics.mc.modern.fabric;

//? if >=1.16 {
import java.util.UUID;

import com.crystalgraphics.mc.modern.platform.command.CommandsModern;
import com.crystalgraphics.platform.command.CgCommandSource;
import com.crystalgraphics.platform.command.CgCommands;
import com.mojang.brigadier.CommandDispatcher;

//? if >=1.19.2 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
//?} else {
/*import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v1.FabricClientCommandSource;
*///?}

/**
 * Fabric's client commands: its own source type, since a client command has no {@code CommandSourceStack}. Client only;
 * Fabric API has none on 1.14.4-1.15.2, where {@code CgCommands.supports(CLIENT)} stays false.
 */
final class ClientCommandsFabric {

    private ClientCommandsFabric() {
    }

    static void register() {
        //? if >=1.19.2 {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> install(dispatcher));
        //?} else {
        /*install(ClientCommandManager.DISPATCHER);
        *///?}
        CommandsModern.clientCommands(true);
    }

    private static void install(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        CgCommands.install(CgCommands.Side.CLIENT, dispatcher, Source::new);
    }

    /** The player at the keyboard: always the client, and as trusted as themselves. */
    private static final class Source implements CgCommandSource {

        private final FabricClientCommandSource source;

        Source(FabricClientCommandSource source) {
            this.source = source;
        }

        @Override public Kind kind() { return Kind.CLIENT; }

        @Override public String name() { return source.getPlayer().getName().getString(); }

        @Override public UUID id() { return source.getPlayer().getUUID(); }

        @Override public int level() { return 4; }

        @Override public void reply(String text) { source.sendFeedback(CommandsModern.text(text)); }

        @Override public void fail(String text) { source.sendError(CommandsModern.text(text)); }

        @Override
        @SuppressWarnings("unchecked")
        public <V> V host() { return (V) source; }
    }
}
//?}
