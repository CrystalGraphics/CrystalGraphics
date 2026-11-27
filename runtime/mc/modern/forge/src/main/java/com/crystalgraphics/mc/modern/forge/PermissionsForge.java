package com.crystalgraphics.mc.modern.forge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.crystalgraphics.platform.world.CgPermissions;
import com.crystalgraphics.platform.world.CgPlayers;

//? if <1.21.6 {
import net.minecraftforge.common.MinecraftForge;
//?}
//? if >=1.18.2 {
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.handler.DefaultPermissionHandler;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;
//?} else {
/*import net.minecraft.world.entity.player.Player;
import net.minecraftforge.server.permission.DefaultPermissionHandler;
import net.minecraftforge.server.permission.DefaultPermissionLevel;
import net.minecraftforge.server.permission.PermissionAPI;
*///?}

/**
 * Forge's permission API, for {@code CgPermission}: asked only while a permission mod replaces Forge's default handler,
 * whose rule for an operators' node would refuse a single-player owner with cheats off.
 */
final class PermissionsForge implements CgPermissions.Host {

    //? if >=1.18.2 {
    /** Each node registered at the last gather, by its id; one declared after it is answered by its default. */
    private final Map<String, PermissionNode<Boolean>> nodes = new ConcurrentHashMap<>();

    PermissionsForge() {
        // EventBus 7 from 1.21.6: an event carries its own bus
        //? if >=1.21.6 {
        /*PermissionGatherEvent.Nodes.BUS.addListener((PermissionGatherEvent.Nodes event) -> {
        *///?} else {
        MinecraftForge.EVENT_BUS.addListener((PermissionGatherEvent.Nodes event) -> {
        //?}
            nodes.clear();
            for (CgPermissions.Declared d : CgPermissions.declared()) {
                Predicate<UUID> byDefault = d.byDefault();
                String[] split = d.node().split(":", 2);
                PermissionNode<Boolean> node = new PermissionNode<>(split[0], split[1], PermissionTypes.BOOLEAN,
                        (player, id, context) -> byDefault.test(id));
                event.addNodes(node);
                nodes.put(d.node(), node);
            }
        });
    }

    @Override
    public Boolean has(UUID player, String node) {
        if (DefaultPermissionHandler.IDENTIFIER.equals(PermissionAPI.getActivePermissionHandler())) return null;
        PermissionNode<Boolean> registered = nodes.get(node);
        ServerPlayer online = CgPlayers.player(player);
        if (registered == null || online == null) return null;
        return PermissionAPI.getPermission(online, registered);
    }
    //?} else {
    /*private final Map<String, String> nodes = new ConcurrentHashMap<>();

    PermissionsForge() {
        for (CgPermissions.Declared d : CgPermissions.declared()) declared(d.node(), d.level(), d.description(), d.byDefault());
    }

    @Override
    public void declared(String node, CgPermissions.Level level, String description, Predicate<UUID> byDefault) {
        String name = node.replace(':', '.');
        PermissionAPI.registerNode(name, level == CgPermissions.Level.EVERYONE ? DefaultPermissionLevel.ALL : DefaultPermissionLevel.OP,
                description);
        nodes.put(node, name);
    }

    @Override
    public Boolean has(UUID player, String node) {
        if (PermissionAPI.getPermissionHandler() == DefaultPermissionHandler.INSTANCE) return null;
        String name = nodes.get(node);
        Player online = CgPlayers.player(player);
        if (name == null || online == null) return null;
        return PermissionAPI.hasPermission(online, name);
    }
    *///?}
}
