package com.crystalgraphics.platform.gl.tracked.tracker;

/**
 * What the tracker did, cumulative since it was made: spec §11's {@code CgDeviceStats}. A count that grows with
 * draws where it should grow with frames — pass breaks, pipeline misses — is the thing to look for.
 */
public final class CgTrackerStats {

    public long draws;
    public long dispatches;
    /** Compute passes: consecutive dispatches share one. */
    public long computePasses;
    /** Stretches of work sent to the compute queue beside the frame's ({@code cgBeginAsync}). */
    public long asyncSections;
    public long passes;
    /** Passes ended by a transfer rather than by a change of target, each resumed with {@code LOAD}. */
    public long passBreaks;
    public long clearsAsLoadOps;
    public long clearsInPass;
    public long pipelineBinds;
    public long pipelineMisses;
    /** CPU writes that went to fresh memory because a frame in flight still read the old. */
    public long renames;
    /** Texel uploads recorded into the frame's commands, their staged bytes, and the passes they ended. */
    public long textureWrites, textureWriteBytes, uploadBreaks;

    @Override
    public String toString() {
        return "draws=" + draws + " dispatches=" + dispatches + " computePasses=" + computePasses + " async=" + asyncSections + " passes=" + passes + " breaks=" + passBreaks + " loadClears=" + clearsAsLoadOps
                + " passClears=" + clearsInPass + " pipelineBinds=" + pipelineBinds + " misses=" + pipelineMisses
                + " renames=" + renames + " textureWrites=" + textureWrites + " textureWriteBytes=" + textureWriteBytes
                + " uploadBreaks=" + uploadBreaks;
    }
}
