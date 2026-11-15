package com.crystalgraphics.animation.rig;

import com.crystalgraphics.api.animation.CgSkeleton;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rig adapters by name: {@link #CRYSTALGRAPHICS} and {@link #GECKOLIB} ship, and a library adds its own.
 *
 * <pre>{@code
 * CgRigAdapter a = CgRigAdapters.get("geckolib");
 * CgRigAdapters.register(new MyLibraryAdapter());
 * }</pre>
 */
public final class CgRigAdapters {

    /** A skinned glTF the engine poses: its models face +z. */
    public static final CgRigAdapter CRYSTALGRAPHICS = CgRigAdapter.of("crystalgraphics", new Vector3f(0f, 0f, 1f));

    /**
     * A GeckoLib model the engine poses: its models face -z (north, as Minecraft's entity models are built), and its
     * bones only rotate, except bone 0 and the bone under it, which the importer places.
     */
    public static final CgRigAdapter GECKOLIB = new CgRigAdapter() {
        private final Vector3fc facing = new Vector3f(0f, 0f, -1f);

        @Override
        public String name() {
            return "geckolib";
        }

        @Override
        public Vector3fc facing() {
            return facing;
        }

        @Override
        public boolean rotationOnly(CgSkeleton skeleton, int bone) {
            return bone > 0 && skeleton.parent(bone) > 0;
        }
    };

    private static final Map<String, CgRigAdapter> BY_NAME = new ConcurrentHashMap<>();

    static {
        register(CRYSTALGRAPHICS);
        register(GECKOLIB);
    }

    private CgRigAdapters() {
    }

    /** Adds {@code adapter} under its name; a name taken throws. */
    public static void register(CgRigAdapter adapter) {
        if (BY_NAME.putIfAbsent(adapter.name(), adapter) != null && BY_NAME.get(adapter.name()) != adapter) {
            throw new IllegalArgumentException("a rig adapter is already registered as " + adapter.name());
        }
    }

    /** The adapter named {@code name}, or null. */
    @Nullable
    public static CgRigAdapter get(String name) {
        return BY_NAME.get(name);
    }
}
