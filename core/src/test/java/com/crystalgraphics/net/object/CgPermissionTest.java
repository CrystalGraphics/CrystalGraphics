package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.net.CgPermission;
import com.crystalgraphics.serialization.CgCodecs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A permission: the server's guard and the client's answer, one declaration. */
public class CgPermissionTest {

    static final CgPermission EDIT = CgPermission.define("test:permission.edit")
            .defaultsTo(CgPermission.OPERATORS).describedAs("Edit a sign").build();
    static final CgPermission READ = CgPermission.define("test:permission.read").defaultsTo(CgPermission.EVERYONE).build();
    static final CgPermission HOST_ONLY = CgPermission.define("test:permission.host").defaultsTo(CgPermission.OWNER).build();

    public static class Sign {
        public String text = "";
    }

    static final CgNetCommand<String> WRITE = CgNetCommand.of("write", CgCodecs.STRING);
    static final CgNetType<Sign> SIGN = CgNetType.define("test:permission/sign", CgCodecs.fieldsOf(Sign.class).build())
            .commands(WRITE).build();

    /** A check runs once a second per peer, and the answer takes a tick or two to arrive. */
    private static final int SECOND = 25;

    private CgNetTestRig rig;

    @After
    public void tearDown() {
        if (rig != null) rig.close();
    }

    private static String[] outcome(CgReply<Void> reply) {
        String[] seen = new String[1];
        reply.then(v -> seen[0] = "ok").onError(e -> seen[0] = e.code());
        return seen;
    }

    @Test
    public void aGuardedCommandRunsForOnePeerAndIsRefusedWithItsReasonForAnother() {
        rig = CgNetTestRig.of(2);
        rig.client(0).operator(true);
        SIGN.onCommand(WRITE, (sign, player, text) -> sign.text = text);
        SIGN.allow(WRITE, EDIT);
        rig.tick(3);
        Sign sign = new Sign();
        CgNetObject obj = SIGN.over(sign);
        obj.watch(rig.client(0).id());
        obj.watch(rig.client(1).id());
        rig.tick(2);

        String[] op = outcome(rig.client(0).visible(SIGN).get(0).ask(WRITE, "Kame House"));
        String[] other = outcome(rig.client(1).visible(SIGN).get(0).ask(WRITE, "vandalised"));
        rig.tick(3);
        assertEquals("ok", op[0]);
        assertEquals(CgNetCommand.REFUSED_NOT_ALLOWED, other[0]);
        assertEquals("Kame House", sign.text);
    }

    @Test
    public void aClientKnowsItsAnswersBeforehandAndHearsThemChange() {
        rig = CgNetTestRig.of(1);
        AtomicInteger changes = new AtomicInteger();
        CgPermission.onLocalChange(changes::incrementAndGet);
        rig.tick(SECOND);
        assertFalse("not an operator yet", EDIT.granted());
        assertTrue("everyone's", READ.granted());
        assertFalse(HOST_ONLY.granted());
        assertFalse(CgPermission.localOperator());
        int heard = changes.get();

        rig.client(0).operator(true);
        rig.tick(SECOND);
        assertTrue("made an operator mid-session: the answer follows", EDIT.granted());
        assertTrue(CgPermission.localOperator());
        assertEquals(heard + 1, changes.get());

        rig.client(0).owner(true).operator(false);
        rig.tick(SECOND);
        assertTrue("the owner with cheats off still has an operators' permission", EDIT.granted());
        assertTrue(HOST_ONLY.granted());
        assertTrue(CgPermission.localOwner());

        List<String> quiet = new ArrayList<>();
        CgPermission.onLocalChange(() -> quiet.add("changed"));
        rig.tick(SECOND * 3);
        assertTrue("nothing changed: nothing sent", quiet.isEmpty());
    }
}
