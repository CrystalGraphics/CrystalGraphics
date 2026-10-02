package com.crystalgraphics.api.mesh;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * One shape at falling detail: a draw takes the level for how tall it stands on screen. Unity's LODGroup -- a level
 * holds while the draw's bounds cover at least its screen height, a fraction of the screen's height, and below the
 * last level's the draw is culled.
 *
 * <pre>{@code
 * CgMeshLods rock = CgMeshLods.builder()
 *         .level(rockHigh, 0.3f)      // from 30% of the screen's height up
 *         .level(rockMid, 0.08f)
 *         .level(rockLow, 0.01f)      // under 1%, nothing
 *         .build();
 * world.draw(rock, stone).at(x, y, z).submit();
 *
 * world.draw(CgMeshShapes.sphereLods(), smoke).at(x, y, z).transform(scale).submit();   // shared, never culled
 * }</pre>
 *
 * <ul>
 *   <li>Screen heights descend level by level; a last level of 0 never culls.</li>
 *   <li>A draw is culled by the finest level's bounds, or the bounds it states, and measured by the same. Levels
 *       should share their bounds and, for a draw of a range, their submeshes.</li>
 *   <li>Immutable, and safe to share between draws and threads.</li>
 * </ul>
 */
public final class CgMeshLods {

    private final CgMesh[] levels;
    private final float[] heights;

    private CgMeshLods(CgMesh[] levels, float[] heights) {
        this.levels = levels;
        this.heights = heights;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The level for a draw covering {@code screenHeight} of the screen's height, or null when it is culled. */
    @Nullable
    public CgMesh pick(float screenHeight) {
        for (int i = 0; i < levels.length; i++) {
            if (screenHeight >= heights[i]) return levels[i];
        }
        return null;
    }

    /** The most detailed level: what a draw of these is culled by. */
    public CgMesh finest() {
        return levels[0];
    }

    public int levelCount() {
        return levels.length;
    }

    public CgMesh level(int i) {
        return levels[i];
    }

    /** The screen height level {@code i} holds down to. */
    public float screenHeight(int i) {
        return heights[i];
    }

    public static final class Builder {

        private final List<CgMesh> levels = new ArrayList<>();
        private final List<Float> heights = new ArrayList<>();

        private Builder() {
        }

        /** The next coarser level, used while a draw covers at least {@code screenHeight} of the screen's height. */
        public Builder level(CgMesh mesh, float screenHeight) {
            if (!heights.isEmpty() && screenHeight >= heights.get(heights.size() - 1)) {
                throw new IllegalArgumentException("screen heights must descend: " + screenHeight + " after "
                        + heights.get(heights.size() - 1));
            }
            if (screenHeight < 0f) throw new IllegalArgumentException("screen height " + screenHeight);
            levels.add(mesh);
            heights.add(screenHeight);
            return this;
        }

        public CgMeshLods build() {
            if (levels.isEmpty()) throw new IllegalStateException("no levels");
            float[] h = new float[heights.size()];
            for (int i = 0; i < h.length; i++) h[i] = heights.get(i);
            return new CgMeshLods(levels.toArray(new CgMesh[0]), h);
        }
    }
}
