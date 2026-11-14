package com.crystalgraphics.animation.graph;

import java.util.Objects;
import java.util.function.Consumer;

import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * A pose read in more than one place, evaluated once a frame: Unreal's Save Cached Pose and Use Cached Pose
 * ({@code FAnimNode_SaveCachedPose}, {@code FAnimNode_UseCachedPose}). The graph saves it by name
 * ({@link CgAnimGraph.Builder#save}); {@link #use} reads it.
 *
 * <pre>{@code
 * CgAnimGraph.builder("mymod:hero", skeleton)
 *         .save("body", CgSlotNode.of("DefaultSlot", locomotion))
 *         .output(CgLayeredBlendNode.of(CgCachedPoseNode.use("body"))
 *                 .layer(CgSlotNode.of("FacialSlot", CgCachedPoseNode.use("body")), CgBlendMask.branch("facialbase", 1)))
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>The saved pose's source is updated once, after the graph's walk, at the heaviest weight any use reached it at
 *       (AnimNode_SaveCachedPose.cpp, {@code PostGraphUpdate}).</li>
 *   <li>A use of a name the graph never saves fails when the graph builds.</li>
 * </ul>
 */
public final class CgCachedPoseNode {

    private CgCachedPoseNode() {
    }

    /** The pose saved as {@code name}. */
    public static Use use(String name) {
        return new Use(name);
    }

    /** A read of a cached pose. */
    public static final class Use extends CgAnimNode {

        private final String name;

        private Use(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public String name() {
            return name;
        }

        @Override
        protected Runtime instantiate(CgAnimGraphInstance g) {
            SaveRuntime save = g.save(name);
            return new Runtime() {
                @Override
                public void update(CgAnimGraphInstance g, float weight) {
                    save.request(weight);
                }

                @Override
                public void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
                    save.evaluate(g, out);
                }
            };
        }

        @Override
        protected void children(Consumer<CgAnimNode> each) {
        }

        @Override
        protected void write(CgAnimNodeWriter out) {
            out.kind("use_cached_pose").put("save", name);
        }

        @Override
        public String toString() {
            return "use(" + name + ")";
        }
    }

    /** A saved pose in one instance: its source, its heaviest use this frame, its pose once evaluated. */
    static final class SaveRuntime {

        final String name;
        private CgAnimNode.Runtime source;
        private CgAnimPose cached;
        private float weight;
        private long evaluated = -1;

        SaveRuntime(String name) {
            this.name = name;
        }

        void source(CgAnimNode.Runtime source) {
            this.source = source;
        }

        void begin() {
            weight = 0f;
        }

        void request(float weight) {
            this.weight = Math.max(this.weight, weight);
        }

        void flush(CgAnimGraphInstance g) {
            if (weight > CgAnimPose.ZERO_WEIGHT) source.update(g, weight);
        }

        void initialize(CgAnimGraphInstance g) {
            source.initialize(g);
        }

        void evaluate(CgAnimGraphInstance g, CgAnimPose out) {
            if (cached == null) cached = new CgAnimPose(g.skeleton());
            if (evaluated != g.frame()) {
                source.evaluate(g, cached);
                evaluated = g.frame();
            }
            out.set(cached);
        }
    }
}
