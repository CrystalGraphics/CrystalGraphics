package com.crystalgraphics.platform.service;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.mixins.early.impl.client.MixinMinecraft;
import com.crystalgraphics.platform.service.CgLifecycleService;

/**
 * MC 1.7.10 implementation of {@link CgLifecycleService}.
 *
 * <p>Delegates directly to {@link CgGraphicsLifecycle}. Context init / destroy / resize
 * are called by {@link MixinMinecraft}
 * on the GL thread.</p>
 */
public final class LifecycleService1710 implements CgLifecycleService {

    @Override public void onContextInit(int w, int h) { CgGraphicsLifecycle.initContext(w, h); }
    @Override public void onContextDestroy()          { CgGraphicsLifecycle.destroyContext(); }
    @Override public void onResize(int w, int h)      { CgGraphicsLifecycle.onResize(w, h); }
}
