package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.render.draw.CgBoundResources;

import javax.annotation.Nullable;

/**
 * A texture the host made, bound as one of ours: what a pass samples of the host's framebuffer in place (RDG's
 * {@code RegisterExternalTexture}). Pointed at the host's texture again each firing; never deletes it.
 *
 * <pre>{@code
 * hostDepth.point(name, target, width, height, imported);   // imported: its RHI texture, or null where none is
 * hostDepth.bind(pass.sceneDepthUnit());                     // a device set reads it as it reads ours
 * }</pre>
 */
final class CgImportedTexture implements CgTexture {

    private int name, target, width, height;
    @Nullable
    private CgTextureView view;

    /** Points it at the host's texture {@code name} on {@code target}, {@code imported} as the device's. */
    void point(int name, int target, int width, int height, @Nullable CgGpuTexture imported) {
        this.name = name;
        this.target = target;
        this.width = width;
        this.height = height;
        if (imported == null) view = null;
        else if (view == null || view.texture() != imported) view = CgTextureView.whole(imported);
    }

    @Override
    public void bind() {
        CgTexture.bind(target, name);
    }

    @Override
    public void bind(int unit) {
        CgBoundResources.texture(unit, this, -1);
        if (CgBoundResources.notesOnly) return;
        CgTexture.active(unit);
        CgTexture.bind(target, name);
    }

    @Override
    @Nullable
    public CgTextureView rhiView() {
        return view;
    }

    @Override
    public int getId() {
        return name;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getTarget() {
        return target;
    }

    @Override
    public boolean isDeleted() {
        return false;
    }

    /** The host's to delete. */
    @Override
    public void delete() {
    }
}
