package com.crystalgraphics.mc.modern.neoforge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.crystalgraphics.platform.world.CgPermissions;
import com.crystalgraphics.platform.world.CgPlayers;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.handler.DefaultPermissionHandler;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * NeoForge's permission API, for {@code CgPermission}: asked only while a permission mod replaces NeoForge's default handler,
 * whose rule for an operators' node would refuse a single-player owner with cheats off.
 */
final class PermissionsNeoForge implements CgPermissions.Host {

    /** Each node registered at the last gather, by its id; one declared after it is answered by its default. */
    private final Map<String, PermissionNode<Boolean>> nodes = new ConcurrentHashMap<>();

    PermissionsNeoForge() {
        NeoForge.EVENT_BUS.addListener((PermissionGatherEvent.Nodes event) -> {
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
}
