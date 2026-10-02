package com.crystalgraphics.mc.legacy.platform.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;

/** The client's level and player, which MCP renamed from {@code theWorld} and {@code thePlayer} after 1.8.9. */
final class ClientLegacy {

    private ClientLegacy() {
    }

    static WorldClient world() {
        //? if >=1.9 {
        return Minecraft.getMinecraft().world;
        //?} else {
        /*return Minecraft.getMinecraft().theWorld;
        *///?}
    }

    static EntityPlayerSP player() {
        //? if >=1.9 {
        return Minecraft.getMinecraft().player;
        //?} else {
        /*return Minecraft.getMinecraft().thePlayer;
        *///?}
    }
}
