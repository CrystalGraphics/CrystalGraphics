package com.crystalgraphics.platform.rhi;

import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;

import java.util.Arrays;

/**
 * A target someone else owns, drawn into by the frame graph: a host's framebuffer, or a texture pair made outside the
 * graph. It names its images and what it lends (Unreal's {@code RegisterExternalTexture}); the graph imports it once
 * per execution and treats it as any storage of its own.
 *
 * <pre>{@code
 * CgExternalTarget main = CgExternalTarget.host(mainFbo, width, height);   // Minecraft's main target
 * try (CgExecutor.Into ignored = CgExecutor.into(main)) {
 *     CgRenderStage.WORLD_OPAQUE.fire();
 * }
 * if (main.lendsAttachments()) { ... }    // a scene of ours may draw beside its depth
 * }</pre>
 *
 * <pre>{@code
 * CgExternalTarget preview = CgExternalTarget.of("preview", color, depth);  // two device textures of yours
 * CgExternalTarget warm = CgExternalTarget.of("warm", color, depth).drawnAsHost();   // drawn as the host's world is
 * }</pre>
 *
 * <ul>
 *   <li>A {@link #host} target is drawn in the host's depth convention ({@code CgGraphicsLifecycle.hostDepth}), every
 *       other in the engine's, reversed; {@link #drawnAsHost} makes one of yours draw as the host's do.</li>
 *   <li>{@link #host} is the one place a framebuffer name becomes a target: the same object for the same name and size
 *       until {@link #forgetHosts}, which a resize calls since a host may remake its targets.</li>
 *   <li>Borrowed: nothing here owns or deletes the images.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgExternalTarget {

    private final String label;
    private final CgTextureView color, depth;
    private final int width, height;
    private final boolean lends;
    private boolean asHost;
    /** The same target without its depth ({@link #colorOnly}), made at first ask. */
    private CgExternalTarget colorOnly;

    private CgExternalTarget(String label, CgTextureView color, CgTextureView depth, int width,
                             int height, boolean lends) {
        if (color == null && depth == null) throw new IllegalArgumentException(label + " has neither colour nor depth");
        this.label = label;
        this.color = color;
        this.depth = depth;
        this.width = width;
        this.height = height;
        this.lends = lends;
    }

    /**
     * Two device textures made outside the graph, drawn at level 0, layer 0: either may be null, not both. They lend
     * their attachments: a pass may draw beside their depth.
     */
    public static CgExternalTarget of(String label, CgGpuTexture color, CgGpuTexture depth) {
        CgGpuTexture sized = color != null ? color : depth;
        if (sized == null) throw new IllegalArgumentException(label + " has neither colour nor depth");
        return new CgExternalTarget(label, color == null ? null : CgTextureView.attachment(color, 0, 0),
                depth == null ? null : CgTextureView.attachment(depth, 0, 0), sized.desc().width(), sized.desc().height(), true);
    }

    /**
     * Host framebuffer {@code fbo}, {@code width} x {@code height}, its attachments imported ({@link CgHostImports}):
     * 0 is the window's. The window's and a multisampled one lend no attachment.
     */
    public static CgExternalTarget host(int fbo, int width, int height) {
        if (fbo >= CgDeviceNames.FIRST) {
            throw new IllegalArgumentException("framebuffer " + fbo + " is a name the engine gave one of its own, no host's:"
                    + " import that framebuffer as its external()");
        }
        for (int i = 0; i < hostCount; i++) {
            int at = i * 3;
            if (hostKeys[at] == fbo && hostKeys[at + 1] == width && hostKeys[at + 2] == height) return hosts[i];
        }
        CgHostImports imports = CgHostImports.get();
        CgTextureView color = imports.attachment(fbo, false, width, height);
        CgTextureView depth = imports.attachment(fbo, true, width, height);
        boolean lends = fbo != 0 && single(color) && single(depth);
        CgExternalTarget made = new CgExternalTarget("framebuffer " + fbo, color, depth, width, height, lends).drawnAsHost();
        if (hostCount == hosts.length) {
            hosts = Arrays.copyOf(hosts, hostCount * 2);
            hostKeys = Arrays.copyOf(hostKeys, hostCount * 6);
        }
        int at = hostCount * 3;
        hostKeys[at] = fbo;
        hostKeys[at + 1] = width;
        hostKeys[at + 2] = height;
        hosts[hostCount++] = made;
        return made;
    }

    private static boolean single(CgTextureView view) {
        return view == null || view.texture().desc().samples() <= 1;
    }

    /** Host targets known so far, by framebuffer and size: a host alternates a few. */
    private static CgExternalTarget[] hosts = new CgExternalTarget[4];
    private static int[] hostKeys = new int[12];
    private static int hostCount;

    /** Host framebuffer {@code fbo}'s targets forgotten: the host remade or closed its images. */
    public static void forgetHost(int fbo) {
        for (int i = hostCount - 1; i >= 0; i--) {
            if (hostKeys[i * 3] != fbo) continue;
            hostCount--;
            hosts[i] = hosts[hostCount];
            System.arraycopy(hostKeys, hostCount * 3, hostKeys, i * 3, 3);
            hosts[hostCount] = null;
        }
    }

    /** Every {@link #host} target forgotten, and their imports with them: at a resize, which may remake a host's targets. */
    public static void forgetHosts() {
        Arrays.fill(hosts, null);
        hostCount = 0;
        if (CgHostImports.installed()) CgHostImports.get().forgetAttachments();
    }

    /** This target without its depth: a GUI over the host's finished frame, tested against nothing of the world's. */
    public CgExternalTarget colorOnly() {
        if (depth == null) return this;
        if (colorOnly == null) {
            colorOnly = new CgExternalTarget(label + " colour", color, null, width, height, lends);
            colorOnly.asHost = asHost;
        }
        return colorOnly;
    }

    /**
     * Draws it in the host's depth convention rather than the engine's: a target of yours standing in for the host's,
     * whose pipelines and depth must be the world's.
     */
    public CgExternalTarget drawnAsHost() {
        asHost = true;
        if (colorOnly != null) colorOnly.asHost = true;
        return this;
    }

    /** Whether it is drawn in the host's depth convention: {@link #host}'s, and any {@link #drawnAsHost}. */
    public boolean drawsAsHost() {
        return asHost;
    }

    public String label() {
        return label;
    }

    /** Its colour, as attached; null for a target of depth alone. */
    public CgTextureView color() {
        return color;
    }

    /** Its depth, as attached; null for a target without one. */
    public CgTextureView depth() {
        return depth;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * Whether a pass may draw a colour of ours beside its depth, or a second attachment beside its colour: false for
     * the window's framebuffer, whose attachments no other framebuffer takes, and for a multisampled one.
     */
    public boolean lendsAttachments() {
        return lends;
    }

    @Override
    public String toString() {
        return label + " (" + width + "x" + height + ")";
    }
}
