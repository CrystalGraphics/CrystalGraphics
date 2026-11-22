package com.crystalgraphics.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.block.CgBlocks;
import com.crystalgraphics.mc.entity.CgEntityData;
import com.crystalgraphics.mc.fluid.CgFluidStack;
import com.crystalgraphics.mc.fluid.CgFluids;
import com.crystalgraphics.mc.item.CgIngredient;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.mc.level.CgBlockPos;
import com.crystalgraphics.mc.level.CgDirection;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.platform.service.CgServers;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtTag;

/**
 * {@code crystalgraphics:values}: each value's command text read by the game's own parser and by ours, and the two
 * compared; on a dedicated server ({@code serverSmoke}) and on a client. Where a version has no parser (before 1.13)
 * the game's own state, registry or NBT parser stands in, and the check says so.
 *
 * <pre>{@code
 * -Dcrystalgraphics.gauntlet=crystalgraphics:server,crystalgraphics:values
 * }</pre>
 *
 * <ul>
 *   <li>{@code values.item}: a damaged stack with custom data, as the running version's {@code /give} writes it.</li>
 *   <li>{@code values.block}: a furnace's state and block entity data, as {@code /setblock} writes it; compared as
 *       the game's whole state, every property.</li>
 *   <li>{@code values.entity}: a sheared red sheep made by the game from {@code /summon}'s data, saved back: every key given is
 *       kept.</li>
 *   <li>{@code values.faces}, {@code values.position}, {@code values.tag}: the game's face names and order, its
 *       position packing, an item tag's contents. {@code values.fluid}, on a client: water's name, sprite and tint.</li>
 * </ul>
 */
public final class CgValuesProbe {

    /** What each era answers with its own parsers. Server thread, or the client's with a world. */
    public interface Host {

        /** Why this node cannot answer at all; null where it can. */
        @Nullable
        String absent();

        /** The game's own parse of {@code /give}'s item text (count 1), copied out; null where the version has none. */
        @Nullable
        CgItemStack gameItem(String text);

        /** The game's own parse of {@code /setblock}'s text: its whole state and the block entity data; null for none. */
        @Nullable
        CgBlockState gameBlock(String text);

        /** The entity the game makes from {@code snbt} (read by the game's SNBT parser), saved back with its id. */
        @Nullable
        CgEntityData gameEntity(String snbt);

        /** This version's id for a sheep: {@code minecraft:sheep}, or {@code Sheep} before 1.11. */
        String sheep();

        /** The game's face names in its own order. */
        List<String> faces();

        /** The game's {@code BlockPos.asLong}, or null before 1.14. */
        @Nullable
        Long packed(int x, int y, int z);

        Host NONE = new Host() {
            @Override public String absent() { return "no values probe host on this node"; }
            @Override public CgItemStack gameItem(String text) { return null; }
            @Override public CgBlockState gameBlock(String text) { return null; }
            @Override public CgEntityData gameEntity(String snbt) { return null; }
            @Override public String sheep() { return "minecraft:sheep"; }
            @Override public List<String> faces() { return new ArrayList<>(); }
            @Override public Long packed(int x, int y, int z) { return null; }
        };

        // After NONE: an interface's constants initialise in order
        CgService<Host> SERVICE = CgService.of("crystalgraphics:values_probe", NONE);
    }

    private CgValuesProbe() {
    }

    public static CgGauntletController test() {
        return new Test();
    }

    private static final class Test extends CgGauntletController {

        @Override
        protected void onStateChange(CgGauntletState from, CgGauntletState to) {
            if (to.equals(CgGauntletState.TITLE)) enterWorld();
        }

        @Override
        protected void onTick(float seconds) {
            // A client's world, or a dedicated server's, which is never "in world"
            boolean ready = state().equals(CgGauntletState.IN_WORLD) || state().equals(CgGauntletState.SERVER);
            if (!ready || timeInState() < 1) return;
            Host host = CgPlatform.get(Host.SERVICE);
            String absent = host.absent();
            if (absent != null) {
                skip("values", absent);
            } else {
                run("values.item", () -> item(host));
                run("values.block", () -> block(host));
                run("values.entity", () -> entity(host));
                run("values.faces", () -> levels(host));
                run("values.tag", this::tag);
                run("values.fluid", this::fluid);
            }
            endTest();
        }

        // A parser that refuses the text fails its own check with the game's message
        private void run(String name, Runnable check) {
            try {
                check.run();
            } catch (RuntimeException e) {
                check(name, false, "threw " + e);
            }
        }

