package com.crystalgraphics.storage.download;

import com.crystalgraphics.storage.download.CgDownloadLocations.Line;
import com.crystalgraphics.storage.download.CgDownloadLocations.Listing;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The build's reading of a locations file: mirrored files, licences and what is refused. */
public class CgDownloadToolTest {

    private static final String PIN = "sha1:0123456789abcdef0123456789abcdef01234567";

    private static String file(String mirror, String entry) {
        return "{\"format\":1,\"self\":[\"https://example.org/l.json\"],"
                + "\"repositories\":{\"central\":[\"https://repo.example.org/{path}\"],\"mirror\":[\"https://mirror.example.org/{file}\"]},"
                + mirror + "\"files\":{" + entry + "}}";
    }

    private static final String MIRROR = "\"mirror\":{\"repository\":\"mirror\",\"release\":\"rel\",\"github\":\"o/r\","
            + "\"licences\":{\"cfr-\":\"MIT\"}},";

    private static final String CFR = "\"a/cfr\":{\"maven\":\"org.benf:cfr:0.152\",\"from\":[\"central\",\"mirror\"],\"digest\":\"" + PIN + "\"}";

    @Test
    public void aMirroredFileIsNamedAndSplitFromItsUpstream() {
        Listing listing = CgDownloadLocations.listing(file(MIRROR, CFR));

        Line line = listing.lines().get(0);
        assertEquals("cfr-0.152.jar", line.mirrorFile());
        assertEquals(List.of("https://repo.example.org/org/benf/cfr/0.152/cfr-0.152.jar"), line.upstream());
        assertEquals(2, line.urls().size());
        assertEquals("MIT", listing.mirror().licenceOf("cfr-0.152.jar"));
        assertTrue(CgDownloadTool.offences(listing).isEmpty());
    }

    @Test
    public void aFileWithNoMirrorBlockMirrorsNothing() {
        Listing listing = CgDownloadLocations.listing(file("", CFR));

        assertNull(listing.lines().get(0).mirrorFile());
        assertTrue(CgDownloadTool.offences(listing).isEmpty());
    }

    @Test
    public void aMirroredFileWithoutALicenceOrAPinIsRefused() {
        Listing unlicensed = CgDownloadLocations.listing(file(MIRROR.replace("cfr-", "other-"), CFR));
        Listing unpinned = CgDownloadLocations.listing(file(MIRROR, CFR.replace(",\"digest\":\"" + PIN + "\"", "")));

        assertTrue(CgDownloadTool.offences(unlicensed).toString().contains("mirror.licences"));
        assertTrue(CgDownloadTool.offences(unpinned).toString().contains("pins nothing"));
    }

    @Test
    public void aPlainHttpAddressIsRefused() {
        Listing listing = CgDownloadLocations.listing(file("",
                "\"x\":{\"urls\":[\"http://example.org/x\"],\"digest\":\"" + PIN + "\"}"));

        assertTrue(CgDownloadTool.offences(listing).toString().contains("is not https"));
    }
}
