package com.crystalgraphics.render.scene;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.CgAnimation;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.frame.CgWorldSubsystem;

import com.crystalgraphics.render.scene.component.CgDecalComponent;
import com.crystalgraphics.render.scene.component.CgDynamicComponent;
import com.crystalgraphics.render.scene.component.CgHazeComponent;
import com.crystalgraphics.render.scene.component.CgPostVolume;
import com.crystalgraphics.render.scene.component.CgColourRegion;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.scene.component.CgHeightFogComponent;
import com.crystalgraphics.render.scene.component.CgMeshCollector;
import com.crystalgraphics.render.scene.component.CgDynamicPrimitive;
import com.crystalgraphics.render.scene.component.CgInstancedMeshComponent;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import com.crystalgraphics.render.scene.component.CgDynamicLights;
import com.crystalgraphics.render.scene.renderer.feature.light.CgLightCollector;
import com.crystalgraphics.render.scene.component.CgPointLight;
import com.crystalgraphics.render.scene.component.CgSpotLight;
import com.crystalgraphics.render.scene.component.CgTubeLight;
import com.crystalgraphics.render.scene.component.CgSceneCaptureComponent;
import com.crystalgraphics.render.scene.component.CgScenePrimitives;
import com.crystalgraphics.render.scene.component.CgSceneRoot;
import com.crystalgraphics.render.scene.component.CgSkeletalMeshComponent;
import com.crystalgraphics.render.scene.gpu.CgGpuScene;
import com.crystalgraphics.render.scene.prefab.CgPrefab;
import com.crystalgraphics.render.scene.prefab.CgPrefabInstance;

import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.render.canvas.CgCanvasLabels;
import com.crystalgraphics.render.host.CgHostEnvironment;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.scene.renderer.CgSceneRenderer;
import com.crystalgraphics.render.scene.renderer.feature.light.CgSceneLights;
import com.crystalgraphics.render.scene.renderer.feature.media.CgSceneMedia;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.world.CgWorldQueries;

import javax.annotation.Nullable;

/**
 * A world of primitives kept until removed, Unreal's {@code FScene}: meshes, instanced meshes, skeletal meshes and
 * dynamic primitives, each a component made here, set up and spawned. Its {@link CgGpuScene} holds every persistent
 * primitive's record on the GPU; each frame, every view that draws it takes what it sees once, as its
 * {@code CgViewVisibility}, without a submit.
 *
 * <pre>{@code
 * CgScene scene = CgScene.client();                  // the client world's; cleared with the level
 * CgScene preview = new CgScene();                   // one of your own: a preview, an editor, a test
 *
 * CgMeshComponent rock = scene.mesh(rockMesh, stone).at(x, y, z).spawn();
 * CgSkeletalMeshComponent goku = scene.skeletal(gokuMesh).materials(skins::get).at(x, y, z).spawn();
 * CgInstancedMeshComponent grass = scene.instanced(blade, grassMaterial).spawn();
 * CgDynamicComponent trail = scene.dynamic(new Trail()).spawn();
 * CgDecalComponent crack = scene.decal(crackMaterial).at(x, y, z).size(0.5f, 2f, 2f).spawn();   // projected onto the ground
 * scene.drawOnce(marker, debugMaterial, x, y, z);    // this frame only: Unreal's debug draw
 * scene.label("Attract + Orbit").at(x, y + 6, z).height(0.5f).font(font).submit();   // this frame's name tag
 * }</pre>
 *
 * <ul>
 *   <li>The frame thread's: its components are made, changed and gathered there.</li>
 *   <li>A component is a handle: keep it to change or remove what it made.</li>
 *   <li>The client's scene poses its skeletal meshes with the client world's {@link CgAnimation} and places what rides
 *       another in {@code ATTACH}. A scene of your own does neither by itself: evaluate each skeletal mesh's
 *       {@code anim()} and call {@link #tick(double)} each frame.</li>
 *   <li>A scene has its own time and sky, as an Unreal world does: the client's are the game's; one of your own runs
 *       on {@link #clock()} and is lit under {@link #environment()}, both its owner's to set.</li>
 * </ul>
 */
public final class CgScene {

    private static final CgScene CLIENT = new CgScene("crystalgraphics.scene.client", new CgScenePrimitives.Driver() {
        @Override
        public void started() {
            Ticks.of(CgWorld.client());
        }

        @Override
        public void pose(CgAnimInstance anim, boolean on) {
            if (on) CgAnimation.client().add(anim);
            else CgAnimation.client().remove(anim);
        }
    });

