package com.crystalgraphics.mc.legacy.platform.world;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.crystalgraphics.platform.world.CgPermissions;
import com.crystalgraphics.platform.world.CgPlayers;

//? if >=1.10 {
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.server.permission.DefaultPermissionHandler;
import net.minecraftforge.server.permission.DefaultPermissionLevel;
import net.minecraftforge.server.permission.PermissionAPI;
//?}

/**
 * Forge's permission API from 1.10.2, for {@code CgPermission}: asked only while a permission mod replaces Forge's
 * default handler, whose rule for an operators' node would refuse a single-player owner with cheats off. Forge 1.8.9
 * has none, and is not given one.
 */
public final class PermissionsLegacy implements CgPermissions.Host {

    //? if >=1.10 {
    private final Map<String, String> nodes = new ConcurrentHashMap<>();

    public PermissionsLegacy() {
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
        EntityPlayer online = CgPlayers.player(player);
        if (name == null || online == null) return null;
        return PermissionAPI.hasPermission(online, name);
    }
    //?}
}
