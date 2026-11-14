package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.mc.container.Action;
import com.crystalgraphics.mc.container.Capabilities;
import com.crystalgraphics.mc.container.CgContainers;
import com.crystalgraphics.mc.container.SlotKind;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
//? if >=1.14.3 {
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.trading.MerchantOffer;
//?}
//? if >=1.20.6 {
/*import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
*///?} else if >=1.14.3 {
import net.minecraft.world.effect.MobEffect;
//?}
//? if >=26.1 {
/*import net.minecraft.world.inventory.ContainerInput;
*///?} else {
import net.minecraft.world.inventory.ClickType;
//?}
import net.minecraft.world.item.ItemStack;
//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries;
//?} else {
/*import net.minecraft.core.Registry;
*///?}

/**
 * {@link CgContainers} on the modern tree: the client player's open menu, read a slot at a time, and actions made
 * through the game's own calls, which send vanilla's packets. Client only, from
 * {@code PlatformServiceModern.installClient}.
 *
 * <ul>
 *   <li>A click is {@code handleInventoryMouseClick} to 1.21.11 and {@code handleContainerInput} from 26.1, the click
 *       type mapped by name. A button is {@code handleInventoryButtonClick}.</li>
 *   <li>Rename and select-trade send {@code ServerboundRenameItemPacket} and {@code ServerboundSelectTradePacket}
 *       through the connection, as the anvil's and merchant's screens do, and update the menu the way they do first.</li>
 *   <li>The cursor's stack is the menu's from 1.17.1, the player's inventory's before.</li>
 *   <li>A menu's kind is its {@code MenuType}'s registry name from 1.14.3; 1.13.2's menus are not typed, so it is
 *       {@code unknown} there, with no state, rename or trade.</li>
 *   <li>A menu's title is its open screen's: empty when the game has opened none (a screen of ours).</li>
 *   <li>A slot's kind is read from its container: the player's inventory by its index, a result container or a result
 *       slot (crafting, furnace output, a merchant's) as a result.</li>
 *   <li>A furnace's progress is whole pixels of its two textures to 1.20.1 and a fraction from 1.20.2.</li>
 * </ul>
 */
public final class ContainersModern implements CgContainers.Host {

    public static final ContainersModern INSTANCE = new ContainersModern();

    private ContainersModern() {
    }

    private static LocalPlayer player() {
        return Minecraft.getInstance().player;
    }

    private static AbstractContainerMenu menu() {
        return player().containerMenu;
    }

    @Override
    public boolean present() {
        return player() != null;
    }

    @Override
    public int id() {
        return menu().containerId;
    }

    @Override
    public int size() {
        return menu().slots.size();
    }

    @Override
    public CgContainers.Layout layout(int index) {
        AbstractContainerMenu menu = menu();
        Slot slot = menu.getSlot(index);
        int group = index;
        for (int i = 0; i < index; i++) {
            if (menu.getSlot(i).container == slot.container) {
                group = i;
                break;
            }
        }
        int inContainer = containerSlot(menu, index);
        return new CgContainers.Layout(group, inContainer, slot.x, slot.y, kind(slot, inContainer), slot.getMaxStackSize());
    }

    //? if >=1.17.1 {
    private static int containerSlot(AbstractContainerMenu menu, int index) {
        return menu.getSlot(index).getContainerSlot();
    }
    //?} else {
    /*// Before 1.17.1 a slot does not say its place in its container: it is its position among the menu's slots of that
    // container, which for the player's inventory is vanilla's fixed layout (main then hotbar; the player's own menu
    // adds armour before and the offhand after).
    private static int containerSlot(AbstractContainerMenu menu, int index) {
        Slot slot = menu.getSlot(index);
        int position = 0;
        int size = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.getSlot(i).container != slot.container) continue;
            if (i < index) position++;
            size++;
        }
        if (!(slot.container instanceof Inventory)) return position;
        if (size == 36) return position < 27 ? position + 9 : position - 27;
        if (size == 41) return position < 4 ? 39 - position : position < 31 ? position + 5 : position < 40 ? position - 31 : 40;
        return position;
    }
    *///?}

