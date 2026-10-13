package com.crystalgraphics.job.reply;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Replies and streams: settling once, late listeners, cancel, composition. */
public class CgReplyTest {

    @Test
    public void settlesOnceAndALateListenerStillHears() {
        CgPendingReply<String> reply = new CgPendingReply<>(null);
        List<String> heard = new ArrayList<>();
        AtomicInteger always = new AtomicInteger();
        reply.then(heard::add).always(always::incrementAndGet);
        reply.resolve("a");
        reply.resolve("b");
        assertFalse(reply.fail(CgReplyError.cancelled()));
        reply.then(heard::add).always(always::incrementAndGet);
        assertEquals(List.of("a", "a"), heard);
        assertEquals(2, always.get());
        assertNull(reply.error());
    }

    @Test
    public void cancellingTellsTheProducerAndSettlesCancelled() {
        AtomicInteger told = new AtomicInteger();
        CgPendingReply<String> reply = new CgPendingReply<>(told::incrementAndGet);
        CgReply<Integer> view = reply.map(String::length);
        AtomicInteger always = new AtomicInteger();
        reply.always(always::incrementAndGet);
        view.cancel();                                        // through the view
        assertEquals(1, told.get());
        assertTrue(reply.error().is(CgReplyError.CANCELLED));
        assertEquals(1, always.get());
        reply.cancel();
        assertEquals("a settled reply ignores cancel", 1, told.get());
    }

    @Test
    public void bothAndAllWaitForEveryOneAndFailWithTheFirst() {
        CgPendingReply<Integer> a = new CgPendingReply<>(null);
        CgPendingReply<String> b = new CgPendingReply<>(null);
        CgReply<CgReply.Both<Integer, String>> both = CgReply.both(a, b);
        CgReply<Void> all = CgReply.all(Arrays.asList(a, b));
        a.resolve(1);
        assertFalse(both.isDone() || all.isDone());
        b.resolve("x");
        assertEquals(Integer.valueOf(1), both.result().first());
        assertTrue(all.isDone() && all.error() == null);

        CgReply<Void> failing = CgReply.all(Arrays.asList(CgReply.of(1), CgReply.failed(new CgReplyError("NOPE", "first")),
                CgReply.failed(new CgReplyError("NOPE", "second"))));
        assertEquals("first", failing.error().detail());
        assertTrue(CgReply.all(List.of()).isDone());
    }

    @Test
    public void everyListenerRunsWhenOneThrows() {
        CgPendingReply<String> reply = new CgPendingReply<>(null);
        AtomicInteger ran = new AtomicInteger();
        reply.then(s -> {
            throw new IllegalStateException("first");
        }).then(s -> ran.incrementAndGet()).always(ran::incrementAndGet);
        try {
            reply.resolve("v");
            fail("the throw is rethrown after the rest");
        } catch (IllegalStateException e) {
            assertEquals("first", e.getMessage());
        }
        assertEquals(2, ran.get());
    }

    @Test
    public void aStreamReplaysItsPiecesAndKeepsThemOnFailure() {
        CgPendingStream<String> stream = new CgPendingStream<>(null);
        stream.emit("a");
        List<String> pieces = new ArrayList<>();
        List<List<String>> whole = new ArrayList<>();
        stream.onPartial(pieces::add).then(whole::add);
        stream.emit("b");
        stream.finish();
        stream.emit("c");
        assertEquals(List.of("a", "b"), pieces);
        assertEquals(List.of(List.of("a", "b")), whole);

        CgPendingStream<String> failed = new CgPendingStream<>(null);
        List<String> kept = new ArrayList<>();
        failed.onPartial(kept::add);
        failed.emit("x");
        failed.fail(new CgReplyError("CLOSED", "gone"));
        failed.emit("y");
        assertEquals(List.of("x"), kept);
        assertEquals("CLOSED", failed.error().code());
    }
}
