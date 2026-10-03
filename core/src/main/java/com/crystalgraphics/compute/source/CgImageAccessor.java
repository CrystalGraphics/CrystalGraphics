package com.crystalgraphics.compute.source;

/**
 * The names a kernel reaches a storage image through, each {@code NAME} plus a suffix, given as
 * {@link CgBufferAccessor}'s are.
 *
 * <pre>{@code
 * vec4 c = SOURCE_LOAD(CG_TEXEL.xy);      // LOAD: readonly or readwrite
 * OUT_WRITE(c);                           // WRITE: this texel; image or general
 * DENSITY_ADD(cell, 1u);                  // ADD, MIN, MAX: r32i or r32ui, readwrite; general
 * ivec2 size = OUT_SIZE();
 * }</pre>
 */
public enum CgImageAccessor {
    LOAD("_LOAD"),
    SIZE("_SIZE"),
    /** {@code NAME_WRITE(v)}: this invocation's texel, {@code CG_TEXEL}. */
    WRITE("_WRITE"),
    /** {@code NAME_STORE(p, v)}: any texel. */
    STORE("_STORE"),
    ADD("_ADD"),
    MIN("_MIN"),
    MAX("_MAX");

    public final String suffix;

    CgImageAccessor(String suffix) {
        this.suffix = suffix;
    }

    /** Why a kernel of {@code shape} may not use this on {@code image}, or null when it may. */
    public String refusal(CgKernelShape shape, CgImageDecl image) {
        String name = image.name() + suffix;
        String declared = image.name() + " is " + image.access().name().toLowerCase();
        switch (this) {
            case LOAD:
                return image.access().readable() ? null : declared + ", so it has no " + name;
            case SIZE:
                return null;
            case WRITE:
                if (!image.access().writable()) return declared + ", so it has no " + name;
                return shape.writesOwnTexel() ? null : name + " is an image or general kernel's";
            case STORE:
                if (!image.access().writable()) return declared + ", so it has no " + name;
                return shape.unrestricted() ? null : name + " writes any texel, which only a general kernel does";
            case ADD: case MIN: case MAX:
                if (image.access() != CgImageAccess.READWRITE) return declared + ": only a readwrite image takes " + name;
                if (!image.format().atomics()) return name + " needs an r32i or r32ui image; " + image.name() + " is "
                        + image.format().qualifier();
                return shape.unrestricted() ? null : name + " is an atomic at any texel, which only a general kernel does";
            default:
                throw new AssertionError(this);
        }
    }
}