        private void item(Host host) {
            CgItemStack sample = CgItemStack.of("minecraft:diamond_sword", 1).withDamage(9)
                    .withCustomData(d -> d.put("cgProbe", 3));   // a bare key: 1.7.10 reads no quoted one
            String text = sample.toCommand();
            CgItemStack ours = CgItemStack.parse(text);
            CgItemStack game = host.gameItem(text);
            if (game == null) {
                skip("values.item", "no item parser here (" + CgItems.shape() + ")");
                return;
            }
            check("values.item", ours.equals(sample) && game.equals(ours),
                    text + " -> ours " + ours + ", the game's " + game);
        }

        private void block(Host host) {
            if (!CgBlocks.present()) {
                skip("values.block", "no block states on this version (1.7.10: a block and its metadata)");
                return;
            }
            boolean flat = CgItems.shape() != CgItems.Shape.LEGACY;
            CgBlockState sample = CgBlockState.parse(flat ? "minecraft:furnace[facing=east,lit=true]{CookTime:3s}"
                    : "minecraft:furnace[facing=east]{CookTime:3s}");
            CgBlockState ours = CgBlockState.parse(sample.toString());
            // Ours through the game's own state: every property, defaults included
            CgBlockState whole = CgBlockState.of(ours.<Object>toHost()).withBlockEntity(ours.blockEntity());
            CgBlockState game = host.gameBlock(sample.toString());
            if (game == null) {
                boolean kept = whole.properties().entrySet().containsAll(ours.properties().entrySet());
                check("values.block", ours.equals(sample) && kept,
                        "no block parser before 1.13: through the game's state, " + sample + " -> " + whole);
                return;
            }
            check("values.block", ours.equals(sample) && game.equals(whole),
                    sample + " -> ours " + whole + ", the game's " + game);
        }

        private void entity(Host host) {
            CgEntityData sample = CgEntityData.parse(host.sheep() + "{Color:2b,Sheared:1b}");
            CgEntityData game = host.gameEntity(sample.toNbt().toString());
            if (game == null) {
                skip("values.entity", "this node made no entity from data");
                return;
            }
            List<String> lost = new ArrayList<>();
            CgNbtCompound saved = game.data();
            for (String key : sample.data().keys()) {
                CgNbtTag mine = sample.data().get(key), theirs = saved.get(key);
                if (theirs == null || !mine.toString().equals(theirs.toString())) lost.add(key + "=" + theirs);
            }
            boolean sameType = game.type().equalsIgnoreCase(sample.type());
            check("values.entity", sameType && lost.isEmpty() && sample.equals(CgEntityData.parse(sample.toString())),
                    sample + " -> the game's " + game.type() + (lost.isEmpty() ? ", every key kept" : ", lost " + lost));
        }

        private void levels(Host host) {
            List<String> ours = new ArrayList<>();
            for (CgDirection d : CgDirection.values()) ours.add(d.toString());
            List<String> game = host.faces();
            check("values.faces", ours.equals(game), "ours " + ours + ", the game's " + game);
            List<CgBlockPos> samples = Arrays.asList(new CgBlockPos(1, 2, 3), new CgBlockPos(-30000000, -64, 29999999),
                    new CgBlockPos(-1, 319, -1));
            Long first = host.packed(1, 2, 3);
            if (first == null) {
                skip("values.position", "no BlockPos.asLong before 1.14");
                return;
            }
            List<String> wrong = new ArrayList<>();
            for (CgBlockPos p : samples) {
                Long g = host.packed(p.x(), p.y(), p.z());
                if (g == null || g != p.asLong() || !CgBlockPos.of(g).equals(p)) wrong.add(p + " -> " + g);
            }
            check("values.position", wrong.isEmpty(), wrong.isEmpty() ? samples.size() + " positions pack as the game's" : "differ: " + wrong);
        }

        private void tag() {
            if (CgItems.shape() == CgItems.Shape.LEGACY) {
                skip("values.tag", "no item tags before 1.13");
                return;
            }
            CgIngredient planks = CgIngredient.parse("#minecraft:planks");
            List<CgItemStack> items = planks.items();
            boolean oak = planks.test(CgItemStack.of("minecraft:oak_planks", 1));
            check("values.tag", !items.isEmpty() && oak, items.size() + " planks; oak among them: " + oak);
        }

        private void fluid() {
            if (CgServers.dedicated()) {
                skip("values.fluid", "a fluid's name, sprite and tint are a client's");
                return;
            }
            CgFluidStack water = CgFluidStack.of(CgItems.shape() == CgItems.Shape.LEGACY ? "water" : "minecraft:water", 1000);
            String name = CgFluids.name(water), sprite = CgFluids.stillSprite(water);
            int tint = CgFluids.tint(water);
            check("values.fluid", !name.equals(water.fluid()) && sprite != null && (tint & 0xFF000000) != 0,
                    "name " + name + ", sprite " + sprite + ", tint " + Integer.toHexString(tint));
        }
    }
}
