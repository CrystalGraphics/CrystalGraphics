package com.crystalgraphics.render.graph;

/** What a graph buffer is used as, which its passes are checked against. */
public enum CgBufferUsage {
    /** A kernel's buffer, or a storage block a draw reads. */
    STORAGE,
    /** Vertex attributes. */
    VERTEX,
    /** Indices. */
    INDEX,
    /** A draw's or a dispatch's arguments. */
    INDIRECT,
    /** A uniform block. */
    UNIFORM,
    /** The source or destination of a copy, a fill or an update. */
    COPY
}
