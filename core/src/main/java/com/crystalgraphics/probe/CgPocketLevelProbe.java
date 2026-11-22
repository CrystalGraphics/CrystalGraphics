package com.crystalgraphics.probe;

import com.crystalgraphics.mc.block.CgBlocks;
import com.crystalgraphics.render.precache.CgPipelinePrecache;
import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

import org.joml.Vector2f;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gauntlet.CgGauntletCapture;
import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.entity.CgEntityData;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.level.CgBlockPos;
import com.crystalgraphics.mc.level.CgDirection;
import com.crystalgraphics.mc.level.CgStructure;
import com.crystalgraphics.mc.render.world.CgItemDisplay;
import com.crystalgraphics.mc.render.world.CgItemStackComponent;
import com.crystalgraphics.mc.render.world.CgLevelPicture;
import com.crystalgraphics.mc.render.world.CgPocketLevel;
import com.crystalgraphics.platform.world.CgWorldStimulus;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgLineBatchComponent;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.scene.component.CgPointLight;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.crystalgraphics.world.CgEntityQueries;

/**
 * The gauntlet's {@code crystalgraphics:pocket_level}: NR6's proof. A tower built as a structure stands in a pocket
 * level under a scene of ours, shown by a view on the HUD's right half; a copper cube of ours is half sunk in the
 * tower's front wall, and a point light of ours stands before it.
 *
 * <pre>
 * crystalgraphics:pocket_level pocket.drawn: true (31210 pixels of the view change as the level shows)
 * crystalgraphics:pocket_level pocket.depth: true (41% of the cube's pixels hidden behind the wall)
 * crystalgraphics:pocket_level pocket.pick: true (minecraft:stone_brick_stairs[...] (2, 7, 0) face UP at y 7.500 and 8.000, 2 outline boxes)
 * crystalgraphics:pocket_level pocket.lit: true (the view's pixels 22.4 brighter under the light, mean of 98304)
 * </pre>
 *
 * <ul>
 *   <li>Drawn: the view with the level against the level drawn with no block shown (the same target and world behind). Depth: of the cube's pixels in the view without
 *       the level, the share the level's capture shows otherwise, which only a wall in front of it does; neither none
 *       (no depth test) nor all (the scene not drawn).</li>
 *   <li>Pick: straight down onto a stair of the top ring, its lower step and its full half: Minecraft's clip against the
 *       outline shape meets each at its own height, as the crosshair does, and the hit carries the stair's two boxes.</li>
 *   <li>Entities: the structure's armour stand spawned as a component in the doorway, a sword item beside the door: both
 *       the scene's, drawn by Minecraft into the level's target.</li>
 *   <li>Picture: the level alone, from another side, in the HUD's top left: drawn once, not again while nothing changes,
 *       again when the range cuts the level.</li>
 *   <li>Ticking: an enchanting table before the tower, its book moving between two captures ten ticks apart.</li>
 *   <li>Outline: the picked stair's boxes drawn black, 3 pixels wide, by a line batch of the scene, as the crosshair
 *       outlines a block.</li>
 *   <li>Range: shown from 0 to 4, the same ray passes the hidden stair and meets the wall's cut top.</li>
 *   <li>Captures: {@code crystalgraphics-pocket_level-level}, {@code -bare}, {@code -bare_lit}, {@code -lit} and {@code -layer}.</li>
 *   <li>Skipped, with the reason, where the host has no pocket levels.</li>
 * </ul>
 */
public final class CgPocketLevelProbe {

    /** Client ticks: in a world first; frames for a change to show. */
    private static final int SETTLE = 40, WATCH = 20, LIGHT_WATCH = 100;

    private enum Phase { WAITING, LEVEL, EMPTY, BARE, BARE_LIT, LIT, OUTLINE, LAYER, TICK, CHECK, DONE }

