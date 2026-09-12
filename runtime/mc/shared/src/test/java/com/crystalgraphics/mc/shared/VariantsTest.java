package com.crystalgraphics.mc.shared;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * What the bootstrapper does on every boot of every loader, without a loader.
 *
 * <p>The table is written by the build's {@code VariantsJson} and read here; the two are twins and a
 * change to either that the other does not follow shows up as a failure in this class.</p>
 */
public class VariantsTest {

    private static final String TABLE =
            "{\n"
            + "  \"format\": 1,\n"
            + "  \"mod\": \"crystalgui\",\n"
            + "  \"variants\": [\n"
            + "    {\"loader\": \"fabric\", \"minecraft\": \"[1.20.1,1.20.2)\", \"era\": \"modern\","
            + " \"common\": \"a.Common\", \"client\": \"a.Client\", \"mixins\": []},\n"
            + "    {\"loader\": \"fabric\", \"minecraft\": \"[1.20.2,1.21)\", \"era\": \"modern\","
            + " \"common\": \"b.Common\", \"client\": \"b.Client\", \"mixins\": [\"mixins.b.json\"]},\n"
            + "    {\"loader\": \"fml1710\", \"minecraft\": \"[1.7.10]\", \"era\": \"1710\","
            + " \"common\": \"c.Common\", \"mixins\": [\"mixins.crystalgui.json\"]}\n"
            + "  ]\n"
            + "}\n";

    private Variants table() {
        return Variants.read(TABLE, "crystalgui");
    }

    @Test
    public void picksTheVariantWhoseRangeCoversTheVersion() {
        assertEquals("a.Common", table().select("fabric", "1.20.1").commonEntry());
        assertEquals("b.Common", table().select("fabric", "1.20.4").commonEntry());
    }

    @Test
    public void loaderIsPartOfTheChoice() {
        assertEquals("c.Common", table().select("fml1710", "1.7.10").commonEntry());
    }

    @Test
    public void anAbsentClientEntryIsNullRatherThanEmpty() {
        assertNull(table().select("fml1710", "1.7.10").clientEntry());
    }

    @Test
    public void aVersionNoVariantClaimsNamesWhatIsSupported() {
        try {
            table().select("fabric", "1.16.5");
            fail("expected a refusal");
        } catch (UnsupportedVariant expected) {
            String message = expected.getMessage();
            assertTrue(message, message.contains("1.16.5"));
            assertTrue(message, message.contains("[1.20.1,1.20.2)"));
            assertTrue(message, message.contains("[1.20.2,1.21)"));
        }
    }

    @Test
    public void aLoaderTheJarDoesNotCarrySaysSoRatherThanThrowingSomethingElse() {
        try {
            table().select("neoforge", "1.20.4");
            fail("expected a refusal");
        } catch (UnsupportedVariant expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("none on this loader"));
        }
    }

    @Test
    public void everyVariantOfOneLoaderIsAvailableToTheMixinPlugin() {
        assertEquals(2, table().of("fabric").size());
        assertEquals(1, table().of("fml1710").size());
        assertEquals("mixins.b.json", table().of("fabric").get(1).mixinConfigs().get(0));
    }

    @Test
    public void aFutureFormatIsRefusedRatherThanGuessedAt() {
        try {
            Variants.read(TABLE.replace("\"format\": 1", "\"format\": 2"), "crystalgui");
            fail("expected a refusal");
        } catch (UnsupportedVariant expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("newer than its own reader"));
        }
    }

    @Test
    public void aMalformedTableSaysWhereRatherThanReturningNothing() {
        try {
            Variants.read("{\"format\": 1, \"variants\": [", "crystalgui");
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("variants.json is malformed"));
        }
    }

    // ── VersionRange, which is the half that decides ────────────────────────────────────────────

    @Test
    public void halfOpenRangesIncludeTheirLowBoundAndNotTheirHigh() {
        VersionRange r = VersionRange.parse("[1.20.1,1.21)");
        assertTrue(r.contains("1.20.1"));
        assertTrue(r.contains("1.20.4"));
        assertFalse(r.contains("1.21"));
        assertFalse(r.contains("1.20"));
    }

    @Test
    public void aBareVersionIsExact() {
        VersionRange r = VersionRange.parse("[1.7.10]");
        assertTrue(r.contains("1.7.10"));
        assertFalse(r.contains("1.7.9"));
    }

    @Test
    public void anOpenHighBoundHasNoTop() {
        assertTrue(VersionRange.parse("[1.20.1,)").contains("1.99.0"));
    }

    @Test
    public void versionsCompareBySegmentSoOneDotNineIsBelowOneDotTwenty() {
        assertTrue(VersionRange.compare("1.9", "1.20") < 0);
        assertFalse(VersionRange.parse("[1.16,1.21)").contains("1.9"));
    }

    /** A pre-release is not a version any variant was built against, so it is refused by name. */
    @Test
    public void aPreReleaseSortsBelowItsReleaseAndIsNotCovered() {
        assertFalse(VersionRange.parse("[1.20.1,1.21)").contains("1.20.1-pre1"));
        assertFalse(VersionRange.parse("[1.20.1,1.21)").contains("23w31a"));
    }
}
