package com.crystalgraphics.net.object;

import com.crystalgraphics.serialization.CgStateMap;

/**
 * A type's own saved form, in place of each saved field under its name by its codec: for a save that should not look
 * like the fields (a compact form, keys of an older version, a value worked out from several fields). Given to
 * {@link CgNetType.Builder#savedAs}; used wherever the type's objects are saved: an entity's data, a player's file, the
 * world's file. Mirrors Unreal's {@code UObject::Serialize} override, which replaces the declared properties' form.
 *
 * <pre>{@code
 * // two fields saved as one "x,y" text, as an older version of the mod wrote them
 * static final CgNetType<CgNetObject> TARGET = CgNetType.define("mymod:target").fields(X.persisted(), Y.persisted())
 *         .onEntities(type -> type.equals("minecraft:zombie"))
 *         .savedAs(new CgNetSaveForm() {
 *             public <T> void write(CgNetObject obj, CgStateMap<T> out) {
 *                 out.putString("at", obj.getInt(X) + "," + obj.getInt(Y));
 *             }
 *             public <T> void read(CgNetObject obj, CgStateMap<T> in) {
 *                 String[] at = in.getString("at", "0,0").split(",");
 *                 obj.setInt(X, Integer.parseInt(at[0])).setInt(Y, Integer.parseInt(at[1]));
 *             }
 *         })
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>Which fields are saved still decides when: a change to a {@code persisted()} field marks the object to be
 *       saved, and a type with none saved refuses a form. Write from those fields.</li>
 *   <li>{@code read} runs on the server before the object starts: set its fields there, nothing is sent yet. A key that
 *       is not there reads its default, as an older save lacks a key added since.</li>
 *   <li>A read that throws is logged and the object keeps what it set before the throw, the rest at their fallbacks, as
 *       with a field that does not decode.</li>
 * </ul>
 */
public interface CgNetSaveForm {

    /** Writes {@code obj}'s saved form into {@code out}. Server thread. */
    <T> void write(CgNetObject obj, CgStateMap<T> out);

    /** Sets {@code obj}'s fields from what {@link #write} wrote. Server thread, before it starts. */
    <T> void read(CgNetObject obj, CgStateMap<T> in);
}