    private static Phase phase = Phase.WAITING;
    private static int ticks;
    @Nullable private static CgPocketLevel level;
    @Nullable private static CgScene scene;
    @Nullable private static CgView view;
    @Nullable private static CgMeshComponent cube;
    @Nullable private static CgItemStackComponent sword;
    @Nullable private static CgLevelPicture picture;
    private static long pictureAtLevel, pictureAtBare;
    private static int entities;
    @Nullable private static CgPointLight light;
    @Nullable private static CgRenderStage.Registration hud;
    @Nullable private static CgGauntletCapture.Shot levelShot, emptyShot, bareShot, bareLitShot, litShot, outlineShot, layerShot, tickShot;
    @Nullable private static CgLineBatchComponent outline;
    @Nullable private static CgPocketLevel.Hit picked;
    /** An enchanting table before the tower: its book moves only while the level ticks. */
    private static final double TABLE_X = 6, TABLE_Y = 0, TABLE_Z = -1;
    private static volatile int viewX, viewY, viewW, viewH;
    private static boolean installed;
    private static volatile boolean inWorld;
    @Nullable private static volatile Test active;

    private CgPocketLevelProbe() {
    }

    /** The gauntlet's {@code crystalgraphics:pocket_level}. */
    public static CgGauntletController test() {
        return new Test();
    }

    /** The tower: stone brick walls, a door, a window, stairs round its top, a chest and a torch inside. */
    static CgStructure tower() {
        CgBlockState bricks = known("minecraft:stone_bricks") ? CgBlockState.parse("minecraft:stone_bricks")
                : CgBlockState.parse("minecraft:stonebrick");
        CgStructure.Builder b = CgStructure.builder(5, 8, 5)
                .fill(0, 0, 0, 4, 0, 4, bricks)
                .fill(0, 1, 0, 4, 6, 0, bricks).fill(0, 1, 4, 4, 6, 4, bricks)
                .fill(0, 1, 0, 0, 6, 4, bricks).fill(4, 1, 0, 4, 6, 4, bricks)
                .fill(2, 1, 0, 2, 2, 0, CgBlockState.AIR)
                .block(2, 4, 0, CgBlockState.parse("minecraft:glass"))
                .block(2, 1, 2, CgBlockState.parse("minecraft:chest[facing=north]"))
                .block(1, 1, 3, CgBlockState.parse("minecraft:torch"))
                .entity(2.5, 1, 0.5, CgEntityData.parse("minecraft:armor_stand{ShowArms:1b}"));
        for (int i = 0; i < 5; i++) {
            b.block(i, 7, 0, CgBlockState.parse("minecraft:stone_brick_stairs[facing=south]"));
            b.block(i, 7, 4, CgBlockState.parse("minecraft:stone_brick_stairs[facing=north]"));
        }
        return b.build();
    }

    private static final class Test extends CgGauntletController {
        @Override
        protected void onInit() {
            active = this;
            phase = Phase.WAITING;
            ticks = 0;
            inWorld = false;
            install();
        }

        @Override
        protected void onStateChange(CgGauntletState from, CgGauntletState to) {
            if (to.equals(CgGauntletState.TITLE)) enterWorld();
            // The player exists while terrain still loads: settle from the world shown
            if (to.equals(CgGauntletState.IN_WORLD)) {
                inWorld = true;
                ticks = 0;
            }
        }

        @Override
        protected void onEnd() {
            active = null;
            tidy();
        }

        void stated(String name, boolean ok, String detail) {
            check(name, ok, detail);
        }

        void noted(String name, String detail) {
            info(name, detail);
        }

        void skipped(String name, String reason) {
            skip(name, reason);
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
        CgEngineLifecycle.onClientTick(CgPocketLevelProbe::tick);
    }

    // Ticks a phase waits for pipelines still building, and how many it has waited
    private static final int HOLD = 600;
    private static int held;

