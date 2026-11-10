package com.crystalgraphics.demo;

import com.crystalgraphics.text.style.CgTextLayer;
import com.crystalgraphics.text.style.CgTextStyle;

/**
 * The text styles the demos label their stations in.
 *
 * <pre>{@code
 * world.text("Attract + Orbit").at(x, y, z).height(1.1f).font(font).style(CgDemoStyles.LABEL).submit();
 * world.text("subtitle").at(x, y, z).height(0.6f).font(font).color(0xFFB8D8FF).style(CgDemoStyles.LABEL).submit();
 * }</pre>
 */
public final class CgDemoStyles {

    /** White, outlined 0.08 em in black: at 75 pt, 100 px, a width of 8. A draw's colour tints it. */
    public static final CgTextStyle LABEL = CgTextStyle.builder("crystalgraphics:demo_label").size(75f)
            .layer(CgTextLayer.outline("outline").width(8f).paint(0xFF000000))
            .layer(CgTextLayer.fill("fill"))
            .build();

    private CgDemoStyles() {
    }
}
