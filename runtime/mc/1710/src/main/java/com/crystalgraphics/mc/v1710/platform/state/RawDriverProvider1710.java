package com.crystalgraphics.mc.v1710.platform.state;

import com.crystalgraphics.platform.gl.state.CgGlGetProvider;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Reads GL state from the driver itself, past Angelica.
 *
 * <p>Angelica rewrites every {@code glGet} call site to its {@code GLStateManager}, which answers from its
 * cache, so under Angelica the ordinary reader cannot tell that cache from the driver. These reads go
 * through method handles, which its call-site rewrite never sees. For
 * {@code -Dcrystalgraphics.state.roundTrip}, which compares the two:</p>
 *
 * <pre>{@code
 * RawDriverProvider1710 raw = RawDriverProvider1710.create();   // null if a handle will not resolve
 * if (raw != null) CgGlState.setDriverReader(raw);
 * }</pre>
 *
 * <p>The active unit it moves for the texture read is moved behind Angelica's back too, and put back before
 * the read returns, so Angelica's cache is left describing the driver as it found it.</p>
 */
public final class RawDriverProvider1710 extends CgGlGetProvider {

    private final MethodHandle integer, bool, real, integers, booleans, activeTexture;

    private RawDriverProvider1710(MethodHandles.Lookup l, Class<?> gl11, Class<?> gl13) throws ReflectiveOperationException {
        integer       = l.findStatic(gl11, "glGetInteger", MethodType.methodType(int.class, int.class));
        bool          = l.findStatic(gl11, "glGetBoolean", MethodType.methodType(boolean.class, int.class));
        real          = l.findStatic(gl11, "glGetFloat", MethodType.methodType(float.class, int.class));
        // LWJGL 2's names, else LWJGL 3's, which lwjgl3ify puts under the same class.
        integers      = either(l, gl11, "glGetInteger", "glGetIntegerv", MethodType.methodType(void.class, int.class, IntBuffer.class));
        booleans      = either(l, gl11, "glGetBoolean", "glGetBooleanv", MethodType.methodType(void.class, int.class, ByteBuffer.class));
        activeTexture = l.findStatic(gl13, "glActiveTexture", MethodType.methodType(void.class, int.class));
    }

    private static MethodHandle either(MethodHandles.Lookup l, Class<?> owner, String name, String alternative,
                                       MethodType type) throws ReflectiveOperationException {
        try {
            return l.findStatic(owner, name, type);
        } catch (NoSuchMethodException lwjgl3) {
            return l.findStatic(owner, alternative, type);
        }
    }

    /** The reader, or {@code null} when this LWJGL has none of the signatures it calls. */
    public static RawDriverProvider1710 create() {
        try {
            return new RawDriverProvider1710(MethodHandles.publicLookup(),
                    Class.forName("org.lwjgl.opengl.GL11"), Class.forName("org.lwjgl.opengl.GL13"));
        } catch (ReflectiveOperationException | LinkageError e) {
            System.err.println("[crystalgraphics] state.roundTrip: no raw driver reader (" + e
                    + ") -- the host cache is not compared with the driver");
            return null;
        }
    }

    @Override protected int integer(int pname) { return (int) call(integer, pname); }

    @Override protected boolean bool(int pname) { return (boolean) call(bool, pname); }

    @Override protected float real(int pname) { return (float) call(real, pname); }

    @Override protected void integers(int pname, IntBuffer into) { call(integers, pname, into); }

    @Override protected void booleans(int pname, ByteBuffer into) { call(booleans, pname, into); }

    @Override protected void activeTexture(int texture) { call(activeTexture, texture); }

    private static Object call(MethodHandle h, Object... args) {
        try {
            return h.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
