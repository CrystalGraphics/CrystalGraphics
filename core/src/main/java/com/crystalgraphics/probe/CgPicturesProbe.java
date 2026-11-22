package com.crystalgraphics.probe;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.canvas.CgPicture;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.gauntlet.CgGauntletCapture;
import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.entity.CgEntityData;
import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.mc.render.CgHostPicture;
import com.crystalgraphics.mc.render.CgHostPictures;
import com.crystalgraphics.mc.render.CgPictureRefresh;
import com.crystalgraphics.mc.render.block.CgBlockPicture;
import com.crystalgraphics.mc.render.block.CgBlockPictures;
import com.crystalgraphics.mc.render.custom.CgCustomPicture;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.custom.CgPictureRenderer;
import com.crystalgraphics.mc.render.entity.CgEntityPicture;
import com.crystalgraphics.mc.render.entity.CgEntityPictures;
import com.crystalgraphics.mc.render.entity.CgEntityPose;
import com.crystalgraphics.mc.render.item.CgItemIcon;
import com.crystalgraphics.mc.render.item.CgItemIcons;
import com.crystalgraphics.mc.render.tooltip.CgTooltipPicture;
import com.crystalgraphics.mc.render.tooltip.CgTooltips;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.platform.service.CgModLoader;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtList;
import com.crystalgraphics.platform.world.CgWorldStimulus;
import com.crystalgraphics.render.stage.CgRenderStage;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

/**
 * The draw point's parity test (renderer § 19.7): Minecraft draws its own objects into a {@link CgCustomPicture} at the
 * draw point, a canvas composites it, and the result is what vanilla's own GUI code draws in a slot. The gauntlet's
 * test {@code crystalgraphics:pictures}; it enters a world itself.
 *
 * <pre>{@code
 * -Dcrystalgraphics.gauntlet=crystalgraphics:pictures
 * -Dcrystalgraphics.pictures.items=create:potato_cannon+mowziesmobs:earthrend_gauntlet   // mods' items, where installed
 * // what it logs, and writes in the run's report:
 * crystalgraphics:pictures pictures.parity.minecraft:clock: true (0 of 1024 pixels differ by more than 1; max 0)
 * crystalgraphics:pictures pictures.cost.static100 is 1.84 ms cpu a frame into the picture, 1.79 ms drawn by vanilla
 * }</pre>
 *
 * <ul>
 *   <li>Each frame of the parity phase, the host's HUD hook fills two panels with one opaque colour and draws the cells
 *       in the left one with vanilla's own GUI code ({@link Host#reference}); the draw point draws the same cells into
 *       {@link #PICTURE} ({@link Host#cells}), and this probe's HUD renderer composites it over the right panel. The
 *       frame's capture is compared panel against panel, cell by cell: pass is no pixel differing by more than one step
 *       a channel.</li>
 *   <li>Beside them: a picture whose renderer throws shows the missing texture and is marked failed; in a dev run, a
 *       draw point inside a device section is refused; a 100-slot grid's draw time, static and glinted.</li>
 *   <li>Hosts provide {@link Host#SERVICE}; where none does, the test skips, saying so.</li>
 * </ul>
 */
public final class CgPicturesProbe {

    /** A thing to draw, at a position in GUI units from its panel's corner. */
    public record Cell(Kind kind, String item, int x, int y, int count, int damage, boolean glint) {

        /** The rect it may touch, in GUI units: {x, y, width, height}. */
        int[] rect() {
            return switch (kind) {
                case ITEM, DECORATED -> new int[]{x - 1, y - 1, 18, 18};
                case PLAYER -> new int[]{x - 26, y - 66, 52, 70};
                case TOOLTIP -> new int[]{x, y - 16, W - x, H - (y - 16)};
                case ENTITY -> new int[]{x, y, Math.round(count * 52f / 30f), Math.round(count * 70f / 30f)};
                case BLOCK -> new int[]{x, y, 16, 16};
            };
        }

        /** Whether a glint is drawn: an enchanted stack's icon, not its tooltip. */
        boolean glinted() {
            return glint && (kind == Kind.ITEM || kind == Kind.DECORATED);
        }

        /** The most a pixel of it may differ, and why when more than 1. */
        int slack() {
            return glinted() ? GLINT_SLACK : kind == Kind.TOOLTIP ? LAYER_SLACK : 1;
        }

        String slackReason() {
            return glinted() ? "the glint's clock moved between the draws"
                    : "its translucent layers round once more each in the picture's own target";
        }

        String name() {
            return switch (kind) {
                case ITEM -> item + (glint ? "+glint" : "");
                case DECORATED -> item + "+decorations";
                case PLAYER -> "player";
                case TOOLTIP -> item + "+tooltip";
                case ENTITY -> "entity:" + item;
                case BLOCK -> "block:" + item;
            };
        }
    }

    /**
     * {@code ITEM}: {@code GuiGraphics.renderItem}'s; {@code DECORATED}: that and its count, bar and cooldown;
     * {@code PLAYER}: the local player, as the inventory draws it, {@code count} its scale, the pointer {@value #LOOK_X},
     * {@value #LOOK_Y} GUI units from it; {@code TOOLTIP}: the item's tooltip, the pointer at its cell; {@code ENTITY}: a
     * {@link CgEntityPicture} of {@code item} ({@code player}, or entity data), {@code count} units a block; {@code BLOCK}:
     * a {@link CgBlockPicture} of the block state {@code item}. The last two are drawn by their host slots in both panels.
     */
    public enum Kind { ITEM, DECORATED, PLAYER, TOOLTIP, ENTITY, BLOCK }

    /** What a host measured as it drew. */
    public static final class Drawn {
        /** CPU time of the draw. */
        public long nanos;
        /** Whether the reference drew: a hook with no GUI object on a recorded GUI, or a node without a fill, cannot. */
        public boolean drew;
        /** Whether the host checks its bound target after each cell: a GL read, which a cost phase must not pay. */
        public boolean checkTarget;
        /** Names of cells after which the host's bound target was no longer the one it drew into. */
        public final List<String> retargeted = new ArrayList<>();

        void clear() {
            nanos = 0;
            drew = false;
            retargeted.clear();
        }
    }

    /** The host's half: vanilla's own GUI calls, into the main target or a picture the draw point points them at. */
    public interface Host {

        Host NONE = new Host() {
            @Override public String absent() { return "no pictures probe host code on this node"; }
            @Override public boolean knows(String item) { return false; }
            @Override public void reference(Object gui, List<Cell> cells, int x, int y, int w, int h, int bx, int by,
                                            int background, Drawn out) { }
            @Override public void cells(CgHostDrawContext ctx, List<Cell> cells, Drawn out) { }
        };

