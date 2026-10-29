package com.crystalgraphics.mc.v1710.platform.world;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * A held camera's view entity on 1.7.10, whose render view entity must be living: never spawned, never ticked, holding
 * nothing. {@code HostCamera1710} poses it each frame.
 */
final class CameraEntity1710 extends EntityLivingBase {

    private static final ItemStack[] NOTHING = new ItemStack[5];

    CameraEntity1710(World world) {
        super(world);
    }

    @Override
    public ItemStack getHeldItem() {
        return null;
    }

    @Override
    public ItemStack getEquipmentInSlot(int slot) {
        return null;
    }

    @Override
    public void setCurrentItemOrArmor(int slot, ItemStack stack) {
    }

    @Override
    public ItemStack[] getLastActiveItems() {
        return NOTHING;
    }
}
