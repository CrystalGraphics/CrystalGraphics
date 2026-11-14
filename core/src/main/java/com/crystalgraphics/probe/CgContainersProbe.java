package com.crystalgraphics.probe;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;

import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.mc.container.Action;
import com.crystalgraphics.mc.container.CgContainers;
import com.crystalgraphics.mc.container.ClickType;
import com.crystalgraphics.mc.container.SlotKind;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgWorldStimulus;
import com.crystalgraphics.world.CgEntityQueries;

/**
 * {@code crystalgraphics:containers}: a furnace, a chest, an anvil and a merchant opened as a player opens them, read through
 * {@link CgContainers} and driven with its clicks, against the single-player server that answers them. A click that
 * lands proves the packet was vanilla's: the server's reply is what the next read shows.
 *
 * <pre>{@code
 * -Dcrystalgraphics.gauntlet=crystalgraphics:containers
 * // what it reports, per container:
 * containers.furnace.open: true (minecraft:furnace, 39 slots)
 * containers.furnace.pickup: true (8 on the cursor, slot 0 empty)
 * containers.furnace.quickmove: true (8 in the player's slots)
 * }</pre>
 *
 * <ul>
 *   <li>It changes the world: a furnace and a chest two blocks from the player, each holding eight cobblestone, an
 *       anvil, and a villager with one trade; the blocks are broken and the villager removed at the end.</li>
 *   <li>Skipped, with the reason, where there is no single-player server or the era has no use-block call.</li>
 * </ul>
 */
public final class CgContainersProbe {

    private static final double SETTLE = 3, PLACED = 2, TIMEOUT = 6;

    private CgContainersProbe() {
    }

    /** The gauntlet's {@code crystalgraphics:containers}. */
    public static CgGauntletController test() {
        return new Test();
    }

    private record Stage(String name, String group, Runnable begin, BooleanSupplier done, String detail) {
    }

    private static final class Test extends CgGauntletController {
        private final Deque<Stage> stages = new ArrayDeque<>();
        private Stage running;
        private double clock, started;
        private boolean placed;
        private int bx, by, bz;

        @Override
        protected void onStateChange(CgGauntletState from, CgGauntletState to) {
            if (to.equals(CgGauntletState.TITLE)) enterWorld();
        }

        @Override
        protected void onTick(float seconds) {
            clock += seconds;
            if (!state().equals(CgGauntletState.IN_WORLD)) return;
            if (!placed) {
                if (!place()) return;
                placed = true;
                started = clock;
                return;
            }
            if (clock - started < PLACED) return;
            advance();
        }

        private boolean place() {
            CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
            CgEntityQuery entities = CgEntityQueries.client();
            int me = entities.localPlayer();
            if (me < 0) return false;
            if (started == 0) {
                stimulus.keepRunning();
                started = clock;
                return false;
            }
            if (clock - started < SETTLE) return false;
            double[] pose = new double[CgEntityQuery.POSE_LENGTH];
            if (!entities.pose(me, 1f, pose)) return false;
            bx = (int) Math.floor(pose[CgEntityQuery.X]);
            by = (int) Math.floor(pose[CgEntityQuery.Y]);
            bz = (int) Math.floor(pose[CgEntityQuery.Z]);
            boolean made = stimulus.container(bx + 2, by, bz, "furnace") & stimulus.container(bx + 2, by, bz + 2, "chest");
            boolean anvil = stimulus.container(bx + 2, by, bz - 2, "anvil");
            boolean merchant = stimulus.merchant(bx - 2, by, bz);
            if (!made) {
                skip("containers", "no single-player server to place a block on");
                endTest();
                return false;
            }
            script("furnace", bx + 2, by, bz, "minecraft:furnace");
            script("chest", bx + 2, by, bz + 2, "minecraft:generic_9x");
            if (anvil) anvil(bx + 2, by, bz - 2);
            else skip("anvil", "this version cannot place an anvil");
            if (merchant) merchant(bx - 2, by, bz);
            else skip("merchant", "this version cannot place a villager");
            return true;
        }

        /** One container's stages: open, read, pick up, put back, quick-move, close. */
        private void script(String group, int x, int y, int z, String type) {
            CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
            stages.add(new Stage(group + ".open", group, () -> {
                if (!stimulus.use(x, y, z)) {
                    skip(group + ".open", "this version has no use-block call");
                    stages.removeIf(stage -> stage.group().equals(group));
                    running = null;
                }
            }, () -> {
                CgContainers.Menu menu = CgContainers.menu();
                return menu != null && !menu.isPlayerInventory() && menu.type().startsWith(type);
            }, "a menu of " + type));
            stages.add(new Stage(group + ".read", group, () -> { }, () -> count(0) == 8,
                    "eight cobblestone in slot 0"));
            stages.add(new Stage(group + ".pickup", group, () -> click(0, ClickType.PICKUP),
                    () -> carried() == 8 && count(0) == 0, "eight on the cursor, slot 0 empty"));
            stages.add(new Stage(group + ".place", group, () -> click(0, ClickType.PICKUP),
                    () -> carried() == 0 && count(0) == 8, "back in slot 0, cursor empty"));
            stages.add(new Stage(group + ".quickmove", group, () -> click(0, ClickType.QUICK_MOVE),
                    () -> count(0) == 0 && inPlayer() >= 8, "eight in the player's slots, slot 0 empty"));
            stages.add(new Stage(group + ".close", group, () -> act(new Action.Close()), () -> {
                CgContainers.Menu menu = CgContainers.menu();
                return menu == null || menu.isPlayerInventory();
            }, "the player's own menu again"));
        }

