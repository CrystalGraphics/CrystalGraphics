package com.crystalgraphics.mc.modern.platform.world;

import net.minecraft.client.Minecraft;

/** The client's halves of the levels and players. Client only: its lambdas name client types the verifier loads. */
public final class ClientModern {

    private ClientModern() {
    }

    /** Once, from {@code PlatformServiceModern.gl()}. */
    public static void install() {
        LevelsModern.INSTANCE.client(new WorldQueryModern(), new EntityQueryModern(), () -> Minecraft.getInstance().level);
        PlayerQueryModern.INSTANCE.client(() -> Minecraft.getInstance().player);
    }
}
