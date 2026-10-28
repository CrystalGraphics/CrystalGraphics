package com.crystalgraphics.vfx.sequence;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.sequence.CgSequenceDefinition;
import com.crystalgraphics.vfx.prefab.CgVfxPrefab;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A sequence a particle plays bakes into its clip: each frame the sequence's own evaluation of its node tracks. */
public class CgVfxClipBakeTest {

    private static final CgMaterialDefinition GLOW =
            CgMaterialDefinition.of("test:glow", "crystalgraphics:shaders/vfx/sequence/part.shader").build();
    private static final CgVfxPrefab RING = CgVfxPrefab.builder("test:ring")
            .node("root").part(GLOW, "test:models/root.glb")
            .node("blade").parent("root").at(0f, 1f, 0f).part(GLOW, "test:models/blade.glb")
            .build();
    private static final String HEAD = "_v: 1\ntick_resolution: 30\ndisplay_rate: 30\nrange: [0, 30]\n";
    private static final String SEQUENCE = HEAD
            + "spawnables:\n  - { id: s1, name: ring, prefab: test:ring }\n"
            + "tracks:\n"
            + "  - { id: t1, kind: transform, binding: { spawnable: s1, node: blade }, sections: [ { id: a, range: [0, 30],"
            + " rotation: { z: { keys: [[0, 0.0, linear], [30, 1.5707964, linear]] } } } ] }\n"
            + "  - { id: t2, kind: property, binding: { spawnable: s1, node: blade }, property: opacity, sections: [ { id: b,"
            + " range: [0, 30], value: { keys: [[0, 0.0, linear], [15, 1.0, linear], [30, 0.0, linear]] } } ] }\n"
            + "  - { id: t3, kind: material, binding: { spawnable: s1, material: glow }, sections: [ { id: c, range: [0, 30],"
            + " colours: { _UV0Window: { r: { keys: [[0, 0.0, linear], [30, -1.0, linear]] }, b: { default: 2.0 } } } } ] }\n";

    @Test
    public void aSequenceBakesIntoTheClipItsParticlesPlay() {
        CgSequenceDefinition sequence = compile(SEQUENCE);
        CgVfxNodeClip clip = CgVfxClipBake.bake("blades", sequence);
        int blade = RING.node("blade");
        assertEquals(30, clip.frames());
        // Its turn at the last frame is the sequence's rotation through its units.
        Quaternionf expected = sequence.units().rotationToHost(0f, 0f, 1.5707964f, new Quaternionf());
        Quaternionf baked = clip.local(blade, 30f, new Matrix4f(), new float[10], new Vector3f(), new Quaternionf())
                .getNormalizedRotation(new Quaternionf());
        assertEquals(1f, Math.abs(baked.dot(expected)), 1e-4f);
        assertEquals(1f, clip.opacity(blade, 15f), 1e-5f);
        assertEquals(0.5f, clip.opacity(blade, 7.5f), 1e-5f);
        // The material track keys every node drawing its material, a colour by component.
        int x = CgVfxNodeClip.MATERIAL_SCALARS.indexOf("_UV0Window.x"), z = CgVfxNodeClip.MATERIAL_SCALARS.indexOf("_UV0Window.z");
        assertEquals(-1f, clip.material(blade, x, 30f), 1e-5f);
        assertEquals(2f, clip.material(RING.node("root"), z, 0f), 1e-5f);
        assertEquals(1 << x | 1 << z, clip.keys(blade));
        // A node's rotationEulerDeg is the same turn in degrees.
        CgVfxNodeClip degrees = CgVfxClipBake.bake("blades", compile(SEQUENCE.replace(
                "kind: transform, binding: { spawnable: s1, node: blade }, sections: [ { id: a, range: [0, 30], rotation: { z: { keys: [[0, 0.0, linear], [30, 1.5707964, linear]] } } } ] }",
                "kind: property, binding: { spawnable: s1, node: blade }, property: rotationEulerDeg, sections: [ { id: a, range: [0, 30], z: { keys: [[0, 0.0, linear], [30, 90.0, linear]] } } ] }")));
        Quaternionf byDegrees = degrees.local(blade, 30f, new Matrix4f(), new float[10], new Vector3f(), new Quaternionf())
                .getNormalizedRotation(new Quaternionf());
        assertEquals(1f, Math.abs(byDegrees.dot(expected)), 1e-4f);
    }

    @Test
    public void whatAClipCannotHoldFailsByName() {
        CgSequenceDefinition systems = compile(HEAD + "spawnables:\n  - { id: s1, name: puff, system: test:puff }\n");
        CgAssetException e = assertThrows(CgAssetException.class, () -> CgVfxClipBake.bake("blades", systems));
        assertTrue(e.getMessage(), e.getMessage().contains("a system spawnable (s1) on a particle is not baked yet"));
        CgSequenceDefinition scale = compile(SEQUENCE.replace("colours: { _UV0Window:", "scalars: { _Shine: { default: 1.0 } }, colours: { _UV0Window:"));
        e = assertThrows(CgAssetException.class, () -> CgVfxClipBake.bake("blades", scale));
        assertTrue(e.getMessage(), e.getMessage().contains("_Shine has no clip slot"));
    }

    /** Units writing rotations as angles: radians, intrinsic x, y, z. */
    private static final CgUnits ANGLES = CgUnits.of(Map.of("angles", "radians", "rotation", Map.of("order", "xyz", "intrinsic", true)), CgUnits.HOST);

    private static CgSequenceDefinition compile(String yaml) {
        CgAssetCompiler.Dependencies deps = new CgAssetCompiler.Dependencies() {
            @Override
            public Object definition(CgType kind, String id) {
                return id.equals("test:ring") ? RING : null;
            }

            @Override
            public CgUnits units() {
                return ANGLES;
            }
        };
        return CgSequenceDefinition.COMPILER.compile(CgAssetData.of("test:blades", CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(yaml), null), deps);
    }
}
