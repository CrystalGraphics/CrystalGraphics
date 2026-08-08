package com.crystalgraphics.shadergraph;

import javax.annotation.Nullable;

/**
 * One thing wrong with a graph, <b>attributed</b> — the compiler's output instead of a sentence.
 *
 * <h3>Why this is not a String</h3>
 *
 * <p>Every problem the compiler raises already knows which node it is about, and most know the port too:
 * they were being formatted into prose one line earlier than they should have been, as
 * {@code "Node 'x' input 'y' wants vec3"}. A consumer then had exactly two options — show the sentence, or
 * parse it back apart — so the editor showed {@code "3 error(s)"} and the graph highlighted nothing, while
 * the information needed to select the offending node was present at the point of the throw.</p>
 *
 * <p>The message stays, because a human still has to read something. What changes is that the identity
 * travels beside it rather than inside it.</p>
 *
 * @param nodeId   the node it is about, or null for a problem about the graph as a whole (no output node,
 *                 a cycle, an absent root)
 * @param portId   the port on that node, or null when the problem is the node's
 * @param severity how bad it is
 * @param message  what to show a human — self-contained, so a display that knows nothing of nodes is still
 *                 correct
 */
public record CgShaderProblem(@Nullable String nodeId, @Nullable String portId,
                              Severity severity, String message) {

    public enum Severity {
        /** The graph will not compile, or this node will not draw. */
        ERROR,
        /**
         * Worth saying, and the graph still compiles.
         *
         * <p>Nothing raises one yet. It is here because the alternative — adding the enum when the first
         * warning appears — changes the shape of every consumer's switch at the moment a warning is being
         * introduced, which is the worst time to be editing them.</p>
         */
        WARNING
    }

    public CgShaderProblem {
        if (severity == null) severity = Severity.ERROR;
        if (message == null) message = "";
    }

    /** A problem about the graph itself — no node to point at. */
    public static CgShaderProblem graph(String message) {
        return new CgShaderProblem(null, null, Severity.ERROR, message);
    }

    /** A problem about one node. */
    public static CgShaderProblem node(String nodeId, String message) {
        return new CgShaderProblem(nodeId, null, Severity.ERROR, message);
    }

    /** A problem about one port of one node. */
    public static CgShaderProblem port(String nodeId, String portId, String message) {
        return new CgShaderProblem(nodeId, portId, Severity.ERROR, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        return message;
    }
}
