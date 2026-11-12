package com.crystalgraphics.api.material;

import com.crystalgraphics.api.state.CgRenderState;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A keyword makes another material, cached: the material asked never changes. No GL context. */
public class CgMaterialVariantTest {

    private static CgMaterial mat(String... featureNames) {
        return CgMaterial.forTest(CgRenderState.DEFAULT, 0, Arrays.asList(featureNames));
    }

    @Test
    public void aKeywordMakesAnotherMaterialAndLeavesItsBase() {
        CgMaterial m = mat("SHADOWS_ON", "FOG");
        CgMaterial shadowed = m.keyword("SHADOWS_ON", true);
        assertTrue(shadowed.isKeywordEnabled("SHADOWS_ON"));
        assertFalse(shadowed.isKeywordEnabled("FOG"));
        assertFalse("the base is immutable", m.isKeywordEnabled("SHADOWS_ON"));
    }

    @Test
    public void theSameKeywordsAnswerTheSameMaterial() {
        CgMaterial m = mat("A", "B", "C");
        CgMaterial ac = m.keyword("A", true).keyword("C", true);
        assertSame(ac, m.keyword("C", true).keyword("A", true));
        assertSame(ac, ac.keyword("A", true));
        assertSame("switching one off goes back through the family", m, ac.keyword("A", false).keyword("C", false));
    }

    @Test
    public void switchingOffAKeywordNotOnAnswersItself() {
        CgMaterial m = mat("SHADOWS_ON");
        assertSame(m, m.keyword("SHADOWS_ON", false));
        assertSame(m, m.keyword("UNDECLARED_FEATURE", false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void anUndeclaredKeywordThrows() {
        mat("SHADOWS_ON").keyword("UNDECLARED_FEATURE", true);
    }
}
