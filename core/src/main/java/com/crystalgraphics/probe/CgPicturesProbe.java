package com.crystalgraphics.probe;

import com.crystalgraphics.api.canvas.CgCanvasBox;
import com.crystalgraphics.gauntlet.CgGauntletCapture;
import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.platform.rhi.CgHostImports;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.world.CgWorldStimulus;
import com.crystalgraphics.render.stage.CgRenderStage;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

/**
 * NR0's proof (renderer § 19.7, § 19.14): Minecraft draws its own objects into a target the host made, the graph imports
 * that target and a canvas composites it, and the result is what vanilla's own GUI code draws in a slot. The gauntlet's
 * test {@code crystalgraphics:pictures}; it enters a world itself.
 *
 * <pre>{@code
 * -Dcrystalgraphics.gauntlet=crystalgraphics:pictures
 * -Dcrystalgraphics.pictures.items=create:potato_cannon+mowziesmobs:earthrend_gauntlet   // mods' items, where installed
 * // what it logs, and writes in the run's report:
 * crystalgraphics:pictures pictures.parity.minecraft:clock: true (0 of 1024 pixels differ by more than 1; max 0)
 * crystalgraphics:pictures pictures.alpha is 412 partial pixels, 0 brighter than their alpha (premultiplied)
 * crystalgraphics:pictures pictures.cost.static100 is 1.84 ms cpu a frame (picture), 1.79 ms (vanilla)
 * }</pre>
 *
 * <ul>
 *   <li>Each frame of the parity phase, the host's HUD hook fills two panels with one opaque colour and draws the cells
 *       in the left one with vanilla's own GUI code ({@link Host#reference}); at the draw point, the head of the HUD
 *       firing before our device section, it draws the same cells into its picture target ({@link Host#picture}); this
 *       probe's HUD renderer composites that target, premultiplied, over the right panel. The frame's capture is
 *       compared panel against panel, cell by cell: pass is no pixel differing by more than one step a channel.</li>
 *   <li>Where it is measured: the target the host had bound after each cell (a renderer restoring the main target,
 *       § 19.14 1), the picture's alpha read back (4), a 100-slot grid's draw time static and glinted (5).</li>
 *   <li>Hosts provide {@link Host#SERVICE} on the nodes NR0 proves; everywhere else the test skips, saying so.</li>
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
        /** Whether the host checks its bound target after each cell: a GL read, which a cost phase must not pay. */
        public boolean checkTarget;
        /** Names of cells after which the host's bound target was no longer the one it drew into. */
        public final List<String> retargeted = new ArrayList<>();
        /** The host's GL state as it began, for a diagnosis: bound target, viewport, masks; empty where not read. */
        public String state = "";

        void clear() {
            nanos = 0;
            retargeted.clear();
            state = "";
        }
    }

    /** NR0's host code: vanilla's own GUI calls, into the main target or a picture target of the host's. */
    public interface Host {

        Host NONE = new Host() {
            @Override public String absent() { return "no picture host code on this node (NR0 proves 1.20.1, 1.12.2, 26.2)"; }
            @Override public boolean knows(String item) { return false; }
            @Override public void reference(Object gui, List<Cell> cells, int x, int y, int w, int h, int bx, int by,
                                            int background, Drawn out) { }
            @Override public Object picture(List<Cell> cells, int w, int h, Drawn out) { return null; }
            @Override public boolean bottomUp() { return true; }
        };

        CgService<Host> SERVICE = CgService.of("crystalgraphics:pictures_probe", NONE);

        /** Why this node has no picture host code; null where it has. */
        @Nullable String absent();

        /** Whether the game has an item of this id. */
        boolean knows(String item);

        /**
         * From the host's HUD hook: fills {@code w x h} at ({@code x}, {@code y}) and at ({@code bx}, {@code by}) with the
         * opaque {@code background}, then draws {@code cells} in the first with vanilla's GUI code. {@code gui} is the
         * hook's GUI object, null where the host makes its own.
         */
        void reference(@Nullable Object gui, List<Cell> cells, int x, int y, int w, int h, int bx, int by, int background,
                       Drawn out);

        /**
         * At the draw point: draws {@code cells} into the host's picture target of {@code w x h} GUI units at the GUI's
         * scale, cleared to transparent, every change through the host's own API put back. The handle
         * {@link CgHostImports#texture} takes; null when it could not draw.
         */
        @Nullable Object picture(List<Cell> cells, int w, int h, Drawn out);

        /** Whether the picture's row 0 is its bottom (a GL framebuffer's). */
        boolean bottomUp();
    }

    /** The panels, in GUI units: the reference at the left, the picture beside it, below it. */
    public static final int W = 200, H = 90, REF_X = 4, REF_Y = 4, PIC_X = 4, PIC_Y = REF_Y + H + 4;
    static final int BACKGROUND = 0xFF3A5F7F;
    /** The pointer's offset from the player, as the inventory's: where it looks. */
    public static final int LOOK_X = 20, LOOK_Y = -30;
    private static final int GRID = 10;
    /** The most a glinted pixel may differ: the glint's offset moving by one millisecond between the two draws. */
    private static final int GLINT_SLACK = 3;
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
    @Nullable private static volatile Object handle;
    private static CgTexture2D texture;
    private static boolean bottomUp = true;
    /** The GUI scale the HUD fired at. */
    private static volatile float scale = 1f;
    private static final Drawn picture = new Drawn(), reference = new Drawn();
    private static long pictureNanos, referenceNanos;
    private static int costFrames;
    private static boolean alphaAsked, stateNoted, pictureStateNoted, retargetNoted;
    private static CgGauntletCapture.Shot shot;

    private CgPicturesProbe() {
    }

    /** The gauntlet's {@code crystalgraphics:pictures}. */
    public static CgGauntletController test() {
        return new Test();
    }

    // ── the host's two calls ─────────────────────────────────────────────────────

    /** Host side: its HUD hook, with the hook's GUI object (null where it makes its own). Render thread. */
    public static void hud(@Nullable Object gui) {
        if (active == null || !drawing()) return;
        Host host = CgPlatform.get(Host.SERVICE);
        reference.clear();
        host.reference(gui, cells, REF_X, REF_Y, panelW(), panelH(), PIC_X, PIC_Y, BACKGROUND, reference);
        referenceNanos += reference.nanos;
        if (phase == Phase.PARITY && !reference.state.isEmpty() && !stateNoted) {
            stateNoted = true;
            active.noted("pictures.reference.state", reference.state);
        }
    }

    /** Host side: the draw point, at the head of the HUD firing, before its device section. Render thread. */
    public static void drawPoint() {
        if (active == null || !drawing()) return;
        Host host = CgPlatform.get(Host.SERVICE);
        picture.clear();
        picture.checkTarget = parity();
        handle = host.picture(cells, panelW(), panelH(), picture);
        bottomUp = host.bottomUp();
        pictureNanos += picture.nanos;
        if (phase == Phase.PARITY && !picture.state.isEmpty() && !pictureStateNoted) {
            pictureStateNoted = true;
            active.noted("pictures.picture.state", picture.state);
        }
        if (phase == Phase.PARITY && !picture.retargeted.isEmpty() && !retargetNoted) {
            retargetNoted = true;
            active.noted("pictures.retargeted", picture.retargeted.toString());
        }
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
            alphaAsked = stateNoted = pictureStateNoted = retargetNoted = false;
            active = this;
            hud = CgRenderStage.HUD.register(Integer.MAX_VALUE - 1, frame -> {
                scale = frame.scale();
                Object h = handle;
                if (!composite || h == null) return;
                CgGpuTexture t = CgHostImports.get().texture(h);
                if (texture == null || texture.rhiTexture() != t) {
                    if (texture != null) texture.delete();
                    texture = CgTexture2D.borrowed(t);
                }
                float s = frame.scale();
                CgCanvasBox box = frame.canvas().box(PIC_X * s, PIC_Y * s, W * s, H * s).fill(texture).premultiplied();
                if (bottomUp) box.region(0f, 1f, 1f, 0f);
                box.draw();
                if (!alphaAsked && phase == Phase.PARITY && ticks > WARM / 2) {
                    alphaAsked = true;
                    readAlpha(texture, Math.round(W * s), Math.round(H * s));
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
                composite = false;
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
        test.noted("pictures.cost." + name, String.format(Locale.ROOT, "%.3f ms cpu a frame into the picture, %.3f ms "
                + "drawn by vanilla into the main target (%d frames, %d items)", pictureNanos / 1e6 / costFrames,
                referenceNanos / 1e6 / costFrames, costFrames, GRID * GRID));
    }

    // ── what is measured ─────────────────────────────────────────────────────────

    /** The capture, the picture's panel against the reference's, per cell and whole. */
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
        for (Cell cell : cells) {
            int[] r = cell.rect();
            int[] d = diff(image, r[0], r[1], r[2], r[3], s);
            // A glint's offset follows the game's millisecond clock, which can tick between the two draws
            boolean ok = d[0] == 0 || cell.glinted() && d[1] <= GLINT_SLACK;
            test.stated("pictures.parity." + cell.name(), ok, d[0] + " of " + d[2] + " pixels differ by more than 1; max "
                    + d[1] + (cell.glinted() && d[0] > 0 && ok ? " (the glint's clock moved between the draws)" : ""));
        }
        List<int[]> glinted = new ArrayList<>();
        for (Cell cell : cells) if (cell.glinted()) glinted.add(cell.rect());
        int[] whole = diff(image, 0, 0, W, H, s, false, glinted);
        test.stated("pictures.parity", whole[0] == 0, "the whole panel: " + whole[0] + " of " + whole[2]
                + " pixels differ by more than 1 (" + GLINT_SLACK + " in a glinted cell); max " + whole[1] + "; scale " + s);
        int[] blank = diff(image, 0, 0, W, H, s, true, List.of());
        test.stated("pictures.drawn", blank[0] > 0, blank[0] + " of the reference panel's pixels are not the background");
    }

    /** {pixels differing by more than 1, the largest difference, pixels compared} of a rect in GUI units. */
    private static int[] diff(BufferedImage image, int gx, int gy, int gw, int gh, float s) {
        return diff(image, gx, gy, gw, gh, s, false, List.of());
    }

    /** As {@link #diff(BufferedImage, int, int, int, int, float)}, {@link #GLINT_SLACK} allowed inside {@code slack}'s rects. */
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
                if (d > (inside(slack, x / s, y / s) ? GLINT_SLACK : 1)) over++;
                max = Math.max(max, d);
            }
        }
        return new int[]{over, max, n};
    }

    private static boolean inside(List<int[]> rects, float gx, float gy) {
        for (int[] r : rects) {
            if (gx >= r[0] && gx < r[0] + r[2] && gy >= r[1] && gy < r[1] + r[3]) return true;
        }
        return false;
    }

    /** The picture's own alpha: how many pixels are partial, and whether their colour is premultiplied. */
    private static void readAlpha(CgTexture2D texture, int w, int h) {
        CgReadback.texture(texture, 0, 0, 0, w, h, new CgReadback.Sink() {
            @Override
            public void accept(ByteBuffer px) {
                int clear = 0, opaque = 0, partial = 0, brighter = 0;
                for (int i = 0; i + 3 < px.limit() && i < w * h * 4; i += 4) {
                    int r = px.get(i) & 255, g = px.get(i + 1) & 255, b = px.get(i + 2) & 255, a = px.get(i + 3) & 255;
                    if (a == 0) clear++;
                    else if (a == 255) opaque++;
                    else {
                        partial++;
                        if (Math.max(r, Math.max(g, b)) > a + 1) brighter++;
                    }
                }
                Test test = active;
                if (test != null) {
                    test.noted("pictures.alpha", clear + " clear, " + opaque + " opaque, " + partial + " partial pixels, "
                            + brighter + " of those brighter than their alpha (" + (brighter == 0 ? "premultiplied"
                            : "not premultiplied") + ")");
                }
            }

            @Override
            public void failed(String reason) {
                Test test = active;
                if (test != null) test.noted("pictures.alpha", "never read: " + reason);
            }
        });
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
