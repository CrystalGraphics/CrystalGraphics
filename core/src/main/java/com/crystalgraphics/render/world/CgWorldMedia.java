package com.crystalgraphics.render.world;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.variable.CgVar;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Smoke and gas in the air as a froxel grid over the view, Frostbite's and Unreal's volumetric fog: what each injector
 * holds is added into it as extinction, then summed from the eye outward into what gets through to each froxel. Every
 * transparent world material reads it at its own depth ({@code cg_Media}, {@code cg_MediaTransmit}) and is dimmed by the
 * smoke in front of it, so a spark inside a cloud fades as it goes deeper. A volume drawing smoke draws its own look;
 * this only carries how much it hides.
 *
 * <pre>{@code
 * // an injector: one kernel over the grid, adding its extinction a block to what came before it
 * CgWorldRenderer.get().media().add((pass, media) -> {
 *     if (clouds.isEmpty()) return false;                         // nothing in the air: nothing added
 *     media.dispatch(pass, inject).texture("_Clouds", clouds).set("_Thickness", 2f);
 *     return true;
 * });
 * }</pre>
 *
 * <pre>{@code
 * // its kernel: declares PROPERTIES and the MEDIA_OUT image, adds to what came before
 * #pragma kernel Inject 4 4 4 image
 * #include "crystalgraphics:shaders/lib/media.glsl"
 * Properties { ...CgWorldMedia.PROPERTIES, copied... _Clouds (...) }
 * Images { MEDIA_OUT ("Extinction so far", r16f, writeonly, 3d) }
 * void Inject() {
 *     vec3 p = media_point(CG_TEXEL, MEDIA_OUT_SIZE(), _MediaRay00, _MediaRay10, _MediaRay01, _MediaRay11);
 *     MEDIA_OUT_WRITE(vec4(media_before(_MediaIn, _MediaFirst, CG_TEXEL) + cloud(p), 0.0, 0.0, 0.0));
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A froxel is {@code crystalgraphics.world.mediaPixels} pixels of the target across and one of {@link #SLICES} slices deep, spaced in eye
 *       depth from {@link #NEAR} to {@link #FAR} blocks as Unreal's volumetric fog spaces them: what
 *       {@code cg_env.glsl}'s {@code CG_MEDIA_*} reads by.</li>
 *   <li>Points are camera-relative, as the world renderer's are.</li>
 *   <li>Each injector reads what those before it added ({@code _MediaIn}, nothing for the first) and writes the sum:
 *       {@code media_before} does it.</li>
 *   <li>With no injector adding anything a frame records nothing, and {@code cg_Media} reads white.</li>
 * </ul>
 */
public final class CgWorldMedia {

    /** Unreal's r.VolumetricFog.GridPixelSize by scalability (BaseScalability.ini): Epic 8, High 16, Cinematic 4. */
    private static final CgVar.Int PIXELS = CgVar.integer("crystalgraphics.world.mediaPixels", 8, 4, 32, CgVar.DEV,
            "Pixels of the screen a froxel of the air's grid spans across: Unreal's Epic 8, High 16. 1.7 ms at 8 in 1080p.");
    /** Slices deep, and the eye depths they run between: as {@code cg_env.glsl}'s {@code CG_MEDIA_*}. */
    public static final int SLICES = 128;
    public static final float NEAR = 0.095f, FAR = 256f;
    /** What an injecting kernel declares, all of which {@link #dispatch} sets. */
    public static final String PROPERTIES = """
                _MediaIn    ("The extinction added before this kernel", sampler3D) = "black"
                _MediaFirst ("1 for the first kernel: nothing before it",  int)       = 1
                _MediaRay00 ("The view ray at the bottom left, a unit of eye depth", vec4) = (0, 0, -1, 0)
                _MediaRay10 ("At the bottom right",                    vec4)      = (0, 0, -1, 0)
                _MediaRay01 ("At the top left",                        vec4)      = (0, 0, -1, 0)
                _MediaRay11 ("At the top right",                       vec4)      = (0, 0, -1, 0)
            """;
    /** The image an injecting kernel writes its sum into. */
    public static final String OUT = "MEDIA_OUT";

    /**
     * What adds to the grid each firing that draws transparent world draws.
     *
     * <pre>{@code
     * media.add((pass, m) -> clouds.inject(pass, m));          // uniforms alone
     * media.add(new CgWorldMedia.Injector() {                   // rows of its own, placed from this firing's eye
     *     public void upload(CgRecording recording, CgWorldMedia m) { recording.update(rows, 0, from(m.cameraX())); }
     *     public boolean inject(CgComputePass pass, CgWorldMedia m) { m.dispatch(pass, kernel).bind("ROWS", rows); return true; }
     * });
     * }</pre>
     */
    @FunctionalInterface
    public interface Injector {
        /** Before the firing's pass opens, its eye known: uploads what {@link #inject} binds. */
        default void upload(CgRecording recording, CgWorldMedia media) {
        }

        /** Adds what it holds in the air: {@link #dispatch} once per kernel; false when it adds nothing this firing. */
        boolean inject(CgComputePass pass, CgWorldMedia media);
    }

    private static final String KERNELS = "crystalgraphics:shaders/env/compute/media.compute";
    /** The GPU's three steps, each a pass of its own: the injectors, the world's lights lighting it, the sum. */
    private static final int GPU_INJECT = CgGpuTrace.name("world.mediaInject"), GPU_SCATTER = CgGpuTrace.name("world.mediaScatter"),
            GPU_INTEGRATE = CgGpuTrace.name("world.mediaIntegrate");
    private static final int ZONE_RECORD = CgTrace.name("world.media.record");
    private static final CgFrameBufferFormat R16F = CgFrameBufferFormat.builder("cg_world_media.r16f")
            .color(0, CgTextureType.R16F).build();
    /** What reaches the eye: the light scattered toward it, then what gets through. */
    private static final CgFrameBufferFormat TRANSMIT = CgFrameBufferFormat.builder("cg_world_media.transmit")
            .color(0, CgTextureType.RGBA16F).build();
    static final String LIGHT_SHADER = "crystalgraphics:shaders/world_media_light.shader";
    private static final int GPU_LIGHT = CgGpuTrace.name("world.mediaLight");
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);

    private final List<Injector> injectors = new CopyOnWriteArrayList<>();
    private final Matrix4f unproject = new Matrix4f();
    /** The last firing's renderer-relative world to clip, and that space's origin; this firing's eye to it. */
    private final Matrix4f lastToClip = new Matrix4f(), reproject = new Matrix4f();
    private double lastX, lastY, lastZ;
    private boolean hadLast, reprojects;
    /** Firings recorded, with or without anything in the air. */
    private long firings;
    private final Vector4f corner = new Vector4f();
    private final Vector3f ray = new Vector3f(), eye = new Vector3f();
    private final float[] rays = new float[12];
    @Nullable
    private CgGraphTexture a, b, transmit, current;
    private int width, height;
    private double cameraX, cameraY, cameraZ;
    private boolean first;
    @Nullable
    private CgKernel integrate;
    /** Whether integrate is IntegrateColumns, a thread a column, or Integrate, a thread a froxel (no compute). */
    private boolean byColumn;
    /** Whether this firing's air was lit: the composite has light to add. */
    private boolean lit;
    private CgMaterial light;

    CgWorldMedia() {
    }

    /** Adds {@code injector}, from any thread; a frame recording now may miss it. */
    public void add(Injector injector) {
        injectors.add(injector);
    }

    public void remove(Injector injector) {
        injectors.remove(injector);
    }

    /** Whether any injector is added: the air may hold something to dim or to light. */
    boolean holdsAny() {
        return !injectors.isEmpty();
    }

    /** Where the firing's eye is, absolute: what an injector subtracts to place what it holds as froxels are placed. */
    public double cameraX() {
        return cameraX;
    }

    public double cameraY() {
        return cameraY;
    }

    public double cameraZ() {
        return cameraZ;
    }

    /** Froxels across and up this firing. */
    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Which firing this is, counted with or without anything in the air: a history kept across firings checks it. */
    long firing() {
        return firings;
    }

    /**
     * This firing's eye-relative points to the last firing's clip space, for a history of the grid read through
     * {@code media.glsl}'s {@code media_uv}; null at the first firing, after one with nothing in the air, or a resize.
     */
    @Nullable
    Matrix4fc reproject() {
        return reprojects ? reproject : null;
    }

    /** The view's corner rays onto {@code d}: {@code media_point}'s, as {@link #dispatch} sets them. */
    public CgDispatch rays(CgDispatch d) {
        return d.set("_MediaRay00", rays[0], rays[1], rays[2], 0f).set("_MediaRay10", rays[3], rays[4], rays[5], 0f)
                .set("_MediaRay01", rays[6], rays[7], rays[8], 0f).set("_MediaRay11", rays[9], rays[10], rays[11], 0f);
    }

    /**
     * {@code kernel} over every froxel, reading what came before it and writing its sum into {@link #OUT}: what an
     * injector calls. Bind the kernel's own inputs on what it answers.
     */
    public CgDispatch dispatch(CgComputePass pass, CgKernel kernel) {
        CgGraphTexture into = current == a ? b : a, before = current != null ? current : b;
        CgDispatch d = rays(pass.dispatch(kernel, width, height, SLICES).image(OUT, into).texture("_MediaIn", before)
                .set("_MediaFirst", first ? 1 : 0));
        current = into;
        first = false;
        return d;
    }

    /**
     * Each injector's extinction under {@code view}, summed into what gets through to each froxel: the texture to bind
     * at {@code CgBindingPoints.MEDIA_TEXTURE_UNIT}, or null when nothing added any.
     */
    @Nullable
    CgGraphTexture record(CgRecording recording, CgPassConstants stage, CgHostView view, Matrix4fc toWorld,
                          float targetWidth, float targetHeight, CgWorldLights lights) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, ZONE_RECORD)) {
            lit = false;
            firings++;
            if (injectors.isEmpty()) {
                hadLast = false;
                return null;
            }
            int tile = PIXELS.get();
            int w = Math.max(1, (int) Math.ceil(targetWidth / tile)), h = Math.max(1, (int) Math.ceil(targetHeight / tile));
            boolean resized = a == null || w != width || h != height;
            if (resized) {
                width = w;
                height = h;
                a = CgGraphTexture.transientTexture("cg_world_media_a", CgTextureDesc.volume(w, h, SLICES, R16F));
                b = CgGraphTexture.transientTexture("cg_world_media_b", CgTextureDesc.volume(w, h, SLICES, R16F));
                transmit = CgGraphTexture.transientTexture("cg_world_media", CgTextureDesc.volume(w, h, SLICES, TRANSMIT));
            }
            corners(view.projection(), toWorld);
            // The eye in the renderer's camera-relative space, which a host may keep off its origin (the harness's view
            // holds its own translation): froxels are placed from it, so what an injector places goes from it too.
            toWorld.transformPosition(eye.set(0f, 0f, 0f));
            cameraX = view.x() + eye.x;
            cameraY = view.y() + eye.y;
            cameraZ = view.z() + eye.z;
            reprojects = hadLast && !resized;
            if (reprojects) {
                reproject.set(lastToClip).translate((float) (cameraX - lastX), (float) (cameraY - lastY), (float) (cameraZ - lastZ));
            }
            toWorld.invert(lastToClip).mulLocal(view.projection());
            lastX = view.x();
            lastY = view.y();
            lastZ = view.z();
            hadLast = true;
            if (integrate == null) {
                CgKernel columns = CgCompute.load(KERNELS).kernel("IntegrateColumns");
                byColumn = columns.runs();
                integrate = byColumn ? columns : CgCompute.load(KERNELS).kernel("Integrate");
                integrate.prepare();
            }
            current = null;
            first = true;
            for (Injector injector : injectors) injector.upload(recording, this);
            CgComputePass pass = recording.compute("world.media", stage).timed(GPU_INJECT);
            boolean added = false;
            for (Injector injector : injectors) added |= injector.inject(pass, this);
            pass.end();
            if (added && current != null) {
                CgComputePass scatter = recording.compute("world.mediaScatter", stage).timed(GPU_SCATTER);
                CgGraphTexture scattered = lights.scatter(recording, scatter, this, current);
                scatter.end();
                lit = scattered != null;
                CgComputePass sum = recording.compute("world.mediaIntegrate", stage).timed(GPU_INTEGRATE);
                sum.dispatch(integrate, width, height, byColumn ? 1 : SLICES).texture("_Media", current).image("MEDIA_TRANSMIT", transmit)
                        .texture("_Scatter", lit ? scattered : CgFallbackTextures.BLACK_VOLUME);
                sum.end();
            }
            return added && current != null ? transmit : null;
        }
    }

    /**
     * The light the lit air scattered toward the eye, added over the opaque scene before the transparent passes
     * ({@code world_media_light.shader}): after {@link #record} answered {@code media}, when the air was lit.
     */
    void composite(CgStageFrame stage, CgRecording recording, CgGraphTexture media) {
        if (!lit) return;
        if (light == null) light = CgMaterial.newInstance(LIGHT_SHADER);
        if (!light.prepare()) return;
        CgPipeline pipeline = light.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        CgRasterPass pass = recording.raster(stage.target(), CgLoad.load(), stage.constants(), null, CgOrder.SORTED)
                .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT).texture(CgBindingPoints.MEDIA_TEXTURE_UNIT, media)
                .timed(GPU_LIGHT);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, light.captureBindings(recording.bindings()), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
    }

    /** The view ray through each corner of the target, camera-relative, scaled to a unit of eye depth. */
    private void corners(Matrix4fc projection, Matrix4fc toWorld) {
        projection.invert(unproject);
        for (int c = 0; c < 4; c++) {
            unproject.transform(corner.set((c & 1) == 0 ? -1f : 1f, (c & 2) == 0 ? -1f : 1f, 0.5f, 1f));
            ray.set(corner.x, corner.y, corner.z).div(corner.w);
            ray.div(-ray.z);
            toWorld.transformDirection(ray);
            rays[c * 3] = ray.x;
            rays[c * 3 + 1] = ray.y;
            rays[c * 3 + 2] = ray.z;
        }
    }
}
