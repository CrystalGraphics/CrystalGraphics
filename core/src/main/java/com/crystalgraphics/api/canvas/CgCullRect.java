/*
 * Ported from Chromium Blink's third_party/blink/renderer/platform/graphics/paint/cull_rect.cc, under this licence:
 *
 * Copyright 2014 The Chromium Authors
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *    * Neither the name of Google LLC nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.crystalgraphics.api.canvas;

/**
 * How much of a scroller's contents to record: its viewport expanded by an interest margin, so a scroll moved by
 * property values ({@link CgCanvas#movable}) shows recorded rows rather than blank ones, and when to record it again.
 * Ported from Chromium Blink's {@code CullRect} ({@code cull_rect.cc}, BSD-3-Clause, see {@code THIRD-PARTY.md}):
 * {@code ApplyScrollTranslation}'s expansion and {@code ChangedEnough}, with Blink's defaults.
 *
 * <pre>{@code
 * float[] cull = new float[4];
 * CgCullRect.forScroller(scrollX, scrollY, viewW, viewH, contentW, contentH, scale, cull);
 * paintRowsInside(cull);                                   // record what the cull rect holds
 *
 * // a later frame, scrolled by values alone:
 * CgCullRect.forScroller(newX, newY, viewW, viewH, contentW, contentH, scale, next);
 * if (CgCullRect.changedEnough(cull, next, contentW, contentH, scale)) recordAgain(next);
 * }</pre>
 *
 * <ul>
 *   <li>Rects are {@code x0, y0, x1, y1} in the contents' units, the contents from (0, 0).</li>
 *   <li>{@code scale} is the device pixels per unit (Blink's expansion ratio): margins are device distances.</li>
 *   <li>A scroller that scrolls both ways expands half as far each way; one that does not scroll an axis does not
 *       expand it.</li>
 * </ul>
 */
public final class CgCullRect {

    /** Device pixels a scroller's cull rect reaches past its viewport: Blink's {@code kCullRectPixelDistanceToExpand}. */
    public static final float DISTANCE_TO_EXPAND = 4000f;
    /** Device pixels a scroll must take the view past the last cull rect to record again: {@code kCullRectChangedEnoughDistance}. */
    public static final float CHANGED_ENOUGH = 512f;
    /** A viewport of at most this many square device pixels is a small scroller, expanded by the minimum alone. */
    public static final float SMALL_SCROLLER_AREA = 100000f;

    private CgCullRect() {
    }

    /** The cull rect of a scroller viewing {@code (viewX, viewY, viewW, viewH)} of {@code contentW x contentH} contents. */
    public static void forScroller(float viewX, float viewY, float viewW, float viewH, float contentW, float contentH,
                                   float scale, float[] out) {
        float k = Math.max(scale, 1e-6f);
        float changed = CHANGED_ENOUGH / k;
        float min = 2f * changed;
        boolean small = viewW * viewH * k * k <= SMALL_SCROLLER_AREA;
        float distance = small ? min : Math.max(DISTANCE_TO_EXPAND / k, min);
        float outsetX = distance, outsetY = distance;
        float rangeX = contentW - viewW, rangeY = contentH - viewH;
        if (rangeX <= 0f) outsetX = 0f;
        if (rangeY <= 0f) outsetY = 0f;
        if (outsetX > 0f && outsetY > 0f) {
            // Scrolling both ways: half each way, the excess past one axis's range given to the other
            outsetX /= 2f;
            outsetY /= 2f;
            if (outsetX > rangeX) outsetY += outsetX - rangeX;
            if (outsetY > rangeY) outsetX += outsetY - rangeY;
        }
        outsetX = Math.max(0f, Math.min(Math.max(outsetX, rangeX > 0f ? min : 0f), rangeX));
        outsetY = Math.max(0f, Math.min(Math.max(outsetY, rangeY > 0f ? min : 0f), rangeY));
        out[0] = Math.max(0f, viewX - outsetX);
        out[1] = Math.max(0f, viewY - outsetY);
        out[2] = Math.min(contentW, viewX + viewW + outsetX);
        out[3] = Math.min(contentH, viewY + viewH + outsetY);
    }

    /**
     * Whether a scroller recorded over {@code old} should record again over {@code now}: the new cull rect reaches
     * {@link #CHANGED_ENOUGH} past the old, or touches an edge of the contents the old did not.
     */
    public static boolean changedEnough(float[] old, float[] now, float contentW, float contentH, float scale) {
        boolean oldEmpty = old[2] <= old[0] || old[3] <= old[1], nowEmpty = now[2] <= now[0] || now[3] <= now[1];
        if (oldEmpty && nowEmpty) return false;
        if (contains(old, now, 0f)) return false;
        if (oldEmpty) return true;
        float k = Math.max(scale, 1e-6f);
        if (!contains(old, now, CHANGED_ENOUGH / k)) return true;
        // Past the contents' edge nothing more can show, so touching one the old did not is reason enough
        return now[0] == 0f && old[0] != 0f || now[1] == 0f && old[1] != 0f
                || now[2] == contentW && old[2] != contentW || now[3] == contentH && old[3] != contentH;
    }

    private static boolean contains(float[] outer, float[] inner, float outset) {
        return inner[0] >= outer[0] - outset && inner[1] >= outer[1] - outset
                && inner[2] <= outer[2] + outset && inner[3] <= outer[3] + outset;
    }
}
