package com.crystalgraphics.mc.legacy.platform.service;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.service.CgLifecycleService;

/** Forge 1.8–1.12.2's {@link CgLifecycleService}: {@link CgGraphicsLifecycle}, called from the mixins. */
public final class LifecycleService implements CgLifecycleService {

    @Override public void onContextInit(int w, int h) { CgGraphicsLifecycle.initContext(w, h); }
    @Override public void onContextDestroy()          { CgGraphicsLifecycle.destroyContext(); }
    @Override public void onResize(int w, int h)      { CgGraphicsLifecycle.onResize(w, h); }
    @Override public void onFrameRendered()           { CgGraphicsLifecycle.tickFrame(); }
}
