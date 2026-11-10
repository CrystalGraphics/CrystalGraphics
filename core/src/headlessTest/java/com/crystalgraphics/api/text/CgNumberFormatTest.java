package com.crystalgraphics.api.text;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** A HUD's compact numbers: plain below a thousand, a rounded tenth of a unit above, the unit rolling over at 1,000. */
public class CgNumberFormatTest {

    @Test
    public void belowAThousandIsAsItIs() {
        assertEquals("0", CgNumberFormat.compact(0));
        assertEquals("999", CgNumberFormat.compact(999));
    }

    @Test
    public void aboveIsATenthOfItsUnitRoundedHalfUp() {
        assertEquals("1.0k", CgNumberFormat.compact(1_000));
        assertEquals("1.0k", CgNumberFormat.compact(1_049));
        assertEquals("1.1k", CgNumberFormat.compact(1_050));
        assertEquals("2.7k", CgNumberFormat.compact(2_699));
        assertEquals("206.4k", CgNumberFormat.compact(206_400));
        assertEquals("2.4m", CgNumberFormat.compact(2_400_000));
        assertEquals("2.2b", CgNumberFormat.compact(2_200_000_000L));
        assertEquals("1.5t", CgNumberFormat.compact(1_500_000_000_000L));
    }

    @Test
    public void roundingUpToAThousandTakesTheNextUnit() {
        assertEquals("999.9k", CgNumberFormat.compact(999_949));
        assertEquals("1.0m", CgNumberFormat.compact(999_950));
        assertEquals("1.0b", CgNumberFormat.compact(999_950_000L));
    }

    @Test
    public void theExtremesNeitherOverflowNorLoseTheirSign() {
        assertEquals("9223372.0t", CgNumberFormat.compact(Long.MAX_VALUE));
        assertEquals("-2.7k", CgNumberFormat.compact(-2_699));
    }
}