        CgService<Host> SERVICE = CgService.of("crystalgraphics:pictures_probe", NONE);

        /** Why this node has no probe host code; null where it has. */
        @Nullable String absent();

        /** Whether the game has an item of this id. */
        boolean knows(String item);

        /** Whether this node's host code draws {@code kind}; the probe asks only for those. */
        default boolean draws(Kind kind) {
            return true;
        }


        /**
         * From the host's HUD hook: fills {@code w x h} at ({@code x}, {@code y}) and at ({@code bx}, {@code by}) with the
         * opaque {@code background}, then draws {@code cells} in the first with vanilla's GUI code. {@code gui} is the
         * hook's GUI object, null where the host makes its own.
         */
        void reference(@Nullable Object gui, List<Cell> cells, int x, int y, int w, int h, int bx, int by, int background,
                       Drawn out);

        /** Draws {@code cells} with {@code ctx}'s GUI objects into the picture the draw point points them at. */
        void cells(CgHostDrawContext ctx, List<Cell> cells, Drawn out);

        /**
         * A draw context over the HUD hook's GUI objects ({@code gui}, or the host's own), at the screen's origin, for the
         * entity and block slots to draw the reference with. Asked only where {@link #draws} says the host draws
         * {@code ENTITY} or {@code BLOCK} cells; null where this frame's hook gives none.
         */
        @Nullable
        default CgHostDrawContext hudContext(@Nullable Object gui) {
            return null;
        }

        /** Keeps {@code item} cooling down for the player, as using it would. Client tick. Nothing where it cannot. */
        default void cooldown(String item) {
        }
    }

    /** The panels, in GUI units: the reference at the left, the picture beside it, below it. */
    public static final int W = 332, H = 90, REF_X = 4, REF_Y = 4, PIC_X = 4, PIC_Y = REF_Y + H + 4;
    /** The item row's width: the entity and block cells sit past it. */
    private static final int ROW_W = 200;
    /** Where the failing picture shows, in GUI units: right of the panels. */
    private static final int FAIL_X = W + 12, FAIL_Y = REF_Y, FAIL_SIZE = 32;
    static final int BACKGROUND = 0xFF3A5F7F;
    /** The pointer's offset from the player, as the inventory's: where it looks. */
    public static final int LOOK_X = 20, LOOK_Y = -30;
    private static final int GRID = 10;
    /**
     * The most a glinted pixel may differ: the glint scrolls with the millisecond clock, and the picture draws up to a
     * frame after the reference under load (12 measured with four slow clients at once; a wrong draw is near 200).
     */
    private static final int GLINT_SLACK = 16;
    /**
     * The most a pixel under stacked translucent layers may differ: the picture blends them over transparent in an 8-bit
     * target and is composited after, a rounding a layer more than vanilla's straight blend (a tooltip's fill and border).
     */
    private static final int LAYER_SLACK = 2;
    /** Client ticks: in the world before drawing; parity frames before the shot; frames a cost is measured over. */
    private static final int SETTLE = 60, WARM = 20, COST = 60;
    /** Ticks past WARM a parity shot waits for the panel's pictures to draw: a slow client spreads first draws. */
    private static final int HOLD = 600;
    private static final String VANILLA = "minecraft:diamond_sword,minecraft:clock,minecraft:compass,minecraft:chest,"
            + "minecraft:shield,minecraft:carved_pumpkin,minecraft:oak_sapling";

    private enum Phase { WAITING, SETTLING, PARITY, SHOT, TIPS, TIPS_SHOT, COST, DONE }

    /** A cost workload: a grid of one kind, {@code scale} its cells' size where the kind has one. */
    private record CostRun(String name, Kind kind, List<String> what, int scale, boolean glint, int count) {
    }

    /** What the cost phase measures, each drawn by the engine alone and by vanilla alone, against the empty frame. */
    private static final List<CostRun> RUNS = List.of(
            new CostRun("static100", Kind.ITEM, List.of(), 1, false, GRID * GRID),
            new CostRun("glinted100", Kind.ITEM, List.of(), 1, true, GRID * GRID),
            new CostRun("entities48", Kind.ENTITY, List.of("minecraft:zombie", "minecraft:sheep{Color:2b}",
                    "minecraft:villager", "player"), 12, false, 48),
            new CostRun("chests50", Kind.BLOCK, List.of("minecraft:chest[facing=south]"), 1, false, 50),
            new CostRun("tooltips12", Kind.TOOLTIP, List.of("minecraft:diamond_sword", "minecraft:book"), 1, true, 12));
    /** The runs to measure, by name ({@code -Dcrystalgraphics.pictures.cost=entities48}); empty measures every one. */
    private static final String ONLY = System.getProperty("crystalgraphics.pictures.cost", "");

    /** The run measured, -1 the empty frame; its side, 0 the engine's pictures, 1 vanilla's drawing. */
    private static int costRun, costSide;
    /** Frame intervals summed over a side, after its settle; the empty frame's, in ms. */
    private static long frameSum, frameLast;
    private static int frameCount;
    private static double emptyFrameMs;

    /** Kept cooling down through the run: its overlay animates as vanilla's does. */
    public static final String COOLING = "minecraft:ender_pearl";
    /** Decorated by the loader's probe decorator where the loader has item decorators (Forge, NeoForge). */
    public static final String DECORATED_BY_PROBE = "minecraft:stick";

    private static volatile Test active;
    private static volatile Phase phase = Phase.WAITING;
    private static int ticks;
    private static boolean installed;
    /** What is drawn now: the parity cells, or a cost grid. */
    private static volatile List<Cell> cells = List.of();
    /** The item ids this game has, of those the probe asks for. */
    private static List<String> known = List.of("minecraft:diamond_sword");
    private static volatile boolean composite;
    /** The GUI scale the HUD fired at. */
    private static volatile float scale = 1f;
    private static final Drawn picture = new Drawn(), reference = new Drawn();
    private static long pictureNanos, referenceNanos, statsFrame = -1L;
    /** Client ticks a cost phase lasts; the render frames inside it, each side's own. */
    private static int costFrames, pictureFrames, referenceFrames;
    private static boolean retargetNoted, pictureAsked, referenceDrew;
    /** The parity rounds and the one shown; {@link #rounds}. */
    private static List<List<Cell>> rounds = List.of();
    private static int round;
    /** Pictures of the panel not drawn yet, last frame: a slow client's budget spreads first draws over many. */
    private static volatile int unreadyPictures = -1;
    private static volatile String pictureRead = "never asked";
    private static volatile int pictureDrawn;
    @Nullable private static volatile String refused;
    private static CgGauntletCapture.Shot shot;
    /** The cells' icons, kept: a cell asks the same handle each frame. */
    private static final Map<Cell, CgItemIcon> ICONS = new ConcurrentHashMap<>();
    private static final Map<Cell, CgEntityPicture> ENTITIES = new ConcurrentHashMap<>();
    private static final Map<Cell, CgBlockPicture> BLOCKS = new ConcurrentHashMap<>();
    /** The reference's own game objects, render thread: never the pictures'. */
    private static final Map<Cell, Object> REFERENCE_ENTITIES = new HashMap<>(), REFERENCE_BLOCKS = new HashMap<>();
    /** Cells whose reference threw, noted once each. */
    private static final Set<String> slotThrown = new TreeSet<>(), slotMade = new TreeSet<>();
    private static final Map<String, Integer> slotTries = new HashMap<>();
    /** Frames a reference entity may take to be made before the probe says the game made none. */
    private static final int MAKE_TRIES = 60;
    /** Frames a cost grid's icons take to draw for the first time, the budget spreading them, before they are counted. */
    private static final int ICONS_SETTLE = 10;
    private static long iconFrame = Long.MIN_VALUE;
    private static int iconFrames, iconFramesRedrawn, iconFirstDraws, iconRedraws;
    /** When the first and the last counted frame drew, nanoseconds. */
    private static long iconFirstNanos, iconLastNanos;
    /** The icon pace put aside while pixels are compared, or negative while it is in force. */
    private static float pacedHz = -1f;