    private static SlotKind kind(Slot slot, int inContainer) {
        Container container = slot.container;
        if (container instanceof Inventory) {
            return inContainer < 9 ? SlotKind.HOTBAR : inContainer < 36 ? SlotKind.INVENTORY : inContainer < 40 ? SlotKind.ARMOR : SlotKind.OFFHAND;
        }
        if (container instanceof ResultContainer || slot instanceof ResultSlot || slot instanceof FurnaceResultSlot || slot instanceof MerchantResultSlot) {
            return SlotKind.RESULT;
        }
        return SlotKind.CONTAINER;
    }

    @Override
    public int stamp(int slot) {
        return stampOf(menu().getSlot(slot).getItem());
    }

    private static int stampOf(ItemStack stack) {
        return stack.isEmpty() ? 0 : 31 * CgItems.stampOf(stack) + stack.getCount();
    }

    @Override
    public CgItemStack stack(int slot) {
        return stack(menu().getSlot(slot).getItem());
    }

    @Override
    public boolean canTake(int slot) {
        return menu().getSlot(slot).mayPickup(player());
    }

    @Override
    public int carriedStamp() {
        return stampOf(carriedHost());
    }

    @Override
    public CgItemStack carried() {
        return stack(carriedHost());
    }

    @Override
    public boolean mayPlace(int slot, CgItemStack stack) {
        ItemStack host = stack.toHost();
        return menu().getSlot(slot).mayPlace(host);
    }

    @Override
    public boolean act(int id, Action action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return false;
        if (action instanceof Action.Click) {
            Action.Click click = (Action.Click) action;
            //? if >=26.1 {
            /*mc.gameMode.handleContainerInput(id, click.slot(), click.button(), ContainerInput.valueOf(click.type().name()), mc.player);
            *///?} else {
            mc.gameMode.handleInventoryMouseClick(id, click.slot(), click.button(), ClickType.valueOf(click.type().name()), mc.player);
            //?}
            return true;
        }
        if (action instanceof Action.Button) {
            mc.gameMode.handleInventoryButtonClick(id, ((Action.Button) action).id());
            return true;
        }
        if (action instanceof Action.Close) {
            if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
            return true;
        }
        //? if >=1.14.3 {
        if (action instanceof Action.Rename) {
            String name = ((Action.Rename) action).name();
            if (mc.player.containerMenu instanceof AnvilMenu) ((AnvilMenu) mc.player.containerMenu).setItemName(name);
            mc.getConnection().send(new ServerboundRenameItemPacket(name));
            return true;
        }
        if (action instanceof Action.SelectTrade) {
            int index = ((Action.SelectTrade) action).index();
            if (!(mc.player.containerMenu instanceof MerchantMenu)) return false;
            MerchantMenu merchant = (MerchantMenu) mc.player.containerMenu;
            merchant.setSelectionHint(index);
            merchant.tryMoveItems(index);
            mc.getConnection().send(new ServerboundSelectTradePacket(index));
            return true;
        }
        //?}
        return false;
    }

    @Override
    public Capabilities capabilities() {
        //? if >=1.14.3 {
        return new Capabilities(true, true, true, true);
        //?} else {
        /*return new Capabilities(true, false, false, false);
        *///?}
    }

    private static ItemStack carriedHost() {
        //? if >=1.17.1 {
        return menu().getCarried();
        //?} else {
        /*return player().inventory.getCarried();
        *///?}
    }

    private static CgItemStack stack(ItemStack stack) {
        return stack.isEmpty() ? CgItemStack.EMPTY : CgItemStack.of(stack);
    }

    /** The menu's registry name; the player's own menu has no type, and neither does anything before 1.14.3. */
    @Override
    public String type() {
        AbstractContainerMenu menu = menu();
        if (menu == player().inventoryMenu) return CgContainers.Menu.PLAYER;
        //? if >=1.14.3 {
        //? if >=1.19.3 {
        Object key = BuiltInRegistries.MENU.getKey(menu.getType());
        //?} else {
        /*Object key = Registry.MENU.getKey(menu.getType());
        *///?}
        if (key != null) return key.toString();
        //?}
        return "unknown";
    }

