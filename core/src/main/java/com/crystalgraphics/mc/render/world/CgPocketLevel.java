package com.crystalgraphics.mc.render.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.level.CgBlockPos;
import com.crystalgraphics.mc.level.CgDirection;
import com.crystalgraphics.mc.level.CgStructure;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.render.CgDepthConvention;
import com.crystalgraphics.render.host.CgHostLevel;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;

/**
 * A Minecraft level of your own, drawn by Minecraft under a scene of yours: a structure editor's, a guidebook's
 * multiblock, a cutscene preview's. Its blocks are meshed by Minecraft's own renderers, again only where one changed;
 * the scene's meshes, grid and gizmos draw beside its depth and its lights light it (Lyra's {@code UPocketLevel}).
 *
 * <pre>{@code
 * CgPocketLevel level = CgPocketLevel.create();             // any thread; Minecraft's copy made at the next draw point
 * level.place(CgStructure.load("mymod:tower"), 0, 0, 0);    // its blocks, block entities' data with them
 * level.setBlock(2, 5, 1, CgBlockState.parse("minecraft:torch"));
 *
 * CgScene editor = new CgScene().hostLevel(level);          // Minecraft draws it under the scene
 * level.place(CgStructure.load("mymod:camp"), 8, 0, 0, editor);   // its entities as the scene's components
 * CgView view = CgView.of(editor);
 * view.camera().set(CgCamera.perspective(60f, w / (float) h, 0.05f, 200f).lookAt(-8, 10, -8, 3, 2, 3));
 * canvas.view(view, x, y, w, h);                            // each frame
 * level.close();                                            // when the editor goes
 * }</pre>
 *
 * <pre>{@code
 * level.visibleRange(0, layer);       // a guidebook's layer by layer: what lies above is hidden, its cut faces shown
 * level.visibleRange();               // everything again
 * level.ticking(true);                // block entities animate: a chest's lid, a beacon's beam, a table's book
 * }</pre>
 *
 * <pre>{@code
 * // A click: Minecraft's own clip against outline shapes, as the crosshair picks (a stair's step, a slab's half)
 * CgPocketLevel.Hit hit = level.pick(eyeX, eyeY, eyeZ, dirX, dirY, dirZ, 64);
 * if (hit != null) select(hit.block(), hit.face());
 * for (CgAABBc box : hit.outline()) drawOutline(box);    // the shape vanilla outlines
 * }</pre>
 *
 * <ul>
 *   <li>Changed on any thread; Minecraft's copy takes the changes at the next draw point, on the render thread.</li>
 *   <li>A client world must be open when Minecraft's copy is made, at the next draw point: its registries and dimension
 *       shape the level, as Ponder's do. Without one the level is closed, with a warning; where pocket levels cannot
 *       exist (a dedicated server, 1.7.10), {@code create} throws with the reason.</li>
 *   <li>Never ticked by a server, never sent anywhere: a client's picture of blocks, not a world.</li>
 *   <li>A view's first frame, and its first at a new size, shows the scene without the level: the level is drawn
 *       before the canvas asks for it, from the next frame on.</li>
 *   <li>{@code pick} answers blocks; entities placed with it are the scene's components, which {@code CgView.pick}
 *       answers. An editor takes the nearer.</li>
 * </ul>
 */
public final class CgPocketLevel implements CgHostLevel, AutoCloseable {

    /**
     * A block a ray met: where, on which face, the point it met, the state there, and its outline as boxes in the level,
     * as the era's crosshair outlines it: the block's shape from 1.13 (a stair's two boxes), its one selection box before
     * ({@link #picksByShape}).
     */
    public record Hit(CgBlockPos block, CgDirection face, double x, double y, double z, CgBlockState state,
                      List<CgAABBc> outline) {
        public Hit {
            outline = List.copyOf(outline);
        }
    }

    /** What an era answers: Minecraft's own level and its drawing. Client, render thread. */
    public interface Host {
        /** Why this node has no pocket levels, or null where it has them. */
        @Nullable
        String absent();