    /** What the draw point draws: the cells, with Minecraft's code, every frame. */
    static final CgCustomPicture PICTURE = CgCustomPicture.of("crystalgraphics:pictures_probe")
            .refresh(CgPictureRefresh.REALTIME)
            .renderer(ctx -> {
                picture.checkTarget = parity();
                CgPlatform.get(Host.SERVICE).cells(ctx, hostDrawn(cells), picture);
                if (parity() && !picture.retargeted.isEmpty() && !retargetNoted && active != null) {
                    retargetNoted = true;
                    active.noted("pictures.retargeted", picture.retargeted.toString());
                }
                picture.clear();
            });

    /** A renderer that throws: its picture shows the missing texture, and the draw point carries on. */
    static final CgCustomPicture FAILING = CgCustomPicture.of("crystalgraphics:pictures_probe.throws")
            .renderer(ctx -> {
                throw new IllegalStateException("thrown on purpose by the pictures probe");
            });

    private CgPicturesProbe() {
    }

    /** The gauntlet's {@code crystalgraphics:pictures}. */
    public static CgGauntletController test() {
        return new Test();
    }

    /**
     * A renderer drawing {@code cells} with this node's probe host code: a custom picture of Minecraft's own objects for
     * a showcase (the Game Gallery). Draws nothing where no host provides the probe.
     */
    public static CgPictureRenderer renderer(List<Cell> cells) {
        List<Cell> copy = List.copyOf(cells);
        Drawn out = new Drawn();
        return ctx -> {
            CgPlatform.get(Host.SERVICE).cells(ctx, copy, out);
            out.clear();
        };
    }

    // ── the host's call ──────────────────────────────────────────────────────────

    /** Whether the test runs: a loader's probe decorator draws its mark only then. Any thread. */
    public static boolean running() {
        return active != null;
    }

    /** Host side: its HUD hook, with the hook's GUI object (null where it makes its own). Render thread. */
    public static void hud(@Nullable Object gui) {
        if (active == null || !drawing() || !referenceShown()) return;
        long start = System.nanoTime();
        Host host = CgPlatform.get(Host.SERVICE);
        reference.clear();
        host.reference(gui, hostKinds(cells), REF_X, REF_Y, panelW(), panelH(), PIC_X, PIC_Y, BACKGROUND, reference);
        CgHostDrawContext ctx = slotCells(cells) ? host.hudContext(gui) : null;
        if (ctx != null) {
            for (Cell c : cells) {
                try {
                    slotReference(ctx, c);
                } catch (RuntimeException e) {
                    // The picture shows the missing texture for the same throw: its parity check fails, the game carries on
                    if (slotThrown.add(c.name()) && active != null) active.noted("pictures.reference.threw." + c.name(), e.toString());
                }
            }
        }
        referenceNanos += System.nanoTime() - start;
        referenceFrames++;
        if (reference.drew) referenceDrew = true;
    }

    /** Whether this node draws item icons; where not (1.13.2), items stay in the probe's picture. */
    private static boolean iconsHere() {
        return CgPlatform.get(CgItemIcons.HOST).absent() == null;
    }

    /** Whether this node draws tooltips; where not (1.13.2), they stay in the probe's picture. */
    private static boolean tooltipsHere() {
        return CgPlatform.get(CgTooltips.HOST).absent() == null;
    }

    /** The cells the probe's own picture draws: what icons, overlays and tooltip pictures do not. */
    private static List<Cell> hostDrawn(List<Cell> all) {
        boolean icons = iconsHere(), tips = tooltipsHere();
        List<Cell> out = new ArrayList<>(all.size());
        for (Cell c : all) {
            boolean item = c.kind() == Kind.ITEM || c.kind() == Kind.DECORATED;
            if (item && icons || c.kind() == Kind.TOOLTIP && tips || slotKind(c.kind())) continue;
            out.add(c);
        }
        return out;
    }

    private static boolean slotKind(Kind kind) {
        return kind == Kind.ENTITY || kind == Kind.BLOCK;
    }

    private static boolean slotCells(List<Cell> all) {
        for (Cell c : all) {
            if (slotKind(c.kind())) return true;
        }
        return false;
    }

    /** What the host's own code draws: every cell but those its entity and block slots do. */
    private static List<Cell> hostKinds(List<Cell> all) {
        List<Cell> out = new ArrayList<>(all.size());
        for (Cell c : all) {
            if (!slotKind(c.kind())) out.add(c);
        }
        return out;
    }

    /** The HUD context of a host drawing with GL state alone (legacy Forge, 1.7.10): no GUI objects, no size. */
    public static final CgHostDrawContext GL_STATE = new CgHostDrawContext() {
        @Override public Object gui() { return null; }
        @Override public Object poseStack() { return null; }
        @Override public Object buffers() { return null; }
        @Override public float width() { return 0; }
        @Override public float height() { return 0; }
        @Override public float scale() { return 1; }
        @Override public float partialTick() { return 1; }
    };

    /** A cell's pose: the player looking at the pointer as the {@code PLAYER} cell's does; anything else turned 30 degrees. */
    private static CgEntityPose pose(Cell c) {
        return c.item().equals("player") ? CgEntityPose.followingPointer(-LOOK_X, -LOOK_Y) : CgEntityPose.facing(-30, 0);
    }

    private static CgEntityRef ref(Cell c) {
        return c.item().equals("player") ? CgEntityRef.player() : CgEntityRef.of(CgEntityData.parse(c.item()));
    }

