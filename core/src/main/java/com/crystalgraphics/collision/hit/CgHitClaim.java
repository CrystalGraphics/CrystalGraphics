package com.crystalgraphics.collision.hit;

import com.crystalgraphics.collision.hitbox.CgHitboxType;
import com.crystalgraphics.net.object.CgNetWire;

import javax.annotation.Nullable;

/**
 * One hit a claimed hitbox's owner says it made: what a {@link CgHitboxType} declared {@code claimed()} sends its
 * server, which checks it before it counts. What a refusal's listeners are handed.
 *
 * <pre>{@code
 * PUNCH.onRefusedClaim((obj, claim) -> Anticheat.note(obj.owner(), claim.target(), claim.reason()));   // server
 * PUNCH.onRefusedHere((obj, claim) -> HitStop.cancel());                                              // the owner
 * }</pre>
 *
 * <ul>
 *   <li>A listener is handed one reused instance: copy what it keeps.</li>
 *   <li>{@link #reason()} is null on a claim not yet judged.</li>
 * </ul>
 */
public final class CgHitClaim {

    /** Why the server refused a claim, checked in this order. */
    public enum Refusal {
        /** The server has no such entity, or it is dead. */
        NO_TARGET,
        /** Its age is more than the type's window from the server's copy. */
        OUT_OF_WINDOW,
        /** The target's box, grown by the padding, touched none of the hitbox's places over the window. */
        OUT_OF_RANGE,
        /** The hitbox's own rules refuse it: its source, its query's filters, once, rehit, maxHits, maxTargets. */
        RULE
    }

    private static final Refusal[] REFUSALS = Refusal.values();

    int target = -1, part = -1, age, hurtbox = -1;
    float x, y, z;
    @Nullable
    Refusal reason;

    /** The entity it says it hit, by network id: a part's own id for a part. */
    public int target() {
        return target;
    }

    /** The hitbox's part that hit, as an index into its parts; -1 for none. */
    public int part() {
        return part;
    }

    /** The hitbox's age, in ticks, when its owner's copy hit. */
    public int age() {
        return age;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float z() {
        return z;
    }

    /** Why it was refused; null for one not judged. */
    @Nullable
    public Refusal reason() {
        return reason;
    }

    /** Fills this from the owner's hit, its part sent as {@code part}, made at {@code age}. */
    public CgHitClaim set(CgHit hit, int part, int age) {
        target = hit.entityId();
        this.part = part;
        this.age = age;
        x = (float) hit.x();
        y = (float) hit.y();
        z = (float) hit.z();
        hurtbox = -1;
        reason = null;
        return this;
    }

    /** Copies {@code o}. */
    public CgHitClaim set(CgHitClaim o) {
        target = o.target;
        part = o.part;
        age = o.age;
        hurtbox = o.hurtbox;
        x = o.x;
        y = o.y;
        z = o.z;
        reason = o.reason;
        return this;
    }

    /** This claim, refused for {@code reason}. */
    public CgHitClaim refused(@Nullable Refusal reason) {
        this.reason = reason;
        return this;
    }

    /** A wire for one command or event: it reads into one claim of its own, so give each its own. About 18 bytes. */
    public static CgNetWire<CgHitClaim> wire() {
        return new Wire();
    }

    private static final class Wire implements CgNetWire<CgHitClaim> {
        private final CgHitClaim read = new CgHitClaim();

        @Override
        public void write(CgHitClaim c, Out out) {
            out.writeVarInt(c.target);
            out.writeVarInt(c.part + 1);
            out.writeVarInt(c.age);
            out.writeFloat(c.x);
            out.writeFloat(c.y);
            out.writeFloat(c.z);
            out.writeVarInt(c.hurtbox + 1);
            out.writeVarInt(c.reason == null ? 0 : c.reason.ordinal() + 1);
        }

        @Override
        public CgHitClaim read(In in) {
            CgHitClaim c = read;
            c.target = in.readVarInt();
            c.part = in.readVarInt() - 1;
            c.age = in.readVarInt();
            c.x = in.readFloat();
            c.y = in.readFloat();
            c.z = in.readFloat();
            c.hurtbox = in.readVarInt() - 1;
            int reason = in.readVarInt();
            c.reason = reason <= 0 || reason > REFUSALS.length ? null : REFUSALS[reason - 1];
            return c;
        }
    }

    @Override
    public String toString() {
        return "CgHitClaim[" + target + " by part " + part + " at age " + age + (reason == null ? "" : ", " + reason) + "]";
    }
}
