package com.crystalgraphics.net.object;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

public class IdTableTest {

    private static final CgNetType TYPE = CgNetType.define("cgtest:id_table").build();

    /** Backward-shift deletion keeps every probe run whole: checked against a map through growth and churn. */
    @Test
    public void agreesWithAMapUnderChurn() {
        IdTable table = new IdTable();
        Map<Integer, CgNetObject> map = new HashMap<>();
        Random random = new Random(7);
        for (int step = 0; step < 200_000; step++) {
            int id = 1 + random.nextInt(step < 100_000 ? 4096 : 64);
            if (random.nextInt(3) == 0) {
                assertSame(map.remove(id), table.remove(id));
            } else {
                CgNetObject obj = new CgNetObject(TYPE, false);
                assertSame(map.put(id, obj), table.put(id, obj));
            }
            if (step % 997 == 0) {
                assertEquals(map.size(), table.size());
                for (int probe = 1; probe <= 4096; probe++) assertSame(map.get(probe), table.get(probe));
            }
        }
        int walked = 0;
        for (int s = 0; s < table.capacity(); s++) if (table.at(s) != null) walked++;
        assertEquals(map.size(), walked);
    }
}
