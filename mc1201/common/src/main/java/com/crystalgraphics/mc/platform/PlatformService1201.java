package com.crystalgraphics.mc.platform;

import com.crystalgraphics.mc.platform.gl.GL1201Backend;
import com.crystalgraphics.mc.platform.gl.GL1201Context;
import com.crystalgraphics.mc.platform.service.LifecycleService1201;
import com.crystalgraphics.mc.platform.service.ReloadService1201;
import com.crystalgraphics.mc.platform.service.RenderingService1201;
import com.crystalgraphics.mc.platform.service.ResourceService1201;
import com.crystalgraphics.platform.CgPlatformService;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.gl.CgGLContext;
import com.crystalgraphics.platform.service.CgLifecycleService;
import com.crystalgraphics.platform.service.CgReloadService;
import com.crystalgraphics.platform.service.CgRenderingService;
import com.crystalgraphics.platform.service.CgResourceService;

/**
 * Complete MC 1.20.x platform bundle. Implements {@link CgPlatformService} by composing
 * all six mc1201 service adapters. Register via {@code CgPlatform.register(PlatformService1201.getInstance())}.
 *
 * <p>No GL calls are made in the constructor or static initializer — all GL work is deferred
 * to {@link CgClientLifecycleBridge#onRenderFrame} / {@code onContextInit}.</p>
 */
public final class PlatformService1201 implements CgPlatformService {

    private static final PlatformService1201 INSTANCE = new PlatformService1201();

    public static PlatformService1201 getInstance() {
        return INSTANCE;
    }

    private final GL1201Context      glContext  = new GL1201Context();
    private final GL1201Backend      glBackend  = new GL1201Backend();
    private final LifecycleService1201 lifecycle  = new LifecycleService1201();
    private final ReloadService1201    reload     = new ReloadService1201();
    private final ResourceService1201  resources  = new ResourceService1201();
    private final RenderingService1201 rendering  = new RenderingService1201();

    @Override public CgGLBackend        gl()           { return glBackend; }
    @Override public CgGLContext         capabilities() { return glContext; }
    @Override public CgLifecycleService  lifecycle()    { return lifecycle; }
    @Override public CgReloadService     reload()       { return reload; }
    @Override public CgResourceService   resources()    { return resources; }
    @Override public CgRenderingService  rendering()    { return rendering; }
}
