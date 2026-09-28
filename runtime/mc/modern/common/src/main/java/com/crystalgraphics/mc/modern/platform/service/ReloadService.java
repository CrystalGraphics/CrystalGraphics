package com.crystalgraphics.mc.modern.platform.service;

import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.platform.service.CgReloadService;

/**
 * Modern implementation of {@link CgReloadService}.
 * Loader bootstrap classes wire the MC reload event to {@code CgPlatform.reload().onReload()}.
 */
public final class ReloadService implements CgReloadService {

    @Override
    public void onReload() {
        CgAssetReloader.reload();
    }
}
