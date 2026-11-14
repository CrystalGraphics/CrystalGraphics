package com.crystalgraphics.mc.modern.platform;

//? if >=1.20.6 {
/*import net.minecraft.client.Minecraft;
*///?}

/** The client's half of {@link ItemsModern}: where the registries of the world the player is in come from. Client only. */
final class ItemsClientModern {

    private ItemsClientModern() {
    }

    static void install() {
        //? if >=1.20.6 {
        /*ItemsModern.client(() -> {
            Minecraft mc = Minecraft.getInstance();
            return mc.level == null ? null : mc.level.registryAccess();
        });
        *///?}
    }
}
