package com.crystalgraphics.render.view;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.api.canvas.CgPicture;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgTexture2D;

/**
 * A view as a picture any canvas shows, from any thread: an editor's viewport, a preview or a minimap in a UI that
 * records off the render thread. Its scene is drawn through its camera at the draw point, at the size the canvas last
 * showed it, and the canvas places the latest drawing.
 *
 * <pre>{@code
 * CgViewPicture viewport = CgViewPicture.of(CgView.of(scene)).background(0xFF3D3D3D);
 * viewport.camera(CgCamera.perspective(60f, w / (float) h, 0.05f, 200f).lookAt(-8, 6, -8, 0, 0, 0));
 * canvas.picture(viewport, x, y, w, h);                  // each frame, on the UI's own thread
 * }</pre>
 *
 * <pre>{@code
 * viewport.beforeDraw(() -> scene.tick(dt));             // render thread, once a drawing: a scene of your own's clock
 * }</pre>
 *
 * <ul>
 *   <li>The camera is copied as it is given; give it again to move it. Never set the view's own camera from another
 *       thread: the draw point copies this one into it.</li>
 *   <li>Its first frame at a size shows nothing: the view is drawn at the next draw point and shown from the next frame
 *       on. {@link #version} moves with each drawing, every frame while it is shown.</li>
 *   <li>Unshown for a few frames, its target is freed; shown again, it is drawn again.</li>
 *   <li>{@code canvas.view} draws a view at once instead, on the render thread only.</li>
 * </ul>
 */
public final class CgViewPicture implements CgPicture {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    /** Frames a picture may go unshown before its target is freed. */
    private static final int FORGET_FRAMES = 4;
    private static final List<CgViewPicture> SHOWN = new CopyOnWriteArrayList<>();

    private final CgView view;
    private final CgCamera camera = new CgCamera();
    private volatile int background = 0xFF000000;
    @Nullable
    private volatile Runnable beforeDraw;
    /** The size last asked, packed (width high, height low), and the frame it was asked in. */
    private volatile long size;
    private volatile long asked = -1;
    private volatile boolean listed;
    private volatile long version;
    @Nullable
    private volatile Image image;
    @Nullable
    private CgTexture2D imageTexture;
    private long drawnFrame = -1;

    private CgViewPicture(CgView view) {
        this.view = Objects.requireNonNull(view, "view");
    }

    /** {@code view} as a picture; not the main view, which its host draws. */
    public static CgViewPicture of(CgView view) {
        if (view == CgView.main()) throw new IllegalArgumentException("the main view is drawn by its host");
        return new CgViewPicture(view);
    }

    public CgView view() {
        return view;
    }

    /** The camera it is drawn from, copied. */
    public CgViewPicture camera(CgCamera camera) {
        synchronized (this.camera) {
            this.camera.set(camera);
        }
        return this;
    }

    /** What shows where the scene draws nothing, ARGB; opaque black by default. */
    public CgViewPicture background(int argb) {
        background = argb;
        return this;
    }

    /** Runs on the render thread before each drawing; null for none. */
    public CgViewPicture beforeDraw(@Nullable Runnable work) {
        beforeDraw = work;
        return this;
    }

    @Override
    @Nullable
    public Image image(int deviceWidth, int deviceHeight, float scale) {
        size = (long) Math.max(1, deviceWidth) << 32 | Math.max(1, deviceHeight);
        asked = CgFrameRing.frame();
        if (!listed) {
            synchronized (this) {
                if (!listed) {
                    listed = true;
                    SHOWN.add(this);
                }
            }
        }
        return image;
    }

    @Override
    public long version() {
        return version;
    }

    /**
     * Host side, at the draw point after the host levels drew: each picture shown in the last few frames drawn at its
     * size. Render thread, outside a device section.
     */
    public static void drawPoint() {
        if (SHOWN.isEmpty()) return;
        long frame = CgFrameRing.frame();
        for (CgViewPicture picture : SHOWN) {
            if (frame - picture.asked > FORGET_FRAMES) {
                picture.forget();
                continue;
            }
            if (picture.drawnFrame == frame) continue;
            picture.drawnFrame = frame;
            try {
                picture.draw();
            } catch (RuntimeException e) {
                LOGGER.warn("a view picture failed to draw; it shows nothing", e);
                picture.forget();
            }
        }
    }

    private void draw() {
        Runnable before = beforeDraw;
        if (before != null) before.run();
        synchronized (camera) {
            view.camera().set(camera);
        }
        long s = size;
        CgTexture2D texture = view.renderPicture((int) (s >>> 32), (int) s, background);
        // Replaced only when the texture is: a new size or the host level's target
        if (texture != imageTexture) {
            imageTexture = texture;
            image = Image.of(texture, 0f, 1f, 1f, 0f);
        }
        version++;
    }

    private synchronized void forget() {
        SHOWN.remove(this);
        listed = false;
        image = null;
        imageTexture = null;
        view.releasePicture();
    }
}
