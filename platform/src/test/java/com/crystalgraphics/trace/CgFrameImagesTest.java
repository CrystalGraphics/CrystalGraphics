package com.crystalgraphics.trace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Which frames are photographed, which pictures are kept, and which one a frame is shown. */
public class CgFrameImagesTest {

    @Before
    public void setUp() {
        CgTrace.resetForTesting();
        CgFrameImages.setInterval(30);
    }

    @After
    public void tearDown() {
        CgTrace.configure(0, 600, 256);
        CgTrace.resetForTesting();
        CgFrameImages.setInterval(30);
    }

    @Test
    public void nothingIsDueWhileTheChannelIsOff() {
        assertFalse(CgFrameImages.isDue(0));
        CgTrace.enable(CgFrameImages.IMAGES.name());
        assertTrue(CgFrameImages.isDue(0));
        assertTrue(CgFrameImages.isDue(60));
        assertFalse(CgFrameImages.isDue(61));
    }

    @Test
    public void aFrameBetweenPicturesShowsTheNearestEarlierOne() {
        CgFrameImages.put(30, 2, 1, new byte[6]);
        CgFrameImages.put(60, 2, 1, new byte[6]);
        assertEquals(30L, CgFrameImages.atOrBefore(59).frameIndex());
        assertEquals(60L, CgFrameImages.atOrBefore(60).frameIndex());
        assertNull(CgFrameImages.atOrBefore(29));
        assertNull(CgFrameImages.at(45));
    }

    @Test
    public void theRingKeepsOnlyWhatItsFramesCanShowAndTheFirstFramesKeepTheirs() {
        CgTrace.configure(60, 90, 256);   // 60 first frames, 90 newest: three newest images at 30 each
        for (long frame = 0; frame <= 600; frame += 30) CgFrameImages.put(frame, 1, 1, new byte[3]);

        assertEquals(0L, CgFrameImages.at(0).frameIndex());
        assertEquals(30L, CgFrameImages.at(30).frameIndex());
        assertNull("dropped: its frame left the ring", CgFrameImages.at(450));
        assertEquals(600L, CgFrameImages.at(600).frameIndex());
        assertEquals(2 + 4, CgFrameImages.count());
    }

    @Test
    public void aClearDropsEveryPicture() {
        CgFrameImages.put(0, 1, 1, new byte[3]);
        CgTrace.clear();
        assertEquals(0, CgFrameImages.count());
    }

    @Test
    public void aPictureTooShortForItsSizeIsRefused() {
        CgFrameImages.put(0, 4, 4, new byte[3]);
        assertEquals(0, CgFrameImages.count());
    }
}
