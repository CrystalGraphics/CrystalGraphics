/*
 * Ported from Chromium's components/download/public/common/rate_estimator.h and
 * components/download/internal/common/rate_estimator.cc, under this licence:
 *
 * Copyright 2015 The Chromium Authors
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
package com.crystalgraphics.job.progress;

import java.util.Arrays;

/**
 * How fast work is arriving: a ring of one-second buckets, ten of them, so the rate is over the last ten seconds.
 * Ported from Chromium's download {@code RateEstimator} (BSD-3-Clause, above), as CrystalGUI's was.
 *
 * <ul>
 *   <li>The divisor is the buckets populated so far, not the window: a second in, dividing by ten would read a tenth
 *       of the rate.</li>
 *   <li>A clock that goes backwards, or a pause longer than the window, starts over.</li>
 *   <li>Not thread-safe: one reporter writes it.</li>
 * </ul>
 */
final class CgRateEstimator {

    static final long DEFAULT_BUCKET_MILLIS = 1000L;
    static final int DEFAULT_BUCKETS = 10;

    private final long bucketMillis;
    private final long[] history;
    private int oldestIndex;
    /** Buckets holding real time: grows to the ring's size, and is the divisor. */
    private int bucketCount;
    private long oldestMillis;

    CgRateEstimator(long nowMillis) {
        this(DEFAULT_BUCKET_MILLIS, DEFAULT_BUCKETS, nowMillis);
    }

    CgRateEstimator(long bucketMillis, int buckets, long nowMillis) {
        this.bucketMillis = Math.max(1L, bucketMillis);
        this.history = new long[Math.max(1, buckets)];
        reset(nowMillis);
    }

    /** Records {@code count} units as arriving now. */
    void increment(long count, long nowMillis) {
        if (count <= 0) return;
        slide(nowMillis);
        history[(oldestIndex + bucketCount - 1) % history.length] += count;
    }

    /** Units a second over the window, or zero before anything arrived. */
    long countPerSecond(long nowMillis) {
        slide(nowMillis);
        long total = 0;
        for (int at = 0; at < bucketCount; at++) total += history[(oldestIndex + at) % history.length];
        return total * 1000L / (bucketCount * bucketMillis);
    }

    private void slide(long nowMillis) {
        if (nowMillis < oldestMillis) {
            reset(nowMillis);
            return;
        }
        long wanted = (nowMillis - oldestMillis) / bucketMillis + 1;
        if (wanted <= bucketCount) return;
        if (wanted > history.length) {
            long retire = wanted - history.length;
            if (retire >= history.length) {
                reset(nowMillis);
                return;
            }
            for (long dropped = 0; dropped < retire; dropped++) {
                history[(int) ((oldestIndex + dropped) % history.length)] = 0L;
            }
            oldestIndex = (int) ((oldestIndex + retire) % history.length);
            oldestMillis += retire * bucketMillis;
            bucketCount = history.length;
            return;
        }
        bucketCount = (int) wanted;
    }

    private void reset(long nowMillis) {
        Arrays.fill(history, 0L);
        oldestIndex = 0;
        bucketCount = 1;
        oldestMillis = nowMillis;
    }
}