    private static final CgScene SERVER = new CgScene("crystalgraphics.scene.server", new CgScenePrimitives.Driver() {
        @Override
        public void started() {
            ServerTicks.of(CgWorld.serverWorld());
        }

        @Override
        public void pose(CgAnimInstance anim, boolean on) {
            if (on) CgAnimation.server().add(anim);
            else CgAnimation.server().remove(anim);
        }
    });

    /** The client world's scene. */
    public static CgScene client() {
        return CLIENT;
    }

    /**
     * The server world's scene: never drawn. Its skeletal meshes are posed by the server world's animation each tick,
     * for their bones, sockets and root motion: what an entity actor builds on a server.
     */
    public static CgScene server() {
        return SERVER;
    }

    private final CgGpuScene gpu;
    private final CgScenePrimitives primitives;
    private final CgSceneRenderer renderer;
    private final CgSceneClock clock;
    private final CgHostEnvironment environment = new CgHostEnvironment();

    public CgScene() {
        this("crystalgraphics.scene", null);
    }

    private CgScene(String name, @Nullable CgScenePrimitives.Driver driver) {
        gpu = new CgGpuScene(name);
        primitives = new CgScenePrimitives(gpu, driver);
        renderer = new CgSceneRenderer(this);
        clock = new CgSceneClock(driver != null);
    }

    /** Its time: what its views' {@code CG_TIME} reads. The client scene's is the game's. */
    public CgSceneClock clock() {
        return clock;
    }

    /**
     * The sky, sun, fog and settings its views draw under: the client scene's is its host's, refilled each frame; one of
     * your own starts absent (no sun, no fog) and is its owner's to fill, from the game's or a sky of its own:
     * {@code lab.environment().set(CgView.main().environment())}.
     */
    public CgHostEnvironment environment() {
        return this == CLIENT ? CgStageHost.frame(CgStageHost.WORLD_OPAQUE).environment() : environment;
    }

    /** What draws it through a view: engine side. */
    public CgSceneRenderer renderer() {
        return renderer;
    }

    /** Its lights: point, spot and tube lights, and their shadows. */
    public CgSceneLights lights() {
        return renderer.lights();
    }

    /** What is in its air: smoke, haze ({@code media().add(injector)}). */
    public CgSceneMedia media() {
        return renderer.media();
    }

    /**
     * A label this frame: {@code text} at a point, facing the camera unless turned, depth-tested and drawn after the
     * translucent draws, as a name tag. Place it on the label, then {@code font(...)} or {@code family(...)} gives its
     * text draw; {@code submit()} that. Render thread.
     *
     * <pre>{@code
     * scene.label("12").at(x, y, z).anchor(0.5f, 0f).font(font).color(0xFFFF5040).style(DAMAGE).submit();
     * scene.label(sign).at(x, y, z).rotation(facingSouth).family(family).targetPx(48).constraints(400f, 0f).submit();
     * }</pre>
     *
     * <ul>
     *   <li>{@code height} is a line's height in blocks; its raster tier follows how tall it stands on screen.</li>
     *   <li>Unlit, unfogged, writing no depth: labels behind a nearer one show through it.</li>
     *   <li>It lives one frame: submit it each frame it shows.</li>
     * </ul>
     */
    public CgCanvasLabels.Label label(String text) {
        CgCanvasLabels.Label label = renderer.labels().text();
        label.draw().text(text);
        return label;
    }

    /** As {@link #label(String)}, of a shaped paragraph, wrapped by the draw's {@code constraints}. */
    public CgCanvasLabels.Label label(CgShapedParagraph paragraph) {
        CgCanvasLabels.Label label = renderer.labels().text();
        label.draw().paragraph(paragraph);
        return label;
    }

    /** As {@link #label(String)}, of a finished layout: its fonts are its own. */
    public CgCanvasLabels.Label label(CgTextLayout layout) {
        CgCanvasLabels.Label label = renderer.labels().text();
        label.draw().layout(layout);
        return label;
    }

    /** This frame's labels: for a caller queuing many, or reading what is queued. */
    public CgCanvasLabels labels() {
        return renderer.labels();
    }

    /** Its records on the GPU. */
    public CgGpuScene gpu() {
        return gpu;
    }

    /** Its primitives: what spawns into it and what a view gathers. */
    public CgScenePrimitives primitives() {
        return primitives;
    }

    /** A mesh, every submesh drawn with {@code material} until {@code material(slot, ...)} says otherwise. */
    public CgMeshComponent mesh(CgMesh mesh, CgMaterialInterface material) {
        return primitives.mesh(mesh, material);
    }

    /** A mesh with levels of detail, each view drawing the level its screen size picks. */
    public CgMeshComponent mesh(CgMeshLods lods, CgMaterialInterface material) {
        return primitives.mesh(lods, material);
    }