        /** A level of the era's own class, empty. Throws where no client world is open. */
        Object create();

        /** {@code state} at {@code (x, y, z)}, its block entity made from its data; null clears it to air. */
        void setBlock(Object level, int x, int y, int z, @Nullable CgBlockState state);

        /**
         * The level drawn for {@code key} (a view) at {@code width} x {@code height}, into a target of the host's own kept
         * for that key: sections meshed where they changed, under {@code view} (relative to the origin
         * {@code (ox, oy, oz)}) and {@code projection}, in the host's depth convention. {@code components} draws the
         * view's scene's host-drawn components: run it after the opaque blocks, with the model-view at identity.
         */
        Drawn draw(Object level, Object key, int width, int height, Matrix4fc view, double ox, double oy, double oz,
                   Matrix4fc projection, float partialTick, Runnable components);

        /** Minecraft's clip from the origin along the direction, at most {@code reach} blocks, against outline shapes. */
        @Nullable
        Hit pick(Object level, double ox, double oy, double oz, double dx, double dy, double dz, double reach);

        /** Whether {@link #pick} follows each block's shape (1.13 on), or its one selection box (legacy's crosshair). */
        default boolean picksByShape() {
            return true;
        }

        /** Only blocks with {@code minY <= y <= maxY} exist: the rest read as air to the mesh, the pick and their renderers. */
        void visibleRange(Object level, int minY, int maxY);

        /** One client tick of its block entities' client tickers. */
        void tick(Object level);

        /** {@code key}'s target freed: its view stopped asking. */
        void forget(Object level, Object key);

        /** Everything the level holds freed. */
        void close(Object level);

        Host NONE = absent("no pocket levels where no Minecraft runs");

        /**
         * A host with no pocket levels, for {@code reason}: what a node that cannot hold one provides, so callers and
         * probes see why rather than the headless default's reason.
         *
         * <pre>{@code
         * CgPlatform.provide(CgPocketLevel.HOST, CgPocketLevel.Host.absent("no block states on 1.7.10"));
         * }</pre>
         */
        static Host absent(String reason) {
            return new Absent(reason);
        }
    }

    /** {@link Host#absent}'s host: {@code create} throws its reason, the rest do nothing. */
    private static final class Absent implements Host {
        private final String reason;

        Absent(String reason) {
            this.reason = reason;
        }

        @Override public String absent() { return reason; }
        @Override public Object create() { throw new IllegalStateException(reason); }
        @Override public void setBlock(Object level, int x, int y, int z, CgBlockState state) { }
        @Override public Drawn draw(Object level, Object key, int w, int h, Matrix4fc view, double ox, double oy,
                                    double oz, Matrix4fc projection, float partialTick, Runnable components) {
            return null;
        }
        @Override public void visibleRange(Object level, int minY, int maxY) { }
        @Override public void tick(Object level) { }
        @Override public Hit pick(Object level, double ox, double oy, double oz, double dx, double dy, double dz,
                                  double reach) { return null; }
        @Override public void forget(Object level, Object key) { }
        @Override public void close(Object level) { }
    }

    /** The slot a host fills. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:pocket_levels", Host.NONE);

    /**
     * Whether a {@link #pick} meets a block's shape and answers its parts (1.13 on: a stair's step and riser), or meets
     * its one selection box as legacy's crosshair does: a tool that outlines picks reads it to expect one box or several.
     */
    public static boolean picksByShape() {
        return CgPlatform.get(HOST).picksByShape();
    }

    private static final Logger LOGGER = LogManager.getLogger("CgPocketLevel");
    /** Frames a view may go unasked before its target is freed. */
    private static final int FORGET_FRAMES = 4;
    private static final List<CgPocketLevel> OPEN = new CopyOnWriteArrayList<>();

    private record Change(int x, int y, int z, @Nullable CgBlockState state) {
    }

