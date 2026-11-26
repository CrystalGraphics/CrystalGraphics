package com.crystalgraphics.api.canvas;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CgPictureHoldTest {

    @Test
    public void aCompletePictureAlwaysShows() {
        CgPictureHold hold = new CgPictureHold("test");
        assertTrue(hold.shows(true, true));
        assertTrue(hold.shows(true, false));
    }

    @Test
    public void theFirstPictureShowsWhateverItIs() {
        assertTrue(new CgPictureHold("test").shows(false, false));
    }

    @Test
    public void anIncompletePictureIsHeldUntilTheDeadline() {
        CgPictureHold hold = new CgPictureHold("test");
        for (int i = 1; i < CgPictureHold.DEADLINE; i++) assertFalse("held " + i, hold.shows(false, true));
        assertTrue("shown at the deadline", hold.shows(false, true));
        // The count starts again after a picture shows
        assertFalse(hold.shows(false, true));
    }

    @Test
    public void aCompletePictureEndsTheCount() {
        CgPictureHold hold = new CgPictureHold("test");
        for (int i = 1; i < CgPictureHold.DEADLINE; i++) hold.shows(false, true);
        assertTrue(hold.shows(true, true));
        assertFalse(hold.shows(false, true));
    }
}
