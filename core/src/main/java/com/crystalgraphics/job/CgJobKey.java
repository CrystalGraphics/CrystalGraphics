package com.crystalgraphics.job;

import java.util.Objects;

/**
 * What makes two jobs the same job: an owner, compared by identity, and a kind. Starting a job whose key is already
 * waiting replaces it; one whose key is running supersedes it. Made by {@link CgJob#keyed}.
 *
 * <ul>
 *   <li>The owner is a live object (a document, an entity), so two owners holding equal values stay apart.</li>
 *   <li>Both halves matter: keyed on the kind alone, two documents' reparses would cancel each other.</li>
 * </ul>
 */
public final class CgJobKey {

    private final Object owner;
    private final String kind;

    private CgJobKey(Object owner, String kind) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public static CgJobKey of(Object owner, String kind) {
        return new CgJobKey(owner, kind);
    }

    public Object owner() {
        return owner;
    }

    public String kind() {
        return kind;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CgJobKey && ((CgJobKey) other).owner == owner && ((CgJobKey) other).kind.equals(kind);
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(owner) * 31 + kind.hashCode();
    }

    @Override
    public String toString() {
        return kind + "@" + Integer.toHexString(System.identityHashCode(owner));
    }
}
