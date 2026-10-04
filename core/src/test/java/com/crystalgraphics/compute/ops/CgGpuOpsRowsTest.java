package com.crystalgraphics.compute.ops;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class CgGpuOpsRowsTest {

    private final List<String> got = new ArrayList<>();
    private final CgGpuOps.Rows sink = new CgGpuOps.Rows() {
        @Override
        public void accept(int count, ByteBuffer rows) {
            got.add(count + " rows " + rows.limit() / 16 + (rows.limit() > 0 ? " first " + rows.getInt(0) : ""));
        }

        @Override
        public void failed(String reason) {
            got.add("failed: " + reason);
        }
    };

    @Test
    public void theRowsStopAtTheCount_andACountPastTheCapacityIsDeliveredAsWritten() {
        deliver(5);
        deliver(12);
        deliver(0);
        assertEquals(List.of("5 rows 5 first 7", "12 rows 8 first 7", "0 rows 0"), got);
    }

    @Test
    public void aCountThatNeverArrived_failsTheRowsOnce() {
        CgGpuOps.RowsReadback rows = new CgGpuOps.RowsReadback(sink, 16, 8);
        rows.count.failed("the context was torn down");
        rows.failed("the context was torn down");
        assertEquals(List.of("failed: its count never arrived: the context was torn down"), got);
    }

    private void deliver(int count) {
        CgGpuOps.RowsReadback rows = new CgGpuOps.RowsReadback(sink, 16, 8);
        rows.count.accept(words(1, count));
        ByteBuffer data = words(32, 7);
        rows.accept(data);
    }

    private static ByteBuffer words(int n, int value) {
        ByteBuffer b = ByteBuffer.allocate(n * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < n; i++) b.putInt(i * 4, value);
        return b;
    }
}