        /** The anvil: opened, its three slots and the player's, a rename sent where the era sends one, closed. */
        private void anvil(int x, int y, int z) {
            CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
            stages.add(new Stage("anvil.open", "anvil", () -> opened(stimulus.use(x, y, z), "anvil"),
                    () -> isType("minecraft:anvil"), "a menu of minecraft:anvil"));
            stages.add(layout("anvil"));
            if (CgContainers.capabilities().rename()) {
                stages.add(new Stage("anvil.rename", "anvil", () -> act(new Action.Rename("cg")),
                        () -> isType("minecraft:anvil"), "a rename sent, the menu still open"));
                stages.add(new Stage("anvil.state", "anvil", () -> { },
                        () -> state("cost"), "the repair cost in the menu's state"));
            } else {
                skip("anvil.rename", "this era sends no rename");
            }
            stages.add(closing("anvil"));
        }

        /** The merchant: a villager found beside the player and used, its slots, a trade selected where the era selects, closed. */
        private void merchant(int x, int y, int z) {
            CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
            stages.add(new Stage("merchant.open", "merchant", () -> opened(stimulus.useEntity(villager(x, y, z)), "merchant"),
                    () -> isType("minecraft:merchant"), "a menu of minecraft:merchant"));
            stages.add(layout("merchant"));
            if (CgContainers.capabilities().selectTrade()) {
                stages.add(new Stage("merchant.select", "merchant", () -> act(new Action.SelectTrade(0)),
                        () -> isType("minecraft:merchant"), "a trade selected, the menu still open"));
                stages.add(new Stage("merchant.state", "merchant", () -> { },
                        () -> state("offers"), "the offers in the menu's state"));
            } else {
                skip("merchant.select", "this era selects no trade");
            }
            stages.add(closing("merchant"));
        }

        private void opened(boolean sent, String group) {
            if (sent) return;
            skip(group + ".open", "this version has no use call for it");
            stages.removeIf(stage -> stage.group().equals(group));
            running = null;
        }

        /** The id of the villager near the point, or -1. */
        private int villager(int x, int y, int z) {
            CgEntityQuery entities = CgEntityQueries.client();
            int me = entities.localPlayer();
            int[] found = {-1};
            entities.within(x - 1, y - 1, z - 1, x + 2, y + 3, z + 2, id -> {
                if (id != me) found[0] = id;
            });
            return found[0];
        }

        private Stage layout(String group) {
            return new Stage(group + ".layout", group, () -> { }, () -> {
                CgContainers.Menu menu = CgContainers.menu();
                return menu != null && menu.slots().size() == 39 && menu.slot(2).kind() == SlotKind.RESULT;
            }, "3 slots and the player's 36, the third a result");
        }

        private Stage closing(String group) {
            return new Stage(group + ".close", group, () -> act(new Action.Close()), () -> {
                CgContainers.Menu menu = CgContainers.menu();
                return menu == null || menu.isPlayerInventory();
            }, "the player's own menu again");
        }

        private static boolean isType(String type) {
            CgContainers.Menu menu = CgContainers.menu();
            return menu != null && !menu.isPlayerInventory() && menu.type().startsWith(type);
        }

        private static boolean state(String key) {
            CgContainers.Menu menu = CgContainers.menu();
            return menu != null && menu.state().has(key);
        }

        private void advance() {
            if (running != null) {
                if (running.done().getAsBoolean()) {
                    check(running.name(), true, running.detail());
                    running = null;
                } else if (clock - started > TIMEOUT) {
                    check(running.name(), false, "not seen in " + TIMEOUT + " s: " + running.detail() + "; " + now());
                    String group = running.group();
                    running = null;
                    // a container that failed is left, closed, and the next one tried
                    act(new Action.Close());
                    stages.removeIf(stage -> stage.group().equals(group));
                } else {
                    return;
                }
            }
            Stage next = stages.poll();
            if (next == null) {
                CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
                stimulus.breakBlock(bx + 2, by, bz);
                stimulus.breakBlock(bx + 2, by, bz + 2);
                stimulus.breakBlock(bx + 2, by, bz - 2);
                stimulus.clear(bx - 2, by, bz, 3);
                endTest();
                return;
            }
            running = next;
            started = clock;
            next.begin().run();
            if (running != null && next.done().getAsBoolean()) {
                check(next.name(), true, next.detail());
                running = null;
            }
        }

        private static void click(int slot, ClickType type) {
            act(new Action.Click(slot, 0, type));
        }

        private static void act(Action action) {
            CgContainers.Menu menu = CgContainers.menu();
            if (menu != null) CgContainers.act(menu.id(), action);
        }

        private static int count(int slot) {
            CgContainers.Menu menu = CgContainers.menu();
            return menu == null || slot >= menu.slots().size() ? -1 : menu.slot(slot).stack().count();
        }

        private static int carried() {
            CgContainers.Menu menu = CgContainers.menu();
            return menu == null ? -1 : menu.carried().count();
        }

        /** Items held by the player's own slots of the open menu. */
        private static int inPlayer() {
            CgContainers.Menu menu = CgContainers.menu();
            if (menu == null) return -1;
            int total = 0;
            for (CgContainers.Slot slot : menu.slots()) {
                SlotKind kind = slot.kind();
                if (kind == SlotKind.INVENTORY || kind == SlotKind.HOTBAR) total += slot.stack().count();
            }
            return total;
        }

        private static String now() {
            CgContainers.Menu menu = CgContainers.menu();
            if (menu == null) return "no menu";
            return menu.type() + ", " + menu.slots().size() + " slots, slot 0 " + count(0) + ", cursor " + carried()
                    + ", player slots " + inPlayer();
        }
    }
}
