package com.crystalgraphics.collision.hitbox;

import com.crystalgraphics.collision.CgHitPart;
import com.crystalgraphics.collision.query.CgHit;
import com.crystalgraphics.net.object.CgNetEvent;
import com.crystalgraphics.net.object.CgNetWire;

import javax.annotation.Nullable;

/**
 * A hit as it crosses the network: what met it (an entity and its whole, a block, a fluid's surface, the edge of what
 * is loaded, the world border, or nothing at a point), the point, the exposure, and the part as an index into a list both sides hold.
 * What {@link CgHitboxType} and VFX cues send their server's hits as.
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
 *   <li>About 20 bytes a hit, the point and exposure as floats.</li>
 *   <li>A wire reuses the one it reads into: copy what you keep past the listener.</li>
 * </ul>
 */
public final class CgSentHit {

    private static final int POINT = 0, ENTITY = 1, BLOCK = 2, FLUID = 3, UNLOADED = 4, BORDER = 5;

    int kind, entity = -1, parent = -1, blockX, blockY, blockZ, fluid, part = -1;
    float x, y, z, exposure = Float.NaN;

    /** Fills this from {@code hit}, its part sent as {@code part}, -1 for none. */
    public CgSentHit set(CgHit hit, int part) {
        switch (hit.kind()) {
            case ENTITY: kind = ENTITY; break;
            case BLOCK: kind = BLOCK; break;
            case FLUID: kind = FLUID; break;
            case UNLOADED: kind = UNLOADED; break;
            case BORDER: kind = BORDER; break;
            default: kind = POINT;
        }
        entity = hit.entity();
        parent = hit.parent();
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

    /** Writes this into {@code into}, its part {@code part}: the one this side holds at {@link #part()}. */
    public CgHit read(CgHit into, @Nullable CgHitPart part) {
        switch (kind) {
            case BLOCK: return into.setBlock(blockX, blockY, blockZ, x, y, z, part);
            case FLUID: return into.setFluid(blockX, blockY, blockZ, fluid, x, y, z, part);
            case UNLOADED: return into.setUnloaded(blockX, blockY, blockZ, x, y, z, part);
            case BORDER: return into.setBorder(blockX, blockY, blockZ, x, y, z, part);
            case ENTITY: return into.setEntity(entity, parent, x, y, z, exposure, part);
            default: return into.setPoint(x, y, z, part);
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
            out.writeVarInt((s.part + 1) << 3 | s.kind);
            if (s.kind == ENTITY) {
                out.writeVarInt(s.entity);
                out.writeZigZag((long) s.parent - s.entity);
            } else if (s.kind != POINT) {
                // Each block coordinate as its offset from the point, which lies on or beside it.
                out.writeZigZag(s.blockX - (long) Math.floor(s.x));
                out.writeZigZag(s.blockY - (long) Math.floor(s.y));
                out.writeZigZag(s.blockZ - (long) Math.floor(s.z));
                if (s.kind == FLUID) out.writeVarInt(s.fluid);
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
            s.kind = head & 7;
            s.part = (head >>> 3) - 1;
            long bx = 0, by = 0, bz = 0;
            s.entity = s.parent = -1;
            s.fluid = 0;
            if (s.kind == ENTITY) {
                s.entity = in.readVarInt();
                s.parent = (int) (s.entity + in.readZigZag());
            } else if (s.kind != POINT) {
                bx = in.readZigZag();
                by = in.readZigZag();
                bz = in.readZigZag();
                if (s.kind == FLUID) s.fluid = in.readVarInt();
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
