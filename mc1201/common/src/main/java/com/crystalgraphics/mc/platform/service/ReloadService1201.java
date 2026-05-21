package com.crystalgraphics.mc.platform.service;

import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.platform.service.CgReloadService;

/**
 * MC 1.20.x implementation of {@link CgReloadService}.
 * Loader bootstrap classes wire the MC reload event to {@code CgPlatform.reload().onReload()}.
 */
public final class ReloadService1201 implements CgReloadService {

    @Override
    public void onReload() {
        CgAssetReloader.reload();
    }
}
