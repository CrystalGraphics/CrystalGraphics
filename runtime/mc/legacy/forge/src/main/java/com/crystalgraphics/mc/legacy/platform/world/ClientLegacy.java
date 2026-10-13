package com.crystalgraphics.mc.legacy.platform.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;

/** The client's level and player, which MCP renamed from {@code theWorld} and {@code thePlayer} after 1.8.9. */
public final class ClientLegacy {

    private ClientLegacy() {
    }

    /** Gives the levels and players their client halves. Client only, once, from {@code PlatformServiceLegacy}. */
    public static void install() {
        LevelsLegacy.INSTANCE.client(new WorldQueryLegacy(), new EntityQueryLegacy(), ClientLegacy::world);
        PlayerQueryLegacy.INSTANCE.client(ClientLegacy::player);
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