    // Whether the host knows a block by this name: legacy names some blocks otherwise (stonebrick)
    private static boolean known(String id) {
        try {
            CgBlocks.toHost(CgBlockState.parse(id));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void tick() {
        Test test = active;
        if (test == null) return;
        // Three clients share the screen in a sweep: an unfocused one must not pause behind its menu
        if (inWorld) CgPlatform.get(CgWorldStimulus.SERVICE).keepRunning();
        // A cold client builds its pipelines as a phase first draws: the phase's count pauses for them, at most HOLD
        // ticks, so each of its moments still comes once
        int building = CgPipelinePrecache.remaining();
        if (building > 0 && held < HOLD) {
            held++;
            return;
        }
        if (held == HOLD) test.noted("pocket.pipelines", building + " still building after " + HOLD + " ticks; the phase went on");
        held = 0;
        ticks++;
        switch (phase) {
            case WAITING: {
                CgEntityQuery entities = CgEntityQueries.client();
                if (!inWorld || entities.localPlayer() < 0 || ticks < SETTLE) return;
                String absent = CgPlatform.get(CgPocketLevel.HOST).absent();
                if (absent != null) {
                    test.skipped("everything", absent);
                    finish(test);
                    return;
                }
                setUp();
                next(Phase.LEVEL);
                return;
            }
            case LEVEL:
                if (ticks == 1) {
                    int hosted = scene.primitives().hostDrawn().size();
                    test.stated("pocket.entities", entities == 1 && hosted == 2, "the structure's " + entities
                            + " entity spawned as a component; " + hosted + " host-drawn components in the scene");
                }
                if (ticks == WATCH) levelShot = test.shoot("level");
                if (ticks < WATCH || !levelShot.done()) return;
                // The level drawn with nothing shown: the same target, the same world behind it, no blocks
                level.visibleRange(-100, -99);
                next(Phase.EMPTY);
                return;
            case EMPTY:
                if (ticks == WATCH) emptyShot = test.shoot("empty");
                if (ticks < WATCH || !emptyShot.done()) return;
                level.visibleRange();
                scene.hostLevel(null);
                next(Phase.BARE);
                return;
            case BARE:
                // Settled once the range's restore has redrawn it, however slow the frames: the watch restarts while
                // the picture's version still moves, and nothing changes from then to the bare capture
                if (picture.version() != pictureAtLevel) {
                    pictureAtLevel = picture.version();
                    ticks = 0;
                    return;
                }
                if (ticks == WATCH) bareShot = test.shoot("bare");
                if (ticks < WATCH || !bareShot.done()) return;
                pictureAtBare = picture.version();
                light = scene.pointLight().at(2.5, 3, -2.5).radius(10f).color(0xFFFFE0C0).intensity(250f).spawn();
                next(Phase.BARE_LIT);
                return;
            case BARE_LIT:
                // The light without the level: whether a view of a scene of ours is lit here at all; its kernels may
                // still be compiling a second after it spawns
                if (ticks == LIGHT_WATCH) bareLitShot = test.shoot("bare_lit");
                if (ticks < LIGHT_WATCH || !bareLitShot.done()) return;
                // Where the light grid last stood: the view's eye if the lights recorded for it, zero if never
                test.noted("pocket.grid", String.format("the light grid's eye at (%.1f, %.1f, %.1f), %d listed, applied %s",
                        scene.lights().cameraX(), scene.lights().cameraY(), scene.lights().cameraZ(),
                        scene.lights().listed(), scene.lights().applied()));
                scene.hostLevel(level);
                next(Phase.LIT);
                return;
            case LIT:
                if (ticks == WATCH) litShot = test.shoot("lit");
                if (ticks < WATCH || !litShot.done()) return;
                // The stairs read before the range hides them, and the one picked outlined as the crosshair outlines it
                pick(test);
                if (picked != null) for (CgAABBc box : picked.outline()) outline.box(box, 0xFF000000);
                next(Phase.OUTLINE);
                return;
            case OUTLINE:
                if (ticks == WATCH) outlineShot = test.shoot("outline");
                if (ticks < WATCH || !outlineShot.done()) return;
                outlined(test);
                outline.clear();
                level.visibleRange(0, 4);
                next(Phase.LAYER);
                return;
            case LAYER:
                if (ticks == WATCH) layerShot = test.shoot("layer");
                if (ticks < WATCH || !layerShot.done()) return;
                CgPocketLevel.Hit cut = level.pick(2.5, 20, 0.25, 0, -1, 0, 32);
                long pictureAtLayer = picture.version();
                test.stated("pocket.picture", pictureAtLevel > 0 && pictureAtBare == pictureAtLevel
                        && pictureAtLayer > pictureAtBare, "the level's picture drawn " + pictureAtLevel
                        + " times once settled, " + pictureAtBare + " fifteen frames on with nothing changed, "
                        + pictureAtLayer + " once the range cut it");
                test.stated("pocket.range", cut != null && cut.y() <= 5 + 1e-6, cut == null ? "a ray down met nothing"
                        : String.format("shown 0 to 4, a ray down meets %s at y %.3f", cut.state().block(), cut.y()));
                // From here only: a ticking level's pictures draw every frame, which the picture check above rules out
                level.ticking(true);
                next(Phase.TICK);
                return;
            case TICK:
                if (ticks == 10) tickShot = test.shoot("tick");
                if (ticks < 10 || !tickShot.done()) return;
                ticked(test);
                next(Phase.CHECK);
                return;
            case CHECK:
                images(test);
                finish(test);
                return;
            default:
        }
    }

    // The tower at the origin, the cube in its front wall at the window's height, a view from before it and above
    private static void setUp() {
        level = CgPocketLevel.create();
        scene = new CgScene().hostLevel(level);
        entities = level.place(tower(), 0, 0, 0, scene).size();
        level.setBlock((int) TABLE_X, (int) TABLE_Y, (int) TABLE_Z, CgBlockState.parse("minecraft:enchanting_table"));
        sword = CgItemStackComponent.of(scene, CgItemStack.of("minecraft:diamond_sword", 1))
                .display(CgItemDisplay.GROUND).at(3.5, 1.2, -0.6).spawn();
        cube = scene.mesh(CgMeshShapes.cube(), CgMaterial.load("crystalgraphics:shaders/demo/copper.shader"))
                .at(1.0, 3.5, 0.0).scale(1.2f).spawn();
        view = CgView.of(scene);
        outline = CgLineBatchComponent.of(scene).thickness(3f).spawn();
        picture = CgLevelPicture.of(level)
                .camera(CgCamera.perspective(40f, 1f, 0.05f, 100f).lookAt(10f, 9f, -8f, 2.5f, 3.5f, 2.5f));
        hud = CgRenderStage.HUD.register(Integer.MAX_VALUE - 2, frame -> {
            if (view == null) return;
            scene.tick(1.0 / 60.0);
            int w = frame.width() / 2, h = frame.height();
            viewX = frame.width() - w;
            viewY = 0;
            viewW = w;
            viewH = h;
            view.camera().set(CgCamera.perspective(55f, w / (float) h, 0.05f, 200f).lookAt(-4f, 7f, -9f, 2.5f, 3.5f, 2.5f));
            frame.canvas().view(view, viewX, viewY, w, h);
            frame.canvas().picture(picture, 8, 8, 200, 200);
        });
    }

    // Drawn: the level against none. Depth: of the cube's pixels without the level, the share the level hides. Lit: the
    // wall about the cube, brighter under the light, and the cube without the level
    private static void images(Test test) {
        BufferedImage withLevel = read(levelShot), bare = read(bareShot), lit = read(litShot), bareLit = read(bareLitShot);
        BufferedImage empty = read(emptyShot);
        if (withLevel == null || bare == null || lit == null || bareLit == null || empty == null) {
            test.stated("pocket.drawn", false, "a capture was not written");
            return;
        }
        int x0 = viewX * withLevel.getWidth() / Math.max(1, viewX + viewW), x1 = withLevel.getWidth();
        int background = bare.getRGB(x1 - 2, 1);
        long changed = 0, cubePixels = 0, cubeHidden = 0, cubeBareSum = 0, cubeLitSum = 0;
        int bx0 = Integer.MAX_VALUE, by0 = Integer.MAX_VALUE, bx1 = -1, by1 = -1;
        for (int y = 0; y < withLevel.getHeight(); y++) {
            for (int x = x0; x < x1; x++) {
                int l = withLevel.getRGB(x, y), b = bare.getRGB(x, y);
                if (Math.abs(luma(l) - luma(empty.getRGB(x, y))) > 8) changed++;
                if (Math.abs(luma(b) - luma(background)) <= 8) continue;
                cubePixels++;
                if (Math.abs(luma(l) - luma(b)) > 8) cubeHidden++;
                cubeBareSum += luma(b);
                cubeLitSum += luma(bareLit.getRGB(x, y));
                bx0 = Math.min(bx0, x);
                by0 = Math.min(by0, y);
                bx1 = Math.max(bx1, x);
                by1 = Math.max(by1, y);
            }
        }
        test.stated("pocket.drawn", changed > 2000, changed + " pixels of the view are the level's blocks (against it drawn"
                + " with none shown)");
        double hidden = cubePixels == 0 ? 0 : cubeHidden / (double) cubePixels;
        // Some hidden, never none or all: a broken depth test shows the whole cube or none; eras range 9-19%
        test.stated("pocket.depth", cubePixels > 200 && hidden > 0.05 && hidden < 0.9, String.format(
                "%.0f%% of the cube's %d pixels hidden behind the wall", hidden * 100, cubePixels));
        if (cubePixels == 0) {
            test.stated("pocket.lit", false, "no cube drawn to measure about");
            return;
        }
        // The wall: the cube's box widened by its size each way, the cube's own pixels out
        int w = bx1 - bx0 + 1, h = by1 - by0 + 1;
        long wall = 0, sumLevel = 0, sumLit = 0;
        for (int y = Math.max(0, by0 - h); y <= Math.min(withLevel.getHeight() - 1, by1 + h); y++) {
            for (int x = Math.max(x0, bx0 - w); x <= Math.min(x1 - 1, bx1 + w); x++) {
                if (Math.abs(luma(bare.getRGB(x, y)) - luma(background)) > 8) continue;
                wall++;
                sumLevel += luma(withLevel.getRGB(x, y));
                sumLit += luma(lit.getRGB(x, y));
            }
        }
        double wallGain = wall == 0 ? 0 : (sumLit - sumLevel) / (double) wall;
        double cubeGain = (cubeLitSum - cubeBareSum) / (double) cubePixels;
        test.stated("pocket.lit", wallGain > 5 && cubeGain > 5, String.format(
                "the wall about the cube %.1f brighter under the light (mean of %d), the cube %.1f", wallGain, wall, cubeGain));
    }

    // Straight down onto the stair at (2, 7, 0), facing south: its north half is a step at 7.5, its south half full height
    private static void pick(Test test) {
        CgPocketLevel.Hit low = level.pick(2.5, 20, 0.25, 0, -1, 0, 32), high = level.pick(2.5, 20, 0.75, 0, -1, 0, 32);
        picked = low;
        if (low == null || high == null) {
            test.stated("pocket.pick", false, "a ray down onto the stair met nothing");
            return;
        }
        boolean stair = low.block().equals(new CgBlockPos(2, 7, 0)) && high.block().equals(low.block())
                && low.state().block().equals("minecraft:stone_brick_stairs") && low.face() == CgDirection.UP;
        // By shape the step meets the lower ray and its riser the upper one; by selection box both meet the block's top
        boolean byShape = CgPocketLevel.picksByShape();
        boolean shape = byShape
                ? Math.abs(low.y() - 7.5) < 1e-6 && Math.abs(high.y() - 8.0) < 1e-6 && low.outline().size() == 2
                : Math.abs(low.y() - 8.0) < 1e-6 && Math.abs(high.y() - 8.0) < 1e-6 && low.outline().size() == 1;
        test.stated("pocket.pick", stair && shape, String.format("%s %s face %s at y %.3f and %.3f, %d outline boxes",
                low.state(), low.block(), low.face(), low.y(), high.y(), low.outline().size()));
    }

    // The stair's box on screen: the black outline changes it between the lit capture and the outlined one
    private static void outlined(Test test) {
        BufferedImage before = read(litShot), after = read(outlineShot);
        if (before == null || after == null || picked == null) {
            test.stated("pocket.outline", false, picked == null ? "nothing picked to outline" : "a capture was not written");
            return;
        }
        int[] box = screenBox(2, 7, 0, 1, 1, 1, before.getWidth());
        long darker = 0, all = 0;
        for (int y = Math.max(0, box[1]); y <= Math.min(before.getHeight() - 1, box[3]); y++) {
            for (int x = Math.max(0, box[0]); x <= Math.min(before.getWidth() - 1, box[2]); x++) {
                all++;
                if (luma(before.getRGB(x, y)) - luma(after.getRGB(x, y)) > 24) darker++;
            }
        }
        test.stated("pocket.outline", lines() == 12 * picked.outline().size() && darker > 50, lines() + " lines for the stair's "
                + picked.outline().size() + " outline boxes; " + darker + " of the " + all + " pixels about it darkened");
    }

    private static int lines() {
        return outline == null ? 0 : outline.lines();
    }

    // A box's corners projected through the view's camera: its rect in the capture's pixels
    private static int[] screenBox(double x, double y, double z, double sx, double sy, double sz, int captureWidth) {
        CgCamera camera = view.camera();
        Vector2f at = new Vector2f();
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int c = 0; c < 8; c++) {
            if (!camera.project(x + (c & 1) * sx, y + (c >> 1 & 1) * sy, z + (c >> 2 & 1) * sz, viewW, viewH, at)) continue;
            x0 = Math.min(x0, viewX + (int) at.x);
            y0 = Math.min(y0, viewY + (int) at.y);
            x1 = Math.max(x1, viewX + (int) at.x);
            y1 = Math.max(y1, viewY + (int) at.y);
        }
        int scale = Math.max(1, captureWidth / Math.max(1, viewX + viewW));
        return new int[]{x0 * scale, y0 * scale, x1 * scale, y1 * scale};
    }

    // The table's box on screen, from the view's camera: its book bobs and turns between two captures ten ticks apart
    private static void ticked(Test test) {
        BufferedImage a = read(layerShot), b = read(tickShot);
        if (a == null || b == null) {
            test.stated("pocket.ticking", false, "a capture was not written");
            return;
        }
        CgCamera camera = view.camera();
        Vector2f at = new Vector2f();
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int c = 0; c < 8; c++) {
            if (!camera.project(TABLE_X + (c & 1), TABLE_Y + 0.75 + (c >> 1 & 1) * 0.75, TABLE_Z + (c >> 2 & 1),
                    viewW, viewH, at)) continue;
            int px = viewX + (int) at.x, py = viewY + (int) at.y;
            x0 = Math.min(x0, px);
            y0 = Math.min(y0, py);
            x1 = Math.max(x1, px);
            y1 = Math.max(y1, py);
        }
        int scale = a.getWidth() / Math.max(1, viewX + viewW);
        long differ = 0, all = 0;
        for (int y = Math.max(0, y0 * scale); y <= Math.min(a.getHeight() - 1, y1 * scale); y++) {
            for (int x = Math.max(0, x0 * scale); x <= Math.min(a.getWidth() - 1, x1 * scale); x++) {
                all++;
                if (Math.abs(luma(a.getRGB(x, y)) - luma(b.getRGB(x, y))) > 8) differ++;
            }
        }
        test.stated("pocket.ticking", differ > 20, differ + " of the " + all + " pixels about the enchanting table's book"
                + " move in ten ticks");
    }

    @Nullable
    private static BufferedImage read(@Nullable CgGauntletCapture.Shot shot) {
        if (shot == null || !shot.written()) return null;
        try {
            return ImageIO.read(shot.file().toFile());
        } catch (IOException e) {
            return null;
        }
    }

    private static int luma(int argb) {
        return ((argb >> 16 & 255) * 2 + (argb >> 8 & 255) * 5 + (argb & 255)) / 8;
    }

    private static void next(Phase p) {
        phase = p;
        ticks = 0;
    }

    private static void tidy() {
        if (hud != null) hud.close();
        hud = null;
        if (view != null) view.releasePicture();
        view = null;
        picture = null;
        if (level != null) level.close();
        level = null;
        scene = null;
        cube = null;
        sword = null;
        outline = null;
        picked = null;
        light = null;
    }

    private static void finish(Test test) {
        tidy();
        phase = Phase.DONE;
        test.done();
    }
}
