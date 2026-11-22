package com.crystalgraphics.probe;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.canvas.CgPicture;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.gauntlet.CgGauntletCapture;
import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.mc.render.CgHostPicture;
import com.crystalgraphics.mc.render.CgHostPictures;
import com.crystalgraphics.mc.render.CgPictureRefresh;
import com.crystalgraphics.mc.render.custom.CgCustomPicture;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.custom.CgPictureRenderer;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.platform.service.CgModLoader;
import com.crystalgraphics.platform.world.CgWorldStimulus;
import com.crystalgraphics.render.stage.CgRenderStage;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
                case TOOLTIP -> new int[]{x, y - 16, W - x, 40};
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
            };
        }
    }

    /**
     * {@code ITEM}: {@code GuiGraphics.renderItem}'s; {@code DECORATED}: that and its count, bar and cooldown;
     * {@code PLAYER}: the local player, as the inventory draws it, {@code count} its scale, the pointer {@value #LOOK_X},
     * {@value #LOOK_Y} GUI units from it; {@code TOOLTIP}: the item's tooltip, the pointer at its cell.
     */
    public enum Kind { ITEM, DECORATED, PLAYER, TOOLTIP }

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
    }

    /** The panels, in GUI units: the reference at the left, the picture beside it, below it. */
    public static final int W = 200, H = 90, REF_X = 4, REF_Y = 4, PIC_X = 4, PIC_Y = REF_Y + H + 4;
    /** Where the failing picture shows, in GUI units: right of the panels. */
    private static final int FAIL_X = W + 12, FAIL_Y = REF_Y, FAIL_SIZE = 32;
    static final int BACKGROUND = 0xFF3A5F7F;
    /** The pointer's offset from the player, as the inventory's: where it looks. */
    public static final int LOOK_X = 20, LOOK_Y = -30;
    private static final int GRID = 10;
    /**
     * The most a glinted pixel may differ: the glint scrolls with the millisecond clock, and the picture draws up to a
     * frame after the reference under load (4 measured with three clients at once; a wrong draw is dozens).
     */
    private static final int GLINT_SLACK = 8;
    /**
     * The most a pixel under stacked translucent layers may differ: the picture blends them over transparent in an 8-bit
     * target and is composited after, a rounding a layer more than vanilla's straight blend (a tooltip's fill and border).
     */
    private static final int LAYER_SLACK = 2;
    /** Client ticks: in the world before drawing; parity frames before the shot; frames a cost is measured over. */
    private static final int SETTLE = 60, WARM = 20, COST = 60;
    private static final String VANILLA = "minecraft:diamond_sword,minecraft:clock,minecraft:compass,minecraft:chest,"
            + "minecraft:shield,minecraft:carved_pumpkin,minecraft:oak_sapling";

    private enum Phase { WAITING, SETTLING, PARITY, SHOT, COST_STATIC, COST_GLINT, DONE }

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
    private static int costFrames;
    private static boolean retargetNoted, pictureAsked, referenceDrew;
    private static volatile String pictureRead = "never asked";
    private static volatile int pictureDrawn;
    @Nullable private static volatile String refused;
    private static CgGauntletCapture.Shot shot;

    /** What the draw point draws: the cells, with Minecraft's code, every frame. */
    static final CgCustomPicture PICTURE = CgCustomPicture.of("crystalgraphics:pictures_probe")
            .refresh(CgPictureRefresh.REALTIME)
            .renderer(ctx -> {
                picture.checkTarget = parity();
                CgPlatform.get(Host.SERVICE).cells(ctx, cells, picture);
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

    /** Host side: its HUD hook, with the hook's GUI object (null where it makes its own). Render thread. */
    public static void hud(@Nullable Object gui) {
        if (active == null || !drawing()) return;
        Host host = CgPlatform.get(Host.SERVICE);
        reference.clear();
        host.reference(gui, cells, REF_X, REF_Y, panelW(), panelH(), PIC_X, PIC_Y, BACKGROUND, reference);
        referenceNanos += reference.nanos;
        if (reference.drew) referenceDrew = true;
    }

    private static boolean drawing() {
        Phase p = phase;
        return parity() || p == Phase.COST_STATIC || p == Phase.COST_GLINT;
    }

    /** The parity panels are drawn: while warming, and until the shot has been read, its frame among them. */
    private static boolean parity() {
        return phase == Phase.PARITY || phase == Phase.SHOT;
    }

    private static int panelW() {
        return parity() ? W : GRID * 18 + 4;
    }

    private static int panelH() {
        return parity() ? H : GRID * 18 + 4;
    }

    // ── the test ─────────────────────────────────────────────────────────────────

    private static final class Test extends CgGauntletController {
        private CgRenderStage.Registration hud;

        @Override
        protected void onInit() {
            phase = Phase.WAITING;
            ticks = 0;
            retargetNoted = false;
            refused = null;
            pictureAsked = referenceDrew = false;
            pictureRead = "never asked";
            pictureDrawn = 0;
            active = this;
            hud = CgRenderStage.HUD.register(Integer.MAX_VALUE - 1, frame -> {
                scale = frame.scale();
                if (!drawing()) return;
                CgHostPictures.Stats stats = CgHostPictures.stats();
                if (stats.frame() != statsFrame) {
                    statsFrame = stats.frame();
                    pictureNanos += stats.nanos();
                }
                if (!composite) return;
                CgCanvas canvas = frame.canvas();
                float s = frame.scale();
                canvas.save().scale(s, s);
                canvas.picture(PICTURE, PIC_X, PIC_Y, panelW(), panelH());
                if (parity()) canvas.picture(FAILING, FAIL_X, FAIL_Y, FAIL_SIZE, FAIL_SIZE);
                canvas.restore();
                if (phase == Phase.PARITY && refused == null) refused = refusal();
                if (phase == Phase.PARITY && !pictureAsked && ticks > WARM / 2) {
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
                cells = parityCells(test, CgPlatform.get(Host.SERVICE));
                composite = true;
                next(Phase.PARITY);
            }
            case PARITY -> {
                if (ticks < WARM) return;
                shot = test.shoot("parity");
                next(Phase.SHOT);
            }
            case SHOT -> {
                if (!shot.done()) return;
                compare(test);
                startCost(false);
                next(Phase.COST_STATIC);
            }
            case COST_STATIC -> {
                if (++costFrames < COST) return;
                cost(test, "static100");
                startCost(true);
                next(Phase.COST_GLINT);
            }
            case COST_GLINT -> {
                if (++costFrames < COST) return;
                cost(test, "glinted100");
                finish(test);
            }
            default -> { }
        }
    }

    /** Vanilla's items, a glint, a clock and a compass, block-entity items, decorations, the player, a tooltip. */
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
        out.removeIf(c -> !host.draws(c.kind()));
        return out;
    }

    /** The parity panel's cells over {@code items}: a row of them, then the fixed cells. */
    public static List<Cell> cellsOf(List<String> items) {
        List<Cell> out = new ArrayList<>();
        int x = 4;
        for (String id : items) {
            if (x + 18 > W) break;
            out.add(new Cell(Kind.ITEM, id, x, 4, 1, 0, false));
            x += 18;
        }
        out.add(new Cell(Kind.ITEM, "minecraft:diamond_sword", 4, 24, 1, 0, true));
        out.add(new Cell(Kind.ITEM, "minecraft:book", 22, 24, 1, 0, true));
        out.add(new Cell(Kind.DECORATED, "minecraft:cobblestone", 40, 24, 37, 0, false));
        out.add(new Cell(Kind.DECORATED, "minecraft:diamond_pickaxe", 58, 24, 1, 900, false));
        out.add(new Cell(Kind.PLAYER, "", 170, 84, 30, 0, false));
        // A short tooltip, whole inside the panel: one running past it shows through the other panel
        out.add(new Cell(Kind.TOOLTIP, "minecraft:book", 4, 60, 1, 0, true));
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

    private static void startCost(boolean glint) {
        List<Cell> grid = new ArrayList<>();
        for (int i = 0; i < GRID * GRID; i++) {
            grid.add(new Cell(Kind.ITEM, known.get(i % known.size()), 2 + 18 * (i % GRID), 2 + 18 * (i / GRID), 1, 0, glint));
        }
        cells = grid;
        pictureNanos = referenceNanos = 0;
        costFrames = 0;
    }

    private static void cost(Test test, String name) {
        test.noted("pictures.cost." + name, String.format(Locale.ROOT, "%.3f ms cpu a frame at the draw point, %.3f ms "
                + "drawn by vanilla into the main target (%d frames, %d items)", pictureNanos / 1e6 / costFrames,
                referenceNanos / 1e6 / costFrames, costFrames, GRID * GRID));
    }

    // ── what is measured ─────────────────────────────────────────────────────────

    /** The capture: the picture's panel against the reference's, per cell and whole; the failing picture; the refusal. */
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
        test.stated("pictures.picture", pictureDrawn > 0, "pixels Minecraft drew on in the picture's own texture: " + pictureRead);
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
        List<int[]> slack = new ArrayList<>();
        for (Cell cell : cells) {
            int[] r = cell.rect();
            int[] own = {r[0], r[1], r[2], r[3], cell.slack()};
            slack.add(own);
            int[] d = diff(image, r[0], r[1], r[2], r[3], s, false, List.of(own));
            test.stated("pictures.parity." + cell.name(), d[0] == 0, d[0] + " of " + d[2] + " pixels differ by more than "
                    + cell.slack() + "; max " + d[1] + (d[1] > 1 && d[0] == 0 ? " (" + cell.slackReason() + ")" : ""));
        }
        int[] whole = diff(image, 0, 0, W, H, s, false, slack);
        test.stated("pictures.parity", whole[0] == 0, "the whole panel: " + whole[0] + " of " + whole[2]
                + " pixels differ by more than their cell allows (1; " + GLINT_SLACK + " glinted; " + LAYER_SLACK
                + " under a tooltip's layers); max " + whole[1] + "; scale " + s);
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
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rx = Math.round(REF_X * s) + x, ry = Math.round(REF_Y * s) + y;
                int px = Math.round(PIC_X * s) + x, py = Math.round(PIC_Y * s) + y;
                if (rx >= image.getWidth() || py >= image.getHeight()) continue;
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

    private static void next(Phase p) {
        phase = p;
        ticks = 0;
    }

    private static void finish(Test test) {
        composite = false;
        phase = Phase.DONE;
        test.done();
    }
}
