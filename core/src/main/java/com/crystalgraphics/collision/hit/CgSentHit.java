package com.crystalgraphics.collision.hit;

import com.crystalgraphics.collision.hitbox.CgHitboxType;
import com.crystalgraphics.net.object.CgNetEvent;
import com.crystalgraphics.net.object.CgNetWire;
import com.crystalgraphics.world.CgEntityQueries;

import javax.annotation.Nullable;

/**
 * A hit as it crosses the network: what met it (an entity and its whole, a hittable hitbox's owner, a block, a fluid's
 * surface, the edge of what is loaded, the world border, or nothing at a point), the point, the exposure, and the part
 * as an index into a list both sides hold. What {@link CgHitboxType} and VFX cues send their server's hits as.
 *
 * <pre>{@code
 * static final CgNetEvent<CgSentHit> HIT = CgNetEvent.of("mymod:hit", CgSentHit.wire());
 * private final CgSentHit sent = new CgSentHit();                    // a field, reused
 * private final CgHit arrived = new CgHit();
 *
 * obj.emit(HIT, sent.set(hit, PARTS.indexOf(hit.part())));          // server
 * type.onEvent(HIT, (obj, s) -> hear(s.read(arrived, s.part() < 0 ? null : PARTS.get(s.part()))));   // client
 * }</pre>
 *
 * <ul>
 *   <li>No normal or distance travels: what is read back has them at 0.</li>
 *   <li>A hittable hitbox arrives as its owner, without {@link CgHit#hitbox()} or {@link CgHit#hitboxPart()}.</li>
 *   <li>About 20 bytes a hit, the point and exposure as floats.</li>
 *   <li>A wire reuses the one it reads into: copy what you keep past the listener.</li>
 * </ul>
 */
public final class CgSentHit {

    private static final CgHit.Kind[] KINDS = CgHit.Kind.values();

    CgHit.Kind kind = CgHit.Kind.POINT;
    int entity = -1, parent = -1, blockX, blockY, blockZ, fluid, part = -1;
    float x, y, z, exposure = Float.NaN;

    /** Fills this from {@code hit}, its part sent as {@code part}, -1 for none. */
    public CgSentHit set(CgHit hit, int part) {
        kind = hit.kind();
        entity = hit.entityId();
        parent = hit.parentId();
        blockX = hit.blockX();
        blockY = hit.blockY();
        blockZ = hit.blockZ();
        fluid = hit.fluid();
        this.part = part;
        x = (float) hit.x();
        y = (float) hit.y();
        z = (float) hit.z();
        exposure = hit.exposure();
        return this;
    }

    /** The part's index, as {@link #set} was given it; -1 for none. */
    public int part() {
        return part;
    }

    /**
     * Writes this into {@code into}, its part {@code part}: the one this side holds at {@link #part()}. Its entity
     * resolves in the client's level, where a hit sent to viewers arrives.
     */
    public CgHit read(CgHit into, @Nullable CgHitPart part) {
        into.setPoint(x, y, z, part);
        switch (kind) {
            case BLOCK: return into.setBlock(blockX, blockY, blockZ);
            case FLUID: return into.setFluid(blockX, blockY, blockZ, fluid);
            case UNLOADED: return into.setUnloaded(blockX, blockY, blockZ);
            case BORDER: return into.setBorder(blockX, blockY, blockZ);
            case ENTITY: return into.setEntity(entity, parent).setExposure(exposure).from(CgEntityQueries.client());
            case HITBOX: return into.setHitbox(entity, null, null).from(CgEntityQueries.client());
            default: return into;
        }
    }

    /** A wire for one {@link CgNetEvent}: it reads into one hit of its own, so give each event its own. */
    public static CgNetWire<CgSentHit> wire() {
        return new Wire();
    }

    private static final class Wire implements CgNetWire<CgSentHit> {
        private final CgSentHit read = new CgSentHit();

        @Override
        public void write(CgSentHit s, Out out) {
            out.writeVarInt((s.part + 1) << 3 | s.kind.ordinal());
            if (s.kind == CgHit.Kind.ENTITY || s.kind == CgHit.Kind.HITBOX) {
                out.writeVarInt(s.entity);
                out.writeZigZag((long) s.parent - s.entity);
            } else if (s.kind != CgHit.Kind.POINT) {
                // Each block coordinate as its offset from the point, which lies on or beside it.
                out.writeZigZag(s.blockX - (long) Math.floor(s.x));
                out.writeZigZag(s.blockY - (long) Math.floor(s.y));
                out.writeZigZag(s.blockZ - (long) Math.floor(s.z));
                if (s.kind == CgHit.Kind.FLUID) out.writeVarInt(s.fluid);
            }
            out.writeFloat(s.x);
            out.writeFloat(s.y);
            out.writeFloat(s.z);
            out.writeFloat(s.exposure);
        }

        @Override
        public CgSentHit read(In in) {
            CgSentHit s = read;
            int head = in.readVarInt();
            s.kind = KINDS[head & 7];
            s.part = (head >>> 3) - 1;
            long bx = 0, by = 0, bz = 0;
            s.entity = s.parent = -1;
            s.fluid = 0;
            if (s.kind == CgHit.Kind.ENTITY || s.kind == CgHit.Kind.HITBOX) {
                s.entity = in.readVarInt();
                s.parent = (int) (s.entity + in.readZigZag());
            } else if (s.kind != CgHit.Kind.POINT) {
                bx = in.readZigZag();
                by = in.readZigZag();
                bz = in.readZigZag();
                if (s.kind == CgHit.Kind.FLUID) s.fluid = in.readVarInt();
            }
            s.x = in.readFloat();
            s.y = in.readFloat();
            s.z = in.readFloat();
            s.exposure = in.readFloat();
            s.blockX = (int) (bx + (long) Math.floor(s.x));
            s.blockY = (int) (by + (long) Math.floor(s.y));
            s.blockZ = (int) (bz + (long) Math.floor(s.z));
            return s;
        }
    }
}
