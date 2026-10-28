package com.crystalgraphics.camera;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Which rig a context runs: Gameplay Cameras' camera directors ({@code Directors/}). The system asks the active
 * context's director each frame and blends to the rig it answers when that changes.
 *
 * <pre>{@code
 * context.director(CgCameraDirector.single(skillRig));          // SingleCameraDirector: always that rig
 *
 * CgCameraDirector.Priority modes = CgCameraDirector.priority(); // PriorityQueueCameraDirector
 * context.director(modes);
 * modes.add(aimRig, 10);                                        // the highest priority runs
 * modes.remove(aimRig);                                         // and the next one blends back in
 * }</pre>
 *
 * <ul>
 *   <li>A null answer means the context's own pose with no rig: the host context's, where nothing is asked of it.</li>
 *   <li>Asked on the frame thread; allocate nothing in {@link #rig()}.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgCameraDirector {

    /** The rig to run this frame, or null for the context's own pose. */
    @Nullable
    CgCameraRig rig();

    /** A director that always runs {@code rig}. */
    static CgCameraDirector single(@Nullable CgCameraRig rig) {
        return () -> rig;
    }

    /** A director of rigs by priority, the highest running; equal priorities, the latest added. */
    static Priority priority() {
        return new Priority();
    }

    /** {@code PriorityQueueCameraDirector}: rigs queued by priority. */
    final class Priority implements CgCameraDirector {
        private final List<CgCameraRig> rigs = new ArrayList<>();
        private final List<Integer> priorities = new ArrayList<>();

        public Priority add(CgCameraRig rig, int priority) {
            remove(rig);
            int at = 0;
            while (at < rigs.size() && priorities.get(at) <= priority) at++;
            rigs.add(at, rig);
            priorities.add(at, priority);
            return this;
        }

        public Priority remove(CgCameraRig rig) {
            int i = rigs.indexOf(rig);
            if (i >= 0) {
                rigs.remove(i);
                priorities.remove(i);
            }
            return this;
        }

        @Nullable
        @Override
        public CgCameraRig rig() {
            return rigs.isEmpty() ? null : rigs.get(rigs.size() - 1);
        }
    }
}
