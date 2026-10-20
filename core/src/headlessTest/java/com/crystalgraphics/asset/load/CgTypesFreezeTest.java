package com.crystalgraphics.asset.load;

import org.junit.Test;

import com.crystalgraphics.asset.kind.CgCameraKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypeKind;
import com.crystalgraphics.object.CgTypes;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** The engine's init ends at the first asset requested: native types frozen, a late one refused by name. */
public class CgTypesFreezeTest {

    @Test
    public void theFirstRequestFreezesNativesAndALateOneNamesItsDeclarer() {
        new CgAssetPipeline(new CgAssetRegistry()).request(CgParticleKinds.SYSTEM, "test:absent");
        assertTrue(CgTypes.global().isFrozen());
        assertTrue("every native kind was declared before the freeze", CgCameraKinds.NODES.frozen());

        IllegalStateException kind = assertThrows(IllegalStateException.class, () -> CgTypes.struct("test:late"));
        assertTrue(kind.getMessage(), kind.getMessage().contains(CgTypesFreezeTest.class.getName()));
        CgType node = CgTypes.global().declare("test:late_node", CgTypeKind.ELEMENT).register();
        IllegalStateException member = assertThrows(IllegalStateException.class, () -> CgCameraKinds.NODES.member("late", node));
        assertTrue(member.getMessage(), member.getMessage().contains(CgTypesFreezeTest.class.getName()));
    }
}
