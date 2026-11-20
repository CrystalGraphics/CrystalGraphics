package com.crystalgraphics.api.vector.svg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.crystalgraphics.api.vector.CgFillRule;
import com.crystalgraphics.api.vector.CgStroke;
import com.crystalgraphics.api.vector.CgStrokeCap;
import com.crystalgraphics.api.vector.CgVectorPath;
import com.crystalgraphics.render.ui.svg.CgSvgPath;
import com.crystalgraphics.render.ui.svg.CgSvgScene;
import com.crystalgraphics.render.ui.svg.CgSvgStyle;

/** A resolved document's nodes as {@link CgSvgShape}s. */
final class CgSvgShapes {

    private CgSvgShapes() {
    }

    static List<CgSvgShape> of(CgSvgScene scene) {
        List<CgSvgShape> out = new ArrayList<>(scene.nodes().size());
        for (CgSvgScene.Node node : scene.nodes()) {
            CgSvgScene.Fill fill = node.fill();
            CgSvgScene.Stroke stroke = node.stroke();
            CgVectorPath path = path(node.contours(), fill != null && fill.evenOdd());
            out.add(new CgSvgShape(node.id(), path, fill == null ? null : paint(fill.paint()),
                    stroke == null ? null : CgStroke.of(stroke.halfWidth() * 2f).cap(cap(stroke.cap() & 3)),
                    stroke == null ? null : paint(stroke.paint())));
        }
        return Collections.unmodifiableList(out);
    }

    private static CgVectorPath path(List<CgSvgPath.Polyline> contours, boolean evenOdd) {
        CgVectorPath.Builder b = CgVectorPath.builder().fillRule(evenOdd ? CgFillRule.EVEN_ODD : CgFillRule.NON_ZERO);
        for (CgSvgPath.Polyline line : contours) {
            List<float[]> points = line.points();
            if (points.isEmpty()) continue;
            b.moveTo(points.get(0)[0], points.get(0)[1]);
            for (int i = 1; i < points.size(); i++) b.lineTo(points.get(i)[0], points.get(i)[1]);
            if (line.closed()) b.close();
        }
        return b.build();
    }

    private static CgSvgShape.Paint paint(CgSvgScene.Paint paint) {
        if (paint instanceof CgSvgScene.Gradient g) return new CgSvgShape.Paint(g.argb(), g.currentColor(), true);
        CgSvgScene.Solid s = (CgSvgScene.Solid) paint;
        return new CgSvgShape.Paint(s.argb(), s.currentColor(), false);
    }

    private static CgStrokeCap cap(int cap) {
        return switch (cap) {
            case CgSvgStyle.CAP_ROUND -> CgStrokeCap.ROUND;
            case CgSvgStyle.CAP_SQUARE -> CgStrokeCap.SQUARE;
            default -> CgStrokeCap.BUTT;
        };
    }
}