    /** A skinned mesh posed by an animation instance of its own: give each slot a material before it spawns. */
    public CgSkeletalMeshComponent skeletal(CgSkeletalMesh mesh) {
        return primitives.skeletal(mesh);
    }

    /** A place that draws nothing, for others to attach to: an actor's root. */
    public CgSceneRoot root() {
        return primitives.root();
    }

    /**
     * {@code prefab} as components of this scene, unspawned: a root a node and a mesh component a part. Place or
     * attach it, then {@code spawn()}.
     *
     * <pre>{@code
     * CgPrefabInstance lantern = scene.spawn(LANTERN).at(x, y, z).spawn();
     * }</pre>
     */
    public CgPrefabInstance spawn(CgPrefab prefab) {
        return new CgPrefabInstance(this, prefab);
    }

    /** Many of one mesh, each instance its own record. */
    public CgInstancedMeshComponent instanced(CgMesh mesh, CgMaterialInterface material) {
        return primitives.instanced(mesh, material);
    }

    /** A {@code #domain decal} material projected onto what is inside a box, faded over its life. */
    public CgDecalComponent decal(CgMaterialInterface material) {
        return primitives.decal(material);
    }

    /** Geometry asked for per view each frame. */
    public CgDynamicComponent dynamic(CgDynamicPrimitive primitive) {
        return primitives.dynamic(primitive);
    }

    /**
     * What {@link #dynamicLights} and {@link #gpuLights} answer: closed, the source is collected no more.
     *
     * <pre>{@code
     * CgScene.Registration embers = scene.dynamicLights(source);
     * embers.close();
     * }</pre>
     */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /** A light shining every way from a point: Unreal's point light. */
    public CgPointLight pointLight() {
        return primitives.pointLight();
    }

    /** A light limited to a cone along its +x: Unreal's spot light. */
    public CgSpotLight spotLight() {
        return primitives.spotLight();
    }

    /** A light coming from a line along its +x: Unreal's point light with a source length. */
    public CgTubeLight tubeLight() {
        return primitives.tubeLight();
    }

    /** A box of haze its lights glow in and cut shafts through: Unreal's local fog volume in role. */
    public CgHazeComponent haze() {
        return primitives.haze();
    }

    /** Fog thickest at and below the height it stands at: Unreal's exponential height fog. */
    public CgHeightFogComponent heightFog() {
        return primitives.heightFog();
    }

    /** A grade inside a shape where it stands: Unreal's colour correct region. */
    public CgColourRegion colourRegion() {
        return primitives.colourRegion();
    }

    /** A look over this scene's views, everywhere or round where it stands: Unreal's post process volume. */
    public CgPostVolume postVolume(CgPostSettings settings) {
        return primitives.postVolume(settings);
    }

    /**
     * Many short-lived lights, collected from {@code source} each frame until closed: sparks, embers, a demo's moving
     * lamps. Unreal's simple lights.
     *
     * <pre>{@code
     * CgScene.Registration on = scene.dynamicLights((view, out) -> out.point(x, y, z, 2f, 0xFFFF8030, 6f));
     * }</pre>
     */
    public Registration dynamicLights(CgDynamicLights source) {
        CgSceneLights lights = renderer.lights();
        lights.add(source);
        return () -> lights.remove(source);
    }

    /** A kernel's lights, appended into the frame's list each transparent firing until closed: a light per particle. */
    public Registration gpuLights(CgSceneLights.Appender appender) {
        CgSceneLights lights = renderer.lights();
        lights.add(appender);
        return () -> lights.remove(appender);
    }

    /** A camera drawing this scene into a render target a material samples: a mirror, a portal, a monitor. */
    public CgSceneCaptureComponent capture() {
        return primitives.capture(this);
    }

    /** {@code mesh} drawn in this frame only, at an absolute position: a prototype or a debug shape. */
    public void drawOnce(CgMesh mesh, CgMaterialInterface material, double x, double y, double z) {
        primitives.drawOnce(mesh, material, x, y, z);
    }

    /**
     * Rows drawn in this frame only (the ring frame they are added in), by every view of the scene that draws after,
     * with a row's every setting: debug geometry, a tool's preview, a mesh rebuilt each frame. Render thread; for what
     * draws every frame, a primitive or {@link #dynamic} is cheaper.
     *
     * <pre>{@code
     * scene.once().mesh(outline, lineMaterial).at(x, y, z).layer(CgSortLayer.OVERLAY).add();
     * scene.once().mesh(faces, fillMaterial).at(x, y, z).sortAt(eyeX, eyeY, eyeZ).sortPriority(1).add();
     * }</pre>
     *
     * <ul>
     *   <li>End each row with {@code add()}: a row left unfinished is dropped by the next {@code mesh}.</li>
     * </ul>
     */
    public CgMeshCollector once() {
        return primitives.once();
    }

