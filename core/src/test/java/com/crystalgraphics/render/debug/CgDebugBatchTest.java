package com.crystalgraphics.render.debug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.crystalgraphics.collision.debug.CgShapeLines;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgSphere;

import static org.junit.Assert.assertEquals;

public class CgDebugBatchTest {

    @Test
    public void aShapeInsideAnotherDrawsBetweenItsBackAndItsFront() {
        CgDebugBatch faces = new CgDebugBatch(3);
        CgShapeLines lines = new CgShapeLines();
        lines.faces(CgSphere.of(3), new CgPose(), faces);
        lines.faces(CgBox.of(1, 1, 1), new CgPose().facing(0.6, 0.3), faces);
        int backs = faces.sortFrom(0.4, 1, -12);
        List<String> runs = new ArrayList<>();
        for (int k = 0; k < faces.count; k++) {
            String run = (faces.pieceOf[faces.order[k]] == 0 ? "sphere " : "box ") + (k < backs ? "back" : "front");
            if (runs.isEmpty() || !runs.get(runs.size() - 1).equals(run)) runs.add(run);
        }
        assertEquals(Arrays.asList("sphere back", "box back", "box front", "sphere front"), runs);
    }
}
