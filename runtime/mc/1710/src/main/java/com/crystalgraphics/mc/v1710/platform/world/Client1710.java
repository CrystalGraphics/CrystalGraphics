package com.crystalgraphics.mc.v1710.platform.world;

import net.minecraft.client.Minecraft;

/** The client's halves of the levels and players. Client only: its lambdas name client types the verifier loads. */
public final class Client1710 {

    private Client1710() {
    }

    /** Once, from {@code PlatformService1710}'s client branch. */
    public static void install() {
        Levels1710.INSTANCE.client(new WorldQuery1710(), new EntityQuery1710(), () -> Minecraft.getMinecraft().theWorld);
        PlayerQuery1710.INSTANCE.client(() -> Minecraft.getMinecraft().thePlayer);
    }
}