    private static CgEntityPicture entityPicture(Cell c) {
        return ENTITIES.computeIfAbsent(c, k -> CgEntityPicture.of(ref(k)).pose(pose(k)).scale(k.count()));
    }

    private static CgBlockPicture blockPicture(Cell c) {
        return BLOCKS.computeIfAbsent(c, k -> CgBlockPicture.of(CgBlockState.parse(k.item())));
    }

    /** The reference of an entity or block cell: its slot drawing straight into the HUD, in the cell's box. */
    private static void slotReference(CgHostDrawContext ctx, Cell c) {
        int[] r = c.rect();
        float x = REF_X + r[0], y = REF_Y + r[1];
        if (c.kind() == Kind.ENTITY) {
            CgEntityPictures.Host host = CgPlatform.get(CgEntityPictures.HOST);
            Object e = REFERENCE_ENTITIES.compute(c, (k, before) -> host.entity(ref(k), before));
            // Both panels would be empty for an entity the game cannot make: that is a failure, not parity. A host may
            // make it a few frames on (1.7.10), so none is stated only after MAKE_TRIES
            int tries = slotTries.merge(c.name(), 1, Integer::sum);
            if ((e != null || tries >= MAKE_TRIES) && slotMade.add(c.name()) && active != null) {
                active.stated("pictures.made." + c.name(), e != null, e == null
                        ? "the game made no entity from it in " + tries + " frames" : "made");
            }
            if (e != null) host.draw(ctx, e, pose(c), c.count(), x, y, r[2], r[3]);
        } else if (c.kind() == Kind.BLOCK) {
            CgBlockPictures.Host host = CgPlatform.get(CgBlockPictures.HOST);
            Object b = REFERENCE_BLOCKS.compute(c, (k, before) -> host.prepare(CgBlockState.parse(k.item()), before));
            host.draw(ctx, b, x, y, r[2]);
        }
    }

    /** A cell's icon: its item, count, durability and, glinted, sharpness by the era's own name for it. */
    private static CgItemIcon icon(Cell c) {
        return ICONS.computeIfAbsent(c, k -> CgItemIcons.of(stackOf(k)));
    }

    /**
     * A cell's stack, the same for the reference and the picture: its item, count, durability, glinted with sharpness by
     * the era's own name for it, a bundle holding three diamonds and five apples.
     */
    public static CgItemStack stackOf(Cell c) {
        CgItemStack s = CgItemStack.of(c.item(), Math.max(1, c.count()));
        if (c.damage() > 0) s = s.withDamage(c.damage());
        if (c.glint()) s = s.withEnchantment(CgItems.shape() == CgItems.Shape.LEGACY ? "16" : "minecraft:sharpness", 1);
        if (c.item().equals("minecraft:bundle")) {
            s = s.with("minecraft:bundle_contents", CgNbtList.of(
                    new CgNbtCompound().put("id", "minecraft:diamond").put("count", 3),
                    new CgNbtCompound().put("id", "minecraft:apple").put("count", 5)));
        }
        return s;
    }

    // The icon atlas's draws, a frame at a time, once the cost grid has settled
    private static void countIcons() {
        CgItemIcons.Stats st = CgItemIcons.stats();
        if (st.frame() == iconFrame) return;
        iconFrame = st.frame();
        Phase p = phase;
        if (p != Phase.COST || costSide != 0 || costRun < 0 || RUNS.get(costRun).kind() != Kind.ITEM
                || costFrames < ICONS_SETTLE) return;
        long now = System.nanoTime();
        if (iconFrames == 0) iconFirstNanos = now;
        iconLastNanos = now;
        iconFrames++;
        iconFirstDraws += st.firstDraws();
        iconRedraws += st.redraws();
        if (st.redraws() > 0) iconFramesRedrawn++;
    }

    private static boolean drawing() {
        Phase p = phase;
        return parity() || p == Phase.TIPS || p == Phase.TIPS_SHOT || p == Phase.COST;
    }

    // In the cost phase each side draws alone, and the empty frame neither
    private static boolean referenceShown() {
        return phase != Phase.COST || costRun >= 0 && costSide == 1;
    }

    private static boolean picturesShown() {
        return phase != Phase.COST || costRun >= 0 && costSide == 0;
    }

    // Each frame's interval, once the side has settled: what the whole frame cost, CPU and GPU, uncapped
    private static void timeFrame() {
        long now = System.nanoTime();
        if (frameLast != 0L && costFrames >= ICONS_SETTLE) {
            frameSum += now - frameLast;
            frameCount++;
        }
        frameLast = now;
    }

    /** The parity panels are drawn: while warming, and until the shot has been read, its frame among them. */
    private static boolean parity() {
        return phase == Phase.PARITY || phase == Phase.SHOT;
    }

    // The parity panels' size, the tooltip ones' too; the cost grid's otherwise
    private static boolean parityPanels() {
        return parity() || phase == Phase.TIPS || phase == Phase.TIPS_SHOT;
    }

    private static int panelW() {
        return parityPanels() ? W : GRID * 18 + 4;
    }

    private static int panelH() {
        return parityPanels() ? H : GRID * 18 + 4;
    }

    // ── the test ─────────────────────────────────────────────────────────────────

    private static final class Test extends CgGauntletController {
        private CgRenderStage.Registration hud;

