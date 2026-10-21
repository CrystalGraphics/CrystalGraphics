package com.crystalgraphics.vfx.system;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.Assume;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.vfx.look.CgVfxParam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** System assets to definitions: emitters by handle, user parameters, effect behaviours, failures by path. */
public class CgVfxSystemAssetsTest {

    private static final Path NOVA = Paths.get("../../plan/crystalgraphics/vfx-aaa/examples/novamod/assets/novamod/vfx/systems/nova_cannon");

    private static CgVfxSystemDefinition compile(String id, String yaml) {
        CgAssetData asset = CgAssetData.of(id, CgParticleKinds.SYSTEM, (Map<?, ?>) CgAssetYaml.read(yaml), null);
        return CgVfxSystemAssets.compile(asset, (kind, ref) -> null);
    }

    @Test
    public void aSystemCompilesItsEmittersAndParameters() {
        CgVfxSystemDefinition d = compile("mymod:puff", "_v: 1\n"
                + "user:\n  radius: { type: float, quantity: length, default: 0.8 }\n"
                + "emitters:\n  - id: h1\n    name: puff\n    emitter:\n"
                + "      properties: { capacity: 40 }\n"
                + "      emitter_update:\n"
                + "        - { id: s0, module: crystalgraphics:emitter_state, inputs: { loop: once, duration: 1 } }\n"
                + "        - { id: s1, module: crystalgraphics:spawn_burst, inputs: { time: 0, count: 20 } }\n");
        assertEquals("mymod:puff", d.name());
        assertEquals(1, d.look().emitters().size());
        assertEquals("puff", d.look().emitters().get(0).name());
    }

