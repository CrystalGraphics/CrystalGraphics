package com.crystalgraphics.mc.render.world;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.canvas.CgPicture;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.host.CgHostLevel;
import com.crystalgraphics.render.host.CgHostCopies;

/**
 * A pocket level drawn by Minecraft alone from a camera, as a picture any canvas shows: a guidebook's multiblock, a
 * recipe's structure, a thumbnail. No scene of yours over it, no lights of yours: what Minecraft draws. Drawn again only
 * when the level, the camera or the size changes, and every frame while the level ticks.
 *
 * <pre>{@code
 * CgLevelPicture tower = CgLevelPicture.of(level)
 *         .camera(CgCamera.perspective(40f, 1f, 0.05f, 100f).lookAt(-6, 9, -6, 2.5f, 3, 2.5f));
 * canvas.picture(tower, x, y, 160, 160);                  // each frame; drawn once, then as the level changes
 * tower.camera(orbit);                                    // a new camera: drawn again next frame
 * }</pre>
 *
 * <ul>
 *   <li>The camera is copied as it is given: change it by giving it again.</li>
 *   <li>Its first frame at a size shows nothing: the level is drawn at the next draw point and shown from the next
 *       frame on.</li>
 *   <li>Closing the level ends it: it shows nothing.</li>
 * </ul>
 */
public final class CgLevelPicture implements CgPicture {

    private final CgPocketLevel level;
    private final CgCamera camera = new CgCamera();
    private volatile long cameraStamp, version;

    private CgLevelPicture(CgPocketLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    public static CgLevelPicture of(CgPocketLevel level) {
        return new CgLevelPicture(level);
    }

    /** The camera it is drawn from, copied. */
    public CgLevelPicture camera(CgCamera camera) {
        synchronized (this.camera) {
            this.camera.set(camera);
        }
        cameraStamp++;
        return this;
    }

    @Override
    @Nullable
    public Image image(int deviceWidth, int deviceHeight, float scale) {
        CgHostLevel.Drawn drawn = level.drawnFor(this, Math.max(1, deviceWidth), Math.max(1, deviceHeight));
        // Minecraft's targets start at the bottom left
        return drawn == null ? null : Image.of(CgHostCopies.sampled(drawn.colour()), 0f, 1f, 1f, 0f);
    }

    @Override
    public long version() {
        return version;
    }

    CgCamera camera() {
        return camera;
    }

    long cameraStamp() {
        return cameraStamp;
    }

    // A new drawing landed: what a canvas showed of it is stale
    void drew() {
        version++;
    }
}