        @Override
        protected void onInit() {
            phase = Phase.WAITING;
            ticks = 0;
            retargetNoted = false;
            slotThrown.clear();
            slotMade.clear();
            slotTries.clear();
            refused = null;
            pictureAsked = referenceDrew = false;
            pictureRead = "never asked";
            pictureDrawn = 0;
            active = this;
            hud = CgRenderStage.HUD.register(Integer.MAX_VALUE - 1, frame -> {
                scale = frame.scale();
                if (phase == Phase.COST) timeFrame();
                if (!drawing() || !picturesShown()) return;
                CgHostPictures.Stats stats = CgHostPictures.stats();
                if (stats.frame() != statsFrame) {
                    statsFrame = stats.frame();
                    pictureNanos += stats.nanos();
                    pictureFrames++;
                }
                if (!composite) return;
                CgCanvas canvas = frame.canvas();
                float s = frame.scale();
                canvas.save().scale(s, s);
                int unready = 0;
                // The probe's own picture: its read waits for it below, so the panel's count leaves it out
                canvas.picture(PICTURE, PIC_X, PIC_Y, panelW(), panelH());
                // Items as their icons and overlays, drawn by Minecraft into the atlas; tooltips as Minecraft draws them,
                // its box where a screen puts it for a pointer at the cell (8 right, 16 up)
                for (Cell c : cells) {
                    if (iconsHere() && (c.kind() == Kind.ITEM || c.kind() == Kind.DECORATED)) {
                        CgItemIcon icon = icon(c);
                        unready += show(canvas, icon, PIC_X + c.x(), PIC_Y + c.y(), 16, 16);
                        // An overlay draws where there is something on it, a count or a bar; one with nothing never does
                        if (c.kind() == Kind.DECORATED) {
                            int notDrawn = show(canvas, icon.overlay(), PIC_X + c.x(), PIC_Y + c.y(), 16, 16);
                            if (c.count() != 1 || c.damage() != 0) unready += notDrawn;
                        }
                    } else if (tooltipsHere() && c.kind() == Kind.TOOLTIP) {
                        unready += show(canvas, CgTooltipPicture.of(stackOf(c)), PIC_X + c.x() + 8, PIC_Y + c.y() - 16);
                    } else if (c.kind() == Kind.ENTITY) {
                        int[] r = c.rect();
                        unready += show(canvas, entityPicture(c), PIC_X + r[0], PIC_Y + r[1], r[2], r[3]);
                    } else if (c.kind() == Kind.BLOCK) {
                        int notDrawn = show(canvas, blockPicture(c), PIC_X + c.x(), PIC_Y + c.y(), 16, 16);
                        if (CgPlatform.get(CgBlockPictures.HOST).absent() == null) unready += notDrawn;
                    }
                }
                unreadyPictures = unready;
                if (parity()) canvas.picture(FAILING, FAIL_X, FAIL_Y, FAIL_SIZE, FAIL_SIZE);
                canvas.restore();
                countIcons();
                if (phase == Phase.PARITY && refused == null) refused = refusal();
                if (phase == Phase.PARITY && !pictureAsked && ticks > WARM / 2 && PICTURE.version() > 0) {
                    pictureAsked = true;
                    readPicture(s);
                }
            });
            install();
        }

        @Override
        protected void onStateChange(CgGauntletState from, CgGauntletState to) {
            if (to.equals(CgGauntletState.TITLE)) enterWorld();
        }

        @Override
        protected int timeoutSeconds() {
            return 600;
        }

        @Override
        protected void onEnd() {
            active = null;
            phase = Phase.DONE;
            composite = false;
            if (hud != null) hud.close();
            hud = null;
        }

        boolean inWorld() {
            return state().equals(CgGauntletState.IN_WORLD);
        }

        void stated(String name, boolean ok, String detail) {
            check(name, ok, detail);
        }

        void skipped(String name, String reason) {
            skip(name, reason);
        }

        void noted(String name, String detail) {
            info(name, detail);
        }

        CgGauntletCapture.Shot shoot(String name) {
            return capture(name);
        }

        void done() {
            endTest();
        }
    }

    /** From inside the HUD's device section: a draw point there must be refused. What happened. */
    private static String refusal() {
        try {
            CgHostPictures.drawPoint();
            return "not refused";
        } catch (IllegalStateException e) {
            return e.getMessage().startsWith("CgHostPictures.drawPoint() inside a device section") ? "refused" : "threw " + e;
        }
    }

    private static synchronized void install() {
        if (installed) return;
        installed = true;
        CgEngineLifecycle.onClientTick(CgPicturesProbe::tick);
    }

    private static void tick() {
        Test test = active;
        if (test == null) return;
        ticks++;
        // A focus lost at any time would pause the game over the panels
        if (phase != Phase.WAITING) {
            CgPlatform.get(CgWorldStimulus.SERVICE).keepRunning();
            CgPlatform.get(Host.SERVICE).cooldown(COOLING);
        }
        switch (phase) {
            case WAITING -> {
                if (!test.inWorld()) return;
                Host host = CgPlatform.get(Host.SERVICE);
                if (host.absent() != null) {
                    test.skipped("everything", host.absent());
                    finish(test);
                    return;
                }
                String noDraw = CgPlatform.get(CgHostPictures.HOST).absent();
                if (noDraw != null) {
                    test.skipped("everything", noDraw);
                    finish(test);
                    return;
                }
                CgPlatform.get(CgWorldStimulus.SERVICE).keepRunning();
                next(Phase.SETTLING);
            }
            case SETTLING -> {
                if (ticks < SETTLE) return;
                rounds = rounds(parityCells(test, CgPlatform.get(Host.SERVICE)));
                round = 0;
                cells = rounds.get(0);
                composite = true;
                next(Phase.PARITY);
            }
            case PARITY -> {
                if (!ready(test, "parity")) return;
                shot = test.shoot("parity");
                next(Phase.SHOT);
            }
            case SHOT -> {
                if (!shot.done()) return;
                if (round == 0) compare(test);
                else if (referenceDrew) {
                    BufferedImage image = read(test, "pictures.parity.round" + round + ".panel");
                    if (image != null) parity(test, image, scale, "round" + round + ".");
                }
                if (++round < rounds.size()) {
                    cells = rounds.get(round);
                    next(Phase.PARITY);
                    return;
                }
                cells = tipCells(test, CgPlatform.get(Host.SERVICE));
                next(Phase.TIPS);
            }
            case TIPS -> {
                if (!ready(test, "parity-tooltips")) return;
                shot = test.shoot("parity-tooltips");
                next(Phase.TIPS_SHOT);
            }
            case TIPS_SHOT -> {
                if (!shot.done()) return;
                if (!referenceDrew) {
                    test.skipped("pictures.parity.tooltips.panel", "no reference panel on this node");
                } else if (cells.isEmpty()) {
                    test.skipped("pictures.parity.tooltips.panel", "no tooltips drawn by this node's probe host");
                } else {
                    BufferedImage image = read(test, "pictures.parity.tooltips.panel");
                    if (image != null) parity(test, image, scale, "tooltips.");
                }
                costRun = -1;
                costSide = 0;
                startCost();
                next(Phase.COST);
            }
            case COST -> {
                if (++costFrames < COST) return;
                double frameMs = frameSum / 1e6 / Math.max(1, frameCount);
                if (costRun < 0) {
                    emptyFrameMs = frameMs;
                } else if (costSide == 0) {
                    oursFrameMs = frameMs;
                    if (RUNS.get(costRun).kind() == Kind.ITEM) iconChecks(test, RUNS.get(costRun).glint());
                } else {
                    cost(test, RUNS.get(costRun), frameMs);
                }
                // The empty frame, then each run's sides: the engine's, then vanilla's
                if (costRun >= 0 && costSide == 0) costSide = 1;
                else {
                    costRun++;
                    while (costRun < RUNS.size() && !ONLY.isEmpty()
                            && !List.of(ONLY.split(",")).contains(RUNS.get(costRun).name())) costRun++;
                    costSide = 0;
                }
                if (costRun >= RUNS.size()) {
                    finish(test);
                    return;
                }
                startCost();
                next(Phase.COST);
            }
            default -> { }
        }
    }

