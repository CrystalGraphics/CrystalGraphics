package com.crystalgraphics.asset.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgModuleTypes;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.object.CgPropertyPath;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.validate.CgIssue;
import com.crystalgraphics.object.validate.CgValidation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Each check, seeded once into an asset that is otherwise valid: its code, at its path. */
public class CgAssetValidationTest {

    private static final String ID = "mymod:charge/core";
    private static final String EMITTER = "_v: 1\n"
            + "properties: { capacity: 64, simulation: gpu }\n"
            + "emitter_update:\n"
            + "  - { id: b1, module: crystalgraphics:spawn_burst, inputs: { time: 0.0, count: { range: [4, 8] } } }\n"
            + "renderers:\n"
            + "  - { id: r1, kind: sprite }\n";

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static List<String> issues(CgType kind, String yaml) {
        CgAssetData d = CgAssetData.of(ID, kind, (Map<?, ?>) CgAssetYaml.read(yaml), null);
        List<String> out = new ArrayList<>();
        for (CgIssue i : d.validate(CgValidation.Usecase.LOAD).issues()) {
            out.add(i.severity() + " " + i.code() + " " + i.path());
        }
        return out;
    }

    private static List<String> emitter(String from, String to) {
        assertTrue(from, EMITTER.contains(from));
        return issues(CgParticleKinds.EMITTER, EMITTER.replace(from, to));
    }

    private static String error(String code, String path) {
        return "ERROR " + code + " " + ID + " " + path;
    }

    @Test
    public void theBaseIsValid() {
        assertEquals("[]", issues(CgParticleKinds.EMITTER, EMITTER).toString());
    }

    @Test
    public void aKeyItsTypeDoesNotDeclare() {
        assertEquals("[" + error(CgIssue.UNKNOWN_KEY, "/properties/capacty") + "]", emitter("capacity:", "capacty:").toString());
    }

    @Test
    public void aMissingRequiredKey() {
        assertEquals("[" + error(CgIssue.REQUIRED, "/_v") + "]", emitter("_v: 1\n", "").toString());
    }

    @Test
    public void aValueOfTheWrongShape() {
        assertEquals("[" + error(CgIssue.SHAPE, "/properties/capacity") + "]", emitter("capacity: 64", "capacity: lots").toString());
    }

    @Test
    public void aValueOutsideItsEnum() {
        assertEquals("[" + error(CgIssue.ENUM, "/properties/simulation") + "]", emitter("simulation: gpu", "simulation: cpu").toString());
    }

    @Test
    public void aFormItsPropertyDoesNotTake() {
        assertEquals("[" + error(CgIssue.FORM, "/emitter_update[b1]/inputs/count") + "]",
                emitter("count: { range: [4, 8] }", "count: { curve: mymod:ramp }").toString());
    }

    @Test
    public void aBadIdAndATakenOne() {
        assertEquals("[" + error(CgIssue.ID, "/renderers[R1]/id") + "]", emitter("id: r1", "id: R1").toString());
        assertEquals("[" + error(CgIssue.DUPLICATE_ID, "/renderers[b1]/id") + "]", emitter("id: r1", "id: b1").toString());
    }

    @Test
    public void aFamilyMemberThatIsNotOne() {
        assertEquals("[" + error(CgIssue.MEMBER, "/renderers[r1]/kind") + "]", emitter("kind: sprite", "kind: sprit").toString());
    }

    @Test
    public void aModuleOrInputTheCatalogueLacks() {
        assertEquals("[" + error(CgModuleTypes.UNKNOWN_MODULE, "/emitter_update[b1]/module") + "]",
                emitter("crystalgraphics:spawn_burst", "crystalgraphics:spawn_bust").toString());
        assertEquals("[" + error(CgModuleTypes.UNKNOWN_INPUT, "/emitter_update[b1]/inputs/tme") + "]",
                emitter("time: 0.0", "tme: 0.0").toString());
    }

    @Test
    public void unsupportedEntriesWarnAndStayValid() {
        String yaml = EMITTER + "unsupported: [ { what: CascadeOrbitModule, source: P_Charge } ]\n";
        CgAssetData d = CgAssetData.of(ID, CgParticleKinds.EMITTER, (Map<?, ?>) CgAssetYaml.read(yaml), null);
        CgValidation v = d.validate(CgValidation.Usecase.LOAD);
        assertTrue(v.valid());
        assertEquals("[WARNING unsupported " + ID + " /unsupported[0]]", issues(CgParticleKinds.EMITTER, yaml).toString());
    }

    @Test
    public void aRegisteredValidatorRunsOnlyOnAValidAssetItTakes() {
        List<String> ran = new ArrayList<>();
        Runnable stop = CgAssetValidator.register(new CgAssetValidator() {
            @Override
            public boolean canValidate(CgValidation.Usecase usecase) {
                return usecase == CgValidation.Usecase.COOK;
            }

            @Override
            public boolean canValidate(CgAssetData asset) {
                return asset.kind() == CgParticleKinds.EMITTER;
            }

            @Override
            public void validate(CgAssetData asset, CgValidation into) {
                ran.add(asset.id());
                into.warning(CgPropertyPath.of(asset.id()), "house_rule", "checked");
            }
        });
        try {
            Map<?, ?> good = (Map<?, ?>) CgAssetYaml.read(EMITTER);
            Map<?, ?> bad = (Map<?, ?>) CgAssetYaml.read(EMITTER.replace("simulation: gpu", "simulation: cpu"));
            CgAssetData.of(ID, CgParticleKinds.EMITTER, good, null).validate(CgValidation.Usecase.LOAD);
            assertEquals("not for this use case", "[]", ran.toString());
            CgValidation cooked = CgAssetData.of(ID, CgParticleKinds.EMITTER, good, null).validate(CgValidation.Usecase.COOK);
            assertEquals("[" + ID + "]", ran.toString());
            assertEquals("house_rule", cooked.issues().get(0).code());
            CgAssetData.of(ID, CgParticleKinds.EMITTER, bad, null).validate(CgValidation.Usecase.COOK);
            assertEquals("not on an asset already failing", 1, ran.size());
        } finally {
            stop.run();
        }
    }

    @Test
    public void aSpawnableIsOneKind() {
        String seq = "_v: 1\ntick_resolution: 24000\ndisplay_rate: 30\nspawnables:\n  - { id: s1, name: a, system: mymod:a, light: { type: point } }\n";
        assertEquals("[ERROR " + CgSequenceKinds.SPAWNABLE_KIND + " mymod:charge/core /spawnables[s1]]",
                issues(CgSequenceKinds.SEQUENCE, seq).toString());
    }
}