    /**
     * One asker's ask: a view's (drawn every frame, its scene drawn over it) or a picture's (drawn when the level, its
     * camera or its size changed). Its size, when it last asked, what was drawn and at which change.
     */
    private static final class Ask {
        @Nullable final CgView view;
        @Nullable final CgLevelPicture picture;
        int width, height;
        long asked;
        @Nullable Drawn drawn;
        long drawnFrame = -1, drawnStamp = -1;

        Ask(@Nullable CgView view, @Nullable CgLevelPicture picture) {
            this.view = view;
            this.picture = picture;
        }

        CgCamera camera() {
            return view != null ? view.camera() : picture.camera();
        }
    }

    private final Map<Long, CgBlockState> blocks = new HashMap<>();
    private final ConcurrentLinkedQueue<Change> changes = new ConcurrentLinkedQueue<>();
    private final Map<Object, Ask> asks = new IdentityHashMap<>();
    private volatile long changeCount;
    private final Matrix4f projection = new Matrix4f();
    @Nullable private Object level;
    private volatile boolean closed;
    private volatile int minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE;
    private volatile boolean rangeChanged;
    private volatile boolean ticking;
    private long drawnFrame = -1;

    private CgPocketLevel() {
    }

    /**
     * An empty level of the client world's dimension. Any thread: Minecraft's copy is made at the next draw point, on the
     * render thread, a client world open. Throws where pocket levels cannot exist, with the reason.
     */
    public static CgPocketLevel create() {
        String absent = CgPlatform.get(HOST).absent();
        if (absent != null) throw new IllegalStateException(absent);
        CgPocketLevel level = new CgPocketLevel();
        OPEN.add(level);
        return level;
    }

    /**
     * {@code structure}'s blocks with their corner at {@code (x, y, z)}; a cell it does not hold keeps what was there. Its
     * entities are not placed: they are components, spawned by the overload taking the scene.
     */
    public CgPocketLevel place(CgStructure structure, int x, int y, int z) {
        structure.forEachBlock((bx, by, bz, state) -> setBlock(x + bx, y + by, z + bz, state));
        return this;
    }

    /**
     * {@code structure} placed as {@link #place(CgStructure, int, int, int)} places it, and its entities spawned into
     * {@code scene} as components standing where they stood in it: they attach, pick and move as every component does.
     * {@code scene} is one this level is under ({@code scene.hostLevel(level)}).
     */
    public List<CgChildEntityComponent> place(CgStructure structure, int x, int y, int z, CgScene scene) {
        place(structure, x, y, z);
        List<CgChildEntityComponent> spawned = new ArrayList<>();
        for (CgStructure.Entity e : structure.entities()) {
            spawned.add(CgChildEntityComponent.of(scene, CgEntityRef.of(e.data())).at(x + e.x(), y + e.y(), z + e.z()).spawn());
        }
        return spawned;
    }

    /** {@code state} at {@code (x, y, z)}, its block entity's data with it; air clears it. */
    public CgPocketLevel setBlock(int x, int y, int z, CgBlockState state) {
        check();
        synchronized (blocks) {
            if (state.isAir()) blocks.remove(key(x, y, z));
            else blocks.put(key(x, y, z), state);
        }
        changes.add(new Change(x, y, z, state.isAir() ? null : state));
        changeCount++;
        return this;
    }

    /**
     * Shows only the blocks from {@code minY} to {@code maxY}, both included: a guidebook's layer by layer. What lies
     * outside is hidden from the views and from {@link #pick}, never removed; the sections it cuts are meshed again.
     */
    public CgPocketLevel visibleRange(int minY, int maxY) {
        check();
        if (minY > maxY) throw new IllegalArgumentException("visibleRange(" + minY + ", " + maxY + "): min above max");
        this.minY = minY;
        this.maxY = maxY;
        rangeChanged = true;
        changeCount++;
        return this;
    }

    /**
     * Runs its block entities' client half each client tick (Minecraft's client tickers): a chest's lid, a beacon's beam,
     * an enchanting table's book animate. Never a server's tick: nothing grows, burns down or is saved. Off by default.
     */
    public CgPocketLevel ticking(boolean on) {
        check();
        ticking = on;
        if (on) installTick();
        return this;
    }

