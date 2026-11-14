package com.crystalgraphics.sequence.binding;

import com.crystalgraphics.animation.CgSocketTest;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgSkeletalMeshComponent;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** A sequence keys a skeletal mesh's material parameters by their own names, and unkeyed puts each material back. */
public class CgMaterialParametersTest {

    private static final String SPRITE = "crystalgraphics:shaders/vfx/particle/sprite.shader";

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static float[] value(CgMaterialInterface m, String name) {
        for (CgMaterialProperty p : m.properties()) if (p.getName().equals(name)) return p.getFloatValue();
        throw new AssertionError(name + " not declared");
    }

    @Test
    public void aParameterIsASlotByItsNameAndUnkeyedRestoresTheMaterialsOwn() {
        CgMaterialDefinition eye = CgMaterialDefinition.of("test:eye", SPRITE).usage(CgMaterialUsage.SPRITES)
                .value("_SoftDistance", 0.25f).build();
        CgSkeletalMeshComponent mesh = new CgScene().skeletal(CgSocketTest.arm()).material(0, CgMaterial.of(eye));
        CgMaterialParameters materials = new CgMaterialParameters(mesh);

        assertEquals("a name no material declares has no slot", -1, materials.slot("FresnelInfluence"));
        int soft = materials.slot("_SoftDistance");
        assertTrue(soft >= 0);
        assertEquals("asked again, the same slot", soft, materials.slot("_SoftDistance"));

        float[] read = new float[4];
        materials.get(soft, read);
        assertTrue("unkeyed before a sequence keys it", Float.isNaN(read[0]));

        materials.set(soft, new float[]{2f, 0f, 0f, 0f}, 1);
        assertEquals(2f, value(mesh.material(0), "_SoftDistance")[0], 0f);
        materials.set(soft, new float[]{Float.NaN, 0f, 0f, 0f}, 1);
        assertEquals("unkeyed: the material's own", 0.25f, value(mesh.material(0), "_SoftDistance")[0], 0f);
    }

    @Test
    public void aVectorKeyedInPartKeepsItsMaterialsOwnElsewhere() {
        CgMaterialDefinition tinted = CgMaterialDefinition.of("test:tinted", SPRITE).usage(CgMaterialUsage.SPRITES)
                .value("_Color", 0.1f, 0.2f, 0.3f, 0.4f).build();
        CgSkeletalMeshComponent mesh = new CgScene().skeletal(CgSocketTest.arm()).material(0, CgMaterial.of(tinted));
        CgMaterialParameters materials = new CgMaterialParameters(mesh);
        int colour = materials.slot("_Color");
        materials.set(colour, new float[]{1f, 0f, 0f, 0f}, 1);   // x alone
        float[] v = value(mesh.material(0), "_Color");
        assertEquals(1f, v[0], 0f);
        assertEquals(0.2f, v[1], 1e-6f);
        assertEquals(0.4f, v[3], 1e-6f);
    }
}
