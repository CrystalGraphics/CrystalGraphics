package io.github.somehussar.crystalgraphics;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CrystalGraphicsVersion}.
 *
 * <p>The actual OpenGL check path requires an active GL context and Forge
 * runtime, so these tests focus on the input-validation logic that can be
 * exercised without either.</p>
 */
public class CrystalGraphicsVersionTest {

    // ---------------------------------------------------------------
    //  setMinimumRequiredOpenGL(int, int) — argument validation
    // ---------------------------------------------------------------

    @Test(expected = IllegalArgumentException.class)
    public void testRejectsZeroMajor() {
        CrystalGraphicsVersion.setMinimumRequiredOpenGL(0, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRejectsNegativeMajor() {
        CrystalGraphicsVersion.setMinimumRequiredOpenGL(-1, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRejectsNegativeMinor() {
        CrystalGraphicsVersion.setMinimumRequiredOpenGL(3, -1);
    }

    // ---------------------------------------------------------------
    //  setMinimumRequiredOpenGL(String) — argument validation
    // ---------------------------------------------------------------

    @Test(expected = IllegalArgumentException.class)
    public void testStringOverloadRejectsNull() {
        CrystalGraphicsVersion.setMinimumRequiredOpenGL((String) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testStringOverloadRejectsEmpty() {
        CrystalGraphicsVersion.setMinimumRequiredOpenGL("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testStringOverloadRejectsGarbage() {
        CrystalGraphicsVersion.setMinimumRequiredOpenGL("not a version");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testStringOverloadRejectsNoDot() {
        CrystalGraphicsVersion.setMinimumRequiredOpenGL("42");
    }

    // ---------------------------------------------------------------
    //  Class structure sanity
    // ---------------------------------------------------------------

    @Test
    public void testCannotInstantiate() {
        // Verify the class is final and has no public constructors
        assertTrue("CrystalGraphicsVersion must be final",
                java.lang.reflect.Modifier.isFinal(CrystalGraphicsVersion.class.getModifiers()));
        assertEquals("CrystalGraphicsVersion must have no public constructors",
                0, CrystalGraphicsVersion.class.getConstructors().length);
    }

    // ---------------------------------------------------------------
    //  ModOpenGlRequirement DTO
    // ---------------------------------------------------------------

    @Test
    public void testRequirementFailureDtoGetters() {
        CrystalGraphicsVersion.ModOpenGlRequirement f =
                new CrystalGraphicsVersion.ModOpenGlRequirement("testmod", "TestMod", 4, 5, "TestMod-1.0.jar");
        assertEquals("TestMod", f.modName());
        assertEquals(4, f.requiredMajor());
        assertEquals(5, f.requiredMinor());
    }

    @Test
    public void testRequirementFailureDtoZeroMinor() {
        CrystalGraphicsVersion.ModOpenGlRequirement f =
                new CrystalGraphicsVersion.ModOpenGlRequirement("anothermod", "AnotherMod", 3, 0, null);
        assertEquals("AnotherMod", f.modName());
        assertEquals(3, f.requiredMajor());
        assertEquals(0, f.requiredMinor());
    }
}
