package com.crystalgraphics.compute.source;

/**
 * The names a kernel reaches a buffer through, each {@code NAME} plus a suffix. Which a kernel is given follows from
 * the buffer's access and the kernel's shape; the compiler refuses the others by name.
 *
 * <pre>{@code
 * Particle p = STATE(CG_ELEMENT);         // READ: readonly, readwrite, append, counter
 * STATE_WRITE(p);                         // WRITE: this element; writeonly or readwrite, not in an image kernel
 * BINS_ADD(bin, 1.0);                     // ADD, MIN, MAX: a float, int or uint element; scatter or general
 * SPAWNS_APPEND(p);                       // APPEND: an append buffer; append or general
 * uint slot = SLOTS_INC(i);               // INC: a counter; scatter or general. Answers the value before
 * }</pre>
 */
public enum CgBufferAccessor {
    /** {@code NAME(i)}: element {@code i}, by value. */
    READ(""),
    /** {@code NAME_LENGTH()}: the elements bound. */
    LENGTH("_LENGTH"),
    /** {@code NAME_WRITE(v)}: this invocation's element, {@code CG_ELEMENT}. */
    WRITE("_WRITE"),
    /** {@code NAME_STORE(i, v)}: any element. */
    STORE("_STORE"),
    /** {@code NAME_ADD(i, v)}, atomically; a counter's answers the value before. */
    ADD("_ADD"),
    MIN("_MIN"),
    MAX("_MAX"),
    /** {@code NAME_APPEND(v)}: the next free element, dropped once the buffer is full. */
    APPEND("_APPEND"),
    /** {@code NAME_COUNT()}: elements appended, at most the buffer's length. */
    COUNT("_COUNT"),
    /** {@code NAME_INC(i)}: adds one to counter {@code i}, answering the value before. */
    INC("_INC"),
    /** {@code NAME_DATA[i]}: the array itself. */
    DATA("_DATA");

    public final String suffix;

    CgBufferAccessor(String suffix) {
        this.suffix = suffix;
    }

    /** Why a kernel of {@code shape} may not use this on {@code buffer}, or null when it may. */
    public String refusal(CgKernelShape shape, CgBufferDecl buffer) {
        CgBufferAccess access = buffer.access();
        String name = buffer.name() + suffix;
        String declared = buffer.name() + " is " + access.name().toLowerCase();
        switch (this) {
            case READ:
                return access.readable() ? null : declared + ", so it has no " + name + "(i)";
            case LENGTH:
                return null;
            case WRITE:
                if (access != CgBufferAccess.WRITEONLY && access != CgBufferAccess.READWRITE) {
                    return declared + ": only a writeonly or readwrite buffer takes " + name;
                }
                return shape.writesOwnElement() ? null : "an image kernel writes images only";
            case STORE:
                if (access != CgBufferAccess.WRITEONLY && access != CgBufferAccess.READWRITE) {
                    return declared + ": only a writeonly or readwrite buffer takes " + name;
                }
                return shape.scatters() ? null : name + " writes any element, which a scatter or general kernel does";
            case ADD: case MIN: case MAX:
                if (access != CgBufferAccess.READWRITE && access != CgBufferAccess.COUNTER) {
                    return declared + ": only a readwrite or counter buffer takes " + name;
                }
                if (!buffer.scalar()) return name + " needs a float, int or uint element; " + buffer.name() + " holds "
                        + buffer.element();
                return shape.scatters() ? null : name + " writes any element, which a scatter or general kernel does";
            case APPEND:
                if (access != CgBufferAccess.APPEND) return declared + ": only an append buffer takes " + name;
                return shape.appends() ? null : name + " is an append or general kernel's";
            case COUNT:
                return access == CgBufferAccess.APPEND ? null : declared + ": only an append buffer has " + name;
            case INC:
                if (access != CgBufferAccess.COUNTER) return declared + ": only a counter buffer takes " + name;
                return shape.scatters() ? null : name + " writes any element, which a scatter or general kernel does";
            case DATA:
                return shape.unrestricted() ? null : name + " is the raw array, which only a general kernel reaches";
            default:
                throw new AssertionError(this);
        }
    }
}