    /**
     * Lights shining in this frame only (the ring frame they are added in), Unreal's simple lights: a demo's lamps
     * moved each frame, an effect's flash. For a light that stays, a {@link #pointLight()} component is cheaper to
     * keep; for many a frame from one owner, {@link #dynamicLights}.
     *
     * <pre>{@code
     * scene.onceLights().point(x, y + 2, z, 8f, 0xFFFFB060, 30f);
     * scene.onceLights().point(x, y + 6, z, 12f, 0xFFFFFFFF, 300f).spot(0f, -1f, 0f, 20f, 35f).castsShadow(false);
     * }</pre>
     *
     * <ul>
     *   <li>Render thread, before the frame's first world firing: one added after it shines from the next view on.</li>
     * </ul>
     */
    public CgLightCollector onceLights() {
        return renderer.lights().frame();
    }

    /**
     * A scene of your own's frame: its clock advances by {@code seconds} (scaled, or held while paused), then
     * {@link #update()}. The client scene's clock is the game's: it refuses this.
     */
    public void tick(double seconds) {
        clock.advance(seconds);
        primitives.update();
    }

    /**
     * Places what rides another and writes the skeletal meshes' palettes now, its time unmoved: the client scene's runs
     * in {@code ATTACH} on its world; a tool driving the client scene without a world calls it by hand.
     */
    public void update() {
        primitives.update();
    }


    /**
     * The nearest visible primitive whose world box the ray from {@code (ox, oy, oz)} along {@code (dx, dy, dz)} meets,
     * absolute; null for none. {@code CgView.pick(x, y)} casts it through a pixel.
     */
    @Nullable
    public CgPrimitiveComponent<?> pick(double ox, double oy, double oz, double dx, double dy, double dz) {
        return primitives.pick(ox, oy, oz, dx, dy, dz);
    }

    /** Removes every primitive: the level ended. */
    public void clear() {
        primitives.clear();
    }

    /** The client level the client scene was last cleared for; {@code Integer.MIN_VALUE} before it first looked. */
    private static int clientLevel = Integer.MIN_VALUE;

    /**
     * Clears the client scene if the client's level changed since it last looked, and answers whether it did. The
     * scene's frame does it in {@code ATTACH}; whatever spawns into a new level's scene earlier (an entity's actor, as
     * the entity joins) calls it first, so that clear never removes what was just spawned. The client's thread.
     *
     * <pre>{@code
     * CgScene.syncClientLevel();                       // the old level's components go now, not after ours
     * body = CgScene.client().skeletal(mesh).spawn();
     * }</pre>
     */
    public static boolean syncClientLevel() {
        int now = CgWorldQueries.client().levelEpoch();
        if (clientLevel == Integer.MIN_VALUE) clientLevel = now;
        if (now == clientLevel) return false;
        clientLevel = now;
        CLIENT.clear();
        return true;
    }

    /**
     * The client scene's frame on the client world: placed in {@code ATTACH}, cleared when the level changes. Added with
     * its first skeletal mesh or attachment.
     */
    private static final class Ticks extends CgWorldSubsystem {

        private static final int UPDATE = CgTrace.name("scene.update");

        private CgTickFunction attach;

        static void of(CgWorld world) {
            if (world.subsystem(Ticks.class) == null) world.add(new Ticks());
        }

        @Override
        protected void initialize() {
            syncClientLevel();
            attach = world().schedule().add(CgTickGroup.ATTACH, "crystalgraphics:scene.attach", dt -> update())
                    .whilePaused(true);
        }

        @Override
        protected void deinitialize() {
            attach.close();
        }

        private void update() {
            syncClientLevel();
            try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, UPDATE)) {
                CLIENT.primitives.update();
            }
        }
    }

    /**
     * The server scene's placement on the server world, each tick in {@code GAMEPLAY}: after the actors stand their
     * roots at their entities ({@code crystalgraphics:actors} sorts first), before {@code AUTHORITY}'s sequences move
     * their montages from there. Added with its first skeletal mesh or attachment.
     */
    private static final class ServerTicks extends CgWorldSubsystem {

        private CgTickFunction place;

        static void of(CgWorld world) {
            if (world.subsystem(ServerTicks.class) == null) world.add(new ServerTicks());
        }

        @Override
        protected void initialize() {
            place = world().schedule().add(CgTickGroup.GAMEPLAY, "crystalgraphics:scene.server", dt -> SERVER.primitives.update())
                    .onServer(true);
        }

        @Override
        protected void deinitialize() {
            place.close();
        }
    }
}
