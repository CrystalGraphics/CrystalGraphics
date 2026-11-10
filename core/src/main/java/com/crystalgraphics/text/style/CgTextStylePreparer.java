package com.crystalgraphics.text.style;

import java.util.function.BooleanSupplier;

import com.crystalgraphics.asset.load.CgAssetPreparer;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.reply.CgReplyError;

/**
 * A text style's face read before it is READY, on a worker, at the size a draw in it asks for: the first text drawn
 * in the style never reads a font file on the render thread.
 *
 * <pre>{@code
 * assets.preparer(CgLookKinds.TEXT_STYLE, new CgTextStylePreparer());   // CgVfx.preparers registers it
 * }</pre>
 *
 * <ul>
 *   <li>A face that will not load fails the style's load, naming the style and the file.</li>
 *   <li>A style with no font of its own (a draw names one) prepares nothing.</li>
 *   <li>A dedicated server registers none: it draws no text.</li>
 * </ul>
 */
public final class CgTextStylePreparer implements CgAssetPreparer {

    @Override
    public void plan(Object definition, Plan plan) {
        if (!(definition instanceof CgTextStyle style) || style.font() == null) return;
        // The size CgTextDraw asks a style's font at: its px, rounded, at least one.
        int px = Math.max(1, Math.round(style.px()));
        plan.warm("face of " + style.id() + " at " + px + " px", new FaceRead(style, px));
    }

    /** Starts the read on a worker once, then answers whether it has finished. */
    private static final class FaceRead implements BooleanSupplier {
        private final CgTextStyle style;
        private final int px;
        private CgJob<Void> read;

        FaceRead(CgTextStyle style, int px) {
            this.style = style;
            this.px = px;
        }

        @Override
        public boolean getAsBoolean() {
            if (read == null) read = CgJob.run(() -> { style.font(px); }).named("face of " + style.id());
            if (!read.isDone()) return false;
            CgReplyError error = read.error();
            if (error != null) {
                throw new IllegalStateException(style.id() + ": its face did not load: " + error.detail(), error.cause());
            }
            return true;
        }
    }
}
