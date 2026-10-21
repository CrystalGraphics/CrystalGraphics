package com.crystalgraphics.vfx;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRenderers;
import com.crystalgraphics.vfx.particle.renderer.CgVfxSpriteRendererProperties;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/** An emitter's renderers as a look places them and the materials resolve them: binds into rows, the factory's too. */
public class CgVfxRendererMaterialsTest {

    private static final String SPRITE = "crystalgraphics:shaders/vfx/particle/sprite.shader";
    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("test:renderer_materials");
    private static final CgVfxParam.Color EMBER = SCHEMA.color("ember", 0xFFFF8040, 2f);

    private static CgMaterialDefinition spark;

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
        spark = CgMaterialDefinition.builder("test:materials/spark", SPRITE).usage(CgMaterialUsage.SPRITES).blend(CgBlendMode.ADDITIVE).build();
    }

    @Test
    public void aLookPlacesRenderersAfterItsLayersByHint() {
        CgVfxEmitter sparks = CgVfxEmitter.builder("sparks")
                .renderer(CgVfxRenderers.sprite("late").material(spark).sortOrderHint(5).build())
                .renderer(CgVfxRenderers.sprite("early").material(spark).sortOrderHint(-1).build())
                .renderer(CgVfxRenderers.sprite("off").material(spark).disabled().build())
                .build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(sparks).build();
        CgVfxLook.Placed[] placed = look.renderers(sparks);
        assertEquals("a disabled renderer is not placed", 2, placed.length);
        assertEquals("early", placed[0].renderer().common().id());
        assertEquals(look.layers().size(), placed[0].order());
        assertEquals(look.layers().size() + 2, look.draws());
    }

    @Test
    public void aBindAndTheFactorysFacingAreRowsAndShareAMaterial() {
        CgVfxEmitter sparks = CgVfxEmitter.builder("sparks")
                .renderer(CgVfxRenderers.sprite("r1").material(spark).bind("_Color", "user.ember").alignment("velocity").build())
                .renderer(CgVfxRenderers.sprite("r2").material(spark).bind("_Color", "user.ember").build())
                .build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(sparks).build();
        CgVfxMaterials materials = new CgVfxMaterials();
        CgVfxMaterials.Resolved aligned = materials.resolve(look, look.renderers(sparks)[0]);
        CgVfxMaterials.Resolved facing = materials.resolve(look, look.renderers(sparks)[1]);
        assertEquals(look.layout().offset(EMBER), aligned.source[0]);
        assertEquals("the facing is a constant of the row", -1, aligned.source[1]);
        assertEquals(CgVfxSpriteRendererProperties.FACE_ALIGNED, aligned.fixed[1][0], 0f);
        assertEquals(CgVfxSpriteRendererProperties.FACE_CAMERA, facing.fixed[1][0], 0f);
        CgMaterial m = materials.material(aligned, CgVfxSlot.Kind.PARTICLES);
        assertSame("renderers differing only in row values share a material", m,
                materials.material(facing, CgVfxSlot.Kind.PARTICLES));
        assertNotEquals(aligned.slots.get("_Color"), aligned.slots.get("_cg_Facing"));
        assertEquals("one row layout for both", aligned.signature, facing.signature);
    }

    @Test
    public void aBindToAParameterTheLookLacksFailsNamingIt() {
        CgVfxEmitter sparks = CgVfxEmitter.builder("sparks")
                .renderer(CgVfxRenderers.sprite("r1").material(spark).bind("_Color", "user.nope").build()).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(sparks).build();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CgVfxMaterials().resolve(look, look.renderers(sparks)[0]));
        assertTrue(e.getMessage(), e.getMessage().contains("user.nope"));
    }
}
