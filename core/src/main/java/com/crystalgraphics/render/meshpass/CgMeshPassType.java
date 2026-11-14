package com.crystalgraphics.render.meshpass;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A mesh pass: what draws in it (its processor) and what it draws into (its layout). Unreal's {@code EMeshPass} with its
 * {@code FMeshPassProcessor}, open to consumers. The engine's are {@code CgEnginePasses}; a consumer registers its own
 * once and runs it from a render stage, where a view's commands for it are drawn into the attachments it declares.
 *
 * <pre>{@code
 * public static final CgMeshPassType THERMAL = CgMeshPassType.register("mymod:thermal",
 *         CgPassLayout.color(CgFormat.R16_FLOAT).depth(CgFormat.DEPTH32_FLOAT),       // what Vulkan builds it for
 *         new ThermalProcessor());
 *
 * CgRenderStage.WORLD_TRANSLUCENT.register(frame -> {
 *     CgGraphTexture heat = CgGraphTexture.transientTexture("mymod.heat", CgTextureDesc.of(w, h, CgFormat.R16_FLOAT));
 *     frame.meshPass(THERMAL)
 *             .color(heat, CgLoad.clear(0f, 0f, 0f, 0f))
 *             .depth(frame.depth(), CgLoad.load(), CgDepthAccess.READ)
 *             .draw();                                    // the processor's commands for this view, culled and sorted
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Register at load, before the first frame: a pass's index sizes every primitive's cache of commands.</li>
 *   <li>Registering an id again answers the type made first.</li>
 * </ul>
 */
public final class CgMeshPassType {

    private static final List<CgMeshPassType> TYPES = new CopyOnWriteArrayList<>();

    private final String id;
    private final int index;
    private final CgPassLayout layout;
    private final CgMeshPassProcessor processor;
    private final boolean perFrame;

    private CgMeshPassType(String id, int index, CgPassLayout layout, CgMeshPassProcessor processor, boolean perFrame) {
        this.id = id;
        this.index = index;
        this.layout = layout;
        this.processor = processor;
        this.perFrame = perFrame;
    }

    /** A pass whose commands are kept for persistent primitives, as most are. */
    public static CgMeshPassType register(String id, CgPassLayout layout, CgMeshPassProcessor processor) {
        return register(id, layout, processor, false);
    }

    /**
     * A pass whose processor answers differently from frame to frame for the same primitive (the velocity pass, which
     * reads {@link CgPrimitiveInfo#hasVelocity()}): its commands are built every frame, never kept.
     */
    public static CgMeshPassType registerPerFrame(String id, CgPassLayout layout, CgMeshPassProcessor processor) {
        return register(id, layout, processor, true);
    }

    private static synchronized CgMeshPassType register(String id, CgPassLayout layout, CgMeshPassProcessor processor,
                                                        boolean perFrame) {
        for (CgMeshPassType t : TYPES) if (t.id.equals(id)) return t;
        CgMeshPassType type = new CgMeshPassType(Objects.requireNonNull(id, "id"), TYPES.size(),
                Objects.requireNonNull(layout, "layout"), Objects.requireNonNull(processor, "processor"), perFrame);
        TYPES.add(type);
        return type;
    }

    /** Every pass registered, by index. */
    public static List<CgMeshPassType> types() {
        return TYPES;
    }

    public String id() {
        return id;
    }

    /** Its place in {@link #types()}: what a primitive's cache of commands is indexed by. */
    public int index() {
        return index;
    }

    public CgPassLayout layout() {
        return layout;
    }

    public CgMeshPassProcessor processor() {
        return processor;
    }

    /** Whether its commands are built every frame rather than kept ({@link #registerPerFrame}). */
    public boolean perFrame() {
        return perFrame;
    }

    @Override
    public String toString() {
        return id;
    }
}
