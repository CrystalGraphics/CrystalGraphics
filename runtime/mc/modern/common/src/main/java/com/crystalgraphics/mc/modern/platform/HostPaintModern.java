package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.platform.service.CgHostPaint;

/** {@link CgHostPaint}'s answer on 1.13-26.3: {@link HostGlHandoff}'s bracket. */
final class HostPaintModern implements CgHostPaint.Host {

    @Override
    public void enter() {
        HostGlHandoff.enter();
    }

    @Override
    public void leave() {
        HostGlHandoff.leave();
    }
}