    /** Vanilla's items, a glint, a clock and a compass, block-entity items, decorations, the player, a tooltip. */
    /**
     * The parity cells in rounds, each its own shot: the rest with the first entity, then one entity a round. From 1.21.6 a
     * GUI frame draws its entities through one picture-in-picture renderer per kind of state, so a reference of several
     * shows the last prepared in every slot.
     */
    private static List<List<Cell>> rounds(List<Cell> all) {
        List<List<Cell>> out = new ArrayList<>();
        List<Cell> first = new ArrayList<>();
        out.add(first);
        boolean entityTaken = false;
        for (Cell c : all) {
            boolean entity = c.kind() == Kind.PLAYER || c.kind() == Kind.ENTITY;
            if (!entity) first.add(c);
            else if (!entityTaken) {
                first.add(c);
                entityTaken = true;
            } else {
                out.add(List.of(c));
            }
        }
        return out;
    }

    private static List<Cell> parityCells(Test test, Host host) {
        List<String> items = new ArrayList<>();
        String wanted = VANILLA + "," + System.getProperty("crystalgraphics.pictures.items", "");
        // '+' as well: prodSmoke's property list is comma-separated itself
        for (String id : wanted.split("[,+]")) {
            if (id.isBlank()) continue;
            if (host.knows(id.trim())) items.add(id.trim());
            else test.noted("pictures.absent." + id.trim(), "not an item here");
        }
        known = items;
        List<Cell> out = cellsOf(items);
        for (Kind kind : Kind.values()) {
            if (!host.draws(kind)) test.noted("pictures.kind." + kind.name().toLowerCase(Locale.ROOT), "not drawn by this node's probe host");
        }
        String entities = CgPlatform.get(CgEntityPictures.HOST).absent(), blocks = CgPlatform.get(CgBlockPictures.HOST).absent();
        if (entities != null) test.noted("pictures.kind.entity", entities);
        if (blocks != null) test.noted("pictures.kind.block", blocks);
        String e = entities, b = blocks;
        out.removeIf(c -> !host.draws(c.kind()) || c.kind() == Kind.ENTITY && e != null || c.kind() == Kind.BLOCK && b != null);
        return out;
    }

    /** The parity panel's cells over {@code items}: a row of them, then the fixed cells. */
    public static List<Cell> cellsOf(List<String> items) {
        List<Cell> out = new ArrayList<>();
        int x = 4;
        for (String id : items) {
            if (x + 18 > ROW_W) break;
            out.add(new Cell(Kind.ITEM, id, x, 4, 1, 0, false));
            x += 18;
        }
        out.add(new Cell(Kind.ITEM, "minecraft:diamond_sword", 4, 24, 1, 0, true));
        out.add(new Cell(Kind.ITEM, "minecraft:book", 22, 24, 1, 0, true));
        out.add(new Cell(Kind.DECORATED, "minecraft:cobblestone", 40, 24, 37, 0, false));
        out.add(new Cell(Kind.DECORATED, "minecraft:diamond_pickaxe", 58, 24, 1, 900, false));
        // Vanilla's cooldown sweep, and a loader decorator's mark (Forge, NeoForge)
        out.add(new Cell(Kind.DECORATED, COOLING, 76, 24, 1, 0, false));
        out.add(new Cell(Kind.DECORATED, DECORATED_BY_PROBE, 94, 24, 1, 0, false));
        out.add(new Cell(Kind.PLAYER, "", 170, 84, 30, 0, false));
        // A short tooltip, whole inside the panel: one running past it shows through the other panel
        out.add(new Cell(Kind.TOOLTIP, "minecraft:book", 4, 60, 1, 0, true));
        // Beside the PLAYER cell: the same player as an entity picture, a mob from its data (a mod's, given
        // crystalgraphics.pictures.entity), small enough that a mod's model reaching past its box stays in the cell (the
        // reference is not clipped; the picture is), a chest with its block entity
        out.add(new Cell(Kind.ENTITY, "player", 200, 18, 30, 0, false));
        out.add(new Cell(Kind.ENTITY, System.getProperty("crystalgraphics.pictures.entity", "minecraft:sheep{Color:2b}"), 256, 18,
                14, 0, false));
        out.add(new Cell(Kind.BLOCK, "minecraft:chest[facing=south]", 312, 24, 1, 0, false));
        return out;
    }

    /** The tooltip panels: a book's, and a bundle's where bundles exist and the host fills one. */
    private static List<Cell> tipCells(Test test, Host host) {
        List<Cell> out = new ArrayList<>();
        if (!host.draws(Kind.TOOLTIP)) return out;
        out.add(new Cell(Kind.TOOLTIP, "minecraft:book", 4, 20, 1, 0, true));
        // A bundle's contents are a component: from 1.20.5, and shown from 1.21.2 where bundles are on by default
        if (CgItems.shape() == CgItems.Shape.COMPONENTS && host.knows("minecraft:bundle")) {
            // Its tooltip is 104 units across: whole inside the panel from here
            out.add(new Cell(Kind.TOOLTIP, "minecraft:bundle", 84, 20, 1, 0, false));
        } else {
            test.noted("pictures.absent.minecraft:bundle", "no bundle with contents here");
        }
        return out;
    }

    /** The picture's own texture, read back: how many of its pixels Minecraft drew on. */
    private static void readPicture(float s) {
        int w = Math.round(W * s), h = Math.round(H * s);
        CgPicture.Image image = PICTURE.image(w, h, s);
        if (image == null || image.texture() == null) {
            pictureRead = "no picture yet at " + w + " x " + h;
            return;
        }
        CgReadback.texture(image.texture(), 0, 0, 0, w, h, new CgReadback.Sink() {
            @Override
            public void accept(ByteBuffer px) {
                int drawn = 0;
                for (int i = 0; i + 3 < px.limit() && i < w * h * 4; i += 4) {
                    if ((px.get(i) | px.get(i + 1) | px.get(i + 2) | px.get(i + 3)) != 0) drawn++;
                }
                pictureRead = drawn + " of " + w * h;
                pictureDrawn = drawn;
            }

            @Override
            public void failed(String reason) {
                pictureRead = "never read: " + reason;
            }
        });
    }

    /** The engine side's frame, kept until vanilla's is measured beside it. */
    private static double oursFrameMs;

    private static void startCost() {
        frameSum = frameLast = 0L;
        frameCount = 0;
        pictureNanos = referenceNanos = 0;
        costFrames = pictureFrames = referenceFrames = 0;
        cells = costRun < 0 ? List.of() : costCells(RUNS.get(costRun));
        if (costRun >= 0 && costSide == 0 && RUNS.get(costRun).kind() == Kind.ITEM) startIconCount();
    }

