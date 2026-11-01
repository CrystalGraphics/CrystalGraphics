package com.crystalgraphics.mc.v1710.platform.service;

import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.mc.v1710.mixins.early.impl.client.MixinMinecraft;
import com.crystalgraphics.platform.service.CgLifecycleService;

/**
 * MC 1.7.10 implementation of {@link CgLifecycleService}.
 *
 * <p>Context destroy goes to {@link CgEngineLifecycle}, resize and the frame to
 * {@link CgGraphicsLifecycle}. Destroy and resize
 * are called by {@link MixinMinecraft}
 * on the GL thread.</p>
 */
public final class LifecycleService1710 implements CgLifecycleService {

    @Override public void onContextDestroy()          { CgEngineLifecycle.shutdown(); }
    @Override public void onResize(int w, int h)      { CgGraphicsLifecycle.onResize(w, h); }
    @Override public void onFrameRendered()           { CgGraphicsLifecycle.tickFrame(); }
}
