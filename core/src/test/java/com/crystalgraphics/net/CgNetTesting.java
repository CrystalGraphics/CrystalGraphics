package com.crystalgraphics.net;

import com.crystalgraphics.net.protocol.CgProtocols;

/** The network's declarations and connections back to none, for a test outside this package. */
public final class CgNetTesting {

    private CgNetTesting() {
    }

    public static void reset() {
        CgNetwork.resetForTesting();
        CgProtocols.resetForTesting();
        CgMessage.resetForTesting();
        CgReplicated.resetForTesting();
    }
}
