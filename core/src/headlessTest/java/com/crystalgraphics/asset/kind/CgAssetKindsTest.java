package com.crystalgraphics.asset.kind;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypeKind;
import com.crystalgraphics.object.CgValueType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The format's 20 kinds and their structs are declared, each in its folder. */
public class CgAssetKindsTest {

    /** Each kind and its folder, in order. */
    private static final Map<String, String> README = new LinkedHashMap<>();

    static {
        README.put("system", "vfx/systems");
        README.put("emitter", "vfx/emitters");
        README.put("module", "vfx/modules");
        README.put("effect_type", "vfx/effect_types");
        README.put("ending", "vfx/endings");
        README.put("field", "fields");
        README.put("material", "materials");
        README.put("texture", "textures");
        README.put("mesh", "models");
        README.put("parameters", "parameters");
        README.put("curve", "curves");
        README.put("prefab", "prefabs");
        README.put("skeleton", "skeletons");
        README.put("springs", "springs");
        README.put("rig", "rigs");
        README.put("retargeter", "retargeters");
        README.put("sequence", "sequences");
        README.put("camera_rig", "cameras/rigs");
        README.put("shake", "cameras/shakes");
        README.put("cue", "cues");
    }

    @Test
    public void theTwentyKindsAreDeclaredInTheirFoldersInTheReadmesOrder() {
        List<CgType> kinds = CgAssetKinds.all();
        assertEquals(20, kinds.size());
        int i = 0;
        for (Map.Entry<String, String> e : README.entrySet()) {
            CgType kind = kinds.get(i++);
            assertEquals("crystalgraphics:" + e.getKey(), kind.id());
            assertEquals(CgTypeKind.KIND, kind.kind());
            assertEquals(e.getValue(), kind.folder());
            assertEquals(1, kind.version());
            assertSame(kind, CgAssetKinds.byFolder(e.getValue()));
        }
    }

    @Test
    public void everyKindOpensAndClosesWithTheSharedKeys() {
        for (CgType kind : CgAssetKinds.all()) {
            List<CgProperty> p = kind.properties();
            assertEquals(kind.id(), Arrays.asList("_v", "parent", "units"),
                    Arrays.asList(p.get(0).name(), p.get(1).name(), p.get(2).name()));
            int n = p.size();
            assertEquals(kind.id(), Arrays.asList("unsupported", "editor", "remove"),
                    Arrays.asList(p.get(n - 3).name(), p.get(n - 2).name(), p.get(n - 1).name()));
            assertTrue(kind.id() + " declares nothing of its own", n > 6);
        }
    }

    @Test
    public void everyFamilyHasTheSpecsMembers() {
        CgAssetKinds.register();
        assertEquals(Arrays.asList("sprite", "mesh", "ribbon", "beam", "light", "system", "sequence", "anim_trail", "arc"),
                Arrays.asList(CgParticleKinds.RENDERERS.members().keySet().toArray()));
        assertEquals(23, CgSequenceKinds.TRACKS.members().size());
        assertEquals(20, CgCameraKinds.NODES.members().size());
        assertEquals(Arrays.asList("perlin", "wave", "composite", "sequence", "trauma"),
                Arrays.asList(CgCameraKinds.PATTERNS.members().keySet().toArray()));
        assertEquals(12, CgFieldKinds.NODES.members().size());
        assertEquals(7, CgBodyKinds.OPS.members().size());
    }

    @Test
    public void anElementListMergesByIdAndAReferenceSaysItsKindAndStrength() {
        CgAssetKinds.register();
        CgProperty emitters = CgParticleKinds.SYSTEM.property("emitters");
        assertEquals(CgValueType.Kind.ID_LIST, emitters.type().kind());
        assertSame(CgParticleKinds.EMITTER_HANDLE, emitters.type().type());
        CgProperty sequence = CgGameplayKinds.CUE.property("sequence");
        assertEquals("sequence", sequence.type().refKind());
        assertEquals(CgValueType.Strength.SOFT, sequence.type().strength());
        assertEquals(CgValueType.Strength.HARD, CgParticleKinds.MODULE_ENTRY.property("module").type().strength());
    }

    @Test
    public void theHeadlessSubsetIsFlaggedOnTheKindsAServerResolves() {
        CgAssetKinds.register();
        assertTrue(CgParticleKinds.SYSTEM.property("user").has(CgPropertyFlag.HEADLESS));
        assertTrue(CgGameplayKinds.CUE.property("kind").has(CgPropertyFlag.HEADLESS));
        assertFalse(CgLookKinds.MATERIAL.property("shader").has(CgPropertyFlag.HEADLESS));
        assertNotNull(CgSequenceKinds.SEQUENCE.property("tick_resolution"));
    }
}
