package com.crystalgraphics.asset.tree;

/** An asset that cannot load: a parent missing, a cycle, units that differ from the parent's, an unknown id to remove. */
public final class CgAssetException extends RuntimeException {

    public CgAssetException(String message) {
        super(message);
    }
}