    @Override
    public String title() {
        //? if >=1.14.3 {
        Screen screen = CgMinecraftClient.screen(Minecraft.getInstance());
        if (screen instanceof AbstractContainerScreen && ((AbstractContainerScreen<?>) screen).getMenu() == menu()) {
            return screen.getTitle().getString();
        }
        //?}
        return "";
    }

    /** The menu's own values by kind; see {@code MenuTypes}. */
    @Override
    public CgNbtCompound state() {
        CgNbtCompound out = new CgNbtCompound();
        //? if >=1.14.3 {
        AbstractContainerMenu menu = menu();
        if (menu instanceof AbstractFurnaceMenu) {
            AbstractFurnaceMenu furnace = (AbstractFurnaceMenu) menu;
            //? if >=1.20.2 {
            /*out.put("burn", (int) (furnace.getLitProgress() * 1000f));
            out.put("cook", (int) (furnace.getBurnProgress() * 1000f));
            *///?} else {
            out.put("burn", furnace.getLitProgress() * 1000 / 13);
            out.put("cook", furnace.getBurnProgress() * 1000 / 24);
            //?}
        } else if (menu instanceof BrewingStandMenu) {
            out.put("brew", ((BrewingStandMenu) menu).getBrewingTicks());
            out.put("fuel", ((BrewingStandMenu) menu).getFuel());
        } else if (menu instanceof EnchantmentMenu) {
            EnchantmentMenu table = (EnchantmentMenu) menu;
            out.put("costs", new int[]{table.costs[0], table.costs[1], table.costs[2]});
            out.put("clue", new int[]{table.enchantClue[0], table.enchantClue[1], table.enchantClue[2]});
            out.put("level", new int[]{table.levelClue[0], table.levelClue[1], table.levelClue[2]});
            out.put("seed", table.getEnchantmentSeed());
        } else if (menu instanceof AnvilMenu) {
            out.put("cost", ((AnvilMenu) menu).getCost());
        } else if (menu instanceof BeaconMenu) {
            BeaconMenu beacon = (BeaconMenu) menu;
            out.put("levels", beacon.getLevels());
            out.put("primary", effectId(beacon.getPrimaryEffect()));
            out.put("secondary", effectId(beacon.getSecondaryEffect()));
        } else if (menu instanceof StonecutterMenu) {
            StonecutterMenu cutter = (StonecutterMenu) menu;
            out.put("selected", cutter.getSelectedRecipeIndex());
            //? if >=1.21.3 {
            /*out.put("count", cutter.getNumberOfVisibleRecipes());
            *///?} else {
            out.put("count", cutter.getNumRecipes());
            //?}
        } else if (menu instanceof LecternMenu) {
            out.put("page", ((LecternMenu) menu).getPage());
        } else if (menu instanceof MerchantMenu) {
            MerchantMenu merchant = (MerchantMenu) menu;
            out.put("level", merchant.getTraderLevel());
            out.put("xp", merchant.getTraderXp());
            CgNbtList offers = new CgNbtList();
            for (MerchantOffer offer : merchant.getOffers()) {
                CgNbtCompound entry = new CgNbtCompound();
                entry.put("a", CgItemStack.CODEC, stack(offer.getCostA()));
                if (!offer.getCostB().isEmpty()) entry.put("b", CgItemStack.CODEC, stack(offer.getCostB()));
                entry.put("result", CgItemStack.CODEC, stack(offer.getResult()));
                entry.put("uses", offer.getUses());
                entry.put("max", offer.getMaxUses());
                offers.add(entry);
            }
            out.put("offers", offers);
        }
        //?}
        return out;
    }

    //? if >=1.20.6 {
    /*private static String effectId(Holder<MobEffect> effect) {
        return effect == null ? "" : String.valueOf(BuiltInRegistries.MOB_EFFECT.getKey(effect.value()));
    }
    *///?} else if >=1.14.3 {
    private static String effectId(MobEffect effect) {
        //? if >=1.19.3 {
        return effect == null ? "" : String.valueOf(BuiltInRegistries.MOB_EFFECT.getKey(effect));
        //?} else {
        /*return effect == null ? "" : String.valueOf(Registry.MOB_EFFECT.getKey(effect));
        *///?}
    }
    //?}
}