    // A run's grid inside the cost panel, GRID * 18 + 4 units square
    private static List<Cell> costCells(CostRun run) {
        if (run.kind() == Kind.ITEM) return iconGrid(run.glint());
        List<Cell> out = new ArrayList<>();
        int w, h;
        switch (run.kind()) {
            case ENTITY -> {
                w = Math.round(run.scale() * 52f / 30f) + 2;
                h = Math.round(run.scale() * 70f / 30f) + 2;
            }
            case TOOLTIP -> {
                w = 90;
                h = 28;
            }
            default -> {
                w = 18;
                h = 18;
            }
        }
        int across = Math.max(1, (GRID * 18) / w);
        for (int i = 0; i < run.count(); i++) {
            int x = 2 + w * (i % across), y = 2 + h * (i / across) + (run.kind() == Kind.TOOLTIP ? 16 : 0);
            String what = run.what().get(i % run.what().size());
            out.add(new Cell(run.kind(), what, x, y, run.kind() == Kind.ENTITY ? run.scale() : 1, 0, run.glint()));
        }
        return out;
    }

    private static void startIconCount() {
        iconFrames = iconFramesRedrawn = 0;
        iconFirstNanos = iconLastNanos = 0L;
        iconFirstDraws = iconRedraws = 0;
    }

    private static List<Cell> iconGrid(boolean glint) {
        // A clock's or compass's hand is a new look each time it moves, drawn once each: not a still grid's
        List<String> items = new ArrayList<>(known);
        items.removeIf(id -> id.equals("minecraft:clock") || id.equals("minecraft:compass"));
        if (items.isEmpty()) items = known;
        List<Cell> grid = new ArrayList<>();
        for (int i = 0; i < GRID * GRID; i++) {
            grid.add(new Cell(Kind.ITEM, items.get(i % items.size()), 2 + 18 * (i % GRID), 2 + 18 * (i / GRID), 1, 0, glint));
        }
        return grid;
    }

    // Vanilla's side just measured; the engine's kept from before it
    private static void cost(Test test, CostRun run, double vanillaFrameMs) {
        test.noted("pictures.cost." + run.name(), String.format(Locale.ROOT, "ours %.3f ms a frame, vanilla %.3f ms, the "
                        + "empty frame %.3f ms; vanilla's own draw %.3f ms (%d frames, %d %s)", oursFrameMs, vanillaFrameMs,
                emptyFrameMs, referenceNanos / 1e6 / Math.max(1, referenceFrames), frameCount, run.count(),
                run.kind().name().toLowerCase(Locale.ROOT)));
    }

    /**
     * A still grid of icons draws nothing once its cells are drawn; a glinted one draws its looks again every frame, as
     * vanilla redraws a glint.
     */
    private static void iconChecks(Test test, boolean glint) {
        if (!iconsHere()) {
            test.skipped(glint ? "icons.glint" : "icons.static", CgPlatform.get(CgItemIcons.HOST).absent());
            return;
        }
        String counted = iconFrames + " frames after " + ICONS_SETTLE + ": " + iconFirstDraws + " first draws, "
                + iconRedraws + " redraws";
        if (!glint) {
            // A still grid draws nothing but what animates in it: a custom renderer is drawn each frame, as vanilla draws it
            Set<String> animating = new TreeSet<>();
            for (Cell c : cells) {
                CgItemIcon icon = ICONS.get(c);
                if (icon != null && icon.animated()) animating.add(c.item() + " (" + icon.why() + ")");
            }
            // Each animating look at the pace; none redrawn where none animates
            boolean ok = iconFrames > 0 && iconFirstDraws == 0
                    && (animating.isEmpty() ? iconRedraws == 0 : iconRedraws <= pacedMost() * animating.size());
            test.stated("icons.static", ok, counted + "; " + animating.size() + " looks animate"
                    + (animating.isEmpty() ? "" : ": " + animating));
            return;
        }
        test.stated("icons.glint", iconFrames > 0 && iconFramesRedrawn >= pacedLeast() && iconFramesRedrawn <= pacedMost(),
                counted + ", redrawn in " + iconFramesRedrawn + String.format(Locale.ROOT, " over %.2f s at %s",
                        (iconLastNanos - iconFirstNanos) / 1e9, CgItemIcons.ANIMATED_HZ.get() + " Hz"));
    }

    // Draws the counted frames allow an animated look at the pace: every frame where it is 0, else one a period, and one more
    private static long pacedMost() {
        float hz = CgItemIcons.ANIMATED_HZ.get();
        if (hz <= 0f) return iconFrames;
        return Math.min(iconFrames, (long) Math.ceil(hz * (iconLastNanos - iconFirstNanos) / 1e9) + 1);
    }

    // The fewest: most of the periods counted, a frame slower than the pace drawing once
    private static long pacedLeast() {
        float hz = CgItemIcons.ANIMATED_HZ.get();
        double due = hz <= 0f ? iconFrames : Math.min(iconFrames, hz * (iconLastNanos - iconFirstNanos) / 1e9);
        return (long) (due * 0.6);
    }

    // ── what is measured ─────────────────────────────────────────────────────────

    /** The capture: the picture's panel against the reference's, per cell and whole; the failing picture; the refusal. */
    /** The tooltip panels' capture; null, stated failed, where it was not written or cannot be read. */
    @Nullable
    private static BufferedImage read(Test test, String check) {
        if (!shot.written()) {
            test.stated(check, false, "the capture was never written: " + shot.failure());
            return null;
        }
        try {
            return ImageIO.read(shot.file().toFile());
        } catch (IOException e) {
            test.stated(check, false, "the capture could not be read: " + e);
            return null;
        }
    }

    private static void compare(Test test) {
        if (!shot.written()) {
            test.stated("pictures.parity", false, "the capture was never written: " + shot.failure());
            return;
        }
        BufferedImage image;
        try {
            image = ImageIO.read(shot.file().toFile());
        } catch (IOException e) {
            test.stated("pictures.parity", false, "the capture could not be read: " + e);
            return;
        }
        float s = scale;
        if (hostDrawn(cells).isEmpty()) {
            test.skipped("pictures.picture", "every cell is an icon, overlay or tooltip picture here; their parity proves the draw");
        } else {
            test.stated("pictures.picture", pictureDrawn > 0, "pixels Minecraft drew on in the picture's own texture: " + pictureRead);
        }
        int magenta = magenta(image, s);
        test.stated("pictures.failure", FAILING.state() == CgHostPicture.State.FAILED && magenta > 0,
                "a renderer that throws: " + FAILING.state() + ", " + magenta + " magenta pixels where it shows");
        if (CgModLoader.development()) {
            test.stated("pictures.refused", "refused".equals(refused), "a draw point inside a device section: " + refused);
        } else {
            test.skipped("pictures.refused", "a dev run refuses it; elsewhere it logs once");
        }
        if (!referenceDrew) {
            test.skipped("pictures.parity", "no reference panel: this node's HUD hook gives no GUI object, or the node no fill");
            return;
        }
        parity(test, image, s, "");
    }

