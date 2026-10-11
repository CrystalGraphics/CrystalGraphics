package com.crystalgraphics.mc;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class CgAssetReloaderTrackTest {

    private static final class Counting implements CgReloadable {
        int refreshed;

        @Override
        public void refresh() {
            refreshed++;
        }
    }

    @Test
    public void aChangeRefreshesOnlyWhatReadIt() {
        Counting material = new Counting(), kernel = new Counting();
        CgAssetReloader.track(material, Arrays.asList("test:shaders/a.shader", "test:shaders/lib/noise.glsl"));
        CgAssetReloader.track(kernel, Collections.singletonList("test:shaders/b.compute"));

        CgAssetReloader.changed("/assets/test/shaders/lib/noise.glsl");   // another form of the same path
        CgAssetReloader.applyChanges();
        assertEquals(1, material.refreshed);
        assertEquals(0, kernel.refreshed);

        CgAssetReloader.changed("test:shaders/unread.glsl");
        CgAssetReloader.applyChanges();
        assertEquals(1, material.refreshed);
    }

    @Test
    public void trackingAgainReplacesAndUntrackForgets() {
        Counting shader = new Counting();
        CgAssetReloader.track(shader, Collections.singletonList("test:shaders/old.glsl"));
        CgAssetReloader.track(shader, Collections.singletonList("test:shaders/new.glsl"));

        CgAssetReloader.changed("test:shaders/old.glsl");
        CgAssetReloader.applyChanges();
        assertEquals(0, shader.refreshed);

        CgAssetReloader.changed("test:shaders/new.glsl");
        CgAssetReloader.applyChanges();
        assertEquals(1, shader.refreshed);

        CgAssetReloader.untrack(shader);
        CgAssetReloader.changed("test:shaders/new.glsl");
        CgAssetReloader.applyChanges();
        assertEquals(1, shader.refreshed);
    }

    @Test
    public void aFailingRefreshDoesNotStopTheRest() {
        CgReloadable broken = () -> {
            throw new IllegalStateException("does not compile");
        };
        Counting fine = new Counting();
        CgAssetReloader.track(broken, Collections.singletonList("test:shaders/shared.glsl"));
        CgAssetReloader.track(fine, Collections.singletonList("test:shaders/shared.glsl"));

        CgAssetReloader.changed("test:shaders/shared.glsl");
        CgAssetReloader.applyChanges();
        assertEquals(1, fine.refreshed);
        CgAssetReloader.untrack(broken);
    }
}
