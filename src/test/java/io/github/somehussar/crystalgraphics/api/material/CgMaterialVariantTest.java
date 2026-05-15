package io.github.somehussar.crystalgraphics.api.material;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.state.CgRenderState;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Unit tests for the keyword enable/disable/isEnabled API in {@link CgMaterial}.
 *
 * <p>All tests use the {@code forTest} path — no GL context required.</p>
 */
public class CgMaterialVariantTest {

    private static CgMaterial mat(String... featureNames) {
        return CgMaterial.forTest(CgRenderState.DEFAULT, 0, Arrays.asList(featureNames));
    }

    // ── enable/disable/isEnabled ──────────────────────────────────────────────

    @Test
    public void enableKeyword_valid_isKeywordEnabled() {
        CgMaterial m = mat("SHADOWS_ON", "FOG");
        m.enableKeyword("SHADOWS_ON");

        assertTrue("SHADOWS_ON must be enabled after enableKeyword", m.isKeywordEnabled("SHADOWS_ON"));
        assertFalse("FOG must not be enabled unless explicitly enabled", m.isKeywordEnabled("FOG"));
    }

    @Test
    public void disableKeyword_removesKeyword() {
        CgMaterial m = mat("SHADOWS_ON");
        m.enableKeyword("SHADOWS_ON");
        assertTrue(m.isKeywordEnabled("SHADOWS_ON"));

        m.disableKeyword("SHADOWS_ON");
        assertFalse("SHADOWS_ON must not be enabled after disableKeyword", m.isKeywordEnabled("SHADOWS_ON"));
    }

    @Test
    public void disableKeyword_noop_whenNotEnabled() {
        CgMaterial m = mat("SHADOWS_ON");
        m.disableKeyword("SHADOWS_ON"); // must not throw
        assertFalse(m.isKeywordEnabled("SHADOWS_ON"));
    }

    @Test
    public void enableKeyword_idempotent() {
        CgMaterial m = mat("SHADOWS_ON");
        m.enableKeyword("SHADOWS_ON");
        m.enableKeyword("SHADOWS_ON"); // second call must not throw or duplicate state
        assertTrue(m.isKeywordEnabled("SHADOWS_ON"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void enableKeyword_undeclared_throws() {
        CgMaterial m = mat("SHADOWS_ON");
        m.enableKeyword("UNDECLARED_FEATURE");
    }

    @Test
    public void multipleKeywords_independentState() {
        CgMaterial m = mat("A", "B", "C");
        m.enableKeyword("A");
        m.enableKeyword("C");

        assertTrue(m.isKeywordEnabled("A"));
        assertFalse(m.isKeywordEnabled("B"));
        assertTrue(m.isKeywordEnabled("C"));
    }

    @Test
    public void disableKeyword_leavesOthersEnabled() {
        CgMaterial m = mat("A", "B");
        m.enableKeyword("A");
        m.enableKeyword("B");
        m.disableKeyword("A");

        assertFalse(m.isKeywordEnabled("A"));
        assertTrue(m.isKeywordEnabled("B"));
    }
}