    @Test
    public void aVec3UserParameterCarriesItsDefaultAndABadOneFailsNamingIt() {
        CgVfxSystemDefinition d = compile("mymod:aimed", "_v: 1\nuser:\n  aim: { type: vec3, default: [0, 1, 0] }\n");
        CgVfxParam.Vector aim = (CgVfxParam.Vector) d.look().layout().param("aim");
        assertEquals(1f, d.look().get(aim, 1), 0f);
        try {
            compile("mymod:aimed", "_v: 1\nuser:\n  aim: { type: vec3, default: [0, 1] }\n");
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("user.aim"));
        }
    }

    @Test
    public void anEffectNothingRegisteredFailsNamingIt() {
        try {
            compile("mymod:beam", "_v: 1\nemitters: []\neffect: mymod:nope\n");
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("mymod:nope"));
        }
        CgVfxSystemAssets.effect("mymod:yes", instance -> (in, dt) -> { });
        assertEquals(CgVfxSystemAssets.effect("mymod:yes"), compile("mymod:beam", "_v: 1\neffect: mymod:yes\n").behaviour());
    }

    @Test
    public void theNovaCannonsChargeAndImpactCompile() throws IOException {
        Path charge = NOVA.resolve("charge.yaml");
        Assume.assumeTrue("the Nova Cannon fixture is absent", Files.isRegularFile(charge));
        CgVfxSystemDefinition d = compile("novamod:nova_cannon/charge",
                new String(Files.readAllBytes(charge), StandardCharsets.UTF_8));
        assertEquals(2, d.look().emitters().size());
        CgVfxSystemDefinition impact = compile("novamod:nova_cannon/impact",
                new String(Files.readAllBytes(NOVA.resolve("impact.yaml")), StandardCharsets.UTF_8));
        assertTrue(impact.look().emitters().size() > 0);
    }

    @Test
    public void aRequiredInputLeftOutFailsNamingIt() {
        try {
            compile("mymod:limited", "_v: 1\nemitters:\n  - id: h1\n    name: puff\n    emitter:\n"
                    + "      particle_update:\n"
                    + "        - { id: u0, module: crystalgraphics:limit_speed }\n");
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("limit_speed.inputs.max_speed"));
        }
    }

    @Test
    public void theNovaCannonsBeamPlaysTheEnergyWaveInTheFilesPalette() throws IOException {
        Path beam = NOVA.resolve("beam.yaml");
        Assume.assumeTrue("the Nova Cannon fixture is absent", Files.isRegularFile(beam));
        CgVfxSystemDefinition d = compile("novamod:nova_cannon/beam", new String(Files.readAllBytes(beam), StandardCharsets.UTF_8));
        assertEquals(CgVfxSystemAssets.effect("crystalgraphics:energy_wave"), d.behaviour());
        CgVfxParam.Scalar radius = (CgVfxParam.Scalar) CgEnergyWave.CONTRACT.look().layout().param("radius");
        assertEquals(0.8f, d.look().get(radius), 0f);
    }

    /** Written, compiled and written again: the same tree, and the same emitters and values. */
    private static void roundTrips(CgVfxSystemDefinition d) {
        Map<String, Object> tree = CgVfxSystemAssets.write(d);
        CgAssetData asset = CgAssetData.of(d.name(), CgParticleKinds.SYSTEM, tree, null);
        CgVfxSystemDefinition back = CgVfxSystemAssets.compile(asset, (kind, ref) -> null);
        assertEquals(d.name(), CgAssetYaml.write(tree, CgParticleKinds.SYSTEM), CgAssetYaml.write(CgVfxSystemAssets.write(back), CgParticleKinds.SYSTEM));
        assertEquals(d.name(), d.emitters().size(), back.emitters().size());
        assertEquals(d.name(), d.behaviour(), back.behaviour());
        for (int i = 0; i < d.emitters().size(); i++) {
            assertEquals(d.name(), Arrays.toString(d.emitters().get(i).moduleWords()), Arrays.toString(back.emitters().get(i).moduleWords()));
        }
    }

    @Test
    public void systemsRoundTripThroughTheirFiles() throws IOException {
        roundTrips(compile("mymod:puff", "_v: 1\n"
                + "user:\n  radius: { type: float, quantity: length, default: 0.8 }\n"
                + "  tint: { type: colour, default: { argb: 0xFF2659FF, intensity: 1.6 } }\n"
                + "  aim: { type: vec3, default: [0, 1, 0] }\n"
                + "emitters:\n  - id: h1\n    name: puff\n    emitter:\n"
                + "      properties: { capacity: 40 }\n"
                + "      emitter_update:\n"
                + "        - { id: s0, module: crystalgraphics:emitter_state, inputs: { loop: once, duration: 1 } }\n"
                + "        - { id: s1, module: crystalgraphics:spawn_burst, inputs: { time: 0, count: 20 } }\n"));
        Assume.assumeTrue("the Nova Cannon fixture is absent", Files.isDirectory(NOVA));
        int systems = 0;
        try (Stream<Path> files = Files.list(NOVA)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                if (!file.toString().endsWith(".yaml")) continue;
                String name = file.getFileName().toString();
                roundTrips(compile("novamod:nova_cannon/" + name.substring(0, name.length() - 5),
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
                systems++;
            }
        }
        assertTrue("no systems in " + NOVA, systems > 0);
    }

    @Test
    public void aContractedSystemWritesOnlyWhatDiffersAndWhatItsContractRequires() throws IOException {
        Path beam = NOVA.resolve("beam.yaml");
        Assume.assumeTrue("the Nova Cannon fixture is absent", Files.isRegularFile(beam));
        CgVfxSystemDefinition d = compile("novamod:nova_cannon/beam", new String(Files.readAllBytes(beam), StandardCharsets.UTF_8));
        Map<?, ?> user = (Map<?, ?>) CgVfxSystemAssets.write(d).get("user");
        for (String required : CgEnergyWave.CONTRACT.required()) assertTrue(required, user.containsKey(required));
        assertTrue(user.size() < CgEnergyWave.CONTRACT.look().layout().params().size());
    }

    @Test
    public void aContractedEffectMissingANameFailsNamingIt() {
        try {
            compile("mymod:beam", "_v: 1\nuser:\n  radius: { type: float, default: 1 }\neffect: crystalgraphics:energy_wave\n");
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("user.maxLength"));
        }
    }
}
