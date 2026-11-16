package com.crystalgraphics.mc.v1710.platform.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.util.MovementInput;

/**
 * The client's own player's movement keys held: its {@code movementInput} swapped for the base one, which reads
 * nothing, and its own put back on release. Client only, its game thread.
 */
final class InputHold1710 {

    private static MovementInput saved;
    private static EntityClientPlayerMP holder;

    private InputHold1710() {
    }

    static boolean hold(boolean held) {
        EntityClientPlayerMP p = Minecraft.getMinecraft().thePlayer;
        if (held) {
            if (p == null) return false;
            if (holder == p) return true;
            saved = p.movementInput;
            holder = p;
            p.movementInput = new MovementInput();
            return true;
        }
        // a respawn made a new player with its own input: the old one is gone with its body
        if (holder != null && holder == p) p.movementInput = saved;
        saved = null;
        holder = null;
        return true;
    }
}
