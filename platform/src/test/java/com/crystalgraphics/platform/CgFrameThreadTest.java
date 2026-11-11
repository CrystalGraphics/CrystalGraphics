package com.crystalgraphics.platform;

import com.crystalgraphics.platform.input.CgClipboard;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CgFrameThreadTest {

    private final StringBuilder clipboard = new StringBuilder();

    @Before
    public void setUp() {
        CgFrameThread.resetForTesting();
        CgPlatform.provide(CgClipboard.HOST, new CgClipboard.Host() {
            @Override public String get() { return clipboard.toString(); }
            @Override public void set(String text) { clipboard.setLength(0); clipboard.append(text); }
        });
    }

    @After
    public void tearDown() {
        CgFrameThread.resetForTesting();
        CgPlatform.provide(CgClipboard.HOST, CgClipboard.Host.NONE);
    }

    @Test
    public void workFromAnotherThreadRunsAtTheNextFrameInOrder() throws InterruptedException {
        CgFrameThread.drain();   // this thread draws frames
        List<String> ran = new ArrayList<>();
        Thread worker = new Thread(() -> {
            CgFrameThread.run(() -> ran.add("first"));
            CgFrameThread.run(() -> ran.add("second"));
        });
        worker.start();
        worker.join();
        assertTrue("nothing runs before the frame", ran.isEmpty());
        CgFrameThread.drain();
        assertEquals(List.of("first", "second"), ran);
    }

    @Test
    public void onTheFrameThreadWorkRunsAtOnce() {
        CgFrameThread.drain();
        List<String> ran = new ArrayList<>();
        CgFrameThread.run(() -> ran.add("now"));
        assertEquals(List.of("now"), ran);
    }

    @Test
    public void aClipboardWriteFromAWorkerLandsAtTheFrame_andAReadWaitsForIt() throws InterruptedException {
        CgFrameThread.drain();
        Thread writer = new Thread(() -> CgClipboard.set("copied"));
        writer.start();
        writer.join();
        assertEquals("", clipboard.toString());
        CgFrameThread.drain();
        assertEquals("copied", clipboard.toString());

        AtomicReference<String> read = new AtomicReference<>();
        Thread reader = new Thread(() -> read.set(CgClipboard.get()));
        reader.start();
        while (reader.isAlive()) {
            CgFrameThread.drain();   // the frame thread keeps drawing frames while the reader waits
            reader.join(5);
        }
        assertEquals("copied", read.get());
    }
}
