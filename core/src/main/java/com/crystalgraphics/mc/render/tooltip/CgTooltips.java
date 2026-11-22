package com.crystalgraphics.mc.render.tooltip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.api.canvas.CgPicture;
import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.mc.render.CgHostPictures;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * Item tooltips as Minecraft draws them: {@link CgTooltipPicture} the whole tooltip, {@link CgItemTooltip} its lines
 * and images as data. This holds the era's {@link Host} and resolves tooltip data at the draw point.
 *
 * <pre>{@code
 * canvas.picture(CgTooltipPicture.of(stack), pointerX + 12, pointerY - 12);   // as every tooltip mod restyles it
 *
 * CgItemTooltip data = CgItemTooltip.of(stack);                              // for a UI that styles its own
 * for (CgStyledText line : data.lines()) ...
 * }</pre>
 */
public final class CgTooltips {

    /**
     * What each era fills: a stack's tooltip measured, drawn as a screen draws it, and read as data.
     *
     * <pre>{@code
     * final class TooltipsModern implements CgTooltips.Host {
     *     public int[] measure(Object stack) { ...the box a screen would draw, in units... }
     *     public void draw(CgHostDrawContext ctx, Object stack, int x, int y) {
     *         ((GuiGraphics) ctx.gui()).renderTooltip(font, (ItemStack) stack, x + 8, y - 8);   // its box at (x, y)
     *     }
     *     public List<CgStyledText> lines(Object stack) { ...each Component's styled runs... }
     * }
     * }</pre>
     */
    public interface Host {

        /** Why tooltips do not draw here, or null where they do. */
        @Nullable
        String absent();

        /** The host's stack for {@code stack}; null where the game does not know its item. */
        @Nullable
        default Object toHost(CgItemStack stack) {
            return CgItems.toHost(stack);
        }

        /**
         * The box a screen draws for {@code hostStack}'s tooltip, border included, in units: {@code {width, height}};
         * null where it shows none. Render thread.
         */
        @Nullable
        int[] measure(Object hostStack);

        /**
         * Draws {@code hostStack}'s tooltip as a screen draws it under the pointer, its box's top-left at {@code (x, y)}
         * in {@code ctx}'s units: through the tooltip events, so a tooltip mod's restyle shows. Render thread, inside a
         * {@link CgHostPictures.Host} draw.
         */
        void draw(CgHostDrawContext ctx, Object hostStack, int x, int y);

        /** {@code hostStack}'s tooltip lines, each loader's tooltip event included. Render thread. */
        List<CgStyledText> lines(Object hostStack);

        /** Pictures of the images its tooltip shows below its lines (a bundle's contents); none by default. Render thread. */
        default List<CgPicture> images(Object hostStack) {
            return List.of();
        }

        /** Draws nothing. */
        Host NONE = new Host() {
            @Override public String absent() { return "no host draws tooltips here"; }
            @Override public int[] measure(Object hostStack) { return null; }
            @Override public void draw(CgHostDrawContext ctx, Object hostStack, int x, int y) {
                throw new UnsupportedOperationException();
            }
            @Override public List<CgStyledText> lines(Object hostStack) { return List.of(); }
        };
    }

    /** The era's host; {@link Host#NONE} until one is provided. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:tooltips", Host.NONE);

    static final Logger LOGGER = LogManager.getLogger("CgTooltips");

    /** Tooltip pictures by stack: a few are shown at once, so the least recent past this many are dropped. */
    private static final int KEPT = 64;
    private static final Map<CgItemStack, CgTooltipPicture> PICTURES = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CgItemStack, CgTooltipPicture> eldest) {
            return size() > KEPT;
        }
    };
    private static final Map<CgItemStack, CgItemTooltip> DATA = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CgItemStack, CgItemTooltip> eldest) {
            return size() > KEPT;
        }
    };
    private static final Queue<CgItemTooltip> ASKED = new ConcurrentLinkedQueue<>();
    private static final List<CgItemTooltip> RESOLVING = new ArrayList<>();

    private static final CgHostPictures.Batch BATCH = new CgHostPictures.Batch() {
        @Override
        public boolean due() {
            return !ASKED.isEmpty() || !RESOLVING.isEmpty();
        }

        @Override
        public void draw(CgHostPictures.Host pictures, long frame, CgHostPictures.Budget budget) {
            resolve(frame);
        }
    };

    static {
        CgHostPictures.join(BATCH);
    }

    private CgTooltips() {
    }

    static synchronized CgTooltipPicture picture(CgItemStack stack) {
        return PICTURES.computeIfAbsent(stack, CgTooltipPicture::new);
    }

    static synchronized CgItemTooltip data(CgItemStack stack) {
        return DATA.computeIfAbsent(stack, CgItemTooltip::new);
    }

    static void asked(CgItemTooltip tooltip) {
        ASKED.add(tooltip);
    }

    /** The host's stack for {@code stack}; null where the game does not know its item, or cannot make it. */
    @Nullable
    static Object toHost(Host host, CgItemStack stack) {
        try {
            return host.toHost(stack);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // Each tooltip asked for in the last frames read again: its lines move with the keys held and the game's state
    private static void resolve(long frame) {
        for (CgItemTooltip t; (t = ASKED.poll()) != null; ) if (!RESOLVING.contains(t)) RESOLVING.add(t);
        Host host = CgPlatform.get(HOST);
        for (int i = RESOLVING.size() - 1; i >= 0; i--) {
            CgItemTooltip t = RESOLVING.get(i);
            if (!t.resolve(host, frame)) {
                RESOLVING.set(i, RESOLVING.get(RESOLVING.size() - 1));
                RESOLVING.remove(RESOLVING.size() - 1);
            }
        }
    }
}
