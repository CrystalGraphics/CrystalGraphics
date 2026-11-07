package com.crystalgraphics.mc.modern.forge;

import com.crystalgraphics.mc.modern.platform.KeyBindingsModern;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
//? if >=1.19 {
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
//?} elif >=1.18 {
/*import net.minecraftforge.client.ClientRegistry;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
*///?} elif >=1.17 {
/*import net.minecraftforge.fmlclient.registry.ClientRegistry;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
*///?} else {
/*import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.DeferredWorkQueue;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
*///?}

/**
 * Forge's window for {@code CgKeyBindings}: {@code RegisterKeyMappingsEvent} from Forge 41 (1.19), client setup on the
 * main thread before it. Client only.
 */
final class KeysForge {

    private KeysForge() {}

    static void register(FMLJavaModLoadingContext context) {
        // Forge 64 (26.1) moved its mod-bus events onto a static BUS; Forge 56 (1.21.6) hands out one per bus group
        //? if >=26.1 {
        /*RegisterKeyMappingsEvent.BUS.addListener(KeysForge::onRegisterKeyMappings);
        *///?} elif >=1.21.6 {
        /*RegisterKeyMappingsEvent.getBus(context.getModBusGroup()).addListener(KeysForge::onRegisterKeyMappings);
        *///?} elif >=1.19 {
        context.getModEventBus().addListener(KeysForge::onRegisterKeyMappings);
        //?} else {
        /*context.getModEventBus().addListener(KeysForge::onClientSetup);
        *///?}
    }

    //? if >=1.19 {
    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        KeyBindingsModern.INSTANCE.register(event::register, false);
    }
    //?} elif >=1.17 {
    /*private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> KeyBindingsModern.INSTANCE.register(ClientRegistry::registerKeyBinding, true));
    }
    *///?} else {
    /*private static void onClientSetup(FMLClientSetupEvent event) {
        DeferredWorkQueue.runLater(() -> KeyBindingsModern.INSTANCE.register(ClientRegistry::registerKeyBinding, true));
    }
    *///?}
}
