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
import com.crystalgraphics.render.scene.component.CgMeshCollector;
import com.crystalgraphics.render.scene.component.CgDynamicPrimitive;
import com.crystalgraphics.render.scene.component.CgInstancedMeshComponent;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.scene.component.CgScenePrimitives;
import com.crystalgraphics.render.scene.component.CgSceneRoot;
import com.crystalgraphics.render.scene.component.CgSkeletalMeshComponent;
import com.crystalgraphics.render.scene.gpu.CgGpuScene;

import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.render.canvas.CgCanvasLabels;
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
 *       {@code anim()} and call {@link #update()} each frame.</li>
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

    /** The client world's scene. */
    public static CgScene client() {
        return CLIENT;
    }

    private final CgGpuScene gpu;
    private final CgScenePrimitives primitives;
    private final CgSceneRenderer renderer;

    public CgScene() {
        this("crystalgraphics.scene", null);
    }

    private CgScene(String name, @Nullable CgScenePrimitives.Driver driver) {
        gpu = new CgGpuScene(name);
        primitives = new CgScenePrimitives(gpu, driver);
        renderer = new CgSceneRenderer(this);
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

    /** This frame's placement: what rides another, then the skeletal meshes' palettes. The client's runs in {@code ATTACH}. */
    public void update() {
        primitives.update();
    }


    /** Removes every primitive: the level ended. */
    public void clear() {
        primitives.clear();
    }

    /**
     * The client scene's frame on the client world: placed in {@code ATTACH}, cleared when the level changes. Added with
     * its first skeletal mesh or attachment.
     */
    private static final class Ticks extends CgWorldSubsystem {

        private static final int UPDATE = CgTrace.name("scene.update");

        private CgTickFunction attach;
        private int levelEpoch;

        static void of(CgWorld world) {
            if (world.subsystem(Ticks.class) == null) world.add(new Ticks());
        }

        @Override
        protected void initialize() {
            levelEpoch = CgWorldQueries.client().levelEpoch();
            attach = world().schedule().add(CgTickGroup.ATTACH, "crystalgraphics:scene.attach", dt -> update())
                    .whilePaused(true);
        }

        @Override
        protected void deinitialize() {
            attach.close();
        }

        private void update() {
            int now = CgWorldQueries.client().levelEpoch();
            if (now != levelEpoch) {
                levelEpoch = now;
                CLIENT.clear();
            }
            try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, UPDATE)) {
                CLIENT.update();
            }
        }
    }
}