    public boolean ticking() {
        return ticking;
    }

    /** Shows every block again. */
    public CgPocketLevel visibleRange() {
        return visibleRange(Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** The box holding every block set, whether shown or not; null while it holds none. */
    @Nullable
    public CgAABBc bounds() {
        synchronized (blocks) {
            if (blocks.isEmpty()) return null;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (long k : blocks.keySet()) {
                int x = (int) (k >> 38), z = (int) (k << 26 >> 38), y = (int) (k << 52 >> 52);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
            return CgAABB.of(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        }
    }

    /**
     * Its blocks as a structure, its corner at the blocks' least corner: what an editor saves. Blocks only: entities are
     * the scene's components. An empty level answers an empty 1 x 1 x 1 structure.
     *
     * <pre>{@code
     * CgNbtCompound saved = level.copy().toNbt();          // vanilla's structure file
     * level.place(CgStructure.fromNbt(saved), 0, 0, 0);    // and back
     * }</pre>
     */
    public CgStructure copy() {
        CgAABBc box = bounds();
        if (box == null) return CgStructure.builder(1, 1, 1).build();
        int x0 = (int) box.minX(), y0 = (int) box.minY(), z0 = (int) box.minZ();
        CgStructure.Builder b = CgStructure.builder((int) box.maxX() - x0, (int) box.maxY() - y0, (int) box.maxZ() - z0);
        synchronized (blocks) {
            for (Map.Entry<Long, CgBlockState> e : blocks.entrySet()) {
                long k = e.getKey();
                b.block((int) (k >> 38) - x0, (int) (k << 52 >> 52) - y0, (int) (k << 26 >> 38) - z0, e.getValue());
            }
        }
        return b.build();
    }

    /** What is at {@code (x, y, z)}: air where nothing was set. */
    public CgBlockState block(int x, int y, int z) {
        synchronized (blocks) {
            CgBlockState s = blocks.get(key(x, y, z));
            return s == null ? CgBlockState.AIR : s;
        }
    }

    /**
     * The first block a ray from {@code (ox, oy, oz)} along {@code (dx, dy, dz)} meets within {@code reach} blocks, by
     * Minecraft's own clip against outline shapes; null for none. Render thread: it takes the changes made so far.
     */
    @Nullable
    public Hit pick(double ox, double oy, double oz, double dx, double dy, double dz, double reach) {
        check();
        Host host = CgPlatform.get(HOST);
        synchronized (this) {
            applyChanges(host);
            return host.pick(level, ox, oy, oz, dx, dy, dz, reach);
        }
    }

    /** Whether {@link #close} has run: a closed level takes no changes, draws nothing and picks nothing. */
    public boolean closed() {
        return closed;
    }

    /** Frees Minecraft's copy and every view's target. Render thread. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        OPEN.remove(this);
        Host host = CgPlatform.get(HOST);
        if (level != null) {
            for (Object key : asks.keySet()) host.forget(level, key);
            host.close(level);
        }
        asks.clear();
        level = null;
    }

    @Override
    @Nullable
    public Drawn drawn(CgView view, int width, int height) {
        // Only this frame's: the scene is drawn over it, so last frame's would ghost
        Ask ask = ask(view, view, null, width, height);
        return ask != null && ask.drawn != null && ask.drawnFrame == CgFrameRing.frame() ? ask.drawn : null;
    }

    /** {@code picture}'s target at its size, the last drawn: nothing draws over a picture's, so it holds. */
    @Nullable
    Drawn drawnFor(CgLevelPicture picture, int width, int height) {
        Ask ask = ask(picture, null, picture, width, height);
        return ask != null && ask.drawn != null ? ask.drawn : null;
    }

    @Nullable
    private synchronized Ask ask(Object key, @Nullable CgView view, @Nullable CgLevelPicture picture, int width, int height) {
        if (closed) return null;
        Ask ask = asks.computeIfAbsent(key, k -> new Ask(view, picture));
        if (ask.width != width || ask.height != height) ask.drawn = null;
        ask.width = width;
        ask.height = height;
        ask.asked = CgFrameRing.frame();
        return ask;
    }

    /**
     * Host side, at the draw point, before the device section: every open level takes its changes and draws each view
     * that asked of it, from the view's camera as now set. Once a frame, however often the draw point runs.
     */
    public static void drawPoint(float partialTick) {
        if (OPEN.isEmpty()) return;
        Host host = CgPlatform.get(HOST);
        long frame = CgFrameRing.frame();
        // Drawn by the host's world pipelines, so in its world's convention, wherever the draw point falls
        boolean before = CgDepthConvention.hostReversed();
        CgDepthConvention.host(CgDepthConvention.worldReversed());
        try {
            for (CgPocketLevel level : OPEN) {
                try {
                    level.draw(host, frame, partialTick);
                } catch (RuntimeException e) {
                    LOGGER.warn("a pocket level failed to draw; it is closed", e);
                    level.close();
                }
            }
        } finally {
            CgDepthConvention.host(before);
        }
    }

    private synchronized void draw(Host host, long frame, float partialTick) {
        if (drawnFrame == frame) return;
        drawnFrame = frame;
        applyChanges(host);
        for (Iterator<Map.Entry<Object, Ask>> it = asks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Object, Ask> e = it.next();
            Ask ask = e.getValue();
            if (frame - ask.asked > FORGET_FRAMES) {
                host.forget(level, e.getKey());
                it.remove();
                continue;
            }
            // A view every frame; a picture on a change, or every frame while its block entities animate
            long stamp = ask.picture == null || ticking ? frame : changeCount * 31 + ask.picture.cameraStamp();
            if (ask.drawn != null && ask.drawnStamp == stamp) continue;
            CgCamera camera = ask.camera();
            hostProjection(camera.projection(), projection);
            Matrix4fc cameraView = camera.view();
            double ox = camera.originX(), oy = camera.originY(), oz = camera.originZ();
            CgView view = ask.view;
            Runnable components = view == null ? () -> { }
                    : () -> CgHostWorld.drawScene(view.scene(), ox, oy, oz, cameraView, partialTick, true);
            ask.drawn = host.draw(level, e.getKey(), ask.width, ask.height, cameraView, ox, oy, oz, projection,
                    partialTick, components);
            ask.drawnFrame = frame;
            ask.drawnStamp = stamp;
            if (ask.picture != null) ask.picture.drew();
        }
    }

    private static boolean tickInstalled;

    private static synchronized void installTick() {
        if (tickInstalled) return;
        tickInstalled = true;
        CgEngineLifecycle.onClientTick(() -> {
            if (OPEN.isEmpty()) return;
            Host host = CgPlatform.get(HOST);
            for (CgPocketLevel level : OPEN) {
                if (!level.ticking) continue;
                try {
                    synchronized (level) {
                        level.applyChanges(host);
                        host.tick(level.level);
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("a pocket level's tick failed; it ticks no more", e);
                    level.ticking = false;
                }
            }
        });
    }

    private void applyChanges(Host host) {
        if (level == null) level = host.create();
        if (rangeChanged) {
            rangeChanged = false;
            host.visibleRange(level, minY, maxY);
        }
        for (Change c; (c = changes.poll()) != null; ) host.setBlock(level, c.x(), c.y(), c.z(), c.state());
    }

    // The engine's reversed-Z projection in the host's convention: unchanged where it is reversed, else GL's -1..1
    private static void hostProjection(Matrix4fc engine, Matrix4f out) {
        out.set(engine);
        if (CgDepthConvention.hostReversed()) return;
        // z' = w - 2z: near (engine 1) to -1, far (engine 0) to 1
        out.m02(engine.m03() - 2f * engine.m02());
        out.m12(engine.m13() - 2f * engine.m12());
        out.m22(engine.m23() - 2f * engine.m22());
        out.m32(engine.m33() - 2f * engine.m32());
    }

    private void check() {
        if (closed) throw new IllegalStateException("the pocket level is closed");
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (long) y & 0xFFF;
    }
}