    /** Each cell's panel against the reference's, then the whole: checks named {@code pictures.parity.<prefix>...}. */
    private static void parity(Test test, BufferedImage image, float s, String prefix) {
        List<int[]> slack = new ArrayList<>();
        for (Cell cell : cells) {
            int[] r = cell.rect();
            int[] own = {r[0], r[1], r[2], r[3], cell.slack()};
            slack.add(own);
            int[] d = diff(image, r[0], r[1], r[2], r[3], s, false, List.of(own));
            test.stated("pictures.parity." + prefix + cell.name(), d[0] == 0, d[0] + " of " + d[2] + " pixels differ by more than "
                    + cell.slack() + "; max " + d[1] + (d[1] > 1 && d[0] == 0 ? " (" + cell.slackReason() + ")" : ""));
        }
        int[] whole = diff(image, 0, 0, W, H, s, false, slack);
        test.stated(prefix.isEmpty() ? "pictures.parity" : "pictures.parity." + prefix + "panel", whole[0] == 0,
                "the whole panel: " + whole[0] + " of " + whole[2]
                + " pixels differ by more than their cell allows (1; " + GLINT_SLACK + " glinted; " + LAYER_SLACK
                + " under a tooltip's layers); max " + whole[1] + "; scale " + s);
        if (!prefix.isEmpty()) return;
        int[] blank = diff(image, 0, 0, W, H, s, true, List.of());
        test.stated("pictures.drawn", blank[0] > 0, blank[0] + " of the reference panel's pixels are not the background");
    }

    /** Pixels of the missing texture's magenta where the failing picture shows. */
    private static int magenta(BufferedImage image, float s) {
        int n = 0;
        for (int y = Math.round(FAIL_Y * s); y < Math.round((FAIL_Y + FAIL_SIZE) * s) && y < image.getHeight(); y++) {
            for (int x = Math.round(FAIL_X * s); x < Math.round((FAIL_X + FAIL_SIZE) * s) && x < image.getWidth(); x++) {
                int p = image.getRGB(x, y);
                if ((p >> 16 & 255) > 230 && (p >> 8 & 255) < 20 && (p & 255) > 230) n++;
            }
        }
        return n;
    }

    /**
     * {pixels differing by more than allowed, the largest difference, pixels compared} of a rect in GUI units: 1 allowed,
     * or the most of {@code slack}'s rects ({x, y, w, h, allowed}) a pixel lies in.
     */
    private static int[] diff(BufferedImage image, int gx, int gy, int gw, int gh, float s, boolean againstBackground,
                              List<int[]> slack) {
        int x0 = Math.round(Math.max(0, gx) * s), y0 = Math.round(Math.max(0, gy) * s);
        int x1 = Math.round(Math.min(W, gx + gw) * s), y1 = Math.round(Math.min(H, gy + gh) * s);
        int over = 0, max = 0, n = 0;
        // Vanilla's crosshair, 15 units square at the screen's centre, falls on a panel in a small window: not compared
        int cx = image.getWidth() / 2, cy = image.getHeight() / 2, ch = Math.round(8 * s);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rx = Math.round(REF_X * s) + x, ry = Math.round(REF_Y * s) + y;
                int px = Math.round(PIC_X * s) + x, py = Math.round(PIC_Y * s) + y;
                if (rx >= image.getWidth() || py >= image.getHeight()) continue;
                if (Math.abs(rx - cx) <= ch && Math.abs(ry - cy) <= ch || Math.abs(px - cx) <= ch && Math.abs(py - cy) <= ch) {
                    continue;
                }
                int a = image.getRGB(rx, ry), b = againstBackground ? BACKGROUND : image.getRGB(px, py);
                int d = Math.max(Math.abs((a >> 16 & 255) - (b >> 16 & 255)),
                        Math.max(Math.abs((a >> 8 & 255) - (b >> 8 & 255)), Math.abs((a & 255) - (b & 255))));
                n++;
                if (d > allowed(slack, x / s, y / s)) over++;
                max = Math.max(max, d);
            }
        }
        return new int[]{over, max, n};
    }

    private static int allowed(List<int[]> rects, float gx, float gy) {
        int most = 1;
        for (int[] r : rects) {
            if (gx >= r[0] && gx < r[0] + r[2] && gy >= r[1] && gy < r[1] + r[3]) most = Math.max(most, r[4]);
        }
        return most;
    }

    // A picture shown at x, y in w by h units (h 0: its own size, a tooltip's); 1 where it has not drawn yet
    private static int show(CgCanvas canvas, CgPicture picture, float x, float y, float w, float h) {
        canvas.picture(picture, x, y, w, h);
        return picture.version() == 0 ? 1 : 0;
    }

    private static int show(CgCanvas canvas, CgPicture picture, float x, float y) {
        canvas.picture(picture, x, y);
        return picture.version() == 0 ? 1 : 0;
    }

    // Warmed, and every picture of the panel drawn; past HOLD ticks it is shot as it is, the wait noted
    private static boolean ready(Test test, String shot) {
        if (ticks < WARM) return false;
        int unready = unreadyPictures;
        // The parity shot also waits for the picture's own read, asked once it has drawn
        boolean waiting = unready != 0 || "parity".equals(shot) && !pictureAsked;
        if (waiting && ticks < WARM + HOLD) return false;
        if (unready != 0) test.noted("pictures.wait." + shot, unready + " pictures still undrawn after " + ticks + " ticks");
        return true;
    }

    private static void next(Phase p) {
        phase = p;
        ticks = 0;
        unreadyPictures = -1;
        paced(p != Phase.PARITY && p != Phase.SHOT && p != Phase.TIPS && p != Phase.TIPS_SHOT);
    }

    // Pixels are compared against vanilla drawn this frame: a glint drawn at the pace may be a period old
    private static void paced(boolean on) {
        if (!on && pacedHz < 0f) {
            pacedHz = CgItemIcons.ANIMATED_HZ.get();
            CgItemIcons.ANIMATED_HZ.set(0f);
        } else if (on && pacedHz >= 0f) {
            CgItemIcons.ANIMATED_HZ.set(pacedHz);
            pacedHz = -1f;
        }
    }

    private static void finish(Test test) {
        paced(true);
        composite = false;
        phase = Phase.DONE;
        test.done();
    }
}
