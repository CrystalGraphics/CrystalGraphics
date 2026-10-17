package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** A binding's sampler is part of its snapshot: interned by value, kept by a copy and by a texture swap elsewhere. */
public class CgBindingTableSamplerTest {

    private static final CgTexture IMAGE = new CgTexture() {
        @Override public void bind() {}
        @Override public void bind(int unit) {}
        @Override public int getId() { return 1; }
        @Override public int getWidth() { return 4; }
        @Override public int getHeight() { return 4; }
        @Override public int getTarget() { return 0x0DE1; }
        @Override public boolean isDeleted() { return false; }
        @Override public void delete() {}
    };

    @Test
    public void aSamplerTellsSnapshotsApartAndAnEqualOneDoesNot() {
        CgBindingTable table = new CgBindingTable();
        int own = table.begin().texture(0, IMAGE).end();
        int wrapped = table.begin().texture(0, IMAGE, CgSampler.BILINEAR_WRAP).end();
        int again = table.begin().texture(0, IMAGE, CgSampler.builder().filter(CgSampler.Filter.BILINEAR)
                .address(CgSampler.Address.WRAP).build()).end();
        int clamped = table.begin().texture(0, IMAGE, CgSampler.BILINEAR_CLAMP).end();
        assertNotEquals(own, wrapped);
        assertEquals("an equal sampler is the same snapshot", wrapped, again);
        assertNotEquals(wrapped, clamped);
        assertNull(table.sampler(own, 0));
        assertEquals(CgSampler.BILINEAR_CLAMP, table.sampler(clamped, 0));
    }

    @Test
    public void aCopyAndASwapElsewhereKeepTheSampler() {
        CgBindingTable from = new CgBindingTable(), into = new CgBindingTable();
        int id = from.begin().texture(0, IMAGE, CgSampler.NEAREST_WRAP).texture(1, IMAGE).end();
        int copied = into.copy(from, id);
        assertSame(CgSampler.NEAREST_WRAP, into.sampler(copied, 0));
        int swapped = from.withTexture(id, 1, IMAGE);
        assertSame("unit 0 kept its sampler", CgSampler.NEAREST_WRAP, from.sampler(swapped, 0));
    }
}
