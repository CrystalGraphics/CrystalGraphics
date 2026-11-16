package com.crystalgraphics.mc.modern.platform.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
//? if >=1.21.3 {
/*import net.minecraft.client.player.ClientInput;
*///?} else {
import net.minecraft.client.player.Input;
//?}

/**
 * The client's own player's movement keys held: its input swapped for the base input, which reads nothing, and its
 * own put back on release, as Minecraft swaps a player's input itself. Client only, its game thread.
 */
final class InputHoldModern {

    //? if >=1.21.3 {
    /*private static ClientInput saved;
    *///?} else {
    private static Input saved;
    //?}
    private static LocalPlayer holder;

    private InputHoldModern() {
    }

    static boolean hold(boolean held) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (held) {
            if (p == null) return false;
            if (holder == p) return true;
            saved = p.input;
            holder = p;
            //? if >=1.21.3 {
            /*p.input = new ClientInput();
            *///?} else {
            p.input = new Input();
            //?}
            return true;
        }
        // a respawn made a new player with its own input: the old one is gone with its body
        if (holder != null && holder == p) p.input = saved;
        saved = null;
        holder = null;
        return true;
    }
}
